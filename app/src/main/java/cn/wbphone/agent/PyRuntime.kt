package cn.wbphone.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** logcat 埋点标签。真机排查下载问题全靠它：`adb logcat -s PyRuntime`。 */
private const val PYTAG = "PyRuntime"

/**
 * Python 运行时：proot + Alpine rootfs，月溯里唯一的「真 Linux」。
 *
 * Android 10 起 targetSdk≥29 的应用不允许执行自己可写目录里的二进制（W^X）。
 * 绕过方式（dsh-Go 已验证、oonid/pr 在 targetSdk 35/36 真机验证）：
 * 把 proot 全家改名 lib*.so 塞进 jniLibs——APK 安装时系统把它们解到 nativeLibraryDir，
 * 那个目录归系统管、应用不可写，所以**允许执行**。PROOT_LOADER 指过去之后，
 * proot 执行 rootfs 里的 python 时走 loader 直接 mmap 载入，不经过 execve，SELinux 不拦。
 *
 * rootfs（Alpine + Python 3 + pip，约 16MB 压缩包）不内置进 APK：装完 APK 就多 16MB
 * 对不用 Python 的用户不公平。首次使用时后台下载（SHA-256 校验，防镜像投毒），
 * 解到 files/rootfs，之后所有 python_run 都直接跑。
 *
 * 线程模型：下载在单条后台线程；run 起进程同步等结果（工具调用本来就是阻塞的）。
 */
class PyRuntime(context: Context, private val workDir: File) : Workbench.PyEngine {

    init {
        // 注册共享句柄：设置页开开关时要直接触发下载、显示进度，
        // 而 PyRuntime 的下载锁是实例级的——绝不能让设置页另造一个实例跟
        // AgentHost 的实例并发下载。全应用就 AgentHost 建的这一个说了算。
        shared = this
    }

    private val appContext = context.applicationContext
    private val rootfsDir = File(appContext.filesDir, "rootfs")
    private val tmpDir = File(appContext.filesDir, "pytmp").apply { mkdirs() }
    private val nativeLibDir: String = appContext.applicationInfo.nativeLibraryDir

    /** 下载线程同时只允许一条。 */
    private val preparing = AtomicInteger(0)

    /**
     * 下载进度（字节）。@Volatile 供 run()/status() 在别的线程读——没有它，
     * 模型分不清「在下」和「卡死」，只会反复调用然后陷入长思考。
     * progressBytes<0 表示本轮还没收到第一个字节。
     */
    @Volatile private var progressBytes = -1L
    @Volatile private var progressTotal = -1L

    /** 最近一次收到字节的时刻。看门狗用它判断下载是否卡住。 */
    @Volatile private var lastProgressAt = 0L

    /** 当前在用的连接。看门狗掐断它能让卡死的 read 立刻抛异常退出。 */
    @Volatile private var currentConn: java.net.HttpURLConnection? = null

    /** 最近一次准备失败的原因，status 带出去，模型能转告用户。 */
    @Volatile private var lastError: String = ""

    // ------------------------------------------------------------ 状态

    override fun status(): String = when {
        isReady() -> "ready"
        preparing.get() > 0 -> "downloading"
        else -> "not_ready"
    }

    /** 设置页状态行用的进度小字。没在下载或还没收到数据时返回 null。 */
    fun progressLabel(): String? {
        if (preparing.get() <= 0) return null
        val total = progressTotal
        val got = progressBytes
        return if (total > 0 && got >= 0) "已下载 %.1f/%.1f MB".format(got / 1048576.0, total / 1048576.0)
        else "连接中…"
    }

    // 标记文件内容以 tag 开头才算就绪（写入时 tag 后面跟着构建信息）。
    // 解包逻辑改了（比如这次补 exec 位）tag 就得变，旧 rootfs 自动整包重装。
    // 注意是 startsWith 不是 equals——曾因写入「tag + 构建信息」却按全文比较，
    // 永远不匹配，无限重下载又回来了。
    private fun isReady(): Boolean =
        File(rootfsDir, PYVER).takeIf { it.isFile }?.readText()?.trim()?.startsWith(PYVER_TAG) == true

