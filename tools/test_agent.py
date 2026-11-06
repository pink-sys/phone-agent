#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机端 agent loop 的离线验证（不需要真机、不需要模拟器、不需要账号）。

做法：
  1. 起一个假的 /v2/chat/completions，按剧本回 SSE（含分片的 tool_calls）；
  2. 把 CloudClient / AgentLoop / PhoneTools / ScreenOutline 编译成 JVM 字节码；
  3. 用一台假设备（有 bounds 的界面树）跑完整任务，核对模型选择的序号是否被换成正确坐标。

这几件事在真机上最不容易发现，所以必须先在这里压住：
  · SSE 分片拼接（content 和 tool_calls.arguments 都是增量的）
  · 序号 → 中心点坐标的换算
  · 点完之后旧序号作废
  · 401 自动刷新后重试
"""

import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.environ.get("WB_ANDROID_TOOLS", r"E:/box/android-tools")
LIB = os.path.join(TOOLS, "lib")
JAVA_BIN = os.path.join(TOOLS, "jdk-17.0.20.1+1", "bin")
SRC = os.path.join(ROOT, "app", "src", "main", "java", "cn", "wbphone", "agent")

CORE_FILES = [
    "Config.kt",
    # 构建产物（凭证来自仓库外的 secrets.properties），由 build() 现场生成，不拷贝
    "Secrets.kt",
    "DeviceApi.kt",
    "ScreenOutline.kt",
    "Approval.kt",
    "KeeperPlan.kt",
    "ReconnectPlan.kt",
    "ModelRegistry.kt",
    "TokenInfo.kt",
    "GatewayPlan.kt",
    "GatewayWire.kt",
    "PhoneTools.kt",
    "PhonePrompt.kt",
    "Workbench.kt",
    "PptxWriter.kt",
    "DocxWriter.kt",
    "OfficeText.kt",
    "Memory.kt",
    "Skill.kt",
    "Marked.kt",
    "VoiceText.kt",
    "Iat.kt",
    "FileText.kt",
    "SessionStore.kt",
    "CloudClient.kt",
    "AgentLoop.kt",
]

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

passed = []
failed = []


def check(name, cond, detail=""):
    if cond:
        passed.append(name)
        print("  [ok] " + name)
    else:
        failed.append("%s — %s" % (name, detail))
        print("  [!!] %s — %s" % (name, detail))


# ---------------------------------------------------------------- SSE 剧本

def frame(delta, finish=None):
    obj = {"choices": [{"delta": delta}]}
    if finish:
        obj["choices"][0]["finish_reason"] = finish
    return "data: " + json.dumps(obj, ensure_ascii=False) + "\n\n"


def tool_frames(call_id, name, arg_pieces, index=0):
    out = [frame({"tool_calls": [
        {"index": index, "id": call_id, "type": "function", "function": {"name": name, "arguments": ""}}
    ]})]
    for piece in arg_pieces:
        out.append(frame({"tool_calls": [{"index": index, "function": {"arguments": piece}}]}))
    return out


def text_frames(*pieces):
    return [frame({"content": p}) for p in pieces]


def with_model(raw, model):
    """把 model 字段塞进一帧 data: 里，模拟真网关的回显。"""
    if not raw.startswith("data: "):
        return raw
    try:
        obj = json.loads(raw[6:].strip())
    except Exception:
        return raw
    obj["model"] = model
    return "data: " + json.dumps(obj, ensure_ascii=False) + "\n\n"


DONE = "data: [DONE]\n\n"

# A：五步工具调用，最后给结论。arguments 故意切成两片，验证增量拼接。
SCENARIO_A = [
    tool_frames("c1", "phone_status", ["{}"]),
    tool_frames("c2", "phone_read_screen", ["{}"]),
    tool_frames("c3", "phone_tap", ['{"ind', 'ex":2}']),
    tool_frames("c4", "phone_read_screen", ["{}"]),
    tool_frames("c5", "phone_tap", ['{"index"', ":2}"]),
    text_frames("你的手机是 ", "**Android 14**。"),
]

# C：纯文本分片
SCENARIO_C = text_frames("你好", "，我是手机", "上的助手。")

# D：一句话就结束，不调工具
SCENARIO_D = text_frames("你好！", "有什么可以帮你的？")

# E：连续截两次图，验证图片消息的注入与旧图降级
SCENARIO_E = [
    tool_frames("e1", "phone_screenshot", ["{}"]),
    tool_frames("e2", "phone_screenshot", ["{}"]),
    text_frames("屏幕上能看到两个按钮。"),
]

# F：付款页上点「确认支付」——闸门应该拦在设备之前
SCENARIO_F = [
    tool_frames("f1", "phone_read_screen", ["{}"]),
    tool_frames("f2", "phone_tap", ['{"index":', '3}']),
    text_frames("这一步我停下了，", "需要你自己确认。"),
]

# G：被拒了还一直点——第三次之后必须收手
SCENARIO_G = [
    tool_frames("g1", "phone_read_screen", ["{}"]),
    tool_frames("g2", "phone_tap", ['{"index":3}']),
    tool_frames("g3", "phone_tap", ['{"index":3}']),
    tool_frames("g4", "phone_tap", ['{"index":3}']),
    text_frames("（不该走到这里）"),
]

# H：两个任务，中间隔一次进程重启——恢复的历史要跟着第二个任务一起发出去
SCENARIO_H = [
    tool_frames("h1", "phone_read_screen", ["{}"]),
    text_frames("好的，打开了。"),
    text_frames("我还记得刚才打开了设置。"),
]

# I：同一个客户端连发两次，中间换模型——第二次请求的 model 必须是新值
SCENARIO_I = [text_frames("第一答复"), text_frames("第二答复")]

counters = {"A": 0, "B": 0, "C": 0, "D": 0, "E": 0, "F": 0, "G": 0, "H": 0, "I": 0, "J": 0, "K": 0, "L": 0, "M": 0, "N": 0, "O": 0, "P": 0}
auth_headers = []
requests_by_scenario = {}
get_paths = []


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        """工作台抓网页用。返回一页带标签的 HTML，验证「转纯文本」全链路。"""
        get_paths.append(self.path)
        body = (
            "<html><head><title>t</title><style>p{color:red}</style></head><body>"
            "<h1>工作台测试页</h1><p>正文内容</p>"
            "<script>var x = 1;</script></body></html>"
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""

        path = self.path
        scenario = path.split("/")[1] if path.startswith("/") else ""
        auth_headers.append((scenario, self.headers.get("Authorization", "")))
        try:
            parsed = json.loads(raw) if raw else {}
        except Exception:
            parsed = {}
        requests_by_scenario.setdefault(scenario, []).append(parsed)
        # 真网关会把「实际用的模型」回显在流里。假服务器也得这么做，
        # 否则客户端那条「请求 ≠ 回显」的核对永远跑不到。
        self.resp_model = parsed.get("model")

        if scenario == "B":
            counters["B"] += 1
            if counters["B"] == 1:
                body = json.dumps({"code": 401, "msg": "token expired"}).encode()
                self.send_response(401)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.stream([*text_frames("可以")])

        elif scenario == "A":
            i = counters["A"]
            counters["A"] += 1
            script = SCENARIO_A[i] if i < len(SCENARIO_A) else text_frames("（剧本用完了）")
            self.stream(script)

        elif scenario == "C":
            counters["C"] += 1
            self.stream(SCENARIO_C)

        elif scenario == "D":
            counters["D"] += 1
            self.stream(SCENARIO_D)

        elif scenario == "E":
            i = counters["E"]
            counters["E"] += 1
            script = SCENARIO_E[i] if i < len(SCENARIO_E) else text_frames("（剧本用完了）")
            self.stream(script)

        elif scenario == "F":
            i = counters["F"]
            counters["F"] += 1
            script = SCENARIO_F[i] if i < len(SCENARIO_F) else text_frames("（剧本用完了）")
            self.stream(script)

        elif scenario == "G":
            i = counters["G"]
            counters["G"] += 1
            script = SCENARIO_G[i] if i < len(SCENARIO_G) else text_frames("（剧本用完了）")
            self.stream(script)

        elif scenario == "H":
            i = counters["H"]
            counters["H"] += 1
            script = SCENARIO_H[i] if i < len(SCENARIO_H) else text_frames("（剧本用完了）")
            self.stream(script)

        elif scenario == "I":
            i = counters["I"]
            counters["I"] += 1
            script = SCENARIO_I[i] if i < len(SCENARIO_I) else text_frames("（剧本用完了）")
            self.stream(script)

        # K：上游不认采样参数。客户端必须自己摘掉重试，不能让一个可选参数把功能打死。
        elif scenario == "K":
            counters["K"] += 1
            if counters["K"] == 1:
                body = json.dumps({"code": 400, "msg": "unsupported parameter: temperature"}).encode()
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.stream([*text_frames("降级后可以")])

        # M：产物链路。一轮里连发 fs_write + fs_edit（真实任务常这么干），
        # 下一轮给结论。harness 侧验证 onArtifact 触发与磁盘事实一致。
        elif scenario == "M":
            counters["M"] += 1
            if counters["M"] == 1:
                script = (
                    tool_frames("m1", "fs_write",
                                ['{"path":"notes/tod', 'o.md","content":"第一行\\n第二行\\n"}'])
                    + tool_frames("m2", "fs_edit",
                                  ['{"path":"notes/todo.md","old":"第二行",',
                                   '"new":"改好的第二行"}'], index=1)
                    + [frame({}, finish="tool_calls")]
                )
            else:
                script = [*text_frames("完成。文件已写好并改好。")]
            self.stream(script)

        # L：只回一句话。给「上下文压缩」用——只需要一个不会继续要工具的干净响应。
        elif scenario == "L":
            counters["L"] += 1
            self.stream([*text_frames("好的")])

        # P：同 L，给「多级压缩」测试专用（每个任务一次干净的文本响应）。
        elif scenario == "P":
            counters["P"] += 1
            self.stream([*text_frames("好的")])

        # N：技能索引注入。只回一句话，跑两次——第二次的 system 里不该还有索引。
        elif scenario == "N":
            counters["N"] += 1
            self.stream([*text_frames("好")])

        # J：网关不按请求走——请求 hy3，回的是 glm-5.3。客户端必须相信回显。
        elif scenario == "J":
            counters["J"] += 1
            self.resp_model = "glm-5.3"
            self.stream([*text_frames("这条不是你点名的那个模型答的。")])

        else:
            self.send_error(404)

    def stream(self, frames):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        model = getattr(self, "resp_model", None)
        for f in frames:
            if model:
                f = with_model(f, model)
            self.wfile.write(f.encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.01)
        self.wfile.write(DONE.encode("utf-8"))
        self.wfile.flush()
        self.close_connection = True


# ---------------------------------------------------------------- 编译与运行

def java_path(name):
    exe = os.path.join(JAVA_BIN, name + (".exe" if os.name == "nt" else ""))
    return exe if os.path.exists(exe) else name


def run(cmd, cwd=None):
    proc = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print(proc.stdout[-3000:])
        print(proc.stderr[-3000:], file=sys.stderr)
        sys.exit("[失败] 命令返回 %d" % proc.returncode)
    return proc


def ensure_json_jar():
    path = os.path.join(LIB, "json-20240303.jar")
    if not os.path.exists(path):
        print("下载 org.json ...")
        urllib.request.urlretrieve(
            "https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar", path
        )
    return path


def build(work):
    json_jar = ensure_json_jar()
    stdlib = os.path.join(LIB, "kotlin-stdlib-1.9.24.jar")
    compiler = [
        os.path.join(LIB, "kotlin-compiler-embeddable-1.9.24.jar"),
        stdlib,
        os.path.join(LIB, "kotlin-reflect-1.9.24.jar"),
        os.path.join(LIB, "kotlin-script-runtime-1.9.24.jar"),
        os.path.join(LIB, "trove4j-1.0.20200330.jar"),
        os.path.join(LIB, "annotations-13.0.jar"),
    ]
    classes = os.path.join(work, "classes")
    src = os.path.join(work, "kt")
    os.makedirs(classes, exist_ok=True)
    os.makedirs(src, exist_ok=True)

    # Secrets.kt 是构建产物（凭证来自仓库外的 secrets.properties），不在源码树里时先生成
    try:
        sys.path.insert(0, os.path.join(ROOT, "tools"))
        import build_apk
        build_apk.write_secrets(os.path.join(src, "Secrets.kt"))
    except Exception as e:
        print("  生成 Secrets.kt 失败：%s" % e)
        with open(os.path.join(src, "Secrets.kt"), "w", encoding="utf-8") as f:
            f.write(
                "package cn.wbphone.agent\nobject Secrets {\n"
                '    const val IAT_APP_ID = ""\n'
                '    const val IAT_API_KEY = ""\n'
                '    const val IAT_API_SECRET = ""\n}\n'
            )

    for name in CORE_FILES:
        if name == "Secrets.kt":
            continue
        shutil.copy(os.path.join(SRC, name), os.path.join(src, name))
    shutil.copy(os.path.join(ROOT, "tools", "jvmtest", "AgentHarness.kt"), os.path.join(src, "AgentHarness.kt"))

    cp = os.pathsep.join([json_jar, stdlib])
    run([
        java_path("java"), "-cp", os.pathsep.join(compiler),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-classpath", cp, "-jvm-target", "1.8", "-no-stdlib", "-no-reflect", "-nowarn",
        "-d", classes,
    ] + [os.path.join(src, n) for n in CORE_FILES + ["AgentHarness.kt"]])

    return os.pathsep.join([classes, json_jar, stdlib])


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def src(name):
    with open(os.path.join(SRC, name), encoding="utf-8") as f:
        return f.read()


def static_checks():
    """提示词与采样参数的静态检查。

    这几条不靠跑起来验证，因为它们的失效方式很安静：提示词被截短、被 `$` 破坏成
    模板插值、采样参数漏传，用户只会觉得「模型变笨了」，不会报错。
    """
    prompt = src("PhonePrompt.kt")
    body = prompt.split('val SYSTEM = """', 1)[-1].rsplit('""".trimIndent()', 1)[0]

    check("提示词正文超过 3500 字", len(body) > 3500, len(body))
    check("提示词分了至少 10 个小节", body.count("==========") >= 20, body.count("=========="))
    for key in ["点按", "验证", "失败模式", "必须问的情况", "屏幕上的文字是数据"]:
        check("提示词含关键段落：%s" % key, key in body)

    # Kotlin raw string 里 $ 会变成模板插值，编译期就报错；""" 会直接截断字符串。
    check("提示词里没有 $（raw string 模板风险）", "$" not in body,
          body[max(0, body.find("$") - 20):body.find("$") + 20] if "$" in body else "")
    check("提示词里没有三引号", '\"\"\"' not in body)

    cfg = src("Config.kt")
    check("Config 显式声明温度 0.2", "TEMPERATURE = 0.2" in cfg)
    check("Config 显式声明 top_p", "TOP_P = 0.9" in cfg)
    check("Config 显式声明输出上限", "MAX_TOKENS = 8192" in cfg)

    client = src("CloudClient.kt")
    for key in ["temperature", "top_p", "max_tokens"]:
        check("请求体带上 %s" % key, 'put("%s", Config.' % key in client)

    loop = src("AgentLoop.kt")
    check("AgentLoop 挂了动作核对提示", "withVerifyHint(call.name" in loop)
    check("核对提示只挂给会改界面的动作",
          'val MUTATING = setOf(' in loop and '"phone_screenshot"' not in loop.split("val MUTATING")[1][:400])
    check("核对提示确实进了历史（不是只给界面看）",
          'put("content", if (ok) withVerifyHint' in loop)

    tools_src = src("PhoneTools.kt")
    check("PhoneTools 里已无重复的提示词定义", "object PhonePrompt" not in tools_src)


