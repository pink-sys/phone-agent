package cn.wbphone.agent

import org.json.JSONObject

/**
 * 本地网关的纯逻辑：路由、模型清单、请求体规整、出站请求头。
 *
 * 为什么要有一层网关：dsh-Go 直连网关会被服务端判定为「非官方渠道」（11128）。
 * 请求到底哪一处露了马脚，从外面猜不到；但把出站这一跳握在自己手里，
 * 就等于让所有请求都以官方客户端的形态发出，同时还能把 dsh-Go 的原始请求记下来。
 *
 * 这个 object 不碰 socket、不碰 Android API，所以能跟着协议层在 JVM 上跑断言。
 */
object GatewayPlan {

    /** 只监听回环，端口被占时顺延几个。 */
    const val PORT = 8787
    private const val PORT_TRIES = 12

    /** 服务端上限，超过会被拒。 */
    const val MAX_OUTPUT_TOKENS = 32_768

    enum class Route { MODELS, CHAT, OTHER }

    fun ports(): List<Int> = (PORT until PORT + PORT_TRIES).toList()

    /** 去掉查询串，统一小写，然后按路径判路由。 */
    fun route(method: String, rawPath: String): Route {
        val path = rawPath.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
        val m = method.trim().uppercase()
        return when {
            path.endsWith("/models") && (m == "GET" || m == "HEAD") -> Route.MODELS
            path.endsWith("/chat/completions") -> Route.CHAT
            else -> Route.OTHER
        }
    }

    /**
     * OpenAI 形态的模型清单。dsh-Go 的「获取模型」走的就是这个，
     * 而 WorkBuddy 网关自己没有 /models（实测 404），所以必须由这里补上。
     */
    fun modelsJson(): String {
        val data = ModelRegistry.OPTIONS.joinToString(",") { o ->
            JSONObject()
                .put("id", o.id)
                .put("object", "model")
                .put("created", 0)
                .put("owned_by", "workbuddy")
                .put("name", o.label)
                .toString()
        }
        return """{"object":"list","data":[$data]}"""
    }

    /**
     * 把 dsh-Go 发来的请求体改写成网关能稳定接受的形态。
     *
     * 三件事必须做：
     *  1. `stream` 强制 true——非流式在网关上是直接报错的（11101）；
     *  2. `model` 落回已知清单，避免一个野 id 把整轮对话变成 11102；
     *  3. 丢掉一批网关不认识、又容易触发奇怪判定的字段（`n`、`user`、`metadata` 之类）。
     */
    fun prepare(incoming: String): String = prepareLevel(incoming, 0)

    /**
     * 档位化请求体。实测已定位：11128 由 **`role=system` 的消息**触发——同样的内容改成 user
     * 角色就能过（内容、大小、tools 都不是原因）。所以阶梯按「语义损失从小到大」排：
     *
     *  0 档：完整规整（原样发，会被 11128 拒，留着它才能确认服务端当前行为）；
     *  1 档：**只把 system 改写成 user 角色，tools / 采样参数 / 历史全部保留**——语义损失最小，
     *        dsh-Go 的智能体能力完整；
     *  2 档：1 档基础上再摘掉 tools 与采样参数——排除「工具定义也参与校验」的可能；
     *  3 档：收成「system + 最后一条 user」——排除多轮历史 / 工具消息；
     *  4 档：3 档再把 system 改写成 user；
     *  5 档：3 档把 system 截到前 8K 字符；
     *  6 档：截到 2K；
     *  7 档：截到 512；
     *  8 档：只剩最后一条 user——提示词全丢，只能当聊天用，最后兜底。
     */
    const val BODY_LEVELS = 9

    fun prepareLevel(incoming: String, level: Int): String {
        val base = prepareBase(incoming)
        if (level <= 0) return base
        val o = JSONObject(base)

        // 1 档：只改角色。这是「能干活」和「能过校验」的交点，其余一概不动。
        if (level == 1) {
            o.optJSONArray("messages")?.let { o.put("messages", systemAsUser(it)) }
            return o.toString()
        }

        // 2 档起才摘 tools / tool_choice / 采样参数
        o.remove("tools")
        o.remove("tool_choice")
        for (key in listOf("temperature", "top_p", "stop", "response_format", "stream_options")) o.remove(key)
        val msgs = o.optJSONArray("messages") ?: return base
        when (level) {
            2 -> {}
            3 -> o.put("messages", systemAndLastUser(msgs))
            4 -> o.put("messages", systemAsUser(systemAndLastUser(msgs)))
            5 -> o.put("messages", truncateSystems(systemAndLastUser(msgs), 8192))
            6 -> o.put("messages", truncateSystems(systemAndLastUser(msgs), 2048))
            7 -> o.put("messages", truncateSystems(systemAndLastUser(msgs), 512))
            else -> o.put("messages", org.json.JSONArray().put(lastUserOf(msgs) ?: JSONObject().put("role", "user").put("content", "hi")))
        }
        return o.toString()
    }

