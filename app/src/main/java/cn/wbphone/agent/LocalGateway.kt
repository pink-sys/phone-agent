package cn.wbphone.agent

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机里的 OpenAI 兼容网关，只监听 127.0.0.1。
 *
 * 存在理由：dsh-Go 直连 WorkBuddy 会被判为「非官方渠道」而拒绝（11128），
 * 那个判定依据从外部猜不到。把出站这一跳搬到我们自己的进程里之后：
 *  - 出站请求一律按官方客户端的形态构造（含身份头、令牌、路径 /v2）；
 *  - `/v1/models` 由我们补上（网关本身没有这个端点），dsh-Go 的「获取模型」能用；
 *  - dsh-Go 的原始请求、上游的原始报错都留痕，出问题时不用再猜。
 *
 * 只绑回环地址：同一部手机上的应用能连，局域网连不进来。
 */
object LocalGateway {

    private const val HEAD_TIMEOUT_MS = 30_000
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 180_000

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptLoop: Thread? = null

    private val workers = Executors.newCachedThreadPool { r ->
        Thread(r, "local-gateway-conn").apply { isDaemon = true }
    }
    private val connSeq = AtomicInteger(0)

    /** 最近一次转发的结论，给设置页显示。 */
    @Volatile var lastResult: String = "还没收到过 dsh-Go 的请求"
        private set

    /** 最近一次请求的细节（路径、模型、上游状态、报错原文），只留一份。 */
    @Volatile var lastDetail: String = ""
        private set

    /** 哪一档请求体能过 11128，进程内记住，下次直接从这档开始。 */
    @Volatile private var winningLevel: Int = 0

    /** 当前生效的档位，给状态卡显示。 */
    val level: Int get() = winningLevel

    @Volatile private var settings: AgentSettings? = null

    /** 档位变了就落盘：冷启动直接从这档开始，不必每次先吃一发 11128。 */
    private fun rememberLevel(lvl: Int) {
        winningLevel = lvl
        try {
            settings?.gatewayLevel = lvl
        } catch (_: Exception) {
        }
    }

    /** dsh-Go 最近一次请求的原文（截断后），出问题时拷出来分析。 */
    @Volatile private var captureDir: java.io.File? = null

    val lastCaptureFile: java.io.File?
        get() = captureDir?.let { dir ->
            java.io.File(dir, "gateway-last-request.txt").takeIf { it.exists() }
        }

    private fun capture(rawBody: String) {
        val dir = captureDir ?: return
        try {
            java.io.File(dir, "gateway-last-request.txt").writeText(
                rawBody.take(400_000), Charsets.UTF_8
            )
        } catch (_: Exception) {
        }
    }

    val running: Boolean get() = server != null
    val port: Int get() = server?.localPort ?: GatewayPlan.PORT

