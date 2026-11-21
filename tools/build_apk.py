#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
不依赖 Android SDK / Gradle 的 APK 构建脚本。

它用到的四件工具全部来自 Maven 镜像（见 tools/README 的说明）：
  aapt2      资源编译与链接
  kotlinc    Kotlin 编译（kotlin-compiler-embeddable）
  d8         字节码转 dex（R8 里带的 D8）
  apksigner  APK 签名

用法：
    python tools/build_apk.py            # 日常构建，产出 build/app-debug.apk
    python tools/build_apk.py --release  # 发布构建，产出 build/yuesu-<版本>.apk
    JAVA_HOME=... WB_ANDROID_TOOLS=... python tools/build_apk.py

两种构建共用同一套工具链和同一份 keystore，区别有两点：发布构建额外做两件事——
**不编入任何凭证**（语音听写关闭），以及产物命名为 `build/yuesu-<版本>.apk` 并多打一行
SHA-256，方便贴进 Release 说明。
"""
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "build")


def find_tools_dir():
    """工具链目录：环境变量优先，其次 ~/android-tools，最后是本机老位置。"""
    env = os.environ.get("WB_ANDROID_TOOLS")
    if env:
        return env
    for cand in (os.path.join(os.path.expanduser("~"), "android-tools"), r"E:/box/android-tools"):
        if os.path.isdir(cand):
            return cand
    return os.path.join(os.path.expanduser("~"), "android-tools")


TOOLS = find_tools_dir()
LIB = os.path.join(TOOLS, "lib")

# 凭证不进仓库：写在仓库根目录的 secrets.properties（gitignored），构建时生成 Secrets.kt。
# 文件不存在的机器照样能构建——生成全空的 Secrets.kt，语音听写自动关闭，其余功能不受影响。
SECRET_KEYS = ("IAT_APP_ID", "IAT_API_KEY", "IAT_API_SECRET")
SECRETS_KT = os.path.join(
    ROOT, "app", "src", "main", "java", "cn", "wbphone", "agent", "Secrets.kt"
)


def load_secrets():
    path = os.path.join(ROOT, "secrets.properties")
    out = {}
    if not os.path.isfile(path):
        return out
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def write_secrets(dest=SECRETS_KT, enabled=True):
    """按 secrets.properties 生成 Secrets.kt。

    enabled=False 时生成全空值版本——发布包必须走这条路：APK 里的字符串是可以
    直接抠出来的，凭证一旦编进 dex 就等于公开。
    """
    values = load_secrets() if enabled else {}
    lines = [
        "package cn.wbphone.agent",
        "",
        "// 由 tools/build_apk.py 从仓库根目录的 secrets.properties 生成。",
        "// 这个文件是构建产物，不要提交，也不要把真实凭证写回源码。",
        "object Secrets {",
    ]
    for k in SECRET_KEYS:
        lines.append('    const val %s = "%s"' % (k, values.get(k, "").replace('"', '\\"')))
    lines.append("}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return bool(values)

PKG = "cn.wbphone.agent"
MIN_SDK = "26"
TARGET_SDK = "34"
VERSION_CODE = "49"
VERSION_NAME = "1.5.5"


def die(msg):
    print("\n[失败] " + msg, file=sys.stderr)
    sys.exit(1)


def is_ascii(path):
    try:
        path.encode("ascii")
        return True
    except UnicodeEncodeError:
        return False


def run(cmd, cwd=None, **kwargs):
    printable = " ".join('"%s"' % c if " " in str(c) else str(c) for c in cmd)
    print("  $ " + printable[:220])
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=cwd, **kwargs)
    if proc.returncode != 0:
        print(proc.stdout[-4000:])
        print(proc.stderr[-4000:], file=sys.stderr)
        die("命令返回 %d" % proc.returncode)
    return proc



def find_java():
    home = os.environ.get("JAVA_HOME")
    if home and os.path.isfile(os.path.join(home, "bin", "java.exe")):
        return os.path.join(home, "bin")
    if home and os.path.isfile(os.path.join(home, "bin", "java")):
        return os.path.join(home, "bin")
    candidates = sorted(glob.glob(os.path.join(TOOLS, "jdk*", "bin", "java.exe")))
    candidates += sorted(glob.glob(os.path.join(TOOLS, "jdk*", "bin", "java")))
    if not candidates:
        die("找不到 JDK。请设置 JAVA_HOME，或把 JDK 解压到 %s/jdk-*" % TOOLS)
    return os.path.dirname(candidates[-1])


def need(path, what):
    if not os.path.exists(path):
        die("缺少%s：%s\n（下载方式见 tools/README.md）" % (what, path))
    return path


def main():
    release = "--release" in sys.argv[1:]
    out_name = "yuesu-%s.apk" % VERSION_NAME if release else "app-debug.apk"

    # 凭证文件先就位：Secrets.kt 是生成的（gitignored）。
    # 发布包一律按空值生成——APK 里的字符串可以被原样抠出来，编进去就等于公开。
    has_secrets = write_secrets(enabled=not release)
    if release:
        print("发布构建：Secrets.kt 按空值生成，APK 内不含任何凭证（语音听写关闭）")
    elif not has_secrets:
        print("提示：没有 secrets.properties，语音听写的凭据为空（其他功能不受影响）")

    javabin = find_java()
    java = os.path.join(javabin, "java")
    javac = os.path.join(javabin, "javac")
    jar = os.path.join(javabin, "jar")
    keytool = os.path.join(javabin, "keytool")
    exe = ".exe" if os.name == "nt" else ""
    if os.path.isfile(java + ".exe"):
        java += ".exe"
    if os.path.isfile(javac + ".exe"):
        javac += ".exe"
    if os.path.isfile(jar + ".exe"):
        jar += ".exe"
    if os.path.isfile(keytool + ".exe"):
        keytool += ".exe"

    aapt2 = need(os.path.join(TOOLS, "bin", "aapt2" + exe), "aapt2")
    android_jar = need(os.path.join(LIB, "android-all-14.jar"), "Android 框架 jar")
    kotlinc = need(os.path.join(LIB, "kotlin-compiler-embeddable-1.9.24.jar"), "Kotlin 编译器")
    kotlin_stdlib = need(os.path.join(LIB, "kotlin-stdlib-1.9.24.jar"), "Kotlin 标准库")
    compiler_cp = [kotlinc, kotlin_stdlib]
    for extra in ("kotlin-reflect-1.9.24.jar", "kotlin-script-runtime-1.9.24.jar", "trove4j-1.0.20200330.jar", "annotations-13.0.jar"):
        compiler_cp.append(need(os.path.join(LIB, extra), "Kotlin 编译器依赖 " + extra))
    r8 = need(os.path.join(LIB, "r8-9.5.28.jar"), "R8/D8")
    apksig = need(os.path.join(LIB, "apksig-8.5.2.jar"), "apksigner")

    for rel, what in (("AndroidManifest.xml", "AndroidManifest.xml"), ("res", "res 目录"), ("java", "源码目录")):
        need(os.path.join(ROOT, "app", "src", "main", rel), what)

    # aapt2 与 kotlinc 都按系统 ANSI 代码页解析参数，路径里有中文就会读不到目录。
    # 因此只要工程路径不是纯 ASCII，就把源码复制到临时目录构建。
    if is_ascii(ROOT):
        work_root = ROOT
        cleanup = False
    else:
        work_root = tempfile.mkdtemp(prefix="wbapk-")
        shutil.copytree(os.path.join(ROOT, "app"), os.path.join(work_root, "app"))
        cleanup = True
        print("源码路径含非 ASCII 字符，改在临时目录构建：%s" % work_root)

    work = os.path.join(work_root, "build")
    if os.path.isdir(work):
        shutil.rmtree(work)
    for sub in ("gen", "classes", "dex"):
        os.makedirs(os.path.join(work, sub), exist_ok=True)

    manifest_src = os.path.join(work_root, "app", "src", "main", "AndroidManifest.xml")
    res_dir = os.path.join(work_root, "app", "src", "main", "res")
    src_dir = os.path.join(work_root, "app", "src", "main", "java")

    print("\n== 1/7 编译资源 ==")
    res_zip = os.path.join(work, "res.zip")
    run([aapt2, "compile", "--dir", res_dir, "-o", res_zip])

    print("\n== 2/7 注入包名（源码 manifest 保持 AGP 8 兼容，不带 package 属性）==")
    with open(manifest_src, "r", encoding="utf-8") as f:
        manifest = f.read()
    # 只认 <manifest ...> 这个开标签，不假定它有哪些命名空间（加了 tools 命名空间也不该崩）
    m = re.search(r"<manifest\b[^>]*>", manifest)
    if not m:
        die("AndroidManifest.xml 结构与预期不符，没法注入包名")
    tag = m.group(0)
    if "package=" not in tag:
        new_tag = tag[:-1].rstrip() + ' package="%s">' % PKG
        manifest = manifest.replace(tag, new_tag, 1)
    manifest_out = os.path.join(work, "AndroidManifest.xml")
    with open(manifest_out, "w", encoding="utf-8") as f:
        f.write(manifest)

    print("\n== 3/7 链接资源并生成 R.java ==")
    base_apk = os.path.join(work, "base.apk")
    gen_dir = os.path.join(work, "gen")
    run([
        aapt2, "link", "-o", base_apk,
        "-I", android_jar,
        "--manifest", manifest_out,
        "--java", gen_dir,
        "--min-sdk-version", MIN_SDK,
        "--target-sdk-version", TARGET_SDK,
        "--version-code", VERSION_CODE,
        "--version-name", VERSION_NAME,
        "--auto-add-overlay",
        # debug 构建开 debuggable：adb 能 run-as 进私有目录（拉流水账、看日志），
        # 这是诊断手段；release 构建绝不带这个标志。
        *(["--debug-mode"] if not release else []),
        res_zip,
    ])

    print("\n== 4/7 编译 R.java 与 Kotlin 源码 ==")
    r_java = []
    for root, _dirs, files in os.walk(gen_dir):
        for name in files:
            if name == "R.java":
                r_java.append(os.path.join(root, name))
    out_classes = os.path.join(work, "classes")
    if r_java:
        run([javac, "-source", "1.8", "-target", "1.8", "-encoding", "UTF-8", "-d", out_classes] + r_java)

    kotlin_files = []
    for root, _dirs, files in os.walk(src_dir):
        for name in files:
            if name.endswith(".kt"):
                kotlin_files.append(os.path.join(root, name))
    if not kotlin_files:
        die("没有找到任何 .kt 源文件")
    print("  Kotlin 源文件 %d 个" % len(kotlin_files))

    cp = os.pathsep.join([android_jar, kotlin_stdlib, out_classes])
    run([
        java, "-cp", os.pathsep.join(compiler_cp),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-classpath", cp,
        "-jvm-target", "1.8",
        "-no-stdlib", "-no-reflect",
        "-nowarn",
        "-d", out_classes,
    ] + kotlin_files)

    print("\n== 5/7 转 dex ==")
    classes_jar = os.path.join(work, "classes.jar")
    run([jar, "--create", "--file", classes_jar, "-C", out_classes, "."])
    dex_dir = os.path.join(work, "dex")
    run([
        java, "-cp", r8, "com.android.tools.r8.D8",
        "--release", "--min-api", MIN_SDK,
        "--lib", android_jar,
        "--output", dex_dir,
        classes_jar, kotlin_stdlib,
    ])
    dex_file = os.path.join(dex_dir, "classes.dex")
    need(dex_file, "classes.dex")

    print("\n== 6/7 组装 APK ==")
    unsigned = os.path.join(work, "app-unsigned.apk")
    with zipfile.ZipFile(base_apk) as zin, zipfile.ZipFile(unsigned, "w") as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            # Android 11+ 要求 resources.arsc 不被压缩，否则安装会被拒绝
            compress = zipfile.ZIP_STORED if item.filename == "resources.arsc" else zipfile.ZIP_DEFLATED
            zout.writestr(item.filename, data, compress_type=compress)
        with open(dex_file, "rb") as f:
            zout.writestr("classes.dex", f.read(), compress_type=zipfile.ZIP_DEFLATED)

        # proot 全家（改名 lib*.so）进 lib/arm64-v8a/，必须 STORED（不压缩）：
        # extractNativeLibs=true 时安装器按「未压缩存储」约定把它们解到
        # nativeLibraryDir，那是 Android 10 W^X 限制下唯一可执行的自带二进制位置。
        jni = os.path.join(work_root, "app", "src", "main", "jniLibs")
        if os.path.isdir(jni):
            n_so = 0
            for arch in sorted(os.listdir(jni)):
                arch_dir = os.path.join(jni, arch)
                if not os.path.isdir(arch_dir):
                    continue
                for so in sorted(os.listdir(arch_dir)):
                    if not so.endswith(".so"):
                        continue
                    with open(os.path.join(arch_dir, so), "rb") as f:
                        zout.writestr("lib/%s/%s" % (arch, so), f.read(),
                                      compress_type=zipfile.ZIP_STORED)
                    n_so += 1
            if n_so:
                print("  已打包 %d 个 native 库（lib/arm64-v8a/…）" % n_so)

    print("\n== 7/7 签名 ==")
    # keystore 必须跨构建稳定：签名一变，用户就得卸载重装、数据全丢。
    # 源码路径含中文时 work 是临时目录，所以 keystore 常驻 tools/，构建时拷进来。
    persistent_ks = os.path.join(ROOT, "tools", "debug.keystore")
    if not os.path.exists(persistent_ks):
        run([
            keytool, "-genkeypair", "-keystore", persistent_ks,
            "-storepass", "android", "-keypass", "android",
            "-alias", "wbphone", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-dname", "CN=WB Phone Agent, O=Local, C=CN",
        ])
        print("已生成常驻签名 tools/debug.keystore（之后所有构建都用它）")
    keystore = os.path.join(work, "debug.keystore")
    shutil.copyfile(persistent_ks, keystore)
    signed = os.path.join(work, out_name)
    signer_src = os.path.join(ROOT, "tools", "SignApk.java")
    need(signer_src, "SignApk.java")
    signer_out = os.path.join(work, "signer")
    os.makedirs(signer_out, exist_ok=True)
    run([javac, "-encoding", "UTF-8", "-cp", apksig, "-d", signer_out, signer_src])
    signer_cp = os.pathsep.join([apksig, signer_out])
    run([
        java, "-cp", signer_cp, "SignApk", "sign",
        keystore, "android", "android", "wbphone", unsigned, signed,
    ])
    run([java, "-cp", signer_cp, "SignApk", "verify", signed])

    print("\n== 产物校验 ==")
    badging = run([aapt2, "dump", "badging", signed]).stdout
    for line in badging.splitlines():
        if line.startswith(("package:", "application-label", "sdkVersion", "targetSdkVersion", "launchable-activity")):
            print("  " + line)

    # 拷回工程目录
    os.makedirs(BUILD, exist_ok=True)
    final = os.path.join(BUILD, out_name)
    shutil.copyfile(signed, final)
    if cleanup:
        shutil.rmtree(work_root, ignore_errors=True)

    size = os.path.getsize(final) / 1024.0
    print("\n完成：%s（%.0f KB）" % (final, size))
    if release:
        # 发布包已经打好了，把本地 Secrets.kt 恢复成带真实凭证的版本，
        # 免得下一次日常构建的语音听写莫名其妙失效。
        write_secrets()
        digest = hashlib.sha256(open(final, "rb").read()).hexdigest()
        # 发版时把这行贴进 GitHub Release 说明，用户下载后可以自己核对
        print("SHA-256：%s" % digest)
    print("安装：adb install -r \"%s\"" % final)


if __name__ == "__main__":
    main()