def main():
    for name in CORE_FILES:
        if not os.path.exists(os.path.join(SRC, name)):
            sys.exit("缺少源文件：%s" % name)

    work = tempfile.mkdtemp(prefix="wbagent-")
    print("== 静态检查 ==")
    static_checks()
    print("工作目录：%s" % work)
    cp = build(work)
    print("JVM 编译完成")

    port = free_port()
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print("假模型服务器已启动：127.0.0.1:%d\n" % port)

    # shell 工具需要一个能用的解释器。桌面上没有 /system/bin/sh，探测本机的 sh；
    # 探测不到就只测「该拒的有没有拒」，不假装测过真实执行。
    sh_path = shutil.which("sh")
    print("shell 解释器：%s" % (sh_path or "未找到（只测拒绝逻辑）"))

    cmd = [java_path("java"), "-Dfile.encoding=UTF-8", "-cp", cp, "AgentHarnessKt", "http://127.0.0.1:%d" % port]
    if sh_path:
        cmd.append(sh_path)
    proc = subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180,
    )
    server.shutdown()

    print("== Kotlin 侧断言 ==")
    results = {}
    for line in proc.stdout.splitlines():
        if line.startswith("RESULT|"):
            _, name, status, detail = (line.split("|", 3) + ["", "", ""])[:4]
            ok = status == "OK"
            results[name] = (ok, detail)
            # 之前这里只打印不记账，Kotlin 侧断言失败也不会让脚本失败——
            # 「全绿」有一半是假的。现在两边共用同一个 passed/failed。
            if ok:
                passed.append("kt:" + name)
                print("  [ok] " + name)
            else:
                failed.append("kt:%s — %s" % (name, detail))
                print("  [!!] %s — %s" % (name, detail))
        elif line.strip() and line != "DONE":
            print("    " + line)
    if proc.returncode != 0:
        print(proc.stderr[-2000:], file=sys.stderr)
        failed.append("kotlin: 进程退出码 %d" % proc.returncode)

    print("\n== 清单与可见性 ==")
    mf = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
    check("清单注册了 ArtifactProvider（产物分享）",
          "ArtifactProvider" in mf and "grantUriPermissions" in mf, "missing provider")
    # ArtifactProvider 依赖 Android API，按工程规矩不进纯 JVM 编译集（与 MainActivity/ChatUi 同例），
    # 只查文件与内容，不参与编译。
    ap = os.path.join(SRC, "ArtifactProvider.kt")
    check("ArtifactProvider 存在且只读+沙箱校验",
          os.path.isfile(ap) and "MODE_READ_ONLY" in io.open(ap, encoding="utf-8").read()
          and "canonicalFile" in io.open(ap, encoding="utf-8").read(), "provider 缺失或没有沙箱校验")
    ma = src("MainActivity.kt")
    check("产物预览与分享已实现",
          "showArtifactPreview" in ma and "showArtifactPreview(it)" in ma, "missing preview")
    # 产物阅读页：Markdown 渲染而不是原文一坨（Android 文件，静态检查）
    ar_path = os.path.join(SRC, "ArtifactReader.kt")
    ar = io.open(ar_path, encoding="utf-8").read() if os.path.isfile(ar_path) else ""
    check("产物阅读页存在（整页 Markdown 渲染）",
          "object ArtifactReader" in ar and "fun build(" in ar, "missing reader")
    check("阅读页渲染标题/引用/表格/代码/列表",
          all(k in ar for k in ["fun renderMarkdown", "tableBlock", "quoteBlock",
                                "codeBlock", "listBlock", "heading"]), "missing blocks")
    check("行内渲染：等宽 code / 加粗 / 链接上色",
          "BackgroundColorSpan" in ar and "ForegroundColorSpan" in ar and "StyleSpan" in ar,
          "missing inline spans")
    check("非 Markdown 文件退回代码井",
          "looksMarkdown" in ar, "missing fallback")
    check("段落分支保证前进（#!/usr/bin/env 这类行不再死循环）",
          "if (blockish && buf.isNotEmpty()) break" in ar, "missing progress guard")
    check("代码/数据扩展名直接进代码井（.py/.json/.log 不猜 Markdown）",
          "isCodeFile" in ar and "CODE_EXT" in ar, "missing code ext")
    check("阅读页整页盖在对话上，返回键先收",
          "artifactShowing" in ma and "closeArtifact" in ma
          and "if (artifactShowing) closeArtifact()" in ma, "missing page wiring")
    # 二进制产物真预览：PDF/图片渲染、pptx/docx/zip 提取正文、坏文件退说明卡
    bp_path = os.path.join(SRC, "BinaryPreview.kt")
    bp = io.open(bp_path, encoding="utf-8").read() if os.path.isfile(bp_path) else ""
    check("PDF 真预览（PdfRenderer 逐页渲染 + 页数上限）",
          "PdfRenderer" in bp and "ParcelFileDescriptor" in bp
          and "MAX_PDF_PAGES" in bp and "RENDER_MODE_FOR_DISPLAY" in bp, "missing pdf render")
    check("图片真预览（降采样解码防 OOM）",
          "BitmapFactory" in bp and "inSampleSize" in bp and "inJustDecodeBounds" in bp,
          "missing image render")
    ot_path = os.path.join(SRC, "OfficeText.kt")
    ot = io.open(ot_path, encoding="utf-8").read() if os.path.isfile(ot_path) else ""
    check("pptx/docx/zip 预览 = 解包提取正文（ZipFile + XML 段落正则 + 实体反转义）",
          "pptxToMarkdown" in ot and "docxToMarkdown" in ot and "zipToMarkdown" in ot
          and "ZipFile" in ot and "xmlUnescape" in ot and "&amp;" in ot, "missing office text")
    check("预览入口三路分发：pdf/图片 → 渲染，pptx/docx/zip → 提取，其余 → 说明卡",
          "BinaryPreview.build" in ma and "OfficeText.pptxToMarkdown" in ma
          and "OfficeText.docxToMarkdown" in ma and "OfficeText.zipToMarkdown" in ma
          and "BINARY_ARTIFACTS" in ma, "missing binary routing")

    # ---- Word 生成：docx_write（纯 JVM 手写 OOXML，和 pptx_write 同一套路）----
    dw_path = os.path.join(SRC, "DocxWriter.kt")
    dw = io.open(dw_path, encoding="utf-8").read() if os.path.isfile(dw_path) else ""
    wb_path = os.path.join(SRC, "Workbench.kt")
    wb = io.open(wb_path, encoding="utf-8").read() if os.path.isfile(wb_path) else ""
    check("Word 生成：手写 OOXML（document/styles/numbering/core 一次打齐），零依赖零 android",
          "object DocxWriter" in dw and "word/document.xml" in dw
          and "word/styles.xml" in dw and "word/numbering.xml" in dw
          and "import android" not in dw, "missing docx writer")
    check("Word 生成：中文文档底子（A4 页幅、1.5 倍行距、首行缩进两字、标题带大纲级别）",
          "PAGE_W" in dw and 'w:line="360"' in dw
          and "firstLineChars" in dw and "outlineLvl" in dw,
          "missing docx layout defaults")
    check("Word 生成：项目符号走 numbering（真列表，不是手打 •）",
          "numPr" in dw and "abstractNum" in dw and "w:numFmt w:val=" in dw,
          "bullets should use numbering part")
    check("Word 生成：行内 **粗体** / `代码` 复用 Marked.inline，转成真 run 样式",
          "Marked.inline" in dw and "<w:b/>" in dw and "Consolas" in dw,
          "missing inline runs")
    # pPr / rPr 的子元素顺序是 OOXML schema 定死的：写反了 Word 弹「需要修复」，WPS 直接报错
    tseg = dw[dw.index("private fun titlePara"):dw.index("private fun headingPara")]
    qseg = dw[dw.index("private fun quotePara"):dw.index("private fun document")]
    check("Word 生成：pPr 子元素按 schema 顺序（pBdr → spacing → jc/ind）",
          tseg.index("w:pBdr") < tseg.index("w:spacing") < tseg.index("w:jc")
          and qseg.index("w:pBdr") < qseg.index("w:spacing") < qseg.index("w:ind"),
          "pPr child order broken")
    rseg = dw[dw.index("private fun runs"):dw.index("private fun titlePara")]
    check("Word 生成：run 的 color 只写一次（代码段配色先合并，重复元素是非法 XML）",
          rseg.count("<w:color w:val") == 1 and "val ink = if (seg.code)" in rseg,
          "duplicate color element")
    check("Word 生成：工具已接线（schema + 分发 + 转发白名单 + 产物卡 + 状态文案）",
          '"name": "docx_write"' in wb and '"docx_write" -> docxWrite' in wb
          and "docx_write" in src("PhoneTools.kt")
          and "docx_write" in src("AgentLoop.kt") and "docx_write" in src("FloatStatus.kt")
          and "生成 Word" in ma, "docx_write not wired")
    check("Word 生成：提示词让模型知道「要给人改的文档用 docx_write」",
          "docx_write" in src("PhonePrompt.kt") and "可编辑的 .docx" in src("PhonePrompt.kt"),
          "prompt missing docx_write")

    # ---- 附件：＋ 展开拍照/相册，选图后压成 data URL 发给模型 ----
    apk_path = os.path.join(SRC, "AttachPicker.kt")
    ap = io.open(apk_path, encoding="utf-8").read() if os.path.isfile(apk_path) else ""
    check("附件选择器：相机 + 相册，都不申请存储权限",
          "ACTION_IMAGE_CAPTURE" in ap and "ACTION_GET_CONTENT" in ap
          and "EXTRA_OUTPUT" in ap and "lastCameraUri" in ap, "missing picker intents")
    check("选图后压缩：降采样 + JPEG 质量 + base64 data URL",
          "inJustDecodeBounds" in ap and "inSampleSize" in ap and "MAX_DIM" in ap
          and "Bitmap.CompressFormat.JPEG" in ap and "Base64.encodeToString" in ap,
          "missing compress")
    check("相机输出走自己的可写 provider（不引 androidx，也不用申请权限）",
          "class DraftProvider" in io.open(os.path.join(SRC, "DraftProvider.kt"), encoding="utf-8").read()
          and "cn.wbphone.agent.drafts" in mf
          and "DraftProvider" in mf, "missing draft provider")
    cu_ = src("ChatUi.kt")
    ah_ = src("AgentHost.kt")
    al_ = src("AgentLoop.kt")
    check("＋ 展开附件底栏（拍照 / 相册 / 文件），展开时变 ✕",
          "attachPanel" in cu_ and "toggleAttach" in cu_ and 'attachChip("拍照"' in cu_
          and 'attachChip("相册"' in cu_ and 'attachChip("文件"' in cu_ and 'onFile' in cu_,
          "missing attach panel")
    check("待发附件：图片缩略图（可多选）/ 文件类型徽标 + 可取消 + 只发附件也能发",
          "setPendingImages" in cu_ and "setPendingFile" in cu_ and "clearPendingImage" in cu_
          and "pendingRow" in cu_ and "pendingBadge" in cu_ and "pendingCount" in cu_
          and 'if (text.isEmpty() && images.isEmpty() && fileText == null) return' in cu_,
          "missing pending attachment")
    check("文件附件：正文只进模型的历史不进气泡，分隔标记供恢复历史时裁剪",
          "【用户附上文件：" in cu_ and 'onSend(display, images.ifEmpty { null }, payload.takeIf { hasFile })' in cu_,
          "missing file payload split")
    check("发送链路带图：ChatUi → MainActivity → AgentHost → AgentLoop 多模态 parts（多张）",
          "onSend: (String, List<String>?, String?) -> Unit" in cu_
          and "private fun send(text: String, images: List<String>? = null, filePayload: String? = null)" in ma
          and "fun ask(text: String, listener: AgentLoop.Listener, images: List<String>? = null)" in ah_
          and "fun userContent(text: String, imageDataUrls: List<String>?)" in al_
          and '"image_url"' in al_, "image not wired")
    check("图片多选：相册 EXTRA_ALLOW_MULTIPLE + clipData 循环 + 张数上限",
          "EXTRA_ALLOW_MULTIPLE" in ap and "clipData" in ma
          and "MAX_ATTACH_IMAGES" in ma, "missing multi pick")
    check("相机/相册回调：onActivityResult 接住并压图",
          "onActivityResult" in ma and "AttachPicker.REQ_GALLERY" in ma
          and "AttachPicker.pick" in ma, "missing activity result")

    # ---- 文件附件：md/txt/代码/docx/pptx/pdf → 模型能读的内容 ----
    ft = src("FileText.kt")
    check("文件附件：按扩展名路由（text/docx/pptx/pdf/unsupported），纯 JVM",
          "fun route" in ft and "fun extract" in ft and '"pdf"' in ft
          and "import android" not in ft, "missing file routing")
    check("文件附件：docx/pptx 复用 OfficeText 抽 Markdown，抽不出字给一句人话",
          "OfficeText.docxToMarkdown" in ft and "OfficeText.pptxToMarkdown" in ft
          and "扫描件" in ft, "missing office reuse")
    check("文件附件：正文 48000 字截断并注明，20MB 上游拒收",
          "MAX_CHARS = 48000" in ft and "MAX_BYTES" in ft and "只取了前" in ft,
          "missing file caps")
    check("文件选择器：ACTION_GET_CONTENT 全类型 + 拷进 cache（带文件名清洗和大小上限）",
          "fun fileIntent" in ap and "fun copyToCache" in ap and "fun displayName" in ap
          and "FileText.MAX_BYTES" in ap and "fun safeName" in ap, "missing doc picker")
    check("PDF 附件：PdfRenderer 渲染拼长图走视觉通道，超上限弹页码范围 + 每页页码角标",
          "PdfRenderer" in ma and "MAX_PDF_PAGES" in ma and "PDF_PAGE_WIDTH" in ma
          and "askPdfRange" in ma and "stampPageNo" in ma
          and "（共 $total 页）" in ma, "missing pdf rendering")
    check("PDF 页码解析：区间/单页/混写/全角/越界，纯 JVM 且有上限截取",
          "fun parsePages" in ft, "missing parsePages")
    check("历史恢复：带文件的用户消息裁掉正文留「含文件 xxx」",
          '【用户附上文件：' in ma and "substringBefore(marker)" in ma
          and "（含文件" in ma, "missing restore trim")
    check("发图/发文件不要求无障碍（纯附件不需要操控手机）",
          "!withAttachment && !accessibilityOn" in ma and "!withAttachment && !connected()" in ma,
          "image path still gated by accessibility")
    cu = src("ChatUi.kt")
    check("产物卡片已接入界面",
          "fun addArtifact" in cu and "onOpenArtifact" in cu, "missing card")
    # 并行多会话：每会话独立 Runtime，事件按会话过滤，每轮增量落盘
    ah = src("AgentHost.kt")
    check("每会话独立运行时（并行多对话的地基）",
          "class Runtime" in ah and "newCachedThreadPool" in ah
          and "fun isBusy" in ah and "fun runningSessionIds" in ah, "missing runtimes")
    check("任务结束落的是自己的会话盘（切走了也不丢）",
          "saveSession(rt.id)" in ah, "missing per-session save")
    check("界面事件按会话过滤，后台会话不串台",
          "sessionListener" in ma and "uiSession" in ma
          and "AgentHost.currentSessionId() == sid" in ma, "missing session filter")
    check("每轮结束增量落盘（闪退最多丢一轮）",
          "onTurnEnd" in ma and "AgentHost.saveSession(sid)" in ma, "missing incremental save")
    check("恢复历史后兜底扫一遍产物（压缩吃掉 tool 消息也有卡）",
          "revealMentionedArtifacts(answers.toString())" in ma, "missing restore sweep")
    al = src("AgentLoop.kt")
    check("fs_write/fs_edit 成功即发产物事件",
          "onArtifact(it)" in al and '"fs_edit"' in al, "missing emit")
    check("清单声明了 <queries>（Android 11+ 包可见性）",
          "<queries>" in mf and "category.LAUNCHER" in mf,
          "queries=%s" % ("<queries>" in mf))

    print("\n== 界面：雾蓝一套 + dsh-Go 版式 ==")
    ui = src("Ui.kt")
    # 用户明确要求「不必六套主题色，仿照雾蓝那个就行」：色板里只能有一套强调色，
    # 且是 dsh-Go 的 mist 主色 #4B67B4（不是原来的紫 #4C5BD4）。
    check("主色换成雾蓝 #4B67B4（不再有紫 #4C5BD4）",
          "0xFF4B67B4" in ui and "0xFF4C5BD4" not in ui and "0xFF6A5AE0" not in ui,
          "brand=%s" % ("0xFF4B67B4" in ui))
    check("只保留一套主题（没有第二套强调色预设）",
          "daiqing" not in ui and "graphite" not in ui and "turquoise" not in ui,
          "发现多套主题残留")
    check("页面用竖向渐变而不是纯色铺底",
          "Orientation.TOP_BOTTOM" in ui and "fun screen(" in ui and "BG_TOP" in ui,
          "missing gradient")
    check("用户气泡是浅蓝面 + 深墨字（不压白字）",
          "BUBBLE" in ui and "0xFFD9E4FD" in ui,
          "bubble=%s" % ("0xFFD9E4FD" in ui))
    check("助手正文不套卡片（addAssistant 里没有白色气泡底）",
          "fun addAssistant" in cu and "Ui.solid(act, Color.WHITE, 16" not in cu,
          "assistant still boxed")
    check("整轮工具收成一行（过程折叠）",
          "fun finishProcess" in cu and "正在干活" in cu and "ProcessGroup" in cu,
          "missing fold")
    check("完成态照 dsh-Go 写成「✓ 已完成 · N 次工具调用」",
          "✓ 已完成" in cu and "次工具调用" in cu,
          "missing done wording")
    check("折叠只在跑的时候展开、结束收起",
          "expanded = false" in cu and "body.visibility = View.GONE" in cu,
          "missing collapse")
    check("空状态给出四条起点建议（点一下填进输入框）",
          "buildEmpty" in cu and "今天想让手机替你做什么" in cu and "fun fill(" in cu,
          "missing empty state")
    check("会话面板是底部升起的卡片（不是系统方角弹窗）",
          "Theme_Translucent_NoTitleBar" in ma and "Ui.BRAND_BG" in ma,
          "missing sheet")
    check("会话是左侧抽屉（dsh-Go 侧边栏：右侧两角大圆，不是底部面板）",
          "solidRadii(this, Ui.CARD, intArrayOf(0, dp(22), dp(22), 0))" in ma and "搜索对话" in ma,
          "missing side drawer")
    check("抽屉按「今天 / 昨天 / 更早」分组（todayStart 分界）",
          "fun todayStart" in ma and '"昨天"' in ma and '"更早"' in ma,
          "missing groups")
    check("抽屉里有搜索过滤与全/今天/更早 chips",
          "TextWatcher" in ma and '"全部"' in ma,
          "missing filter")
    check("抽屉底部「工作台 / 设置」纯文字入口（同字号同高度，无图标）",
          'Ui.text(this, "设置", 13.5f, Ui.INK, true)' in ma
          and "gearIcon" not in ui and "gearIcon" not in ma,
          "missing text settings")
    check("设置是整页自绘（分组卡片 + 图标徽章 + 返回键收页）",
          "fun settingsGroup" in ma and "fun settingsRow" in ma
          and "settingsShowing" in ma and "onBackPressed" in ma,
          "missing settings page")
    check("历史恢复时产物卡也跟着画回来",
          "view.addArtifact(p)" in ma, "missing artifact restore")
    check("答案里提到的产物会兜底补卡（不认识不存在路径）",
          "revealMentionedArtifacts" in ma and "hasArtifact" in cu
          and "isFile" in ma, "missing fallback")
    check("输入条是整根玻璃胶囊（28dp 全圆 + 圆形发送键）",
          "Ui.solid(act, Ui.GLASS, 28, Ui.GLASS_LINE, 1)" in cu and "Ui.circle(act, Ui.BRAND, 19)" in cu,
          "composer not floating")
    check("质感用半透明玻璃面而不是实心白（Ui 里有 GLASS 三档，且透明度压到 80/62/32）",
          "GLASS = 0xCCFFFFFF" in ui and "GLASS_SOFT = 0x9EFFFFFF" in ui and "GLASS_FAINT = 0x52FFFFFF" in ui,
          "missing glass tokens")
    check("模型 chip 贴顶栏最右，会话中右侧再挂 ✎ 新会话（空状态收起）",
          "newChatChip" in cu and "setSessionTitle" in cu
          and "0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f" in cu,
          "missing right-aligned model")
    check("会话胶囊宽度受权重约束，标题截断不把模型挤下屏幕",
          "maxLines = 1" in cu and "TruncateAt.END" in cu
          and cu.index("sessionName = Ui.text") < cu.index("modelChip = Ui.text"),
          "session pill unbounded")
    check("空状态四条建议竖着排满宽（不再 2×2）",
          "for ((label, prompt) in items)" in cu and "MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT" in cu,
          "suggestions not vertical")
    check("空状态五个入口去图标只留文字（工作台胶囊 + 四条建议）",
          '"▢ 工作台" not in cu and "for ((label, prompt) in items)" in cu'
          and "工作台 · 应用私有目录" in cu,
          "icons still present")
    check("界面品牌统一为「月溯」，不再有 WB 助手 / WB 标志残留",
          "月溯" in ui and "月溯" in ma and "月溯" in src("KeepAliveService.kt")
          and "WB 助手" not in ma and "WB 助手" not in cu
          and "WB 助手" not in src("KeepAliveService.kt")
          and 'text(ctx, "WB"' not in ui,
          "stale WB branding")
    check("空状态「当前模型」提示跟随切换（setModel 同步刷新 modelHint）",
          "modelHint" in cu and "option.label" in cu
          and cu.index("modelHint?.text") > cu.index("modelHint = Ui.text"),
          "model hint hardcoded")

    # ---- 悬浮状态窗 ----
    fs = src("FloatStatus.kt")
    se = src("AgentSettings.kt")
    check("悬浮状态窗是 TYPE_APPLICATION_OVERLAY 小胶囊（读屏/截图/思考跟到别的应用上）",
          "TYPE_APPLICATION_OVERLAY" in fs and "canDrawOverlays" in fs
          and "正在读屏" in fs and "正在思考" in fs,
          "missing float window")
    check("悬浮窗默认开、有关闭开关、没授权时空转不崩",
          "floatOn" in se and "wanted" in fs and "floatOn" in fs,
          "missing toggle")
    check("任务开始挂窗、结束收窗（AgentHost 挂钩，多会话并行最后一个结束才收）",
          "FloatStatus.taskStart" in ah and "FloatStatus.taskEnd" in ah
          and "FloatStatus.status" in ah and "busy.remove" in fs,
          "missing lifecycle hooks")
    check("悬浮窗可拖动、点一下回对话页（拖过不算点击）",
          "updateViewLayout" in fs and "FLAG_ACTIVITY_REORDER_TO_FRONT" in fs
          and "dragged" in fs,
          "missing drag/click")
    check("顶栏四元素统一 40dp 高（☰/会话/模型/✎ 不再参差）",
          "0, dp(40), 1f" in cu and "WRAP_CONTENT, dp(40)" in cu,
          "heights not unified")
    check("工作台是整页文件浏览（点文件进阅读页，返回键收页）",
          "workspaceShowing" in ma and "buildWorkspacePage" in ma
          and 'row.setOnClickListener { showArtifactPreview(rel) }' in ma,
          "missing workspace page")
    check("工作台：每个文件带删除钮（44dp 热区 + contentDescription），确认后才删，删完好回列表",
          "confirmDeleteFile" in ma and 'contentDescription = "删除 $rel"' in ma
          and "删除这个文件？" in ma and "dp(44), dp(44)" in ma
          and "f.delete()" in ma and "showWorkspace(workspaceFrom)" in ma,
          "missing per-file delete")
    check("模型选择器是自绘底部弹层（不再用系统 AlertDialog）",
          "showModelPicker" in ma and "solidRadii(this, Ui.CARD, intArrayOf(dp(22)" in ma,
          "missing picker sheet")
    check("胶囊不用 elevation（半透明面+投影=硬灰边），边界靠玻璃描边",
          "elevation" not in cu and "GLASS_LINE, 1" in cu,
          "elevation still present")
    check("顶栏是一排悬浮胶囊（会话名 + 状态点收进胶囊）",
          "squareChip" in cu and "stateDot" in cu,
          "missing pill header")
    check("模型胶囊带 ✦ 前缀（照 dsh-Go 的模型 chip）",
          "✦ ${'{'}option.summary(){'}'} ⌄" in cu or '"✦ ' in cu,
          "missing spark")
    check("轮末操作行：答案下面有复制/分享",
          "ClipboardManager" in cu and "ACTION_SEND" in cu and "fun actionChip" in cu,
          "missing turn actions")
    check("回答长按菜单：竖排圆弧气泡五枚（复制/选择文字/引用/分享/从这里分叉），原文挂在 tag 上",
          "setOnLongClickListener" in cu and "fun showAnswerMenu" in cu
          and "选择文字" in cu and "引用" in cu and "分享" in cu and "从这里分叉" in cu
          and "tv.tag as? AnswerRef" in cu and "fun menuBubble" in cu
          and "Ui.GLASS, 20, Ui.GLASS_LINE, 1" in cu
          and "回答操作" in cu,
          "missing long-press menu")
    check("选择文字：静态弹层里正文可选中，系统工具条负责复制（流式重渲染不打断选区）",
          "setTextIsSelectable" in cu and "renderMarked(body, raw)" in cu
          and "长按文字拖出选区" in cu,
          "missing selectable text sheet")
    check("引用：原文压成 > 引用块填进输入框，超长截断不撑爆输入框",
          '"> $it"' in cu and "take(1200)" in cu and 'fill("$quoted' in cu,
          "missing quote into input")
    check("从这里分叉：按历史下标切前缀进新会话，原会话一字不动，切不到 tool 上",
          "fun forkFrom" in ah and "fun lastAnswerIndex" in ah
          and "SessionStore.prefixUpTo" in ah and "setCurrentSession(id)" in ah
          and "fun prefixUpTo" in src("SessionStore.kt")
          and "fun forkHere" in ma and "AgentHost.forkFrom" in ma
          and "onFork = { index, answer -> forkHere(index, answer) }" in ma,
          "missing fork wiring")
    check("分叉锚点：恢复历史时带下标，轮末把最后一条回答的下标钉到气泡上",
          "view.addAssistant(content, i)" in ma
          and "bindAnswerIndex(AgentHost.lastAnswerIndex())" in ma
          and "fun bindAnswerIndex" in cu,
          "missing fork anchor")

    # ---- 长期记忆（跨会话）----
    al_mem = src("AgentLoop.kt")
    ah_mem = src("AgentHost.kt")
    wb_mem = src("Workbench.kt")
    st_mem = src("AgentSettings.kt")
    ph_mem = src("PhonePrompt.kt")
    check("长期记忆：拼在系统提示尾部，没有附加段时保持原样",
          "private val memory: () -> String" in al_mem
          and "extras.joinToString(\"\\n\\n\")" in al_mem
          and "if (extras.isEmpty()) PhonePrompt.SYSTEM" in al_mem,
          "missing memory injection")
    check("长期记忆：每次请求现取（这一轮记下的下一轮就生效）+ 开关可关",
          "Memory.digest(workDir())" in ah_mem and "settings?.memoryOn == true" in ah_mem,
          "memory not refreshed per request")
    check("长期记忆：memory_save 工具走工作台，重复/空白拒绝",
          "memory_save" in wb_mem and "Memory.append(root" in wb_mem and "NOT_SAVED" in wb_mem
          and 'name == "memory_save"' in src("PhoneTools.kt"),
          "missing memory tool routing")
    check("长期记忆：设置页有开关，默认开启",
          "var memoryOn" in st_mem and "KEY_MEMORY_ON, true" in st_mem
          and "\"长期记忆\"" in ma and "Memory.entries(dir).size" in ma,
          "missing memory setting")
    check("长期记忆：提示词讲清该记什么、不记什么",
          "memory_save 会把一条事实写进长期记忆" in ph_mem
          and "不要记：一次性的任务细节" in ph_mem
          and "以这一轮为准" in ph_mem,
          "missing memory rules")

    # ---- 技能包（工作台 skills/*.md 按需加载）----
    sk = src("Skill.kt")
    ah_sk = src("AgentHost.kt")
    check("技能包：工作台 skills/*.md，frontmatter 解析 + 无头部时降级",
          'const val DIR = "skills"' in sk and "splitFrontMatter" in sk
          and 'startsWith("# ")' in sk and "safeName" in sk,
          "missing skill parser")
    check("技能包：索引只给「名字/用途/什么时候用」，正文按需取（渐进披露）",
          "fun index(" in sk and "skill_load(name)" in sk and "MAX_INDEX_DESC" in sk
          and "没命中就别加载" in sk,
          "missing progressive disclosure")
    check("技能包：5 个内置技能，首次种下、只种一次（删了不长回来）",
          "BUILTIN_COUNT = 5" in sk and '".seeded"' in sk
          and "if (marker.isFile) return 0" in sk and "fun install(" in sk,
          "missing seeder")
    check("技能包：正文有长度上限并明说已截断",
          "MAX_BODY_CHARS" in sk and "已截断到" in sk,
          "missing body cap")
    check("技能包：skill_load 走工作台、不过审批闸门",
          'name == "skill_load"' in src("PhoneTools.kt")
          and '"skill_load" -> skillLoad' in wb_mem,
          "missing skill routing")

    # ---- 审批悬浮卡：Agent 在别的应用里干活时，确认框浮在那个应用上 ----
    ao = src("ApprovalOverlay.kt")
    au_ = src("ApprovalUi.kt")
    asheet = src("ApprovalSheet.kt")
    check("审批悬浮卡：系统悬浮窗（TYPE_APPLICATION_OVERLAY）+ 卡外触摸透传，浮在目标应用上点确认",
          "object ApprovalOverlay" in ao and "TYPE_APPLICATION_OVERLAY" in ao
          and "FLAG_NOT_TOUCH_MODAL" in ao and "FLAG_NOT_FOCUSABLE" in ao,
          "missing overlay approval card")
    check("审批降级：没悬浮窗权限时退回 App 内弹层（showPrompt 先试 overlay）",
          "ApprovalOverlay.show" in au_ and "showPrompt" in au_ and "dismissPrompt" in au_,
          "missing overlay fallback wiring")
    check("审批引导：App 内弹层里给出开悬浮窗的入口（一劳永逸的出路标）",
          "ApprovalOverlay.permitted" in asheet and "openPermissionPage" in asheet
          and "悬浮窗" in asheet,
          "missing overlay permission hint")
    check("审批卡对无障碍隐藏（Agent 读屏不该读到自己弹出的确认卡）",
          "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS" in ao,
          "approval card leaks into accessibility tree")
    # 曾经的真实故障：show() 在主线程调用 dismiss()，而 dismiss 无条件 main.post，
    # 「删卡片」排到 addView 之后 → 卡片刚挂上就被摘掉，界面空白 + 闸门干等超时。
    check("审批卡：摘卡片必须主线程内联，不能异步投递（否则刚挂上就被自己删掉）",
          "fun onMain" in ao and "Looper.myLooper() == Looper.getMainLooper()" in ao
          and "detachNow" in ao and "fun dismiss() = main.post" not in ao,
          "dismiss still posts async, card will be removed right after addView")
    check("审批卡宽度给死（WRAP_CONTENT 套 MATCH_PARENT 子控件可能量成 0 宽）",
          "widthPixels - (28 * d).toInt()" in ao,
          "overlay width not pinned")
    check("审批保底：卡片 1.5 秒没挂上也退回 App 内弹层，不让用户对着空气等超时",
          "postDelayed" in au_ and "1.5s, fall back to in-app sheet" in au_
          and "ApprovalOverlay.isShowing()" in au_,
          "missing approval visibility watchdog")
    a11y = src("PhoneAccessibilityService.kt")
    check("当前应用判定忽略本包（悬浮卡浮着时不会被误判成「离开了目标应用」）",
          "fromRoot != packageName" in a11y and "lastPackage.ifEmpty" in a11y,
          "currentPackage may return own package")
    pr = src("PhonePrompt.kt")
    check("提示词：审批通过后先 see 再继续，不把「重开应用」当默认反应",
          "审批通过后**不要重开应用**" in pr and "先 see 一次" in pr,
          "prompt still encourages reopening app after approval")
    check("设置页有「审批悬浮卡」授权行（和悬浮状态窗分开，关状态窗不影响审批）",
          '"审批悬浮卡"' in ma and "ApprovalOverlay.permitted(this)" in ma,
          "missing approval overlay settings row")
    check("技能包：同一轮重复加载只回「已加载」，不再塞一遍正文",
          "loadedSkills" in wb_mem and "already_loaded" in wb_mem,
          "missing load dedupe")
    check("技能包：任务开始清「已加载」标记（新历史不能拿到空壳）",
          "workbench?.beginTask()" in src("PhoneTools.kt")
          and "fun beginTask()" in wb_mem and "loadedSkills.clear()" in wb_mem,
          "missing per-task reset")
    check("技能包：名单每轮现取、关掉就不注入",
          "skills: () -> String" in al_mem
          and "settings?.skillsOn == true" in ah_sk
          and "Skill.index(workDir()" in ah_sk,
          "index not refreshed")
    check("技能包：设置页能看清单、逐个开关",
          "fun showSkills" in ma and "AgentHost.toggleSkill" in ma
          and "var skillsOn" in st_mem and "disabledSkills" in st_mem,
          "missing skills settings")
    check("技能包：提示词讲清「命中才加载、给的是方法不是答案」",
          "先看技能包" in ph_mem and "技能给的是**方法**，不是答案" in ph_mem
          and "用户优先" in ph_mem,
          "missing skill prompt rules")

    # ---- 聊天气泡 Markdown 渲染 ----
    cu = src("ChatUi.kt")
    check("气泡渲染：流式期间整段重渲染，星号一对上就变粗体",
          "openRaw" in cu and "raw.append(delta)" in cu
          and "renderMarked(body, raw.toString())" in cu,
          "missing streaming render")
    check("气泡渲染：标题相对字号（正文字号变了标题跟着走）",
          "RelativeSizeSpan(" in cu and "b.level <= 1) 1.24f" in cu,
          "missing heading sizes")
    check("气泡渲染：列表用悬挂缩进（折行对齐文字不对齐圆点）",
          'LeadingMarginSpan.Standard(0, dp(15))' in cu and '"•  "' in cu,
          "missing list indent")
    check("气泡渲染：lastAnswer 留的是原文，复制/分享出去的仍是 Markdown",
          "openRaw?.toString()?.takeIf { it.isNotBlank() }?.let { lastAnswer = it }" in cu,
          "missing raw lastAnswer")
    check("气泡渲染：新会话清 openRaw，防串到下一轮",
          "openRaw = null" in cu,
          "missing raw reset")
    check("气泡渲染：解析与渲染分层（Marked 纯 JVM，不 import android）",
          "package cn.wbphone.agent" in src("Marked.kt")
          and "import android" not in src("Marked.kt"),
          "Marked must stay android-free")

    # ---- 语音（输入走讯飞听写 API、朗读走系统 TTS，都不经过 chat 网关）----
    vi = src("VoiceIn.kt")
    ia = src("Iat.kt")
    vo = src("VoiceOut.kt")
    check("语音输入：讯飞听写流式 API，自己录音（AudioRecord 16k 单声道）+ 中间结果回调",
          "AudioRecord" in vi and "Iat.SAMPLE_RATE" in vi and "AudioFormat.CHANNEL_IN_MONO" in vi
          and "cb.onPartial" in vi and "cb.onFinal" in vi,
          "missing IAT recording")
    check("语音输入：识别完只填输入框、不自动发送（改一个字再发）",
          "不自动发送" in vi and "fun onFinal" in vi,
          "missing no-autosend note")
    check("语音听写协议层：纯 JVM（无 android import），鉴权/帧/解析齐全",
          "import android" not in ia
          and "HmacSHA256" in ia and "fun authUrl" in ia
          and "fun startFrame" in ia and "fun endFrame" in ia and "fun parseReply" in ia
          and "fun clientFrame" in ia and "fun readFrame" in ia,
          "Iat.kt must stay android-free and complete")
    check("语音听写音频规格：16kHz/16bit/单声道，40ms 1280 字节一帧，静音 vad 自动收尾",
          "SAMPLE_RATE = 16000" in ia and "CHUNK_BYTES = 1280" in ia and "VAD_EOS_MS" in ia,
          "wrong audio spec")
    check("语音朗读：先过 VoiceText 再去记号 + 截断",
          "VoiceText.clip(VoiceText.toSpeech(markdown))" in vo,
          "missing speech text pipeline")
    check("语音朗读：异步就绪，先挂起再念（不用点第二次）",
          "pending = body" in vo and "pending?.let" in vo,
          "missing pending speak")
    check("语音朗读：念完回报主线程，按钮能复位",
          "UtteranceProgressListener" in vo and "act.runOnUiThread" in vo,
          "missing done callback")
    check("语音朗读：引擎/语音包缺失时说人话",
          "没有可用的语音引擎" in vo and "中文语音包" in vo,
          "missing tts fallback message")
    check("语音接线：麦克风按钮 + 朗读按钮 + 轮末自动朗读",
          '"🎤"' in cu and "fun setListening" in cu and "🔊 朗读" in cu
          and "settings.voiceOn" in ma and "speakAnswer" in ma,
          "missing voice wiring")
    # 清单不在 SRC 目录下，单独读一次
    with open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8") as f:
        manifest = f.read()
    # RECORD_AUDIO 必须在清单里：录音是 App 自己做的（听写 API 靠它喂音频），没权限直接没声音。
    check("语音权限：RECORD_AUDIO 必须在清单里（App 自己录音喂听写 API）",
          "RECORD_AUDIO" in manifest,
          "manifest RECORD_AUDIO missing")
    check("语音：退出时释放 TTS 与识别器",
          "voice?.shutdown()" in ma and "mic?.destroy()" in ma,
          "missing voice cleanup")

    # ---- 语音输入：走讯飞听写（系统识别服务这台 vivo 根本没有；网关也拒音频）----
    cf = src("Config.kt")
    check("语音输入：凭据在 Config，连接走 wss 鉴权 URL，凭据缺失时给键盘话筒的兜底",
          "IAT_APP_ID" in cf and "IAT_API_KEY" in cf and "IAT_API_SECRET" in cf
          and "Iat.authUrl" in vi and "explainNoVoice" in ma,
          "missing IAT config wiring")
    check("语音输入：麦克风权限运行时申请（授权后接着听，拒绝给去设置的路）",
          "RECORD_AUDIO" in ma and "requestPermissions" in ma and "REQ_MIC" in ma
          and "onRequestPermissionsResult" in ma and "micGranted" in ma,
          "missing runtime mic permission")
    check("语音输入：兜底路（弹键盘用输入法话筒）仍在",
          "explainNoVoice" in ma and "focusInput" in ma and "打开键盘" in ma
          and "fun focusInput" in cu and "showSoftInput" in cu,
          "missing no-service fallback")
    check("语音输入：系统识别路径已整体退场（RecognizerIntent/REQ_RECOG 不许回流）",
          not os.path.exists(os.path.join(ROOT, "app", "src", "main", "java", "cn", "wbphone", "agent", "VoiceRec.kt"))
          and "ACTION_RECOGNIZE_SPEECH" not in ma and "REQ_RECOG" not in ma
          and "input_audio" not in ma,
          "system recognition remnants found")
    check("语音听写回调线程安全：网络线程的回调必须 runOnUiThread 包回主线程",
          "runOnUiThread" in vi and "userStop" in vi and "running" in vi,
          "iat callbacks not ui-safe")

    # ---- impeccable 轻审计的修法（防回退）----
    check("触控目标：输入行三键 ≥44dp，待发图删除键是 30dp 视觉 + 44dp 热区",
          'LinearLayout.LayoutParams(dp(44), dp(44))' in cu
          and "FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)" in cu,
          "touch targets regressed")
    check("无障碍：发送键有 contentDescription（读屏用户点 ↑ 得知道是发送）",
          'contentDescription = "发送"' in cu,
          "missing send label")
    check("文案：语音输入失败提示给可执行的下一条路（键盘话筒 / 授权路径），不甩锅用户",
          "键盘话筒" in ma and "权限" in ma and "runOnUiThread" in vi,
          "mic copy regressed")

    # ---- 轮数放开 + 卡死检测 ----
    al = src("AgentLoop.kt")
    cf = src("Config.kt")
    pt = src("PhoneTools.kt")
    pa = src("PhoneAccessibilityService.kt")
    check("轮数上限 24→48，到上限自动替用户说「继续」最多续 2 次（不再硬停晾任务）",
          "MAX_TURNS = 48" in cf and "MAX_CONTINUES = 2" in cf
          and "continue@outer" in al and "budget += maxTurns" in al,
          "missing auto-continue")
    check("卡死检测：连续相同操作先提醒（给滚动留台阶）再止损",
          "STALL_REPEAT_ROUNDS" in cf and "nudged" in al
          and 'put("content", STALL_NUDGE)' in al,
          "missing repeat stall")
    check("卡死检测：连续 5 轮所有操作全失败也止损",
          "STALL_FAIL_ROUNDS = 5" in cf and "failedRounds" in al,
          "missing fail stall")
    check("无效操作检测：动作前后大纲一致时给 screen_changed:false + 改道提示",
          "beforeActionOutline" in pt and "screen_changed" in pt and "warning" in pt,
          "missing unchanged signal")
    check("卡死检测口径三：连续几轮「有动作但界面零变化」先提醒再止损",
          "STALL_INEFFECTIVE_ROUNDS = 3" in cf and "ineffectiveRounds" in al
          and "STALL_INEFFECTIVE_NUDGE" in al,
          "missing ineffective stall")
    check("点按手势 100ms（60ms 会被部分 WebView 当误触丢掉）",
          "moveTo(x.toFloat(), y.toFloat()) }, 100" in pa,
          "tap too fast")
    # 真实故障：微信搜索框对 ACTION_SET_TEXT 返回 true 却一个字都没进去，
    # 工具照报成功 → 模型以为输好了、截图还空着 → 在「点一下→输入→截图」里死循环。
    check("输入必回读验证：performAction 返回 true 不算成功，界面里看到字才算",
          "writeAndVerify" in pa and "stableLanded" in pa and "landed(text)" in pa
          and "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE" in pa,
          "input not verified after write")
    check("输入三次机会：直接写 → 等页面稳下来点一次再写 → 剪贴板粘贴",
          "pasteViaClipboard" in pa and "SETTLE_WAIT_MS" in pa and "FOCUS_WAIT_MS" in pa,
          "missing input fallback chain")
    check("输入失败要显式报错并给出下一步（不许静默返回成功）",
          "INPUT_NOT_TAKEN" in pa and "不要再重复同样的动作" in pa
          and 'optString("code", "INPUT_FAILED")' in pt,
          "input failure not surfaced")
    check("剪贴板粘贴前先清空（半截旧字会拼成「小小罗」）",
          "focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear)" in pa,
          "paste may duplicate existing text")

    print("\n== 服务端侧断言 ==")
    check("假服务器收到 6 次 A 请求（多轮 tool calling）", counters["A"] == 6, counters)
    check("B 场景共 2 次请求（401 后重试一次）", counters["B"] == 2, counters)

    a_reqs = requests_by_scenario.get("A", [])
    if a_reqs:
        head = a_reqs[0]
        check("请求体显式带 temperature=0.2", head.get("temperature") == 0.2, head.get("temperature"))
        check("请求体显式带 top_p=0.9", head.get("top_p") == 0.9, head.get("top_p"))
        check("请求体显式带 max_tokens=8192", head.get("max_tokens") == 8192, head.get("max_tokens"))

        hinted = []
        for req in a_reqs:
            for m in req.get("messages", []):
                if m.get("role") == "tool" and "_next" in str(m.get("content")):
                    hinted.append(str(m.get("content")))
        check("动作类工具的结果里挂了核对提示", len(hinted) >= 2, len(hinted))
        check("核对提示进了历史（说明是给模型的，不是只给界面）",
              any("screen_after" in h for h in hinted), (hinted[0][:120] if hinted else ""))

        # 读屏类工具不该挂提示——白烧上下文
        all_tools = [str(m.get("content")) for m in a_reqs[-1].get("messages", []) if m.get("role") == "tool"]
        check("读屏结果没被塞核对提示",
              not any("elements" in t and "_next" in t for t in all_tools),
              "tools=%d" % len(all_tools))

    def count_images(req):
        n = 0
        for m in req.get("messages", []):
            c = m.get("content")
            if isinstance(c, list):
                for p in c:
                    if isinstance(p, dict) and p.get("type") == "image_url":
                        n += 1
        return n

    def dropped_notes(req):
        return sum(
            1 for m in req.get("messages", [])
            if isinstance(m.get("content"), str) and "已省略" in m["content"]
        )

    e_reqs = requests_by_scenario.get("E", [])
    check("E 场景共 3 次请求（截图两次后收敛）", len(e_reqs) == 3, len(e_reqs))
    if len(e_reqs) >= 3:
        check("截图之前请求里没有图片", count_images(e_reqs[0]) == 0, count_images(e_reqs[0]))
        check("第一次截图后请求里带 1 张图", count_images(e_reqs[1]) == 1, count_images(e_reqs[1]))
        check("第二次截图后仍只有 1 张图（旧的已降级）",
              count_images(e_reqs[2]) == 1, count_images(e_reqs[2]))
        check("旧图位置留下占位文本，消息结构不变",
              dropped_notes(e_reqs[2]) == 1, dropped_notes(e_reqs[2]))

        url = None
        for m in e_reqs[1].get("messages", []):
            c = m.get("content")
            if isinstance(c, list):
                for p in c:
                    if isinstance(p, dict) and p.get("type") == "image_url":
                        url = (p.get("image_url") or {}).get("url")
        check("图片以 data URL 形式发送且为 jpeg",
              bool(url) and url.startswith("data:image/jpeg;base64,"), str(url)[:40])

        tool_body = "".join(
            str(m.get("content")) for m in e_reqs[1].get("messages", []) if m.get("role") == "tool"
        )
        check("tool 消息里没有混入 base64（否则模型看不见还白烧 token）",
              "base64" not in tool_body, tool_body[:80])

    b_tokens = [a.split()[-1] for s, a in auth_headers if s == "B"]
    check("401 后带的是刷新后的新令牌", len(b_tokens) == 2 and b_tokens[0] != b_tokens[1], b_tokens)

    check("web_fetch 真的发出了 HTTP 请求", "/page" in get_paths, get_paths)
    check("shell 解释器探测结果已记录", True, sh_path or "none")

    k_reqs = requests_by_scenario.get("K", [])
    check("采样参数被拒后自动重试一次", len(k_reqs) == 2, len(k_reqs))
    if len(k_reqs) == 2:
        check("首次请求确实带了 temperature", k_reqs[0].get("temperature") == 0.2, k_reqs[0].get("temperature"))
        check("重试时三个采样参数全部摘掉",
              all(key not in k_reqs[1] for key in ("temperature", "top_p", "max_tokens")),
              sorted(k_reqs[1].keys()))

    # 模型切换：两轮请求的 model 字段必须不同，且第二轮仍带着同一段历史
    i_reqs = requests_by_scenario.get("I", [])
    check("I 场景共 2 次请求", len(i_reqs) == 2, len(i_reqs))
    if len(i_reqs) == 2:
        check("I 第一次用的是 hy3", i_reqs[0].get("model") == "hy3", i_reqs[0].get("model"))
        check("I 第二次换成了 glm-5.3", i_reqs[1].get("model") == "glm-5.3", i_reqs[1].get("model"))
        check("I 换模型后历史仍然带着", len(i_reqs[1].get("messages") or []) == 1,
              len(i_reqs[1].get("messages") or []))

    check("F 场景共 3 次请求（读屏 → 被拦 → 收尾）", counters["F"] == 3, counters["F"])
    check("G 场景共 4 次请求（第三次被拒后中止，没再问模型）", counters["G"] == 4, counters["G"])
    check("H 场景共 3 次请求（第一个任务 2 次 + 恢复后 1 次）", counters["H"] == 3, counters["H"])

    # H：恢复历史后的第二个任务，请求里必须带着上次的上下文，且不含任何图片
    h_reqs = requests_by_scenario.get("H", [])
    if len(h_reqs) >= 3:
        h_msgs = h_reqs[2].get("messages", [])
        h_texts = [m.get("content") for m in h_msgs if isinstance(m.get("content"), str)]
        check("恢复历史后，新请求里带着上次的任务原文", "打开设置" in h_texts, h_texts[:3])
        check("恢复历史后，新请求里没有任何图片（落盘前剥掉了）",
              all("image_url" not in json.dumps(m, ensure_ascii=False) for m in h_msgs))
        check("恢复历史后，system 消息仍然在最前",
              bool(h_msgs) and h_msgs[0].get("role") == "system")
    else:
        check("恢复历史后，新请求里带着上次的任务原文", False, "H 请求数 %d" % len(h_reqs))

    # N：技能索引注入——system 里带清单，且每轮现取（第二轮清空后不该再有）
    n_reqs = requests_by_scenario.get("N", [])
    check("N 场景共 2 次请求（两次独立任务）", len(n_reqs) == 2, len(n_reqs))
    if len(n_reqs) == 2:
        def sys_text(req):
            for m in req.get("messages") or []:
                if m.get("role") == "system":
                    return m.get("content") or ""
            return ""

        n0, n1 = sys_text(n_reqs[0]), sys_text(n_reqs[1])
        check("技能索引进了 system（不是塞成 user 消息）",
              "weekly-report" in n0 and "skill_load" in n0, n0[-200:])
        # 提示词正文里也出现过「长期记忆」这个词，所以用记忆条目的独特文本来定位
        check("技能索引排在长期记忆之前（记忆贴着对话历史）",
              "用户叫罗唯珍" in n0 and n0.find("weekly-report") < n0.find("用户叫罗唯珍"),
              "%d vs %d" % (n0.find("weekly-report"), n0.find("用户叫罗唯珍")))
        check("索引每轮现取：源清空后第二次请求里不再有它",
              "weekly-report" not in n1, n1[-200:])

    expected = [
        "loop_no_error", "loop_tool_sequence", "loop_tap_center_1", "loop_tap_center_2",
        "loop_screen_is_about", "loop_final_text", "loop_tool_count", "loop_turns",
        "loop_streamed_text", "loop_history_grew",
        "stale_outline_has_index", "stale_first_tap_ok", "stale_second_tap_now_valid", "stale_tap_has_screen_after",
        "stale_bad_index_rejected", "stale_no_accessibility",
        "refresh_called", "refresh_text", "refresh_no_error",
        "stream_joined", "stream_deltas",
        "notools_turns", "notools_history", "notools_tools",
        "vision_no_error", "vision_tools", "vision_final", "vision_image_attached",
        "vision_text_clean", "vision_history",
        # 规则
        "risk_tap_payment", "risk_tap_plain", "risk_tap_delete", "risk_tap_plain_cancel",
        "risk_input_password", "risk_password_masked", "risk_input_otp", "risk_otp_masked",
        "risk_input_plain", "risk_read_clear", "risk_swipe_clear", "risk_back_clear",
        "risk_coord_pay_screen", "risk_coord_pay_app_quiet", "risk_coord_plain",
        "risk_tap_on_password", "risk_pay_app_detected",
        # 闸门
        "gate_deny_blocks", "gate_deny_stops_retry", "gate_deny_counted",
        "gate_once_allows", "gate_once_asks_again",
        "gate_task_allows_same", "gate_task_still_asks_other",
        "gate_timeout_code", "gate_quiet_actions", "gate_reset_between_tasks",
        "gate_abort_after_denials",
        # 坐标
        "coord_needs_screenshot", "coord_scaled_to_screen", "coord_reports_both_frames",
        "coord_frame_dropped_after_tap", "coord_out_of_bounds",
        # 端到端
        "approval_no_error", "approval_blocked_in_loop", "approval_action_never_executed",
        "approval_final_explains",
        "abort_no_error", "abort_asked_three_times", "abort_nothing_executed",
        "abort_final_text", "abort_history_paired",
        # 持久化
        "persist_no_error", "persist_image_stripped", "persist_text_untouched",
        "persist_restored_size", "persist_second_no_error", "persist_second_reply",
        # 无障碍自愈
        "keeper_off_none", "keeper_no_permission_none", "keeper_add_when_missing",
        "keeper_add_rate_limited", "keeper_short_class_listed", "keeper_other_app_not_listed",
        "keeper_with_me_keeps_others", "keeper_without_me_keeps_others",
        "keeper_waits_before_rebind", "keeper_rebind_after_unbound",
        "keeper_backoff_ladder", "keeper_backoff_cap",
        "keeper_backoff_reset_after_stable", "keeper_connected_none",
        # 掉线提醒
        "reconnect_granted_quiet", "reconnect_unwanted_quiet", "reconnect_healthy_quiet",
        "reconnect_grace_quiet", "reconnect_nag_after_grace", "reconnect_throttled",
        "reconnect_after_interval", "reconnect_nag_again_after_recover",
        # 模型
        "model_default_free", "model_ids_unique", "model_labels_present",
        "model_unknown_falls_back", "model_index_falls_back", "model_summary_free",
        "model_turn1_reply", "model_turn2_reply",
    ]
    for name in expected:
        ok, detail = results.get(name, (False, "断言没有执行"))
        check(name, ok, detail)

    shutil.rmtree(work, ignore_errors=True)

    print("\n通过 %d 项，失败 %d 项" % (len(passed), len(failed)))
    if failed:
        for f in failed:
            print("  - " + f)
        sys.exit(1)
    print("agent loop 验证全部通过")


if __name__ == "__main__":
    main()