    /** system → user：内容原样，只改角色。改写后模型少了系统级权威，但指令仍然跟。 */
    private fun systemAsUser(msgs: org.json.JSONArray): org.json.JSONArray {
        val out = org.json.JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role") == "system") m.put("role", "user")
            out.put(m)
        }
        return out
    }

    /** 把每条 system 的内容截到前 n 个字符，给「按长度定位触发点」用。 */
    private fun truncateSystems(msgs: org.json.JSONArray, n: Int): org.json.JSONArray {
        val out = org.json.JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role") == "system") {
                val c = m.optString("content")
                if (c.length > n) m.put("content", c.take(n))
            }
            out.put(m)
        }
        return out
    }

    private fun prepareBase(incoming: String): String {
        val src = JSONObject(incoming)
        val out = JSONObject()

        val asked = src.optString("model").trim()
        out.put("model", ModelRegistry.resolve(asked).id)

        val messages = src.optJSONArray("messages")
            ?: throw IllegalArgumentException("请求里没有 messages")
        if (messages.length() == 0) throw IllegalArgumentException("messages 是空的")
        out.put("messages", messages)

        out.put("stream", true)

        src.optJSONArray("tools")?.let { if (it.length() > 0) out.put("tools", it) }
        // tool_choice 既可能是 "auto"/"none" 这种字符串，也可能是 {"type":"function",...} 对象，
        // 只取字符串会把「强制调用某个工具」悄悄改成自动，行为就不一样了。
        if (src.has("tool_choice") && !src.isNull("tool_choice")) out.put("tool_choice", src.get("tool_choice"))

        for (key in listOf("temperature", "top_p", "stop", "response_format", "stream_options")) {
            if (src.has(key) && !src.isNull(key)) out.put(key, src.get(key))
        }
        // 思考深度：外部客户端（dsh-Go / 调试脚本）想带就带，认不认由上游决定。
        // 以前这里按白名单过滤，把它悄悄吞掉了——「设了没效果」有一半是这种静默丢弃造成的。
        if (src.has("reasoning_effort") && !src.isNull("reasoning_effort")) {
            out.put("reasoning_effort", src.get("reasoning_effort"))
        }

        // 内置联网搜索：各家参数名不一样（混元 enable_search / search_info，OpenAI web_search_options）。
        // 原样透传——认不认由上游决定，网关不该替它判断。若上游认，客户端就不用再爬搜索引擎了。
        for (key in listOf("enable_search", "search_info", "web_search_options", "search_mode")) {
            if (src.has(key) && !src.isNull(key)) out.put(key, src.get(key))
        }

        // 输出上限：网关认 32768，超出或非法就夹回来。
        var cap = when {
            src.has("max_completion_tokens") -> src.optInt("max_completion_tokens", 0)
            src.has("max_tokens") -> src.optInt("max_tokens", 0)
            else -> 0
        }
        if (cap <= 0) cap = 8_192
        if (cap > MAX_OUTPUT_TOKENS) cap = MAX_OUTPUT_TOKENS
        out.put("max_tokens", cap)

        return out.toString()
    }

    private fun lastUserOf(msgs: org.json.JSONArray): JSONObject? {
        for (i in msgs.length() - 1 downTo 0) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") return m
        }
        return null
    }

    private fun systemAndLastUser(msgs: org.json.JSONArray): org.json.JSONArray {
        val out = org.json.JSONArray()
        val lastUser = lastUserOf(msgs)
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role") == "system" || m == lastUser) out.put(m)
        }
        if (out.length() == 0) lastUser?.let { out.put(it) }
        return out
    }

    /** 只有 11128（渠道校验）才值得降档重试；别的错误（11101/11102/401）重试也不会好。 */
    fun isBlockedError(status: Int, errBody: String): Boolean =
        status == 400 && errBody.contains("11128")

    /** 档位的人话解释，状态卡上直接给用户看，别让人对着一个数字猜能力损失了多少。 */
    fun levelNote(level: Int): String = when (level) {
        0 -> "原样发送（会被渠道校验拒；能走到这档说明服务端改了规则）"
        1 -> "system 已改写成 user 角色，提示词与工具定义完整——智能体能力齐全"
        2 -> "已摘掉工具定义与采样参数——只能聊天，不能调用工具"
        3 -> "只保留 system 与最后一条提问——多轮上下文丢失"
        4 -> "只保留 system 与最后一条提问，且改了角色——上下文丢失"
        5 -> "系统提示词截到 8K——长提示词的一部分规则会失效"
        6 -> "系统提示词截到 2K——大部分规则失效"
        7 -> "系统提示词截到 512——几乎只剩一句话"
        else -> "只剩最后一条提问——提示词全丢，只能当聊天用"
    }

    /**
     * 官方客户端在会话请求上带的那组身份头。
     *
     * 这组头的来源是桌面端 `applyGatewayHeaders()`：它把 IDE 类型、产品线、请求来源
     * 都标了出来。值本身不是密钥，但少了它们，服务端就可能认为请求不是官方客户端发的。
     */
    fun officialHeaders(
        conversationId: String,
        conversationRequestId: String,
        messageId: String,
        requestId: String,
        connectionId: String,
        traceId: String,
        spanId: String,
        parentSpanId: String,
    ): Map<String, String> = linkedMapOf(
        "x-requested-with" to "XMLHttpRequest",
        "X-Conversation-ID" to conversationId,
        "X-Conversation-Request-ID" to conversationRequestId,
        "X-Conversation-Message-ID" to messageId,
        "X-Request-ID" to requestId,
        "acp-connection-id" to connectionId,
        "X-Agent-Intent" to "craft",
        "X-Agent-Purpose" to "conversation",
        "x-codebuddy-request" to "1",
        "X-IDE-Type" to "WorkBuddy",
        "X-IDE-Name" to "WorkBuddy",
        "X-IDE-Version" to "0.0.0",
        "X-Private-Data" to "true",
        "X-Product" to "SaaS",
        "traceparent" to "00-$traceId-$spanId-01",
        "b3" to "$traceId-$spanId-1-$parentSpanId",
        "X-B3-TraceId" to traceId,
        "X-B3-SpanId" to spanId,
        "X-B3-ParentSpanId" to parentSpanId,
        "X-B3-Sampled" to "1",
        "X-Trace-ID" to traceId,
    )

    /** 给 dsh-Go 填的 Base URL。末尾不带斜杠，客户端自己拼 /chat/completions。 */
    fun localBaseUrl(port: Int): String = "http://127.0.0.1:$port/v1"
}
