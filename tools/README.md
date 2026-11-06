# 构建工具链清单

`build_apk.py` 不需要 Android SDK，它只用下面这几个 jar。全部从 Maven 镜像下载，
放在 `$WB_ANDROID_TOOLS` 指向的目录（脚本里有个本机默认值，换机器时用环境变量覆盖即可）：

```bash
# macOS / Linux
export WB_ANDROID_TOOLS=/opt/android-tools
# Windows PowerShell
$env:WB_ANDROID_TOOLS = "D:/android-tools"
```

## 目录结构

```
android-tools/
  jdk-17.0.20.1+1/         JDK 17（含 javac / jar / keytool）
  bin/aapt2.exe            资源编译与链接
  lib/
    android-all-14.jar     Android 14 框架（等价于 SDK 的 platforms/android-34/android.jar）
    aapt2-8.5.2-*.jar      上面 aapt2.exe 的来源
    kotlin-compiler-embeddable-1.9.24.jar
    kotlin-stdlib-1.9.24.jar
    kotlin-reflect-1.9.24.jar
    kotlin-script-runtime-1.9.24.jar
    trove4j-1.0.20200330.jar
    annotations-13.0.jar
    r8-9.5.28.jar          含 D8（class → dex）
    apksig-8.5.2.jar       APK 签名库
    json-20240303.jar      仅测试时用（org.json）
```

## 为什么是这套拼法

`dl.google.com` 在本机连不上，装不了官方的 Android 命令行工具。但 Android 的构建工具
大部分是作为 Maven 构件发布的，于是：

- **android.jar** 用 Robolectric 的 `android-all`（137 MB，含 `resources.arsc` 与 `res/`，
  是 AOSP 编译出来的完整框架），`aapt2 link -I` 认它；
- **aapt2** 从 `com.android.tools.build:aapt2` 的 windows classifier 里解出来；
- **d8** 用 `com.android.tools:r8`，主类是 `com.android.tools.r8.D8`；
- **apksigner** 这个包只有库、没有命令行，所以工程里带了 `tools/SignApk.java` 直接调 API。

## 重新下载

```bash
P=https://maven.aliyun.com/repository/public
G=https://maven.aliyun.com/repository/google
cd android-tools/lib

curl -O "$G/com/android/tools/build/aapt2/8.5.2-11315950/aapt2-8.5.2-11315950-windows.jar"
curl -O "$G/com/android/tools/build/apksig/8.5.2/apksig-8.5.2.jar"
curl -O "$G/com/android/tools/r8/9.5.28/r8-9.5.28.jar"
curl -O "$P/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar"
curl -O "$P/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.24/kotlin-compiler-embeddable-1.9.24.jar"
curl -O "$P/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar"
curl -O "$P/org/jetbrains/kotlin/kotlin-reflect/1.9.24/kotlin-reflect-1.9.24.jar"
curl -O "$P/org/jetbrains/kotlin/kotlin-script-runtime/1.9.24/kotlin-script-runtime-1.9.24.jar"
curl -O "$P/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
curl -O "$P/org/jetbrains/annotations/13.0/annotations-13.0.jar"
curl -O "$P/org/json/json/20240303/json-20240303.jar"

# 解出 aapt2.exe
cd .. && mkdir -p bin && cd bin
unzip -o ../lib/aapt2-8.5.2-11315950-windows.jar 'aapt2*'
```

JDK 从清华镜像拿：

```bash
curl -O https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip
unzip -q OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip
```

## 换成真正的 Android SDK 也可以

装了官方 SDK 的话，把 `android-all-14.jar` 换成 `$ANDROID_HOME/platforms/android-34/android.jar`、
`aapt2` 换成 `build-tools/34.0.0/aapt2`、`d8` 换成 `build-tools/34.0.0/d8`，
脚本其余部分不用改。

## 测试脚本

| 脚本 | 作用 | 需要什么 |
| --- | --- | --- |
| `build_apk.py` | 出可安装的 APK | JDK + 上面那堆 jar |
| `test_agent.py` | 验证 agent loop、工具层、多模态注入、审批闸门与坐标点按（83 项） | 同上 + `json-20240303.jar` |
| `SignApk.java` | 签名与验签（apksig 无命令行入口） | 被 build_apk.py 调用 |

`test_agent.py` 只编译这 6 个文件到 JVM：`Config / DeviceApi / ScreenOutline / PhoneTools / CloudClient / AgentLoop`。
它们没有一个引 Android API——这是刻意的分层，让最关键的协议与循环能在没有真机的机器上被验证。
凡是需要 Android 的东西（Keystore、无障碍、SharedPreferences）都留在 `Auth.kt` / `PhoneAccessibilityService.kt` 里，
测试不碰，也就不会出现"测试过了真机挂"的假安全。
