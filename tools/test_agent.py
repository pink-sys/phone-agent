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
import re
import shutil
import socket
import struct
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
    "PhoneLease.kt",
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
    "TranscriptText.kt",
    "Errors.kt",
    "CloudClient.kt",
    "AgentLoop.kt",
    # 工具抽象 + 子代理：loop 只认 Toolbox 接口，子代理是另一套只读工具箱
    "Toolbox.kt",
    "SubAgent.kt",
    # HTML 渲染的本机回环 HTTP 服务（纯 JVM，能真起真连）
    "ArtifactServer.kt",
    # 文本截图的排版层（纯 JVM）：折行/列宽在 harness 里跑真断言
    "ShotText.kt",
    # xlsx 生成与读取（纯 JVM 真产物，harness 里解包断言）
    "XlsxWriter.kt",
    # 图表的布局层（纯 JVM）：刻度/坐标在 harness 里跑真断言；ChartShot 是 Android 像素层
    "ChartText.kt",
    # tar.gz 解包器（纯 JVM）：Python rootfs 就靠它落盘，头解析/越界拒绝在 harness 里真断言
    "TarGz.kt",
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


def usage_frame(prompt, completion):
    """真实上游会在流末尾报 token 用量。子代理的账单能不能算进本轮，全靠这一帧。"""
    obj = {"choices": [{"delta": {}}], "usage": {"prompt_tokens": prompt, "completion_tokens": completion}}
    return "data: " + json.dumps(obj, ensure_ascii=False) + "\n\n"


def reasoning_frames(*pieces):
    """思考流：上游把「想」的过程单独发在 reasoning_content 里，正文要等思考结束才开始。"""
    return [frame({"reasoning_content": p}) for p in pieces]


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

# PA：一个回合里同时发两个工具调用（真机实录：模型说「派 task，同时自己再搜」，
# 引擎串行执行让 task 独占九十秒）。假工具各睡 400ms，并行必须明显快于串行 800ms。
SCENARIO_PA = [
    [
        frame({"tool_calls": [
            {"index": 0, "id": "p1", "type": "function",
             "function": {"name": "web_search", "arguments": "{}"}},
            {"index": 1, "id": "p2", "type": "function",
             "function": {"name": "web_fetch", "arguments": "{}"}},
        ]}),
    ],
]

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

# X：思考 + 工具 + 正文。思考要跟着消息落盘（_think），发回上游前必须剥掉。
SCENARIO_X = [
    [*reasoning_frames("先读屏看看", "有没有可点的东西。"), *tool_frames("x1", "phone_read_screen", ["{}"])],
    [*reasoning_frames("看清楚了，", "可以直接答。"), *text_frames("屏幕上是一个设置页。")],
    text_frames("接着答：还是那个设置页。"),
]