    @Synchronized
    fun start(context: android.content.Context): Boolean {
        if (running) return true
        val socket = bindLoopback() ?: run {
            lastResult = "端口被占用，本地网关没起来"
            return false
        }
        server = socket
        captureDir = try { context.filesDir } catch (_: Exception) { null }
        try {
            settings = AgentSettings(context)
            winningLevel = settings?.gatewayLevel ?: 0
        } catch (_: Exception) {
        }
        acceptLoop = Thread({
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break // 关掉时 accept 会抛，正常退出
                }
                try {
                    workers.execute { serve(client) }
                } catch (e: Exception) {
                    closeQuietly(client)
                }
            }
        }, "local-gateway-accept").apply { isDaemon = true }
        acceptLoop?.start()
        lastResult = "已监听 " + GatewayPlan.localBaseUrl(socket.localPort)
        AgentHost.log("[网关] 已启动，dsh-Go 里填 " + GatewayPlan.localBaseUrl(socket.localPort))
        return true
    }

    @Synchronized
    fun stop() {
        acceptLoop?.interrupt()
        acceptLoop = null
        closeQuietly(server)
        server = null
    }

    /** 先试固定端口，被占就顺延；全都占不上返回 null。 */
    private fun bindLoopback(): ServerSocket? {
        val loop = InetAddress.getByName("127.0.0.1")
        for (p in GatewayPlan.ports()) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(loop, p), 8)
                }
            } catch (e: Exception) {
                // 换下一个端口
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 连接处理

    private fun serve(socket: Socket) {
        val id = connSeq.incrementAndGet()
        try {
            socket.soTimeout = HEAD_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            val head = GatewayWire.readHead(input) ?: return
            val body = GatewayWire.readBody(input, head.headers)
            socket.soTimeout = 0

            when (GatewayPlan.route(head.method, head.path)) {
                GatewayPlan.Route.MODELS -> {
                    val json = GatewayPlan.modelsJson()
                    note("模型清单 → dsh-Go（${ModelRegistry.OPTIONS.size} 个）")
                    respond(out, 200, "application/json; charset=utf-8", json.toByteArray())
                }
                GatewayPlan.Route.CHAT -> forward(out, body, id)
                GatewayPlan.Route.OTHER -> {
                    note("未支持的路径 ${head.method} ${head.path}")
                    respond(
                        out, 404, "application/json; charset=utf-8",
                        GatewayWire.jsonError(
                            "本地网关只提供 /v1/chat/completions 与 /v1/models", "not_found"
                        ).toByteArray()
                    )
                }
            }
            out.flush()
        } catch (e: Exception) {
            note("处理请求失败：${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            closeQuietly(socket)
        }
    }

    private fun forward(out: OutputStream, rawBody: String, id: Int) {
        val model = try {
            JSONObject(GatewayPlan.prepare(rawBody)).optString("model")
        } catch (e: Exception) {
            note("请求体不合规：${e.message}")
            respond(
                out, 400, "application/json; charset=utf-8",
                GatewayWire.jsonError("请求体解析失败：" + (e.message ?: "")).toByteArray()
            )
            return
        }
        capture(rawBody)

        val saved = Auth.accessToken()
        if (saved.isNullOrEmpty()) {
            note("未登录，无法转发")
            respond(
                out, 401, "application/json; charset=utf-8",
                """{"error":{"message":"本地网关没有可用令牌，请先在本应用登录 WorkBuddy","type":"unauthorized"}}""".toByteArray()
            )
            return
        }
        var token: String = saved

        // 降档实验：先从上次能过的档位开始，被 11128 拒就逐档降。别的错误不值得重试。
        val levels = (listOf(winningLevel) + (0 until GatewayPlan.BODY_LEVELS)).distinct()
        var conn: HttpURLConnection? = null
        var code = -1
        var usedLevel = winningLevel
        var lastErr = ""
        for (lvl in levels) {
            val c = open(GatewayPlan.prepareLevel(rawBody, lvl), token)
            val cd = c.responseCode
            if (cd in 200..299) {
                conn = c; code = cd; usedLevel = lvl
                break
            }
            val err = try {
                c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } catch (_: Exception) {
                ""
            }
            c.disconnect()
            lastErr = err
            if (!GatewayPlan.isBlockedError(cd, err)) {
                // 不是渠道拦截：401 就刷新令牌重来一次，其余直接把原文报回去
                if (cd == 401 || cd == 403) {
                    val refreshToken = Auth.credentials()?.refreshToken
                    val fresh = if (refreshToken.isNullOrEmpty()) "" else try {
                        Auth.refresh(refreshToken).accessToken
                    } catch (_: Exception) {
                        ""
                    }
                    if (fresh.isNotEmpty()) {
                        token = fresh
                        note("令牌过期，已自动刷新后重试")
                        return forwardAfterRefresh(out, rawBody, id, model, token)
                    }
                }
                detail("POST /chat/completions · 模型 $model · 上游 $cd\n$err")
                note("上游拒绝 · 模型 $model · HTTP $cd")
                respond(out, cd, "application/json; charset=utf-8", err.ifEmpty {
                    """{"error":{"message":"上游返回 HTTP $cd"}}"""
                }.toByteArray())
                return
            }
            note("11128 · 档位 $lvl 也被拒，降档重试")
        }

        if (conn == null || code !in 200..299) {
            // 所有档位都被 11128 拒：报错里写清试过哪些档，请求原文已留痕
            detail(
                "POST /chat/completions · 模型 $model · 全部 ${levels.size} 档请求体都被上游拒（11128）。\n" +
                    "请求原文已存到应用目录 gateway-last-request.txt，上游原文：${lastErr.take(400)}"
            )
            note("上游拒绝 · 模型 $model · HTTP $code（降档后仍未通过）")
            respond(out, code, "application/json; charset=utf-8", lastErr.ifEmpty {
                """{"error":{"message":"上游返回 HTTP $code"}}"""
            }.toByteArray())
            return
        }

        lastDetail = ""
        if (usedLevel != winningLevel) {
            rememberLevel(usedLevel)
            note("第 $usedLevel 档请求体通过，已记住：" + GatewayPlan.levelNote(usedLevel))
        }
        note("转发成功 · 模型 $model · 档位 $usedLevel · 请求 #$id")
        out.write(GatewayWire.head(200, "text/event-stream; charset=utf-8", null).toByteArray())
        out.flush()
        // 边收边发：不能让 SSE 在本地积压，否则 dsh-Go 那边的打字机效果会变成一次性吐完。
        val buf = ByteArray(4096)
        conn.inputStream.use { input ->
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    break
                }
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        }
        conn.disconnect()
    }

    /** 刷新令牌后的重放：同样带降档，但 winningLevel 已在，通常第一发就中。 */
    private fun forwardAfterRefresh(out: OutputStream, rawBody: String, id: Int, model: String, token: String) {
        val levels = (listOf(winningLevel) + (0 until GatewayPlan.BODY_LEVELS)).distinct()
        for (lvl in levels) {
            val c = open(GatewayPlan.prepareLevel(rawBody, lvl), token)
            val cd = c.responseCode
            if (cd in 200..299) {
                lastDetail = ""
                if (lvl != winningLevel) {
                    rememberLevel(lvl)
                    note("第 $lvl 档请求体通过：" + GatewayPlan.levelNote(lvl))
                }
                note("转发成功 · 模型 $model · 档位 $lvl · 请求 #$id")
                out.write(GatewayWire.head(200, "text/event-stream; charset=utf-8", null).toByteArray())
                out.flush()
                val buf = ByteArray(4096)
                c.inputStream.use { input ->
                    while (true) {
                        val n = try { input.read(buf) } catch (e: IOException) { break }
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
                c.disconnect()
                return
            }
            val err = try { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "" } catch (_: Exception) { "" }
            c.disconnect()
            if (!GatewayPlan.isBlockedError(cd, err)) {
                detail("POST /chat/completions · 模型 $model · 上游 $cd\n$err")
                respond(out, cd, "application/json; charset=utf-8", err.ifEmpty {
                    """{"error":{"message":"上游返回 HTTP $cd"}}"""
                }.toByteArray())
                return
            }
        }
        detail("POST /chat/completions · 模型 $model · 刷新令牌后仍全档被拒（11128）")
        respond(out, 400, "application/json; charset=utf-8", """{"error":{"message":"上游渠道校验未通过（11128），请求原文已留痕"}}""".toByteArray())
    }

    private fun open(body: String, token: String): HttpURLConnection {
        val conn = (URL(Config.BASE_URL + Config.REQUEST_PATH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer $token")
        }
        // 官方客户端的身份头。动态 id 每次请求都新生成，别复用。
        for ((k, v) in GatewayPlan.officialHeaders(
            conversationId = UUID.randomUUID().toString(),
            conversationRequestId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            requestId = UUID.randomUUID().toString(),
            connectionId = UUID.randomUUID().toString(),
            traceId = randomHex(16),
            spanId = randomHex(8),
            parentSpanId = randomHex(8),
        )) {
            try {
                conn.setRequestProperty(k, v)
            } catch (e: Exception) {
                // 个别头被平台禁止设置，跳过即可，不该因此中断请求
            }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn
    }

    // ---------------------------------------------------------------- HTTP 细节

    private fun respond(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        out.write(GatewayWire.head(status, contentType, body.size).toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { String.format("%02x", it) }
    }

    private fun note(message: String) {
        lastResult = message
        AgentHost.log("[网关] $message")
    }

    private fun detail(text: String) {
        lastDetail = text.take(1200)
    }

    private fun closeQuietly(c: java.io.Closeable?) {
        try {
            c?.close()
        } catch (e: Exception) {
        }
    }
}
