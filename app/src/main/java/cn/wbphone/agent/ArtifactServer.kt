package cn.wbphone.agent

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 工作台文件的本机回环 HTTP 静态服务。
 *
 * 为什么必须有它：产物分享走的是 content:// URI（ArtifactProvider），WPS、
 * 文件管理器都认，但**手机浏览器不认**——拿到的浏览器直接把这段 URI 当搜索词
 * 发给了搜索引擎（2026-11-17 用户实测，cet6-reading-lab 就是这么「打不开」的）。
 * HTML 网页要真正在浏览器里打开，就得给它一个浏览器认识的 http:// 地址。
 *
 * 设计：
 *  - 只绑 127.0.0.1：同一部手机上的应用（自带 WebView、外部浏览器）能连，
 *    局域网连不进来——和 LocalGateway 同一条安全底线。
 *  - 根目录锁死在工作台：canonical 前缀校验，../ 和符号链接都逃不出去。
 *  - 纯 JVM（java.net），不引 Android API，能在测试 harness 里真起真连。
 *  - 一次性起、常驻不动：端口是随机的，占用一个回环端口的代价远小于每次
 *    起停的复杂度；线程全是 daemon，进程退出即消亡。
 */
object ArtifactServer {

    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "artifact-server-conn").apply { isDaemon = true }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var root: File? = null

    val running: Boolean get() = server != null

    /** 当前服务的端口（未启动时 -1，别拿来拼 URL）。 */
    val port: Int get() = server?.localPort ?: -1

    /**
     * 日志出口。默认只 println（logcat 可见）；宿主想接自己的日志就注入。
     * 故意不直接依赖 AgentHost——这层是纯 JVM，要能在测试 harness 里编译运行。
     */
    @Volatile var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        println(msg)
        onLog?.invoke(msg)
    }

    /** 起服务。已起就直接复用，换根目录不生效（工作台目录一个进程内不会变）。 */
    @Synchronized
    fun start(rootDir: File): Boolean {
        server?.let { return true }
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
            }
            server = socket
            root = rootDir.canonicalFile
            Thread({
                while (true) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    try {
                        workers.execute { serve(client) }
                    } catch (e: Exception) {
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            }, "artifact-server-accept").apply { isDaemon = true }.start()
            log("[产物服务] http://127.0.0.1:${socket.localPort}/ ← ${rootDir.name}")
            true
        } catch (e: Exception) {
            log("[产物服务] 启动失败：${e.message ?: e.javaClass.simpleName}")
            server = null
            root = null
            false
        }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        root = null
    }

    /** 某个工作台相对路径对应的本机 URL（percent 编码交给 WebView/浏览器自己拼也行，这里只做最小转义）。 */
    fun url(relPath: String): String {
        val port = port
        require(port > 0) { "产物服务还没启动" }
        val enc = relPath.trim().trimStart('/')
        .split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "http://127.0.0.1:$port/$enc"
    }

    // ---------------------------------------------------------------- 请求处理

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            // 只读请求行 + 头，读完即答，答完关连接（Connection: close）。
            // 浏览器对 HTTP/1.1 短连接完全兼容，省掉 keep-alive 的全部复杂度。
            val requestLine = readLine(input) ?: return
            var n = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (++n > 200) return   // 头多到不正常：直接断，别被人喂爆内存
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            when (method) {
                "GET", "HEAD" -> respondFile(out, method, target)
                else -> respond(out, method, 405, "text/plain; charset=utf-8",
                    "只支持 GET".toByteArray(Charsets.UTF_8))
            }
            out.flush()
        } catch (_: Exception) {
            // 单个连接出错不影响服务：浏览器会自己重试
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun respondFile(out: java.io.OutputStream, method: String, target: String) {
        val dir = root ?: return
        // 去掉 query（?a=b），percent 解码，再走 canonical 前缀校验
        val rel = target.substringBefore('?')
            .let { percentDecode(it) }
            .trimStart('/')
        if (rel.isEmpty()) {
            respond(out, method, 404, "text/plain; charset=utf-8",
                "没有目录列表，直接访问文件".toByteArray(Charsets.UTF_8))
            return
        }
        val f = File(dir, rel).canonicalFile
        if (!f.path.startsWith(dir.path + File.separator)) {
            respond(out, method, 403, "text/plain; charset=utf-8",
                "越界".toByteArray(Charsets.UTF_8))
            return
        }
        if (!f.exists() || !f.isFile) {
            respond(out, method, 404, "text/plain; charset=utf-8",
                "文件不存在：$rel".toByteArray(Charsets.UTF_8))
            return
        }
        val body = try { f.readBytes() } catch (_: Exception) {
            respond(out, method, 500, "text/plain; charset=utf-8",
                "读不出来".toByteArray(Charsets.UTF_8))
            return
        }
        respond(out, method, 200, mime(rel), body)
    }

    private fun respond(out: java.io.OutputStream, method: String, status: Int, type: String, body: ByteArray) {
        val statusLine = when (status) {
            200 -> "200 OK"; 403 -> "403 Forbidden"; 404 -> "404 Not Found"
            405 -> "405 Method Not Allowed"; else -> "500 Internal Server Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(statusLine).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        // HEAD 只要头：浏览器探测用，不吐正文
        if (method != "HEAD") out.write(body)
    }

    /**
     * percent 解码：先按 %XX 还原成字节序列，再整段按 UTF-8 解码。
     * 中文路径编码出来是三字节一组（%E4%BD%A0），逐字节 toChar() 会得到 Latin-1 乱码。
     */
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val bytes = ByteArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes[n++] = v.toByte()
                    i += 3
                    continue
                }
            }
            bytes[n++] = c.code.toByte()
            i++
        }
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    /** 扩展名 → MIME。多文件网页（css/js/图片）全靠这张表。 */
    private fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "txt" -> "text/plain; charset=utf-8"
        "md" -> "text/markdown; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder(80)
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                sb.append(c.toChar())
                if (sb.length > 8192) return null   // 单行超长：当攻击处理
            }
        }
        return sb.toString()
    }
}