    /**
     * 触发后台下载。返回值区分四种情形，run() 据此给模型准确的口径：
     * ready=已就绪；started=本次调用新起了一条下载线程；
     * downloading=线程本来就在跑（附进度）；stalled=检测到卡死，刚掐断连接。
     */
    override fun prepare(): String {
        if (isReady()) return "ready"
        if (preparing.getAndIncrement() > 0) {
            preparing.decrementAndGet()
            // 看门狗：下载超过 STALL_MS 没有任何字节进展，多半是网络换了但旧连接
            // 还挂在死路上。掐断它，下载线程会自己抛异常退出、释放锁，下次调用
            // 就能带着新网络重新起一条线程。不掐的话旧线程能龟速拖几分钟，
            // 把所有重试都挡在锁外。
            if (lastProgressAt > 0 && System.currentTimeMillis() - lastProgressAt > STALL_MS) {
                Log.i(PYTAG, "watchdog: no byte progress for ${STALL_MS / 1000}s, aborting stuck connection")
                currentConn?.disconnect()
                return "stalled"
            }
            return "downloading"
        }
        progressBytes = -1L
        progressTotal = -1L
        lastProgressAt = System.currentTimeMillis()
        Thread {
            try {
                downloadAndExtract()
                lastError = ""
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                preparing.decrementAndGet()
            }
        }.start()
        return "started"
    }

    // ------------------------------------------------------------ 下载

