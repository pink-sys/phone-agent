package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 推理客户端：POST /v2/chat/completions，读 SSE 流。
 *
 * 协议是 OpenAI 形态，但有两处必须自己处理：
 *  1. `delta.content` 是逐片到达的，得边收边吐给界面；
 *  2. `delta.tool_calls` 是**增量**——先给 index+id+name，再分片给 function.arguments。
 *     必须按 index 累积，拼完才能解析 JSON。少做这一步，模型的多步任务就会哑火。
 *
 * 这个类刻意不引任何 Android API（只用 org.json + HttpURLConnection），
 * 所以能在 JVM 上直接跑测试，不需要真机。
 */
class CloudClient(
    private val baseUrl: String,
    /** 当前模型。可在会话中途改：下一次请求就换过去，历史不用重发。 */
    @Volatile var model: String,
    private val tokens: TokenSource,
    private val log: (String) -> Unit = {},
) {

    data class ToolCall(val id: String, val name: String, val args: String)

    /**
     * @param model 服务端在流里回传的模型名。它是「实际是谁答的」的唯一硬证据：
     *   请求体里写什么都不算数，网关可能静默回落到别的模型。取不到就是 null。
     */
    data class Turn(
        val content: String,
        val toolCalls: List<ToolCall>,
        val usage: JSONObject?,
        val model: String? = null,
    )

    /**
     * 跑一次模型调用，把流收成完整的一轮结果。
     * @param onText 文本增量回调；tool_calls 不会走到这里
     */
    fun stream(
        messages: JSONArray,
        tools: JSONArray?,
        onText: ((String) -> Unit)? = null,
    ): Turn {
        var attempt = 0
        // 采样参数不是所有上游都认。第一次被拒就摘掉重试一次——
        // 宁可少一个 temperature，也不能让整个功能因为一个可选参数直接报错。
        var sampling = true
        while (true) {
            attempt++
            val token = tokens.accessToken() ?: throw IllegalStateException("未登录，请先登录 WorkBuddy 账号")
            val conn = open(messages, tools, token, sampling)
            var completed = false
            try {
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    conn.errorStream?.close()
                    if (attempt <= 1 && tokens.refresh()) {
                        log("令牌过期，已刷新后重试")
                        continue
                    }
                    throw IllegalStateException("账号令牌被拒绝（HTTP $code），请重新登录")
                }
                if (code !in 200..299) {
                    val body = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    if (sampling && isSamplingRejected(code, body)) {
                        sampling = false
                        log("上游不接受采样参数，去掉后重试：${body.take(120)}")
                        continue
                    }
                    throw IllegalStateException("HTTP $code：${body.take(300)}")
                }
                // 发出去的是 model，回来看的是 turn.model。两者不一致就得留痕——
                // 用户「感觉换了模型没反应」时，这行日志是唯一能分辨是客户端没发还是网关回落的地方。
                val turn = readStream(conn, onText)
                completed = true
                if (turn.model != null && turn.model != model) {
                    log("注意：请求 $model，服务端实际用了 ${turn.model}")
                }
                return turn
            } finally {
                if (completed) {
                    // 流已读到结尾：只关流，不 disconnect——disconnect 会把底层 socket 一起关掉，
                    // JVM 的 keep-alive 连接池就永远空着，每轮请求都重付一次 TCP+TLS 握手。
                    // 一个几十轮的任务，白烧的握手时间累计能到好几秒。
                    runCatching { conn.inputStream?.close() }
                } else {
                    // 没读完（报错/取消/重试）：连接上的残余数据清不掉，JVM 不会复用这种连接，
                    // 主动断开，别让残连接占着池子。
                    conn.disconnect()
                }
            }
        }
    }

    private fun open(messages: JSONArray, tools: JSONArray?, token: String, sampling: Boolean = true): HttpURLConnection {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
        // 显式给采样参数。不传就走服务端默认，而默认值是为通用对话调的——
        // 温度偏高、输出偏长，放在「点屏幕」这种每步都有唯一正确答案的任务上就是纯噪声。
        // sampling=false 是被上游拒绝后的降级重试，此时一个都不带。
        if (sampling) {
            body.put("temperature", Config.TEMPERATURE)
                .put("top_p", Config.TOP_P)
                .put("max_tokens", Config.MAX_TOKENS)
        }
        if (tools != null && tools.length() > 0) body.put("tools", tools)

        val conn = (URL(baseUrl + Config.REQUEST_PATH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $token")
            // 必须关掉压缩：gzip 会让 SSE 的行缓冲变得不可控，流式就废了
            setRequestProperty("Accept-Encoding", "identity")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return conn
    }

    /**
     * 上游是不是因为采样参数拒了这次请求。
     *
     * 只认「参数不被接受」这一类错误：400/422 且报错里点名了参数。
     * 刻意排除 11128 —— 那个是渠道校验失败（role=system 触发的），文案里也可能出现别的
     * 词，但摘掉采样参数对它一点用没有，重试只是白打一次请求。
     */
    private fun isSamplingRejected(code: Int, body: String): Boolean {
        if (code != 400 && code != 422) return false
        val b = body.lowercase()
        if (b.contains("11128")) return false
        return listOf("temperature", "top_p", "top-p", "max_tokens", "sampling").any { b.contains(it) }
    }

    private fun readStream(conn: HttpURLConnection, onText: ((String) -> Unit)?): Turn {
        val content = StringBuilder()
        val calls = HashMap<Int, MutableCall>()
        var usage: JSONObject? = null
        var servedModel: String? = null

        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val raw = reader.readLine() ?: break
                val line = raw.trim()
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") {
                    if (payload == "[DONE]") break
                    continue
                }

                val obj = try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    continue // 心跳帧或非 JSON，忽略
                }
                obj.optJSONObject("usage")?.let { usage = it }
                obj.optString("model").takeIf { it.isNotEmpty() }?.let { servedModel = it }

                val choices = obj.optJSONArray("choices") ?: continue
                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue

                delta.optString("content").takeIf { it.isNotEmpty() }?.let {
                    content.append(it)
                    onText?.invoke(it)
                }

                val parts = delta.optJSONArray("tool_calls") ?: continue
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val index = part.optInt("index", 0)
                    val slot = calls.getOrPut(index) { MutableCall() }
                    part.optString("id").takeIf { it.isNotEmpty() }?.let { slot.id = it }
                    val fn = part.optJSONObject("function") ?: continue
                    fn.optString("name").takeIf { it.isNotEmpty() }?.let { slot.name += it }
                    fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { slot.args += it }
                }
            }
        }

        val toolCalls = calls.entries
            .sortedBy { it.key }
            .map { it.value }
            .filter { it.name.isNotEmpty() }
            .mapIndexed { i, c -> ToolCall(c.id.ifEmpty { "call_$i" }, c.name, c.args.ifEmpty { "{}" }) }

        return Turn(content.toString(), toolCalls, usage, servedModel)
    }

    private class MutableCall {
        var id: String = ""
        var name: String = ""
        var args: String = ""
    }
}

/** 令牌来源。App 里是 Auth；测试里是假实现。 */
interface TokenSource {
    fun accessToken(): String?

    /** 刷新令牌，成功返回 true。 */
    fun refresh(): Boolean
}
