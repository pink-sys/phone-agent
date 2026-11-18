package cn.wbphone.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

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

    private fun isReady(): Boolean = File(rootfsDir, PYVER).isFile

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
                part.delete()
            }
        }
        if (!fetched) throw Exception("rootfs 下载失败（$why）")

        val sha = MessageDigest.getInstance("SHA-256").digest(part.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (sha != ROOTFS_SHA256) {
            part.delete()
            throw Exception("rootfs 校验失败（SHA-256 不符），丢弃了下载内容。可能是网络劫持，稍后再试")
        }

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
    }

    private fun fetchTo(url: String, out: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            // 这是「单次 read」超时：只要还有字节滴漏就不算超时。慢网的龟速下载
            // 可能拖很久——所以必须有 lastProgressAt 看门狗（prepare 里掐断）兜底。
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", Workbench.UA_MOBILE)
        }
        currentConn = conn
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val input = conn.inputStream
            progressTotal = if (total > 0) total else -1L
            out.outputStream().use { fos ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    got += n
                    progressBytes = got
                    lastProgressAt = System.currentTimeMillis()
                    // 上限 64MB：正常 16MB，超过说明下到了错误的东西
                    if (got > MAX_DOWNLOAD_BYTES) throw Exception("下载超出体积上限，中止")
                }
            }
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
    override fun run(script: File, args: List<String>, timeoutSec: Int): String {
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
        val finished = try {
            proc.waitFor(limit, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            proc.destroyForcibly()
            return err("INTERRUPTED", "被中断了")
        }
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
                "报错看 output；跑出的 PNG/xlsx/docx 直接用对应工具接住")
            .toString()
    }

    private fun err(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("error", message).toString()

    companion object {
        /** 当前唯一的实例（AgentHost 构造时注册）。MainActivity 只读不造。 */
        @Volatile var shared: PyRuntime? = null
            private set

        private const val PYVER = "pyver.txt"

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