counters = {"SUB": 0, "SUBF": 0, "S": 0, "IG": 0, "NB": 0, "A": 0, "B": 0, "C": 0, "D": 0, "E": 0, "F": 0, "G": 0, "H": 0, "I": 0, "J": 0, "K": 0, "L": 0, "M": 0, "N": 0, "O": 0, "P": 0, "Q": 0, "R": 0, "T": 0, "TN": 0, "U": 0, "V": 0, "W": 0, "X": 0, "Y": 0, "Z": 0, "IM": 0, "PA": 0}
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

        # PA：一回合两个工具调用，验证并行执行（详见 SCENARIO_PA 注释）。
        # 必须按次数切剧本：第 1 轮发两个工具调用，第 2 轮给结论——
        # 不然模型每轮都收到同样的两个 tool_calls，工具会循环跑到 maxTurns。
        elif scenario == "PA":
            i = counters["PA"]
            counters["PA"] += 1
            if i == 0:
                self.stream(SCENARIO_PA[0])
            else:
                self.stream(text_frames("两个都跑完了。"))

        # SUBF：子代理连续抓取失败 → 引擎熔断（真机实录：GitHub 不可达时
        # 子代理换镜像连抓 39 步 ×15 秒超时，用户干等 14 分钟）。
        elif scenario == "SUBF":
            counters["SUBF"] += 1
            if counters["SUBF"] == 1:
                script = [frame({"tool_calls": [
                    {"index": i, "id": "f%d" % i, "type": "function",
                     "function": {"name": "web_fetch",
                                  "arguments": json.dumps({"url": "https://github.com/x%d" % i})}}
                    for i in range(4)
                ]}), frame({}, finish="tool_calls"), usage_frame(200, 20)]
            else:
                script = [*text_frames("（熔断后不该再被调到）"), usage_frame(10, 1)]
            self.stream(script)

        # SUBL：子代理永远不给结论，每轮都发一个 fs_list。用来验「子代理不自动续跑」——
        # 真机实录：10 轮被自动续成 30 轮，3 个子代理并排跑了 19 分钟。
        elif scenario == "SUBL":
            counters["SUBL"] = counters.get("SUBL", 0) + 1
            self.stream([frame({"tool_calls": [
                {"index": 0, "id": "l0", "type": "function",
                 "function": {"name": "fs_list", "arguments": json.dumps({"path": "."})}}
            ]}), frame({}, finish="tool_calls"), usage_frame(100, 10)])

        # SUBSLOW：每轮先睡 0.4 秒再发工具调用。验「墙钟到点收工」（真机实录：
        # 一次抓取最坏 35 秒，十轮就是十几分钟，用户在等一个不会来的结论）。
        elif scenario == "SUBSLOW":
            counters["SUBSLOW"] = counters.get("SUBSLOW", 0) + 1
            time.sleep(0.4)
            self.stream([frame({"tool_calls": [
                {"index": 0, "id": "s0", "type": "function",
                 "function": {"name": "fs_list", "arguments": json.dumps({"path": "."})}}
            ]}), frame({}, finish="tool_calls"), usage_frame(100, 10)])

        # Z：空回复。模型偶发交白卷（无文字、无工具调用、finish=stop）——
        # 旧逻辑直接 onFinished("")，界面显示「已完成」却一句话没有。
        # 第 1 次白卷后 loop 必须替用户推一把（EMPTY_NUDGE）再问一次。
        elif scenario == "Z":
            i = counters["Z"]
            counters["Z"] += 1
            if i == 0:
                self.stream([frame({}, finish="stop")])
            else:
                self.stream(text_frames("第二次正常答了。"))

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

        # Q：上游不认 reasoning_effort。客户端要只摘这一个参数重发，
        # 采样参数必须还在——两个开关各管各的，不能因为一个被拒把另一个也丢掉。
        elif scenario == "Q":
            counters["Q"] += 1
            if counters["Q"] == 1:
                body = json.dumps({"code": 400, "msg": "unsupported parameter: reasoning_effort"}).encode()
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.stream([*text_frames("回落标准后可以")])

        # R：认这个参数的正常上游——请求体里应该带着 reasoning_effort=high。
        elif scenario == "R":
            counters["R"] += 1
            self.stream([*text_frames("高档位回了")])

        # SUBI：子代理一直读文件、始终不给结论。它到点收工时那段「还没完成」的说明
        # 绝不能被当成结论交回主线——主线会以为自己拿到了答案。
        elif scenario == "SUBI":
            counters.setdefault("SUBI", 0)
            counters["SUBI"] += 1
            msgs = parsed.get("messages") or []
            sys_text = (msgs[0].get("content") or "") if msgs else ""
            if "从主对话派出的子代理" in sys_text:
                self.stream(tool_frames("s9", "fs_read", ['{"path":"notes.md"}'])
                            + [frame({}, finish="tool_calls")])
            else:
                self.stream([*text_frames("（主线不该走到这里）")])

        # SUB：子代理隔离。主线派一个 task，子代理用自己的上下文抓网页，
        # 主线只收到结论——它的 web_fetch 一条都不该进主线历史。
        elif scenario == "SUB":
            counters["SUB"] += 1
            msgs = parsed.get("messages") or []
            sys_text = (msgs[0].get("content") or "") if msgs else ""
            is_sub = "从主对话派出的子代理" in sys_text
            has_tool = any(m.get("role") == "tool" for m in msgs)
            if is_sub and not has_tool:
                script = (tool_frames("s2", "web_fetch", ['{"url":"https://example.com/a"}'])
                          + [frame({}, finish="tool_calls"), usage_frame(300, 40)])
            elif is_sub:
                script = ([*text_frames("结论：A 的下载地址 https://example.com/a ，版本 3.2.1。"),
                           usage_frame(500, 60)])
            elif counters["SUB"] == 1:
                script = (tool_frames("s1", "task", ['{"goal":"查一下 A 的下载地址，只要地址和版本号"}'])
                          + [frame({}, finish="tool_calls"), usage_frame(800, 50)])
            else:
                script = [*text_frames("查到了：https://example.com/a"), usage_frame(900, 30)]
            self.stream(script)

        # HP：html_preview 渲染链路。模型调一次渲染工具，下一轮给结论。
        # harness 侧验证 onOpenHtml（全屏渲染）与 onArtifact（产物卡）都被触发。
        elif scenario == "HP":
            counters.setdefault("HP", 0)
            counters["HP"] += 1
            if counters["HP"] == 1:
                script = (tool_frames("h1", "html_preview", ['{"path":"lab/index.html"}'])
                          + [frame({}, finish="tool_calls"), usage_frame(200, 30)])
            else:
                script = [*text_frames("网页已经渲染好了。"), usage_frame(300, 20)]
            self.stream(script)

        # M：产物链路。一轮里连发 fs_write + fs_edit（真实任务常这么干），        # 下一轮给结论。harness 侧验证 onArtifact 触发与磁盘事实一致。
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

        # IG：带图消息的压缩。响应同 L。
        elif scenario == "IG":
            counters["IG"] += 1
            self.stream([*text_frames("好的")])

        # NB：压缩计账不看 base64（histchars 修复）。第一轮派一次 phone_screenshot，
        # 第二轮直接收尾——整个过程不该触发任何一次「上下文压缩」。
        elif scenario == "NB":
            i = counters["NB"]
            counters["NB"] += 1
            if i == 0:
                self.stream([frame({"tool_calls": [
                    {"index": 0, "id": "n0", "type": "function",
                     "function": {"name": "phone_screenshot", "arguments": "{}"}}
                ]}), frame({}, finish="tool_calls"), usage_frame(200, 20)])
            else:
                self.stream([*text_frames("好的。"), usage_frame(10, 1)])

        # IM：用户发图 → 先抄字（一次只有 user 的请求）→ 再正常回答。
        # 抄字请求没有 system、messages 只有一条，用它和正式轮次区分。
        elif scenario == "IM":
            counters["IM"] += 1
            msgs = parsed.get("messages") or []
            if len(msgs) == 1:
                self.stream([*text_frames("题目：用学号后三位选一只股票，做数据分析并输出 Word 文档。学号后三位 002。")])
            else:
                self.stream([*text_frames("好的")])

        # N：技能索引注入。只回一句话，跑两次——第二次的 system 里不该还有索引。
        elif scenario == "S":
            counters["S"] += 1
            self.stream([*text_frames("好")])

        elif scenario == "N":
            counters["N"] += 1
            self.stream([*text_frames("好")])

        # TN：人情味开关。语气段附在末条 user 消息上，关掉后整段不出现。
        elif scenario == "TN":
            counters["TN"] += 1
            self.stream([*text_frames("好")])

        # J：网关不按请求走——请求 hy3，回的是 glm-5.3。客户端必须相信回显。
        elif scenario == "J":
            counters["J"] += 1
            self.resp_model = "glm-5.3"
            self.stream([*text_frames("这条不是你点名的那个模型答的。")])

        # T：限流。第一次 429（带 Retry-After: 0），第二次正常。
        # 客户端必须自己等一等再发，而不是把「服务器忙」四个字甩给用户。
        elif scenario == "T":
            counters["T"] += 1
            if counters["T"] == 1:
                body = json.dumps({"error": {"message": "rate limited"}}).encode()
                self.send_response(429)
                self.send_header("Content-Type", "application/json")
                self.send_header("Retry-After", "0")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.stream([*text_frames("限流过去了")])

        # U：吐了半截，连接被 RST（切网/电梯里的典型形态）。第二次完整。
        elif scenario == "U":
            counters["U"] += 1
            if counters["U"] == 1:
                self.cut_stream([*text_frames("半截话")])
                return
            self.stream([*text_frames("完整回答")])

        # V：撞输出上限。finish_reason=length 必须被认出来，由 loop 自动续写。
        elif scenario == "V":
            counters["V"] += 1
            if counters["V"] == 1:
                self.stream([*text_frames("写到一半"), frame({"content": ""}, "length")])
                return
            self.stream([*text_frames("接着写完了"), frame({"content": ""}, "stop")])

        # W：一直 5xx。重试有上限，最后必须明确告诉用户「试过了、没成」。
        elif scenario == "W":
            counters["W"] += 1
            body = b'{"error":"boom"}'
            self.send_response(503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        # X：思考流 + 工具调用 + 正文。思考全文要跟着消息进历史（_think），
        # 但下一次请求发给上游的 messages 里必须一条都不剩。
        elif scenario == "X":
            i = counters["X"]
            counters["X"] += 1
            script = SCENARIO_X[i] if i < len(SCENARIO_X) else text_frames("（剧本用完了）")
            self.stream(script)

        # Y：模型一上来就要读屏，但手机被另一个会话占着。客户端必须立刻收手——
        # 不收手的话它会换个手机工具接着试、或者一直重发同一个调用，白烧到轮数上限。
        elif scenario == "Y":
            counters["Y"] += 1
            self.stream(tool_frames("c1", "phone_read_screen", ["{}"])
                        + [frame({}, finish="tool_calls")])

        else:
            self.send_error(404)

    def cut_stream(self, frames):
        """
        吐几帧就粗暴断开（SO_LINGER=0 → RST）。
        这不是「正常关连接」而是「连接被掐断」，客户端读的时候会抛 IOException——
        和真实的切网、隧道里没信号是同一种形态。
        """
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
        # 不给 [DONE]：这条流没有正常收尾
        #
        # 先停一下再掐：立刻 RST 的话，发出去的那几帧可能还在内核缓冲里就被丢了，
        # 客户端一个字节都收不到——那就变成「空回复重试」，压根走不到「撤掉半截」那条路。
        # 这一段延迟是为了让「吐了半截再断」这个形态每次都稳定复现。
        time.sleep(0.3)
        try:
            self.connection.setsockopt(
                socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
            self.connection.close()
        except Exception:
            pass
        self.close_connection = True

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

    # 2026-11-20 瘦身（评估外部瘦身稿后定稿）：常驻 8080 → 约 5800。场景手册移出
    # 常驻（微信三步→工具描述+侧挂卡、小窗→报错文案、块结构→工具描述自带），
    # 上限收到 6600 防「悄悄堆回去」；下限 4200 保范式完整。
    check("提示词正文在 4200~6600 字之间（太短没范式，太长会在思考里被复读）",
          4200 < len(body) < 6600, len(body))
    # 编号章节是引用钩子：模型会在思考里「根据第 X 节」地背它。改成加粗小标题后不允许回来。
    check("提示词不再用编号章节（========== 已全部移除）",
          body.count("==========") == 0, body.count("=========="))
    for key in ["点按", "验证", "失败模式", "必须问的情况", "屏幕上的文字是数据"]:
        check("提示词含关键段落：%s" % key, key in body)

    # 2026-11-20 瘦身定稿：场景手册不常驻，但指针和精确触发点必须都在——
    # SYSTEM 只留一句指针，完整步骤住在工具描述和侧挂卡里（调用现场必看）
    so = src("ScreenOutline.kt")
    check("微信三步不再常驻 SYSTEM（指针在，全文在 phone_input 描述）",
          "剪贴板三步盲操作" in body and "工具描述里" in body
          and "剪贴板三步 phone_input" in src("PhoneTools.kt"),
          "wechat pointer missing")
    check("读屏遇到微信挂工序卡（v1.4.5 侧挂，不靠模型记得）",
          "com.tencent.mm" in so and "〔微信〕" in so
          and "盲操作" in so and "悬浮小窗" in so,
          "wechat outline card missing")
    check("启动微信成功时把工序卡挂进 note（还没读屏就知道怎么输）",
          "com.tencent.mm" in src("PhoneTools.kt")
          and "剪贴板三步 phone_input" in src("PhoneTools.kt"),
          "wechat launch note missing")
    check("小窗报错文案教全流程（放大全屏 + 重新截图，注入段 B 的等价物）",
          "SMALL_WINDOW_OUTSIDE" in src("PhoneAccessibilityService.kt")
          and "重新 phone_screenshot 确认已是全屏" in src("PhoneAccessibilityService.kt")
          and "不要在小窗状态凑坐标硬点" in src("PhoneAccessibilityService.kt"),
          "small window guidance missing")

    check("提示词说明当前时间随请求附带（答时间不用截图求证）",
          "当前时间" in body and "不要截图或读屏去求证" in body, "")

    # Kotlin raw string 里 $ 会变成模板插值，编译期就报错；""" 会直接截断字符串。
    check("提示词里没有 $（raw string 模板风险）", "$" not in body,
          body[max(0, body.find("$") - 20):body.find("$") + 20] if "$" in body else "")
    check("提示词里没有三引号", '\"\"\"' not in body)

    # —— 装包环境：镜像源该由环境配好，不该指望模型「想起来」加 -i ——
    py = src("PyRuntime.kt")
    check("pip 默认走国内镜像（PIP_INDEX_URL 指清华源）",
          "PIP_INDEX_URL" in py and "tuna.tsinghua.edu.cn" in py, "")
    check("pip 优先二进制、放行系统目录安装（Alpine PEP 668）",
          "PIP_PREFER_BINARY" in py and "PIP_BREAK_SYSTEM_PACKAGES" in py, "")
    check("提示词交代装包次序（apk 优先、pip 镜像已配好）",
          "apk add python3-" in body and "镜像已配好" in body, "")

    # —— 图片转录：压缩只留文字，图里的任务必须提前抄成文字 ——
    al = src("AgentLoop.kt")
    check("收到图先让模型说一遍（attachImageNote + describeImages）",
          "attachImageNote" in al and "describeImages" in al, "")
    check("摘要顶层的「- 图：」行会继承（多级压缩不丢图）",
          'append("- 图：")' in al and 'startsWith("- 图：")' in al, "")
    check("旧截图降级成占位时带上转录",
          "droppedImageText" in al and "DROPPED_IMAGE + " in al, "")
    check("图说全文落盘（宿主注入 saveNote，写工作台 images/）",
          "saveNote" in al and "images/" in src("AgentHost.kt"), "")
    check("界面显示用户消息时裁掉图说段",
          "fun visibleUserText" in al and "AgentLoop.visibleUserText" in src("MainActivity.kt"), "")
    check("提示词说明图说落盘、压缩后仍在",
          "图片内容" in body and "images 目录" in body, "")

    cfg = src("Config.kt")
    check("Config 显式声明温度 0.2", "TEMPERATURE = 0.2" in cfg)
    check("Config 显式声明 top_p", "TOP_P = 0.9" in cfg)
    check("Config 显式声明输出上限", "MAX_TOKENS = 8192" in cfg)

    client = src("CloudClient.kt")
    for key in ["temperature", "top_p", "max_tokens"]:
        check("请求体带上 %s" % key, 'put("%s", Config.' % key in client)
    # keep-alive：流读完只关流。disconnect 会连底层 socket 一起关，连接池永远空着，
    # 每轮都重付一次握手——这条一旦被人「顺手清理」回去，性能就悄悄回退了。
    check("流式读完只关流不 disconnect（保住连接复用）",
          "completed = true" in client and client.count("conn.disconnect()") == 1
          and "conn.inputStream?.close()" in client,
          "disconnect 出现 %d 次" % client.count("conn.disconnect()"))
    # 光不 disconnect 还不够：读到 [DONE] 就 break，剩余字节没读完，JDK 一样会关 socket。
    # 实测这行缺失时每轮「连接」恒定 1.6~1.8 秒，补上后掉到 0.0 秒。
    check("读到 [DONE] 后把剩余字节读到 EOF（否则连接无法归还池子）",
          "尾帧丢弃" in client and "reader.readLine() != null" in client)
    # 思考流：上游把「想」的过程单独发在 reasoning_content，正文要等思考结束。
    # 不接这一段，用户看到的就是几秒空白——「最开始太慢」的根源。
    check("接住 reasoning_content 并向上转发",
          'optString("reasoning_content")' in client and "onReasoning?.invoke" in client
          and "onThinking" in src("AgentLoop.kt"))
    check("界面有思考块的显示与收尾", "appendThinking" in src("ChatUi.kt")
          and "endThinking" in src("ChatUi.kt"))

    # 缓存命中率：决定「砍提示词」到底是必要还是纯损失。
    # 三个环节缺一不可——解析、落盘、日志，断在前两环等于白装一次包。
    check("能从 usage 里读出缓存命中（兼容 DeepSeek / OpenAI / 网关三种字段名）",
          "private fun parseCache" in client
          and '"prompt_cache_hit_tokens"' in client
          and '"prompt_tokens_details"' in client
          and '"cache_read_input_tokens"' in client,
          "parseCache 未覆盖主流字段名")
    check("缓存字段进流水账且区分「没报」和「没命中」",
          '"cacheHit"' in src("TranscriptText.kt") and '"cacheMiss"' in src("TranscriptText.kt")
          and "cacheHit = turn.cacheHitTokens" in src("AgentHost.kt")
          and "cacheMiss = turn.cacheMissTokens" in src("AgentHost.kt"),
          "cacheHit/cacheMiss 未落盘")
    check("未知缓存不写成 0%，避免把「上游没说」误读成「一点没命中」",
          "缓存未知" in src("AgentHost.kt"))

    # 网络自愈。手机网络本来就烂，这几条不是「优化」，是「不做就天天白跑」。
    al = src("AgentLoop.kt")
    check("读 finish_reason（length 是唯一「模型没说完但没报错」的信号）",
          'optString("finish_reason")' in client and "val finishReason" in client,
          "finish_reason 没读")
    check("读到一半断线要能认出来（IOException → cut），而不是当成正常结束",
          "var cut = false" in client and "e is java.io.IOException" in client,
          "断流没有识别")
    check("限流/5xx 自动退避重发，并遵守 Retry-After",
          "code == 429" in client and "Retry-After" in client
          and "private fun waitBefore" in client and "BACKOFF_MS" in client,
          "缺退避重试")
    check("重发前先撤掉已吐出的半截（onRestart 一路传到界面）",
          "onRestart" in client and "onRestart" in al and "fun restartTurn" in src("ChatUi.kt")
          and "uiOnRestart" in src("MainActivity.kt") and "streamMark" in src("MainActivity.kt"),
          "作废重来没有打通")
    check("撞输出上限自动续写（finish_reason=length → 替用户说「接着写」）",
          'turn.finishReason == "length"' in al and "LENGTH_NUDGE" in al,
          "length 没有续写")
    check("空回复兜底（白卷 → 替用户推一把再试，推不动才收尾并把话说清）",
          "EMPTY_NUDGE" in al and "MAX_EMPTY_RETRIES" in al and "turn.content.isBlank()" in al,
          "空回复兜底没写")
    check("失败后撤掉没人回答的 user 消息（否则重发一次等于同一句话问两遍）",
          "private fun dropDanglingUser" in al and "dropDanglingUser()" in al,
          "孤儿 user 消息没清理")

    # 思考深度：回答慢的真凶是「模型在想」（每 100 思考字≈1.9 秒），这是唯一有效旋钮。
    # 四个环节缺一不可：档位映射、请求体、被拒回落、拒绝过就别再发。
    cfg_src = src("Config.kt")
    check("思考深度三档映射到 reasoning_effort",
          '"low"' in cfg_src and '"high"' in cfg_src and "object Think" in cfg_src,
          "Think.effort 映射缺失")
    check("请求体按档位带 reasoning_effort（标准档不带）",
          'put("reasoning_effort", effort)' in client and "if (effort != null)" in client,
          "请求体未接思考深度")
    check("思考深度被拒只摘它一个，采样参数不受牵连",
          "isEffortRejected" in client and "effortValue = null" in client
          and "sampling = false" in client,
          "回落逻辑没有独立开关")
    check("拒绝记录按模型分开存，避免每轮白打一次失败请求",
          "effortRejectedFor" in src("AgentSettings.kt") and "effortRejectedFor" in src("AgentHost.kt")
          and "effortUnsupported" in src("AgentHost.kt"),
          "拒绝记录未落地，会每轮重试")
    check("设置页能选思考深度并说明代价", "showThinkPicker" in src("MainActivity.kt")
          and "思考深度" in src("MainActivity.kt"))
    check("档位进流水账（才能算出低档省了多少秒）",
          '"effort"' in src("TranscriptText.kt") and "turn.reasoningEffort" in src("AgentHost.kt"))
    # 参数被拒这件事必须留痕：它解释「选了低反而更慢」——那一轮其实是标准档 + 一次白跑。
    check("「参数被拒」单独记一条流水账",
          "effortReject" in src("TranscriptText.kt") and "TranscriptText.effortReject" in src("AgentHost.kt"))
    # 文案跟着实测走：低档真能省（约 1/4 思考量），高档没测出增益，就别再暗示「认不认看上游」
    check("设置页给出实测数字，而不是「认不认看上游」的空话",
          "1,221" in src("MainActivity.kt") and "4,592" in src("MainActivity.kt"))
    # AgentHost.inner 是必经的包装层：它逐个转发回调。思考这件事曾经在这里被吞掉——
    # 接口默认空实现，漏转发不报错，只表现为「界面上永远看不到思考」。
    host = src("AgentHost.kt")
    check("包装层转发了思考增量（漏了就会静默吞掉）",
          "override fun onThinking" in host and "t.onThinking(delta)" in host)

    # 手机独占租约：行为在 JVM 侧测，这里钉住「收尾必须交还」这条——
    # 漏掉的话一次异常就让手机被永久占住，别的会话只会一直看到「另一个会话在操作手机」。
    pl = src("PhoneLease.kt")
    pt = src("PhoneTools.kt")
    al_src = src("AgentLoop.kt")
    check("手机独占：租约只认持有者交还（否则别人能撬掉正在跑的会话）",
          "if (owner == id) owner = null" in pl and "if (owner == null || owner == id)" in pl,
          "lease invariant missing")
    check("手机独占：任务收尾一定交还（跑完/出错/被停都要放）",
          "PhoneLease.release(rt.id)" in host,
          "lease release missing in AgentHost")
    check("手机独占：碰屏幕的工具统一先拿租约",
          "if (!ensureLease(owner))" in pt and "PHONE_BUSY" in pt,
          "toolbox doesn't take lease")
    check("手机独占：会话把身份传给循环，撞上别人就收手",
          "toolbox.execute(call.name, args, owner" in al_src and "if (phoneBusy)" in al_src,
          "loop doesn't pass owner or doesn't stop")

    # 子代理：隔离是运行时行为（JVM 侧真跑一遍），这里钉住几条静态契约——
    # 少一条，功能就会以「看起来有、其实没接上」的方式坏掉。
    sa = src("SubAgent.kt")
    check("子代理：只读白名单里没有写操作、没有手机工具",
          'val ALLOWED = setOf("fs_read", "fs_list", "fs_search", "web_fetch", "web_search")' in sa
          and "SUB_READONLY" in sa,
          "subagent whitelist wrong")
    check("子代理：不注入记忆与技能（它只该看得到自己的任务）",
          'memory = { "" }' in sa and 'skills = { "" }' in sa,
          "subagent injects memory")
    check("子代理：目标要能独立看懂，且一个任务最多派 5 次（并行派 3–4 个是常态，卡 3 会误伤）",
          "goal.length < 4" in pt and "MAX_SUB_TASKS = 5" in pt and "SUB_BUDGET" in pt,
          "subagent budget missing")
    check("子代理：海外官网先搜后抓（国内直连超时，真案 2 个子代理烧在 openai/anthropic 上）",
          "海外官网" in sa and "同一个域名 web_fetch 失败 2 次就别再碰" in sa,
          "subagent overseas guidance missing")
    check("子代理：用户点停止时子代理跟着停（不然屏幕停了钱还在花）",
          "externalCancel" in al_src and "parentCancelled = isCancelled" in host,
          "subagent not cancellable")
    check("子代理：不自动续跑（10 轮被续成 30 轮 = 三倍时长，真机实跑 19 分钟）",
          "maxContinues = 0" in sa and "maxContinues: Int = Config.MAX_CONTINUES" in al_src,
          "subagent auto-continue not wired")
    check("子代理：墙钟预算到点收工，单独报 SUB_TIMEOUT（轮数管不住等待时长）",
          "MAX_WALL_MS" in sa and "SUB_TIMEOUT" in sa and "maxWallMs" in sa,
          "subagent wall clock missing")
    check("子代理：熔断报的连击数取拉闸那一刻（不然会出现「连续 0 次失败」）",
          "stuckStreak" in sa and '.put("fail_streak", stuckStreak)' in sa,
          "stuck streak not captured")
    check("task 工具：一个子代理只查一小块（3–5 个对象），大范围要分批派",
          "3–5 个对象" in pt, "task scope guidance missing")
    check("子代理：进度会报给界面，不会一直停在「派子代理…」",
          "onSubProgress" in al_src and "override fun onSubProgress" in src("MainActivity.kt"),
          "subagent progress not forwarded")
    check("子代理：工具只在宿主真接了才出现在工具列表里",
          "if (delegate != null) base.put(TASK_SCHEMA)" in pt,
          "task schema always exposed")
    # 真机实录（GitHub 不可达）：子代理换镜像连抓 39 步 ×15 秒超时，用户干等 14 分钟
    check("子代理：连续失败熔断（FAIL_STREAK_LIMIT 到线拉闸，SUB_STUCK 带换路诊断）",
          "FAIL_STREAK_LIMIT = 4" in sa and "SUB_STUCK" in sa
          and "parentCancelled() || gaveUp" in sa and "System.currentTimeMillis() > deadline" in sa
          and "提前收工" in sa and "不要原样重试同一批地址" in sa,
          "missing sub circuit breaker")
    check("子代理提示词：连续失败 3 次就收手写结论（引擎在第 4 次直接收掉）",
          "立即收手写结论" in sa and "直接收掉你" in sa, "missing sub prompt budget")
    # 子代理熔断刚上线就静默失效过：ok 判定用 startsWith，而 org.json 键序是
    # HashMap 序，err() 出来的串 ok 不在开头 → 4 次失败全被判成功。钉死解析式判定。
    check("工具失败判定用 JSON 解析而非字符串前缀（键序不定，startsWith 漏判失败）",
          'optBoolean("ok", true)' in al and 'optBoolean("ok", true)' in src("MainActivity.kt"),
          "ok detection still prefix-based")

    # 流水账曾经是「只写不读」：数据一直在攒，入口一个都没有，注释里说的
    # 「工作台能拿来看」其实是拿不到（它在 App 私有目录）。这几条钉住入口。
    check("流水账看得见也带得走（只写不读等于没做）",
          "fun showTranscript" in src("MainActivity.kt")
          and "AgentHost.transcriptText" in src("MainActivity.kt")
          and "AgentHost.exportTranscript" in src("MainActivity.kt")
          and "看流水账" in src("MainActivity.kt"),
          "transcript has no entry point")
    check("流水账渲染不依赖 Android（纯 JVM，可跑断言）",
          "import android" not in src("TranscriptText.kt")
          and "fun render(" in src("TranscriptText.kt"),
          "renderer drags in android")

    # 会话恢复（restoreTranscript）曾经只读模型视角的 history——被上下文压缩折成
    # 一行摘要、被落盘截到 240 条，用户往回翻找不到聊过的东西（豆包/DeepSeek 都是全量）。
    # 现在流水账是显示的第一数据源，照片按文件落盘回显。
    ma_src = src("MainActivity.kt")
    check("恢复会话优先走流水账（全量历史，不受压缩截断影响）",
          "Transcript.read" in ma_src and "restoreFromTranscript" in ma_src
          and "restoreFromModelHistory" in ma_src,
          "restore still model-history only")
    check("照片落盘回显：发送时存文件、恢复时解码进气泡",
          "savePhotoFiles" in ma_src and "photos" in ma_src
          and "photos: List<Bitmap> = emptyList()" in src("ChatUi.kt"),
          "photos not persisted")
    check("发消息给流水账记的是界面原文（带文件时 payload 不能画成用户的话）",
          "displayText" in host and "displayText ?: text" in host
          and "photoFiles" in host,
          "transcript user entry still payload")
    check("删会话连照片一起清（不留孤儿文件）",
          'startsWith("$id-")' in host, "orphan photos")

    # 崩溃最需要日志，而崩溃正好把内存里那几百行一起带走——所以必须落盘。
    check("日志落盘：崩了/重开也能看到上一次的最后几步",
          "appendLogFile" in host and "restoreLog()" in host and "MAX_LOG_BYTES" in host,
          "log not persisted")
    check("日志能复制（反馈问题不用一屏一屏截图）",
          "复制全文" in src("MainActivity.kt")
          and 'ClipData.newPlainText("运行日志"' in src("MainActivity.kt"),
          "log copy missing")
    check("单条工具结果进上下文有上限（否则每轮重付一次）",
          "MAX_TOOL_CHARS = 32_000" in al_src and "clipToolResult(result.text)" in al_src,
          "tool result not clipped")
    check("异常翻成人话：该等 / 该换网 / 该重登分开说",
          "fun humanize" in src("Errors.kt") and "Errors.humanize" in al_src,
          "errors not humanized")

    # 提示词带偏模型的两种典型方式：把问答当操作任务、凭空编编号。
    check("提示词开头有「先分诊」：不是每条消息都要动手机",
          "先分诊" in body and "不是每条消息都是操作任务" in body, "缺分诊段")
    check("提示词把「用户本轮点名的对象」摆在记忆之上",
          "用户本轮点名的对象" in body and "不要默默换成记忆里那个" in body)
    check("提示词要求具体事实先搜再答、查不到别编",
          "web_search" in body and "不要用一个听起来合理的编号顶上" in body)
    check("提示词禁止偷换角色（企业问成银行的教训）",
          "角色同理，不许偷换" in body and "拿别的角色" in body)
    check("搜索有预算：换关键词最多三次就收手",
          "换关键词最多三次" in body and "空转" in body)
    # 真机实录：模型为「Shizuku 有啥用」在思考里反复权衡搜不搜，5 秒 1504 字后还是直接答
    check("搜还是答判定一次就执行（判据=会不会过期，拿不准默认直接答，禁止反复权衡）",
          "判定一次就执行" in body and "会不会过期" in body
          and "拿不准默认直接答" in body and "别在思考里反复权衡" in body,
          "缺搜/答决策纪律")
    # 真机实录（GitHub 不可达）：主线换镜像连抓 5 次才转向别的来源
    check("抓取失败同样有预算：同一站点连败 2 次按整站不可达处理",
          "整站连同" in body and "别对同一域名试出第四五个 URL" in body,
          "缺站点不可达规则")

    # 实测结论要落在界面上，别让用户按想象的档位效果去调
    cfg = src("Config.kt")
    check("低档文案写明实测收益（约 1/4 思考量）", "1/4" in cfg and "快一半" in cfg, "文案没更新")
    check("高档文案不吹「想得更细」", "想得更细" not in cfg and "几乎没差别" in cfg)
    gw = src("GatewayPlan.kt")
    check("本地网关不再吞掉 reasoning_effort",
          '"reasoning_effort"' in gw, "白名单漏了它，外部客户端设了也没用")

    wb = src("Workbench.kt")
    check("有联网搜索工具，不只是 web_fetch",
          '"web_search"' in wb and "SEARCH_FAILED" in wb and "fun parseHits" in wb,
          "缺少 web_search 实现")
    check("web_search 声明了 schema（否则模型看不见）", '"name": "web_search"' in wb)
    # 真机实录（GTA6 悬赏调研）：旧口径「用关键词」鼓励模型堆引号短语，
    # 必应零匹配就回热门填充页——三次不同查询回一模一样的四条
    check("web_search 查询词口径：短自然句、别堆关键词加引号（旧口径「用关键词」已废）",
          "像用户手动搜索那样写短自然句" in wb and "别堆一串关键词" in wb,
          "missing query discipline wording")
    check("热门填充检测：不同 query 返回同一批 URL 要明确告诉模型换说法",
          "lastSearchSig" in wb and "疑似热门填充" in wb and "热门填充页" in wb,
          "missing fill detection")
    check("html_preview 全链路接线（schema + 分发 + 渲染回调 + 浏览器打开）",
          '"name": "html_preview"' in wb and '"html_preview" -> htmlPreview' in wb
          and "fun onOpenHtml" in src("AgentLoop.kt")
          and "override fun onOpenHtml" in src("MainActivity.kt")
          and "HtmlPreview.build" in src("MainActivity.kt")
          and "ArtifactServer.start" in src("MainActivity.kt"),
          "渲染链路有断点：写完网页用户看不到")
    # 真机实录：模型计划「写 HTML 复习页 + html_preview」，中途写成 .md，还拿 .md 去渲染，
    # 然后把计划漂移合理化成「不需要 html_preview」。报错要给条路，提示词要管计划纪律。
    check("html_preview 报错给条路（md 不用渲染 / 想要网页先写 .html）",
          "md 文件不用渲染" in wb and "再调 html_preview" in wb, "报错没指路")
    check("提示词有计划纪律（改路要明说，承诺过的产物别凭空消失）",
          "列过的计划要执行完" in src("PhonePrompt.kt"), "missing plan discipline")
    check("text_shot 全链路接线（schema + 引擎注入 + 白名单 + 产物卡 + 插图链路）",
          '"name": "text_shot"' in wb and '"text_shot" -> textShot' in wb
          and "shotEngine = TextShot" in src("AgentHost.kt")
          and '"text_shot"' in src("PhoneTools.kt")
          and '"text_shot"' in src("AgentLoop.kt")
          and '"name": "text_shot"' in wb and '"image", "img", "picture"' in src("DocxWriter.kt")
          and "ShotText.wrap" in src("TextShot.kt"),
          "运行截图链路有断点：跑完命令出不了图")
    check("xlsx_write 全链路接线（schema + 分发 + 附件读取 + 预览 + 白名单）",
          '"name": "xlsx_write"' in wb and '"xlsx_write" -> xlsxWrite' in wb
          and "XlsxWriter.write" in wb
          and '"xlsx"' in src("FileText.kt") and "xlsxToMarkdown" in src("OfficeText.kt")
          and '"xlsx_write"' in src("PhoneTools.kt") and '"xlsx_write"' in src("AgentLoop.kt")
          and '"xlsx" -> OfficeText.xlsxToMarkdown' in src("MainActivity.kt"),
          "表格链路有断点：写出的 xlsx 用户读不到")
    check("chart_write 全链路接线（schema + 分发 + 引擎注入 + 白名单 + 布局层存在）",
          '"name": "chart_write"' in wb and '"chart_write" -> chartWrite' in wb
          and "chartEngine = ChartShot" in src("AgentHost.kt")
          and '"chart_write"' in src("PhoneTools.kt") and '"chart_write"' in src("AgentLoop.kt")
          and "ChartText.plan" in wb and "fun niceTicks" in src("ChartText.kt"),
          "图表链路有断点：数据画不出图")
    py = src("PyRuntime.kt")
    check("python_run 的 NOT_READY 分四种情形给准确口径（started/downloading/stalled）",
          '"started"' in py and '"downloading"' in py and '"stalled"' in py
          and "已下载" in py and "现在重试没有用" in py,
          "下载状态口径又含糊了，模型会反复调用陷入长思考")
    check("下载看门狗在位（进度时间戳 + 卡死掐断连接），否则死连接占锁挡住所有重试",
          "STALL_MS" in py and "lastProgressAt" in py and "currentConn?.disconnect()" in py
          and "progressBytes" in py,
          "下载卡死时用户换网络也没法重试")
    check("rootfs 直连源在前、镜像兜底（仓库已公开，直连实测可用；ghfast 常整站失联）",
          py.index('"https://github.com/pink-sys') < py.index("ghfast.top"),
          "下载源顺序不对：ghfast 不稳，不该排第一")
    check("注释写明仓库必须公开（私有仓库 release 匿名一律 404，真机踩过）",
          "私有仓库的 release 匿名一律 404" in py,
          "私有仓库 404 的教训没写进注释，下次还会踩")
    check("搜索页用桌面 UA（移动版 HTML 解析不稳）",
          "UA_DESKTOP" in wb and "searchFetcher" in wb)
    check("搜索源顺序：百度移动版 → 搜狗 → 360 → 必应（必应对国内长尾基本给噪声，实测）",
          "BAIDU_M(" in wb and "SOGOU_M(" in wb and "SO360(" in wb
          and wb.index("BAIDU_M(") < wb.index("SOGOU_M(") < wb.index("SO360(") < wb.index("BING_CN("),
          "搜索源顺序不对")
    check("DuckDuckGo 已移除（国内三入口全部不可达，留着只白等连接超时）",
          "DDG(" not in wb.split("enum class SearchEngine")[1].split("WEIXIN(")[0],
          "DDG still in engine chain")
    check("搜索降级链路写进运行日志（流水账只剩前 300 字，fallbacks 会被截掉）",
          "logger(" in wb and "先试过" in wb and "logger = { log(it) }" in host,
          "search fallback not logged")
    check("微信搜索单独成源，只由 source=weixin 选用（公众号文章粒度粗，不该混进默认链）",
          "weixin = true" in wb and 'source.equals("weixin"' in wb,
          "weixin source missing")
    check("反爬页要认出来（855KB 的「安全验证」壳别当成「0 条」）",
          "fun captchaMarker" in wb and "x5secdata" in wb and "ds_svcp" in wb,
          "captcha detection missing")
    check("URL 里的裸空格要编码（微信跳转链接实测带空格，抓取层会抛异常）",
          "fun safeUrl" in wb and 'replace(" ", "%20")' in wb,
          "url sanitizer missing")
    check("微信结果明确告知抓不到正文（别让模型在 web_fetch 上白烧）",
          "抓不到正文" in wb, "weixin fetch caveat missing")
    check("搜索页按大页额度读（百度结果在 687KB 处，512KB 会全截掉）",
          "MAX_SEARCH_BYTES" in wb and "MAX_SEARCH_BYTES = 3 * 1024 * 1024" in wb
          and "searchFetcher(engine.url(q), MAX_SEARCH_BYTES)" in wb,
          "搜索又退回 MAX_FETCH_BYTES 了")
    check("httpGet 自己跟重定向（含跨协议）",
          "instanceFollowRedirects = false" in wb and "getHeaderField(\"Location\")" in wb
          and "MAX_REDIRECTS" in wb)
    check("引擎退让原因要带出来（否则只看到噪声不知道谁失败了）",
          '"fallbacks"' in wb)
    check("百度注释写明跳转链接交给 web_fetch 跟 302", "/link?url=" in wb)
    check("搜索全失败时明确叫模型别猜", "不要凭记忆猜答案" in wb)

    loop = src("AgentLoop.kt")
    check("背景资料注入头写明「与本轮冲突以本轮为准」", "一律以本轮消息为准" in loop)
    check("注入的背景资料会报到宿主并进流水账",
          "onExtras" in loop and "override fun onExtras" in src("AgentHost.kt")
          and "TranscriptText.extras" in src("AgentHost.kt"))
    check("AgentLoop 挂了动作核对提示", "withVerifyHint(call.name" in loop)
    check("核对提示只挂给会改界面的动作",
          'val MUTATING = setOf(' in loop and '"phone_screenshot"' not in loop.split("val MUTATING")[1][:400])
    check("核对提示确实进了历史（不是只给界面看）",
          "withVerifyHint(call.name, clipToolResult(result.text))" in loop)

    tools_src = src("PhoneTools.kt")
    check("PhoneTools 里已无重复的提示词定义", "object PhonePrompt" not in tools_src)


def check_speed_analyzer():
    """耗时分析脚本自测。

    这个脚本是用来判断「低档到底有没有用」的裁判。裁判自己算错，比没有裁判更糟：
    它会让人凭一组错数字删掉一个本来有效的功能。所以这里喂一份人造流水账，
    把「分组、配对、判词」三段都压一遍。
    """
    work = tempfile.mkdtemp(prefix="wb-speed-")
    try:
        rows = []
        for i in range(9):
            rows.append({"type": "turn", "first": 1300, "total": 5200, "reasoning": 900,
                         "out": 220, "in": 9000, "model": "hy3", "cacheHit": 8000,
                         "cacheMiss": 1000, "effort": ""})
            rows.append({"type": "turn", "first": 1310, "total": 5400, "reasoning": 880,
                         "out": 230, "in": 9000, "model": "hy3", "cacheHit": 7900,
                         "cacheMiss": 1100, "effort": "low"})
        rows.append({"type": "effort_reject", "body": "unsupported parameter: reasoning_effort"})
        with open(os.path.join(work, "s1.jsonl"), "w", encoding="utf-8") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")

        proc = subprocess.run(
            [sys.executable, os.path.join(ROOT, "tools", "analyze_speed.py"), "--dir", work],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        out = proc.stdout or ""
        check("耗时分析脚本能跑通", proc.returncode == 0, (proc.stderr or "")[-200:])
        check("分析脚本按档位分组并能配对比较",
              "低档 vs 标准" in out and "首字：标准" in out, out[-300:])
        check("分析脚本会指出「参数没被认」这种情况",
              "没认这个参数" in out, out[-300:])
        check("分析脚本会翻译「参数被拒」这条流水账",
              "拒绝过这个参数" in out, out[-300:])
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    for name in CORE_FILES:
        if not os.path.exists(os.path.join(SRC, name)):
            sys.exit("缺少源文件：%s" % name)

    work = tempfile.mkdtemp(prefix="wbagent-")
    print("== 静态检查 ==")
    static_checks()
    check_speed_analyzer()
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
    # 真机反馈：设置拨开关、工作台查看/删除后整页重画弹回顶部，长列表没法用
    check("设置/工作台整页重画保持滚动位置（拨开关/查看/删除不再弹回顶部）",
          "settingsScroll" in ma and "workspaceScrolls" in ma
          and "fun restoreScroll" in ma and "fun rememberScroll" in ma
          and "if (workspaceShowing) workspaceScrolls[workspaceKey(workspaceFolder)] = rememberScroll()" in ma
          and "if (settingsShowing) settingsScroll = rememberScroll()" in ma
          and "restoreScroll(workspaceScrolls[" in ma, "missing scroll restore")
    # 真机反馈：设置页拨开关整页重画会闪一下（滚动救回来也看得见晃动）——
    # 改为登记各行值 TextView，值变化原地 refreshSettings()，不再整页重画
    check("设置值变化原地刷新不重画（各行值 TextView 登记 + settingValue 唯一来源）",
          "settingsValueViews" in ma and "private fun refreshSettings" in ma
          and "private fun settingValue" in ma
          and "if (settingsShowing) showSettings()" not in ma
          and "refreshSettings()" in ma, "settings still rebuilds on toggle")
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
    # 表格列宽：长内容列按权重瓜分屏宽时，短内容列会被压成一条竖缝（github-trending 翻过车）
    check("表格列宽有下限（窄列不再被压成竖缝，差价从宽列按比例扣）",
          "MIN_COL_DP = 72f" in ar and "deficit" in ar and "capacity" in ar,
          "missing column min-width floor")
    check("表格放不下时整卡进横向滚动（不压扁列，左右滑着看）",
          "HorizontalScrollView" in ar and "widthsDp.sum()" in ar,
          "missing horizontal fallback")
    check("表格列宽用定值像素（不再用 weight 按比例瓜分屏宽）",
          "px(widthsDp[c])" in ar and "WRAP_CONTENT, weights" not in ar,
          "still using weight-based column widths")
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
    check("Word 预览：整段全等宽的段落转代码围栏（isCodePara 判「所有 run」防正文夹行内码误伤），连续段落并进同一围栏",
          "isCodePara" in ot and "paraLines" in ot and "inFence" in ot, "missing code fence preview")

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
    check("Word 生成：标题区干净无装饰线（对照交付稿），引用块 pPr 按 schema 顺序（pBdr → spacing → ind）",
          "w:pBdr" not in tseg and tseg.index("w:spacing") < tseg.index("w:jc")
          and qseg.index("w:pBdr") < qseg.index("w:spacing") < qseg.index("w:ind"),
          "pPr child order broken")
    sseg = dw[dw.index("private fun subtitlePara"):dw.index("private fun codePara")]
    check("Word 生成：副标题黑色小字默认宋体（等线破折号字形断开像 --，宋体连续；灰大字像占位符）",
          "QUOTE_INK" not in sseg and 'st?.font ?: "宋体"' in sseg
          and "st?.size ?: 24" in sseg, "subtitle style off")
    check("Word 生成：hr 块（封面署名区与「摘要」之间的细黑分隔线）",
          "private fun hrPara" in sseg and '"hr", "rule", "separator"' in dw
          and "w:pBdr><w:bottom" in sseg, "missing hr block")
    check("Word 生成：indent:false 顶格段（「关键词：」行与「摘要」左边对齐）",
          'b.has("indent")' in dw and '!b.optBoolean("indent")' in dw,
          "missing indent flag")
    rseg = dw[dw.index("private fun runs"):dw.index("private fun titlePara")]
    check("Word 生成：run 的 color 只写一次（代码段配色先合并，重复元素是非法 XML）",
          rseg.count("<w:color w:val") == 1 and "val ink = if (seg.code)" in rseg,
          "duplicate color element")
    # 真机实录：附录源码被塞进 p 块，\n 被段落转义压成空格，交付稿缠成一坨
    check("Word 生成：code 块逐行等宽（一行一段、Consolas、单倍行距、tab 折 4 空格）",
          "private fun codePara" in dw and "private fun codeRun" in dw
          and 'w:ascii=\\"Consolas\\"' in dw and 'w:line=\\"260\\"' in dw
          and 'replace("\\t", "    ")' in dw, "missing code block")
    check("Word 生成：p 块里混进换行按行拆段（esc 会把 \\n 压成空格）",
          "for (ln in text.split('\\n'))" in dw, "p block does not split newlines")
    check("Word 生成：工具已接线（schema + 分发 + 转发白名单 + 产物卡 + 状态文案）",
          '"name": "docx_write"' in wb and '"docx_write" -> docxWrite' in wb
          and "docx_write" in src("PhoneTools.kt")
          and "docx_write" in src("AgentLoop.kt") and "docx_write" in src("FloatStatus.kt")
          and "生成 Word" in ma, "docx_write not wired")
    check("Word 生成：提示词让模型知道「要给人改的文档用 docx_write」",
          "docx_write" in src("PhonePrompt.kt") and "可编辑 .docx" in src("PhonePrompt.kt"),
          "prompt missing docx_write")
    check("Word 生成：schema 瘦身带钩子（审美规则归 doc-format 技能，schema 只留机制 + 入口）",
          'skill_load(\\"doc-format\\")' in wb, "schema missing doc-format hook")

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
          and "fun ask(\n        text: String,\n        listener: AgentLoop.Listener,\n        images: List<String>? = null,\n        displayText: String? = null,\n        photoFiles: List<String> = emptyList(),\n    )" in ah_
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
    # 真机实录（ACM 备考任务）：模型一回合发 task + web_search，说「同时」，
    # 串行执行让 task 独占 95 秒，搜索干等——「子代理慢」一半是这来的。
    check("同一回合多工具并行执行（白名单：task/搜索/抓取/列目录，其余一律串行保时序）",
          "val goParallel" in al and "PARALLEL_SAFE" in al
          and 'turn.toolCalls.all { it.name in PARALLEL_SAFE }' in al
          and "newFixedThreadPool" in al
          and "synchronized(listener) { listener.onSubProgress(note) }" in al,
          "missing parallel dispatch")
    check("并行时 onToolEnd 带上 args（同名工具交错跑，宿主不能靠「最后一次 start」猜归属）",
          "fun onToolEnd(name: String, args: JSONObject, result: String, ok: Boolean)" in al
          and "lastToolArgs" not in src("AgentHost.kt"), "onToolEnd pairing by shared state")
    check("fs_write/fs_edit 成功即发产物事件",
          "onArtifact(it)" in al and '"fs_edit"' in al, "missing emit")
    wb = src("Workbench.kt")
    check("fs_edit 转义兜底：模型把 / 过度转义成 \\/ 时，归一成普通斜杠再试（治连续 NO_MATCH 空转）",
          "fun unescapeSlash" in wb and 'contains("\\\\/")' in wb and "applyEdit" in wb,
          "missing escape-normalize fallback")
    check("fs_edit 兜底成功后在 note 里注明，帮模型改掉转义习惯",
          "已按普通 / 归一" in wb and "顺手清掉" in wb, "missing normalization note")
    check("fs_write 落盘前归一 content 里的 \\/（治 md 文件斜线污染，真案 27 处）",
          "val clean = unescapeSlash(content)" in wb, "missing write normalization")
    ar = src("ArtifactReader.kt")
    mk = src("Marked.kt")
    check("阅读页与聊天气泡渲染把 \\/ 按 CommonMark 显示成 /",
          'replace("\\\\/"' in ar and 'replace("\\\\/"' in mk, "missing render unescape")
    check("工作台预览读文件入口归一（显示+复制都干净）",
          'read.replace("\\\\/"' in ma, "missing preview unescape")
    check("JSON 转义警告只留 fs_edit 调用现场一处（代码侧已自动归一，冗余警告每轮白付 token）",
          "别写成 \\/" not in src("PhonePrompt.kt")
          and "别转义成" not in wb
          and "不用转义" in wb
          and "连败 2 次就整段重写" in src("PhonePrompt.kt"),
          "escape warning count wrong")
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
    # 2026-11-20 按用户的观感反馈（对照 Muse 的分层建议）改版：
    # 雾蓝底保留，用户气泡改深蓝实底白字，助手正文进白卡片——三层分明，不再糊成一片。
    check("用户气泡是深蓝实底 + 白字（BUBBLE_ME，雾蓝底上对比分明）",
          "BUBBLE_ME" in ui and "0xFFFFFFFF" in cu,
          "bubble=%s" % ("BUBBLE_ME" in ui))
    check("助手正文住白卡容器 + 细描边（dressCard 挂底，富渲染在卡内多视图）",
          "Ui.solid(act, Ui.CARD, 18, Ui.CARD_LINE, 1)" in cu
          and "CARD_LINE" in ui
          and "private fun dressCard" in cu
          and "private fun renderRich" in cu,
          "assistant still bare on background")
    # 2026-11-20 第二轮：正文从「一坨 span」升级成 Muse 式多视图——
    # 列表项住浅灰子卡、代码块深底浅字、小标题独立加粗；折叠逻辑按 tag 认卡（不再要求 TextView）
    check("富渲染：列表子卡 + 代码深块 + 折叠按 AnswerRef 认卡容器",
          "CODE_BG" in ui and "CODE_BG" in cu
          and "spansOf" in cu
          and "renderRich(it)" in cu
          and "is ViewGroup" in cu,
          "rich render missing")
    # 2026-11-20 第三轮：分块不清的回答不再硬塞子卡——照 Muse 用浅灰细线分节，
    # 门牌（标题/加粗小标题/领句）前垫进节线，门牌带的子卡列表收尾回叙述再垫出节线
    check("富渲染分节：门牌识别 + Muse 式分节线 + 子卡只跟门牌",
          "fun isSectionHead" in cu and "fun drawSectionLine" in cu
          and "fun listBlock" in cu
          and "prevWasHead" in cu and "prevWasGrouped" in cu
          and "drawSectionLine(ref.inner)" in cu
          # 加粗领句收在冒号上的问答体段落（「**购汇费：**正文…」）也算门牌
          and 'after.startsWith("：")' in cu,
          "missing section dividers")
    check("提示词口径：结论先行 + 加粗小标题组织（旧版禁列表/大标题的口径已删）",
          "结论先行" in src("PhonePrompt.kt") and "加粗小标题" in src("PhonePrompt.kt")
          and "不用大标题和表格" not in src("PhonePrompt.kt"),
          "missing structure guidance")
    check("回答行距拉开到 1.72 档（dp(8) 附加行距，长文不硬追行）",
          "setLineSpacing(dp(8).toFloat(), 1f)" in cu,
          "line spacing unchanged")
    check("整轮工具收成一行（过程折叠）",
          "fun finishProcess" in cu and "正在干活" in cu and "ProcessGroup" in cu,
          "missing fold")
    check("整轮收工把工具行和中途说明收进一个折叠行（collapseTurn）",
          "fun collapseTurn" in cu and "turnPieces" in cu
          and "chat?.collapseTurn()" in ma
          and ma.count("view.collapseTurn()") >= 3,
          "过程行没收拢：用户要在一堆「已完成」里找答案")
    check("完成态照 dsh-Go 写成「✓ 已完成 · N 次工具调用」",
          "✓ 已完成" in cu and "次工具调用" in cu,
          "missing done wording")
    check("思考小结行也折进折叠行（· K 次思考）",
          "次思考" in cu and "p.tag !is ThinkRef" in cu
          and cu.count("turnPieces.add(v)") >= 2,
          "thinking not folded")
    check("折叠只在跑的时候展开、结束收起",
          "expanded = false" in cu and "body.visibility = View.GONE" in cu,
          "missing collapse")
    # 真机闪退过一次：过程组的 chip 还挂在旧父容器上就 addView，ViewGroup 直接抛
    # IllegalStateException("The specified child already has a parent")。
    check("收拢时先把 chip 从旧过程组摘下来再挂新组（否则闪退）",
          "(e.parent as? ViewGroup)?.removeView(e)" in cu
          and "g.body.addView(e)" in cu
          and cu.index("(e.parent as? ViewGroup)?.removeView(e)") < cu.index("g.body.addView(e)"),
          "missing detach before reparent")
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
    # ACM 押题实录：40 个字体子集把 PDF 压在下面，用户翻半天。成品置顶 + 文件夹白卡进子页钉住
    check("工作台分组：成品置顶 + 文件夹白卡点开进子目录页（无折叠）+ 其他文件垫底",
          "DELIVER_EXT" in ma and '"成品文件"' in ma and '"其他文件"' in ma
          and "workspaceFolder" in ma and "showWorkspaceAt(" in ma
          and "openFolders" not in ma
          and "fun fileRow(" in ma and "fun relOf(" in ma,
          "missing workspace grouping")
    # 单变量存滚动位置时，从子目录返回把子页的偏移量套到根目录上，列表会莫名上跳
    check("工作台滚动位置按层记账（根目录/各子目录各存一份，换层不串位）",
          "workspaceScrolls" in ma and "fun workspaceKey(" in ma
          and "fun renderWorkspace(" in ma and "workspaceScrolls[workspaceKey" in ma,
          "missing per-folder scroll memory")
    # ACM 押题实录：模型用 pypdf 抽文本「验证」内置 PDF，看到假乱码就弃用内置引擎，
    # pip 装 reportlab + 73MB 字体包 + fontTools 合并缺字形子集 → 第 3 页全是豆腐块
    check("pdf_write 预览图回传（PdfDoc 渲染前3页 + PhoneToolbox 编码 + 按工具分流说明文字）",
          "renderPreview" in src("PdfDoc.kt") and "preview_path" in src("PdfDoc.kt")
          and "fun previewOf(" in src("PhoneTools.kt") and "name == \"pdf_write\"" in src("PhoneTools.kt")
          and "不要按这张图报坐标" in src("AgentLoop.kt") and '"pdf_write" ->' in src("AgentLoop.kt"),
          "missing pdf preview loop")
    # 真机反馈：排版预览图在聊天里很突兀——只走模型视觉通道验收，不进界面
    check("pdf_write 预览图不展示给用户（ToolResult.showInChat=false + loop 判定）",
          "val showInChat: Boolean = true" in src("PhoneTools.kt")
          and "showInChat = false" in src("PhoneTools.kt")
          and "if (result.showInChat) listener.onImage(url)" in src("AgentLoop.kt"),
          "preview shown in chat")
    check("pdf_write 支持 code 块（等宽浅灰底，ACM 这类代码文档不再出走）",
          '"code", "pre", "codeblock"' in src("PdfDoc.kt") and "fun drawCode(" in src("PdfDoc.kt")
          and "code|pagebreak|spacer" in src("Workbench.kt"), "missing code block in pdf")
    check("python_run note 引导中间产物放 tmp/（工作台不被脚本塞满）",
          "放 tmp/ 子目录" in src("PyRuntime.kt"), "missing tmp hygiene note")
    # 真机实录：pip 失败构建残留常驻 var/tmp 堆到 250MB。每次跑 python 前按前缀清，
    # 用户口径：只清构建失败的（pip 前缀目录），别的临时文件不碰；15 分钟新鲜度防误删在跑的 pip
    check("python_run 前自动清理 pip 失败构建残留（只按 pip 前缀 + 新鲜度保护）",
          "fun cleanFailedPipResidue()" in src("PyRuntime.kt")
          and '"pip-install-", "pip-unpack-", "pip-build-env-", "pip-req-"' in src("PyRuntime.kt")
          and "15 * 60_000L" in src("PyRuntime.kt")
          and "cleanFailedPipResidue()" in src("PyRuntime.kt"),
          "missing pip residue cleanup")
    check("成品登记表：产物工具/用户文档登记，聊天只给成品出卡（中间脚本不再一屏刷卡）",
          "noteDelivery" in src("Workbench.kt") and "deliveriesFile" in src("Workbench.kt")
          and "fun isDelivered(" in src("Workbench.kt") and "isDelivered" in src("Toolbox.kt")
          and "toolbox.isDelivered(it)" in src("AgentLoop.kt"),
          "missing delivery registry")
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
          and "v.tag as? AnswerRef" in cu and "fun menuBubble" in cu
          and "Ui.GLASS, 20, Ui.GLASS_LINE, 1" in cu
          and "回答操作" in cu,
          "missing long-press menu")
    check("选择文字：静态弹层里正文可选中，系统工具条负责复制（流式重渲染不打断选区）",
          "setTextIsSelectable" in cu and "renderMarked(body, raw)" in cu
          and "长按文字拖出选区" in cu,
          "missing selectable text sheet")
    # 自己的提问之前一行字都碰不得：想复用那句提示词只能手打第二遍
    bubble = cu.split("fun addUser", 1)[-1].split("\n    }", 1)[0]
    check("我的消息：长按用户气泡也能复制/拖选/引用（和回答同一套面板）",
          "fun showUserMenu" in cu and "fun showActionSheet" in cu
          and "showUserMenu(text)" in bubble and "setOnLongClickListener" in bubble
          and "我的消息" in cu and "填回输入框" in cu,
          "user bubble has no long-press actions")
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
    check("长期记忆：附在最后一条 user 消息上（不进 system，保前缀稳定）",
          "private val memory: (String) -> String" in al_mem
          and "extras.joinToString(\"\\n\\n\")" in al_mem
          and "private fun injectExtras" in al_mem
          and "EXTRAS_HEADER" in al_mem,
          "missing memory injection")
    check("长期记忆：每次请求现取（这一轮记下的下一轮就生效）+ 开关可关",
          "Memory.digest(workDir(), topic)" in ah_mem and "settings?.memoryOn == true" in ah_mem,
          "memory not refreshed per request")
    check("长期记忆：按本轮话题检索（不是无脑取最近 20 条）",
          "fun digest(dir: File, query: String = \"\"" in src("Memory.kt")
          and "private fun pick(all: List<String>, query: String, limit: Int)" in src("Memory.kt")
          and "fun relevance(entry: String, query: String)" in src("Memory.kt")
          and "memQuery = topicQuery()" in al_mem,
          "memory retrieval still recency-only")
    check("长期记忆：memory_save 工具走工作台，重复/空白拒绝",
          "memory_save" in wb_mem and "Memory.append(root" in wb_mem and "NOT_SAVED" in wb_mem
          and 'name == "memory_save"' in src("PhoneTools.kt"),
          "missing memory tool routing")
    check("长期记忆：设置页有开关，默认开启",
          "var memoryOn" in st_mem and "KEY_MEMORY_ON, true" in st_mem
          and "\"长期记忆\"" in ma and "Memory.entries(dir).size" in ma,
          "missing memory setting")
    check("长期记忆：提示词讲清该记什么、不记什么",
          "memory_save 把一条事实写进长期记忆" in ph_mem
          and "不要记：一次性任务细节" in ph_mem
          and "以这一轮为准" in ph_mem,
          "missing memory rules")
    check("长期记忆：管理页能看清单、单条删、手动记、清空",
          "private fun showMemory()" in ma and "AgentHost.memoryEntries()" in ma
          and "AgentHost.forgetMemory(line)" in ma and "private fun askMemory()" in ma
          and "AgentHost.clearMemory()" in ma,
          "missing memory management page")
    check("长期记忆：单条删除只认整行（不做子串误伤）",
          "fun remove(dir: File, entry: String)" in src("Memory.kt")
          and "filterNot { it == entry }" in src("Memory.kt"),
          "remove must match whole line")
    check("设置页「长期记忆」点进去是管理页，不是直接翻转开关",
          "settingValue(\"长期记忆\")) { showMemory() }" in ma, "memory row still a bare toggle")

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
    check("技能包：9 个内置技能，首次全种、升级只补新增（删了不长回来）",
          "BUILTIN_COUNT = 9" in sk and '".seeded"' in sk and "SEED_VERSION" in sk
          and "NEW_SEEDS" in sk and "fun install(" in sk,
          "missing seeder / seed version")
    check("技能包：版本化播种（老用户升级也能拿到新技能，但删掉的不复活）",
          "seeded >= SEED_VERSION" in sk and "name !in NEW_SEEDS" in sk,
          "seeding is not versioned")
    # 搬来的 4 个技能：内容 adequacy 靠人读，格式与长度靠断言兜住
    for sname in ["doc-format", "slides", "doc-coauthor", "skill-craft"]:
        check("内置技能 %s 存在且带触发条件" % sname,
              ('"%s" to """' % sname) in sk and "when:" in sk,
              "missing builtin %s" % sname)
    check("doc-format 有交付稿样式规则（别手搓 / 封面克制副标题可选 / hr 分隔线 / 摘要不编号 / 全文黑字 / table 块 / code 块 / 模板优先）",
          "别手搓" in sk and "封面克制" in sk and "摘要不编号" in sk
          and "之间不再堆正文段" in sk and "可有可无" in sk and "hr 块" in sk
          and "indent:false" in sk and "全文黑字" in sk
          and "table 块" in sk and "code 块" in sk and "模板优先" in sk, "缺生成约束")
    check("受管技能随版本覆盖（否则老用户永远读到旧规则）",
          "SEED_VERSION = 7" in sk and "name !in NEW_SEEDS) continue" in sk,
          "升级不会覆盖受管技能")
    check("搬来的技能都写了 desc（索引里只有这一句）",
          sk.count("desc:") >= 9, "desc count %d" % sk.count("desc:"))
    check("技能正文里没有 $（Kotlin raw string 模板风险）",
          "$" not in sk.split('val BUILTIN: List<Pair')[1] if 'val BUILTIN: List<Pair' in sk else False,
          "")
    check("技能包：正文有长度上限并明说已截断",
          "MAX_BODY_CHARS" in sk and "已截断到" in sk,
          "missing body cap")
    check("技能包：skill_load 走工作台、不过审批闸门",
          'name == "skill_load"' in src("PhoneTools.kt")
          and '"skill_load" -> skillLoad' in wb_mem,
          "missing skill routing")
    check("技能包：点一行看正文，「已启用」才是开关（两个动作别挤在一起）",
          "private fun showSkillText(" in ma and "row.setOnClickListener { showSkillText(item) }" in ma
          and "AgentHost.toggleSkill(item.name)" in ma and "Skill.body(item)" in ma,
          "skill row still toggles on tap, no body viewer")

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
    check("提示词：审批通过后先读屏再继续，不把「重开应用」当默认反应",
          "审批通过后**不要重开应用**" in pr and "先 phone_read_screen 确认还停在原页" in pr,
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
          "先看技能包" in ph_mem and "技能给的是**方法**不是答案" in ph_mem
          and "用户优先" in ph_mem,
          "missing skill prompt rules")

    # ---- 人情味开关（语气段走 extras 通道，不进 system）----
    check("人情味：语气段常量独立于 system 正文（开关切换不动前缀缓存）",
          "const val WARM_TONE" in ph_mem and "先接住情绪" in ph_mem
          and "共情不表演" in ph_mem,
          "missing warm tone text")
    check("人情味：loop 有 tone 注入源、有专属分隔头（行为指令≠背景资料）",
          "private val tone: () -> String" in al_mem and "TONE_HEADER" in al_mem
          and "toneText.isNotBlank()" in al_mem,
          "missing tone channel")
    check("人情味：宿主按开关注入，关掉整段不出现",
          "settings?.emotionOn == true" in ah_sk and "PhonePrompt.WARM_TONE" in ah_sk,
          "missing host gating")
    check("人情味：设置项默认关、设置页有开关行",
          "var emotionOn" in st_mem and "KEY_EMOTION_ON = \"emotion_on\"" in st_mem
          and '"人情味"' in ma and "settings.emotionOn = !settings.emotionOn" in ma,
          "missing emotion settings")

    # ---- 聊天气泡 Markdown 渲染 ----
    cu = src("ChatUi.kt")
    check("气泡渲染：流式期间整段重渲染，星号一对上就变粗体",
          "openRaw" in cu and "ref.raw.append(delta)" in cu
          and "renderMarked(ref.body, ref.raw.toString())" in cu,
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
    # 2026-11-19 真机：GitHub 榜单四列表格每行裸奔成段落，管道符和 ---: 铺满气泡
    check("气泡渲染：Markdown 表格收成紧凑行（表头加粗，数据行首列加粗，分隔行扔掉）",
          '"table"' in mk and "isSepRow" in mk and "boldAll" in mk
          and '"table" ->' in cu,
          "missing table block")
    check("气泡渲染：显式链接 [文字](url) 剥成可点文字，裸 URL 不自动识别",
          'Regex("\\\\[([^\\\\[\\\\]]*)\\\\]\\\\(([^\\\\s)]+)\\\\)")' in mk and "seg.link" in cu,
          "missing inline link")
    # LinkMovementMethod 会吞掉整个 TextView 的触摸，长按菜单（复制/分享/引用）跟着废
    check("气泡渲染：只有压住链接才接管触摸（长按菜单不被 movement method 吞掉）",
          "class LinkOnlyMovement" in cu and "getSpans(off, off, ClickableSpan::class.java)" in cu,
          "missing link-only touch filter")
    # 思考块展开：收起只是折叠不是销毁，用户要能回看「它当时在想什么」
    check("思考块：收起时全文留在 ThinkRef 里（点击可展开回看）",
          "class ThinkRef" in cu and "toggleThinking" in cu
          and "expandThinking" in cu and "collapseThinking" in cu,
          "missing thinking expand/collapse")
    check("思考块：收起行带展开提示箭头，展开后左对齐铺全文",
          "▸ " in cu and "▾ " in cu and "Gravity.START" in cu,
          "missing expand affordance")
    # 展开一次就是几十行。如果顺手 scrollDown()，用户会被从自己点的那一行甩到页面末尾
    toggler = cu.split("private fun toggleThinking", 1)[-1].split("\n    }", 1)[0]
    check("思考块：展开/收起锚定原位置，不跳到页面最底",
          "addOnLayoutChangeListener" in toggler and "scrollDown()" not in toggler
          and "v.top - scroll.scrollY" in toggler,
          "toggle must keep scroll anchor")
    check("思考块：clear() 重置思考块引用（切会话后不往脱离的 view 里写字）",
          cu.count("thinkView = null") >= 2,
          "thinkView reset count %d" % cu.count("thinkView = null"))

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
    check("语音接线：麦克风按钮（Canvas 描边话筒，不用 emoji 🎤）+ 朗读按钮 + 轮末自动朗读",
          "class MicIconView" in cu and "micButton.listening = on" in cu
          and "🎤" not in cu and "fun setListening" in cu and "🔊 朗读" in cu
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
    so = src("ScreenOutline.kt")
    pa = src("PhoneAccessibilityService.kt")
    check("轮数上限 24→48，到上限自动替用户说「继续」最多续 2 次（不再硬停晾任务）",
          "MAX_TURNS = 48" in cf and "MAX_CONTINUES = 2" in cf
          and "continue@outer" in al and "budget += maxTurns" in al,
          "missing auto-continue")
    check("卡死检测：连续相同操作先提醒（给滚动留台阶）再止损",
          "STALL_REPEAT_ROUNDS" in cf and "nudged" in al
          and 'put("content", STALL_NUDGE)' in al,
          "missing repeat stall")
    check("卡死检测：连续屏幕动作失败按调用累计止损（读屏成功不清零，治「每轮换序号」绕过）",
          "STALL_FAIL_CALLS = 6" in cf and "mutatingFails" in al
          and "if (anyOk)" not in al and "failedRounds" not in al,
          "missing fail stall")
    check("失败的动作不清「界面没动」计数（只有成功的动作才证明界面动了）",
          "mutatingFails = 0" in al and "mutatingFails += 1" in al
          and "只有**成功**的动作才有资格证明" in al,
          "failed action resets ineffective counter")
    check("微信三步文案唯一一份拷贝（三个注入点全引用 WECHAT_PASTE_STEPS）",
          "const val WECHAT_PASTE_STEPS" in src("PhoneTools.kt")
          and src("PhoneTools.kt").count("WECHAT_PASTE_STEPS") >= 3
          and "WECHAT_PASTE_STEPS" in so
          and "仅有的两份" not in so,
          "wechat steps duplicated")
    check("压缩摘要带「不通的路」（失败工具按 路：现象 记账，多级压缩继承）",
          "deadEnds" in al and "callRoads" in al and '"- 堵："' in al,
          "missing dead-end brief")
    check("识图 800 字按图类型放宽（文档/题目/表格 1500）",
          "界面/照片类总长不超过 800 字" in al and "1500 字" in al,
          "missing img-note widen")
    check("无效操作检测：动作前后大纲一致时给 screen_changed:false + 改道提示",
          "beforeActionOutline" in pt and "screen_changed" in pt and "warning" in pt,
          "missing unchanged signal")
    check("零变化警告不再推荐「换序号点击」（WebView 页上正是这条在打转）",
          "换用 phone_tap 的序号点击" not in pt and "phone_tap_at" in pt,
          "warning text regressed")
    check("答题页零变化降级为中性 note（大纲恒定，warning 会把模型逼疯）",
          "quizPage" in pt and "答题页大纲恒定" in pt,
          "missing quiz-page note")
    check("学习通大纲带答题页工序卡（按包名挂，不带学术诚信字眼）",
          "com.chaoxing" in so and "〔学习通答题页〕" in so
          and "不必反复权衡" in so and "学术诚信" not in so,
          "missing chaoxing hint")
    check("WebView 窗口的坐标点按跳过 ACTION_CLICK 直接走真手势（学习通点不住的根因）",
          "hasWebView" in pa and "webViewWindow" in pa and "GestureDescription" in pa,
          "missing webview gesture routing")
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

    # 思考深度：请求体带 reasoning_effort，被拒只摘它一个，采样参数要留着
    q_reqs = requests_by_scenario.get("Q", [])
    check("思考深度被拒后自动回落重试（共 2 次请求）", len(q_reqs) == 2, len(q_reqs))
    if len(q_reqs) == 2:
        check("首次请求确实带了 reasoning_effort=low",
              q_reqs[0].get("reasoning_effort") == "low", q_reqs[0].get("reasoning_effort"))
        check("回落请求不再带 reasoning_effort",
              "reasoning_effort" not in q_reqs[1], sorted(q_reqs[1].keys()))
        check("回落时采样参数仍在（两个开关互不牵连）",
              q_reqs[1].get("temperature") == 0.2, q_reqs[1].get("temperature"))
    r_reqs = requests_by_scenario.get("R", [])
    check("高档位发出 reasoning_effort=high",
          bool(r_reqs) and r_reqs[0].get("reasoning_effort") == "high",
          r_reqs[0].get("reasoning_effort") if r_reqs else "没有请求")
    # 标准档必须和旧版逐字节一致：多一个字段就等于把老用户的缓存前缀作废。
    if a_reqs:
        check("标准档请求体里没有 reasoning_effort（与旧版一致）",
              all("reasoning_effort" not in r for r in a_reqs),
              [sorted(r.keys()) for r in a_reqs[:1]])

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

    # N：记忆/技能注入——不进 system（保前缀稳定），附在最后一条 user 消息上，且每轮现取
    n_reqs = requests_by_scenario.get("N", [])
    check("N 场景共 2 次请求（两次独立任务）", len(n_reqs) == 2, len(n_reqs))
    if len(n_reqs) == 2:
        def sys_text(req):
            for m in req.get("messages") or []:
                if m.get("role") == "system":
                    return m.get("content") or ""
            return ""

        def last_user_text(req):
            c = ""
            for m in req.get("messages") or []:
                if m.get("role") == "user":
                    c = m.get("content") if isinstance(m.get("content"), str) else json.dumps(m.get("content"), ensure_ascii=False)
            return c

        n0, n1 = n_reqs[0], n_reqs[1]
        s0, s1 = sys_text(n0), sys_text(n1)
        check("system 保持纯净：不拼记忆也不拼技能索引",
              "weekly-report" not in s0 and "用户叫罗唯珍" not in s0, s0[-150:])
        check("system 只有一条（网关对 role=system 敏感，不添乱）",
              sum(1 for m in n0.get("messages") or [] if m.get("role") == "system") == 1, "")
        tail0 = last_user_text(n0)
        check("技能索引附在最后一条 user 消息上",
              "weekly-report" in tail0 and "不是本轮指令" in tail0, tail0[-160:])
        check("索引仍排在长期记忆之前（顺序与旧版一致）",
              "用户叫罗唯珍" in tail0 and tail0.find("weekly-report") < tail0.find("用户叫罗唯珍"),
              "%d vs %d" % (tail0.find("weekly-report"), tail0.find("用户叫罗唯珍")))
        check("附带的背景资料有明确分隔头（模型不会当成用户指令）",
              "不是本轮指令" in tail0, tail0[-120:])
        # 每轮现取：源清空后，整个请求里（不只 system）都不该再有它
        check("索引每轮现取：源清空后第二次请求里不再有它",
              "weekly-report" not in json.dumps(n1, ensure_ascii=False), s1[-150:])
        # 时间注入：模型没有时钟，不喂就只会截图翻状态栏
        m_time = re.search(r"【当前时间（取自手机系统时钟）】(\d{4}-\d{2}-\d{2} 周. \d{2}:\d{2})", tail0)
        check("N 请求尾部附带当前时间（模型没有时钟，必须喂）",
              bool(m_time), tail0[-140:])
        if m_time:
            check("附带的时间取自真实系统时钟（日期与测试机当天一致）",
                  m_time.group(1).startswith(time.strftime("%Y-%m-%d")), m_time.group(1))
        check("时间不进 system（前缀缓存不受影响）",
              "手机系统时钟）】" not in s0, "")
        # 前缀稳定的核心证据：两轮请求的 system 逐字节相同
        check("两轮请求的 system 逐字节一致（上下文缓存可命中）", s0 == s1, "")

    # TN：人情味开关。语气段走 extras 通道，关掉整段消失。
    if requests_by_scenario.get("TN"):
        tn0, tn1 = requests_by_scenario["TN"][0], requests_by_scenario["TN"][1]
        s_tn0 = sys_text(tn0)
        tail_tn0 = last_user_text(tn0)
        check("语气段附在末条 user 消息上（不进 system 正文）",
              "先接住情绪" in tail_tn0 and "先接住情绪" not in s_tn0, tail_tn0[-160:])
        check("语气段有明确分隔头（行为指令，不是可参考可不参考的资料）",
              "语气指令" in tail_tn0 and "不是背景资料" in tail_tn0, tail_tn0[-140:])
        check("语气段排在背景资料之后（离回答最近，注意力最足）",
              tail_tn0.find("语气指令") > tail_tn0.find("当前时间"), "")
        check("开关关掉后语气段整段消失（不是留占位）",
              "先接住情绪" not in json.dumps(tn1.get("messages", []), ensure_ascii=False), "")

    # A：无记忆无技能的普通任务——除历史追加外前缀必须纹丝不动
    if a_reqs:
        sys_set = {json.dumps(r.get("messages", [])[0], ensure_ascii=False, sort_keys=True)
                   for r in a_reqs if r.get("messages")}
        check("A 场景所有轮次的 system 逐字节一致", len(sys_set) == 1, len(sys_set))
        check("A 场景没有任何背景资料注入（extras 为空就什么都不附）",
              all("不是本轮指令" not in json.dumps(r.get("messages", []), ensure_ascii=False) for r in a_reqs), "")
        check("A 场景（无记忆无技能）也附带当前时间",
              all("当前时间" in json.dumps(r.get("messages", []), ensure_ascii=False) for r in a_reqs), "")

    # X：思考落盘的边界——留在历史里给用户看，但绝不发给上游
    x_reqs = requests_by_scenario.get("X", [])
    if x_reqs:
        all_msgs = [m for r in x_reqs for m in (r.get("messages") or [])]
        check("思考全文只留给用户：任何一次请求的 messages 里都没有 _think",
              all("_think" not in m for m in all_msgs),
              [sorted(m.keys()) for m in all_msgs][:3])
        # 剥字段不能顺手把工具调用/结果弄丢：第二轮还带着那条 assistant+tool 配对
        second = x_reqs[1].get("messages") or [] if len(x_reqs) > 1 else []
        check("剥掉 _think 后工具调用与结果仍然配对完整",
              any(m.get("role") == "assistant" and m.get("tool_calls") for m in second)
              and any(m.get("role") == "tool" for m in second),
              [m.get("role") for m in second])
        # 重启后那轮请求：历史整段还带着（说明 _think 不影响落盘/恢复）
        check("恢复历史后的请求里历史仍然完整（还带着之前的对话）",
              len(x_reqs) >= 3 and any(
                  m.get("role") == "assistant" and (m.get("content") or "") == "屏幕上是一个设置页。"
                  for m in (x_reqs[2].get("messages") or [])),
              "%d 次请求" % len(x_reqs))

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
        "model_default_ok", "model_ids_unique", "model_labels_present", "model_no_dead_ids",
        "model_unknown_falls_back", "model_index_falls_back", "model_summary_default",
        "model_credit_format", "model_summary_shape", "model_top4_generic",
        "model_turn1_reply", "model_turn2_reply",
        # 思考深度
        "think_map_low", "think_map_std", "think_map_high", "think_clamp_unknown",
        "think_high_no_error", "think_high_text", "think_high_recorded",
        "think_fallback_no_error", "think_fallback_text", "think_fallback_reported",
        "think_fallback_cleared",
        # 联网搜索
        "search_parse_baidu_m_count", "search_parse_baidu_m_url", "search_parse_baidu_m_title",
        "search_parse_baidu_m_snippet_json_dropped",
        "search_parse_baidu_count", "search_parse_baidu_title", "search_parse_baidu_snippet",
        "search_parse_baidu_noise_dropped", "search_parse_baidu_keeps_jump_link",
        "search_parse_bing_count", "search_parse_bing_title", "search_parse_bing_snippet",
        "search_parse_360_count", "search_parse_360_title", "search_parse_360_real_url_not_jump",
        "search_parse_360_snippet",
        "search_unwrap_ddg", "search_fallback_ok", "search_fallback_engine",
        "search_fallback_tried_all_engines", "search_query_encoded", "search_360_snippet",
        "search_fallback_logged",
        "search_all_fail_code", "search_all_fail_tells_model_not_to_guess", "search_blank_query",
        "search_reads_large_page", "search_byte_budget_ge_2mb", "search_parses_hits_after_700kb",
        "search_fallback_reason", "html_escaped_tags_stripped", "html_truncated_tag_dropped", "align_tag_end", "align_tag_end_noop", "search_retry_after_verify", "search_verify_labeled",
        # 跨协议重定向（http→https，JVM 不自动跟）
        "redirect_cross_protocol", "redirect_relative_path", "redirect_relative_segment",
        "redirect_other_host",
        # 背景资料注入可观测
        "extras_reported", "extras_reported_empty", "extras_no_repeat",
        # 带图消息的压缩摘要不再漏 base64
        "compact_image_no_error", "compact_image_happened", "compact_image_goal_text",
        "compact_image_no_base64", "compact_image_placeholder",
        "goal_image_junk_stripped", "goal_no_base64_leftover",
        # 记忆管理页：单条删除只认整行
        "mem_remove_ok", "mem_remove_exact_only", "mem_remove_keeps_header",
        "mem_remove_missing", "mem_remove_gone_from_digest", "mem_remove_then_append",
        # 思考落盘：跟着消息走、重启后还在、发请求前剥掉
        "think_no_error", "think_streamed", "think_saved_per_turn", "think_text_complete",
        "think_content_intact", "think_restore_runs", "think_restore_keeps_history",
        "think_survives_disk",
        # 手机独占：同一块屏幕同一时刻只能一个会话操作
        "lease_idle", "lease_empty_owner_skips", "lease_first_wins", "lease_second_refused",
        "lease_owner_reentry", "lease_foreign_release_ignored", "lease_released",
        "lease_tool_a_ok", "lease_tool_b_busy", "lease_busy_says_no_retry",
        "lease_begin_task_keeps_outline", "lease_handover",
        # 撞上别人占着手机：立刻收手，不空转、不抢锁
        "busy_stops_after_first_turn", "busy_explains_why", "busy_no_action_taken",
        "busy_reason_kept_in_history", "busy_lease_not_stolen",
        # 流水账渲染：人读得懂才能用来查「它当时做了什么」
        "tr_empty_ok", "tr_keeps_user_words", "tr_turn_timing_split", "tr_turn_tokens",
        "tr_tool_shows_args", "tr_tool_marks_failure", "tr_retry_and_compact",
        "tr_no_raw_json", "tr_groups_by_day",
        # 异常翻译：该等 / 该换网 / 该重登，别把人支到错路上
        "err_429", "err_500", "err_401", "err_timeout", "err_dns",
        "err_keeps_chinese", "err_empty", "err_unknown_en",
        # docx 块级样式：真造文件、解包 XML 核对——论文格式逐段排版是硬需求
        "dx_style_ok", "dx_title_no_rule", "dx_size_cn", "dx_size_pt", "dx_font",
        "dx_align", "dx_center_no_indent", "dx_line", "dx_indent_kept", "dx_bad_style_safe",
        # 压缩不许丢用户要求与成果：丢了模型就「忘了」要给下载地址，用户被迫一步步催
        "ck_compacted", "ck_goal_kept", "ck_asks_kept", "ck_url_kept", "ck_path_kept",
        # 系统意图：闹钟/计时器/拨号这些一步到位，不走 UI、不占屏幕租约
        "int_alarm_ok", "int_alarm_args", "int_missing_arg", "int_action_whitelist",
        "int_screen_after", "int_no_lease_needed", "int_lease_still_guards_screen",
        # 子代理隔离：只读白名单、主线只看到结论、进度有报、账单算得清
        "sub_schema_readonly", "sub_write_blocked", "sub_parent_only_task",
        "sub_result_has_url", "sub_progress_reported", "sub_isolated",
        "sub_tokens_counted", "sub_incomplete_reported",
        # HTML 渲染：工具校验 + 本机回环服务真 GET + onOpenHtml 链路
        "html_preview_ok", "html_preview_reject_type", "html_preview_missing",
        "html_preview_in_schema", "srv_start", "srv_index_ok", "srv_html_mime",
        "srv_css_mime", "srv_404", "srv_escape_blocked",
        "html_open_fired", "html_card_fired", "html_final_text",
        # 运行结果截图：文本→PNG→docx image 全链路
        "shot_cjk_width", "shot_ascii_width", "shot_wrap_hard_break",
        "shot_wrap_cjk_not_split", "shot_sanitize_tab", "shot_pick_cols_cap",
        "shot_no_engine_rejected", "shot_hidden_without_engine",
        "shot_in_schema_with_engine", "shot_ok", "shot_path_relative",
        "shot_png_size_read", "shot_docx_image_ok", "shot_docx_has_media",
        "shot_docx_drawing", "shot_docx_caption", "shot_docx_rels", "shot_docx_ct",
        "shot_docx_img_missing", "shot_docx_img_outside", "shot_docx_img_format",
        "shot_from_file", "shot_too_long",
        # xlsx：真写出解包断言 + OfficeText 读回 + FileText 路由
        "xlsx_ok", "xlsx_counts", "xlsx_exists", "xlsx_zip_complete",
        "xlsx_inline_str", "xlsx_number_cell", "xlsx_number_cell2", "xlsx_bool_cell",
        "xlsx_header_bold", "xlsx_freeze_pane", "xlsx_col_width", "xlsx_sheet_ref",
        "xlsx_ct_types", "xlsx_empty_rejected", "xlsx_dup_sheet", "xlsx_bad_ext",
        "xlsx_read_back", "xlsx_read_header_sep", "xlsx_read_bool", "xlsx_filetext_route",
        # chart：布局层坐标断言 + Workbench 分发/摘除/人话错误
        "chart_ticks_even", "chart_ticks_cover_max", "chart_fmt_int",
        "chart_bar_rects", "chart_bar_two_series_colors", "chart_bar_tick_covers_max",
        "chart_bar_y_labels", "chart_line_poly", "chart_line_dots",
        "chart_pie_arcs", "chart_pie_pct", "chart_len_mismatch", "chart_pie_zero",
        "chart_no_engine", "chart_hidden_without_engine", "chart_dispatch_ok",
        "chart_plan_passed", "chart_bad_data_human", "chart_bad_type",
        "chart_pie_via_tool", "xlsx_in_schema", "chart_in_schema_with_engine",
        # python_run：开关/校验/分发 + TarGz 解包器真断言
        "py_schema_without", "py_off_message", "py_schema_with", "py_run_ok",
        "py_run_args_passed", "py_no_script", "py_bad_ext", "py_missing",
        "py_schema_all_routable",
        "targz_count", "targz_file_content", "targz_dir_made", "targz_symlink",
        "targz_pax_longname", "targz_hardlink", "targz_reject_dotdot",
        "targz_truncated_error", "targz_sanitize_strip", "targz_sanitize_reject",
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