    /**
     * 下载 + 校验 + 解包。任何一步失败都清掉半成品，不会留下「看起来就绪其实缺文件」的状态。
     */
    private fun downloadAndExtract() {
        val part = File(tmpDir, "rootfs.tar.gz.part")
        var fetched = false
        var why = "no source"
        for (url in ROOTFS_URLS) {
            try {
                fetchTo(url, part)
                fetched = true
                break
            } catch (e: Exception) {
                why = "${e.javaClass.simpleName}: ${e.message}"
                Log.i(PYTAG, "fetch failed from ${runCatching { URL(url).host }.getOrDefault(url)}: $why")
                // .part 故意留着：下一次尝试（换源或整轮重试）从断点续传，
                // 不再从 0 开始——「快下完又重头」就是这里以前直接 delete 造成的。
                // 例外：超过体积上限说明下到的是垃圾，留着只会让续传永远 416。
                if (part.length() > MAX_DOWNLOAD_BYTES) part.delete()
            }
        }
        if (!fetched) throw Exception("rootfs 下载失败（$why）")

        val sha = MessageDigest.getInstance("SHA-256").digest(part.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (sha != ROOTFS_SHA256) {
            Log.i(PYTAG, "sha mismatch: got $sha")
            part.delete()
            throw Exception("rootfs 校验失败（SHA-256 不符），丢弃了下载内容。可能是网络劫持，稍后再试")
        }
        Log.i(PYTAG, "sha ok, extracting ${part.length() / 1048576} MB")

        val staging = File(appContext.filesDir, "rootfs.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        part.inputStream().use { TarGz.extract(it, staging) }
        // proot 的绑定点：工作台 bind 到 /bench（不存在的绑定点 proot 会自动建，
        // 但预建了更稳，也不用在运行时补）
        for (d in listOf("bench", "tmp", "root")) File(staging, d).mkdirs()
        part.delete()

        // 原子换装：旧的删掉再上新的，中间窗口 status 是 not_ready，模型会重试
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        if (!staging.renameTo(rootfsDir)) {
            throw Exception("rootfs 就位失败：目录改名被系统拒绝")
        }
        // 就绪标记——isReady() 只认这个文件且内容必须等于 PYVER_TAG。**忘了写
        // 它 = 下载永远白下**：每次调用都判「未就绪」再从头下载一遍，「快下完
        // 又重新开始」的真凶就是它。内容是 tag + 构建信息，便于以后排查。
        File(rootfsDir, PYVER).writeText("$PYVER_TAG alpine-3.24 python-3.14 built 2026-11-18")
        Log.i(PYTAG, "rootfs ready at ${rootfsDir.absolutePath}")
    }

    private fun fetchTo(url: String, out: File) {
        // 断点续传：本地已有多长的 .part 就从那里接着下（GitHub 的 CDN 支持
        // Range）。服务器不支持时返回 200 全量，从头覆盖就是了。
        val already = if (out.exists()) out.length() else 0L
        val host = runCatching { URL(url).host }.getOrDefault(url)
        Log.i(PYTAG, "fetch start from $host (resume at ${already / 1048576} MB)")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            // 这是「单次 read」超时：只要还有字节滴漏就不算超时。慢网的龟速下载
            // 可能拖很久——所以必须有 lastProgressAt 看门狗（prepare 里掐断）兜底。
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", Workbench.UA_MOBILE)
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
        }
        currentConn = conn
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            // 206 = 续传被接受，响应体是「剩余部分」；200 = 给了全量（或本来就从头下）
            val resume = code == 206 && already > 0
            if (already > 0 && !resume) Log.i(PYTAG, "server ignored Range (HTTP $code), restart from 0")
            val base = if (resume) already else 0L
            val total = conn.contentLengthLong + base
            progressTotal = if (total > 0) total else -1L
            progressBytes = base
            Log.i(PYTAG, "response $code, total ${(total) / 1048576} MB, resume=$resume")
            val input = conn.inputStream
            FileOutputStream(out, resume).use { fos ->
                val buf = ByteArray(64 * 1024)
                var got = base
                var lastLog = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    got += n
                    progressBytes = got
                    lastProgressAt = System.currentTimeMillis()
                    // 每 2MB 落一行，够定位「死在第几 MB」又不刷屏
                    if (got - lastLog >= 2L * 1048576) {
                        lastLog = got
                        Log.i(PYTAG, "progress $got/$total")
                    }
                    // 上限 64MB：正常 16MB，超过说明下到了错误的东西
                    if (got > MAX_DOWNLOAD_BYTES) throw Exception("下载超出体积上限，中止")
                }
            }
            Log.i(PYTAG, "fetch done: ${out.length()} bytes")
            if (out.length() < 1_000_000) throw Exception("下载内容只有 ${out.length()} 字节，不完整")
        } finally {
            currentConn = null
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 执行

    /**
     * 跑一个 .py 脚本。脚本必须在工作台里（会以 /bench/… 出现在 rootfs 内），
     * 产物也写工作台（相对当前目录 /bench）——这样 python 生成的文件天然被
     * fs_read / docx_write / text_shot 接住，不用另通文件桥。
     */
    override fun run(script: File, args: List<String>, timeoutSec: Int, onProgress: (String) -> Unit): String {
        if (!isReady()) {
            // 触发后台下载；四种情形给模型四种准确口径，让它能如实转告用户
            // （「已经重新开始下了」和「还在下，已 4MB」是两回事，含糊会导致
            // 模型反复调用确认、陷入长思考）。prepare 万一在竞态里返回 ready
            // （别的线程刚下完），直接落到下面的真执行。
            val st = prepare()
            if (st != "ready") {
                val prevErr = lastError.takeIf { it.isNotEmpty() }?.let { "。上次失败原因：$it" } ?: ""
                val msg = when (st) {
                    "started" ->
                        if (prevErr.isEmpty()) "Python 环境首次准备：已开始后台下载（约 15.8MB，视网络几分钟）。" +
                            "转告用户后结束本轮，等用户下次发消息时再调用本工具；现在重试没有用"
                        else "上次下载失败$prevErr。这次调用已经重新开始下载（用户换了网络就会生效）。" +
                            "转告用户后结束本轮，下次消息时再试；现在重试没有用"
                    "downloading" -> {
                        val prog = if (progressTotal > 0 && progressBytes >= 0)
                            "（已下载 %.1f/%.1f MB）".format(progressBytes / 1048576.0, progressTotal / 1048576.0)
                        else "（刚启动，还没收到数据）"
                        "Python 环境正在后台下载$prog。转告用户稍等，结束本轮；" +
                            "下次消息时再调用即可看到新进度，不要连续调用查进度"
                    }
                    else -> "下载卡住了（超过 90 秒没有任何进展），刚掐断了这次尝试。" +
                        "转告用户检查网络（或换网络）后，下次发消息时会自动重新下载。结束本轮"
                }
                return err("NOT_READY", msg)
            }
        }

        val rootfs = rootfsDir.absolutePath
        cleanFailedPipResidue()
        // 脚本必须真的在工作台里，映射成 /bench/… 才传给 python
        val workPath = workDir.canonicalPath
        val scriptPath = script.canonicalPath
        if (!scriptPath.startsWith(workPath + File.separator)) {
            return err("OUTSIDE", "脚本不在工作台里：${script.name}")
        }
        val guestScript = "/bench/" + scriptPath.substring(workPath.length + 1).replace(File.separatorChar, '/')

        val proot = File(nativeLibDir, "libproot.so")
        if (!proot.isFile) {
            return err("NO_PROOT", "proot 没有随 APK 安装（nativeLibraryDir 里找不到 libproot.so）。这个构建可能不完整")
        }

        val cmd = mutableListOf(
            proot.absolutePath,
            // -R = -r rootfs + 推荐的默认绑定（/dev /proc /sys 等）
            "-R", rootfs,
            "-b", workDir.absolutePath + ":/bench",
            "-w", "/bench",
            "--kill-on-exit",
            "/usr/bin/python3", guestScript,
        )
        cmd.addAll(args)
        Log.i(PYTAG, "exec: python3 ${args.joinToString(" ")} (timeout ${timeoutSec}s)")

        val proc = try {
            ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    val env = environment()
                    env["PROOT_LOADER"] = File(nativeLibDir, "libproot-loader.so").absolutePath
                    env["PROOT_TMP_DIR"] = tmpDir.absolutePath
                    // seccomp 过滤器在部分 ROM 上会让 ptrace 出诡异问题，Termux 惯例关掉
                    env["PROOT_NO_SECCOMP"] = "1"
                    // libtalloc / libandroid-shmem 都在 nativeLibraryDir
                    env["LD_LIBRARY_PATH"] = nativeLibDir
                    env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                    env["HOME"] = "/root"
                    env["TMPDIR"] = "/tmp"
                    env["LANG"] = "C.UTF-8"
                    env["PYTHONUNBUFFERED"] = "1"
                    // —— pip 默认走国内镜像 ——
                    // 直连 pypi.org 在手机上基本等于「装不上」：真机实录里 pip 装 matplotlib
                    // 卡在源码包下载上超时，模型只能改用 apk 装系统包绕路。镜像不该靠模型
                    // 「想起来」加 -i，那是环境该配好的事——配在这里，任何 pip 调用都自动生效，
                    // 模型照常写 pip install 就行。
                    // 与 apk 源保持一致（rootfs 里 /etc/apk/repositories 已经是清华源）。
                    env["PIP_INDEX_URL"] = "https://pypi.tuna.tsinghua.edu.cn/simple"
                    env["PIP_TRUSTED_HOST"] = "pypi.tuna.tsinghua.edu.cn"
                    // 默认 15 秒对手机网络太紧，弱网一次抖动就要从头再来
                    env["PIP_DEFAULT_TIMEOUT"] = "60"
                    // Alpine 的 python 装在系统目录，PEP 668 会拦 pip，除非显式允许
                    env["PIP_BREAK_SYSTEM_PACKAGES"] = "1"
                    // 能拿 wheel 就别拿 sdist：手机上编译 C 扩展是分钟级起步，还常缺头文件
                    env["PIP_PREFER_BINARY"] = "1"
                    env["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
                }
                .start()
        } catch (e: Exception) {
            return err("SPAWN_FAILED", "proot 起不来：${e.message}")
        }
        try {
            proc.outputStream.close()
        } catch (e: Exception) {
        }

        val out = StringBuilder()
        var truncated = false
        val pump = Thread {
            try {
                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val room = MAX_PY_OUTPUT - out.length
                    if (room > 0) {
                        val take = minOf(n, room)
                        out.appendRange(buf, 0, take)
                        if (take < n) truncated = true
                    } else {
                        truncated = true // 超限继续读，防管道满死锁
                    }
                }
            } catch (e: Exception) {
            }
        }
        pump.isDaemon = true
        pump.start()

        val limit = timeoutSec.coerceIn(1, MAX_PY_TIMEOUT).toLong()

        // —— 进度上报 ticker（进程活着才报，5 秒一跳）——
        // 必须在 waitFor 之前起：长任务真正的「无声期」就是同步等结果的那几分钟
        // （真机实录：apk 装 166 个包、proot 首次 import scipy 都是分钟级，
        // 界面全程只挂一行「跑 Python」，用户以为断了）。本线程等结束后掐掉它。
        val startedAt = System.currentTimeMillis()
        val ticker = Thread {
            while (true) {
                try {
                    Thread.sleep(5_000)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!proc.isAlive) return@Thread
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                try {
                    onProgress("python 运行中 · 已 ${sec}s（上限 ${limit}s）")
                } catch (e: Exception) {
                }
            }
        }
        ticker.isDaemon = true
        ticker.start()

        val finished = try {
            proc.waitFor(limit, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            ticker.interrupt()
            proc.destroyForcibly()
            return err("INTERRUPTED", "被中断了")
        }
        ticker.interrupt()

        if (!finished) {
            proc.destroyForcibly()
            try { proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
        }
        pump.join(1000)

        val code = if (finished) runCatching { proc.exitValue() }.getOrDefault(-1) else -1
        val body = out.toString().trimEnd()

        return JSONObject()
            .put("ok", finished && code == 0)
            .put("exit", code)
            .put("timed_out", !finished)
            .put("truncated", truncated)
            .put("chars", body.length)
            .put("output", body)
            .put("note", "脚本与产物都在工作台目录（rootfs 里是 /bench）。" +
                "报错看 output；跑出的 PNG/xlsx/docx 直接用对应工具接住。" +
                "中间脚本、下载的依赖包这类过程文件放 tmp/ 子目录（会自动创建），用完可删；" +
                "几十 MB 的下载物（字体包、模型包）用完当场删掉，工作台不是仓库；" +
                "工作台根目录只留最终成品，别让用户在一堆脚本里找文件")
            .toString()
    }

    private fun err(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("error", message).toString()

    /**
     * 清理 pip 构建失败的残留。pip 的临时构建目录（/var/tmp/pip-install-* 等）
     * 构建成功时会被它自己删掉——能留下来的全是被打断/失败的那次（2026-11-21
     * 真机实录：PyMuPDF 两次失败构建悄悄堆了 250MB，用户在系统设置里看到 800MB）。
     *
     * 口径（用户要求）：只认 pip 前缀的构建目录，别的临时文件一概不碰；
     * 另加 15 分钟新鲜度保护——万一真有 pip 正在跑，也不误删它脚下的目录。
     */
    private fun cleanFailedPipResidue() {
        val varTmp = File(rootfsDir, "var/tmp")
        val staleMs = 15 * 60_000L
        val prefixes = listOf("pip-install-", "pip-unpack-", "pip-build-env-", "pip-req-")
        val now = System.currentTimeMillis()
        var freed = 0L
        runCatching {
            for (f in varTmp.listFiles() ?: emptyArray()) {
                if (prefixes.none { f.name.startsWith(it) }) continue
                if (now - f.lastModified() < staleMs) continue
                val size = f.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                if (f.deleteRecursively()) freed += size
            }
        }
        if (freed > 0) {
            Log.i(PYTAG, "cleaned %.1f MB failed pip build residue".format(freed / 1048576.0))
        }
    }

    companion object {
        /** 当前唯一的实例（AgentHost 构造时注册）。MainActivity 只读不造。 */
        @Volatile var shared: PyRuntime? = null
            private set

        private const val PYVER = "pyver.txt"

        /** rootfs 代次。解包逻辑有变更（权限位/结构）就 +1，旧环境自动重装。 */
        private const val PYVER_TAG = "v3-exec"

        /**
         * rootfs 下载源，依次尝试。都是同一个文件的镜像，SHA-256 校验兜底——
         * 镜像被投毒也拦得住，这是走第三方镜像的前提。
         * 仓库必须公开：私有仓库的 release 匿名一律 404。直连在前（仓库公开后
         * 实测可用），ghfast 兜底——它经常整站失联，但挂着只损失一次连接超时。
         */
        private val ROOTFS_URLS = listOf(
            "https://github.com/pink-sys/phone-agent/releases/latest/download/rootfs-arm64-python.tar.gz",
            "https://ghfast.top/https://github.com/pink-sys/phone-agent/releases/latest/download/rootfs-arm64-python.tar.gz",
        )

        /** 下载看门狗：超过这个时间没有收到任何字节就认定卡死，掐断连接重试。 */
        const val STALL_MS = 90_000L

        /** rootfs-arm64-python.tar.gz（Alpine 3.24 + Python 3.14 + pip，2026-11-18 构建） */
        const val ROOTFS_SHA256 = "2ad590b72f82c93d8f22ddf1e021e7fc2ce4575ea6e582c492178a06fae567d8"

        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        const val MAX_PY_OUTPUT = 30_000
        const val MAX_PY_TIMEOUT = 600
    }
}
