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
    /**
     * 这次请求要带的思考深度（"low"/"high"），null 表示不传。
     *
     * 用函数而不是值：设置里改了档位，下一句话就该生效，不必重建客户端。
     * 传 null 的语义是「保持旧请求体不变」——标准档永不往请求里加东西。
     */
    private val effort: () -> String? = { null },
    /**
     * 上游明确拒绝了 `reasoning_effort` 时回调一次，参数是**服务端原话**。
     *
     * 为什么要往外传：这种拒绝只需要吃一次。宿主持有「哪个模型不认」的记录，
     * 之后同一个模型就不再带这个参数——否则每一轮都要多一次注定失败的往返。
     */
    private val onEffortRejected: ((String) -> Unit)? = null,
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
        /** 从开始发请求到拿到响应头：TCP+TLS+上传+服务端预填充，都算在这里。 */
        val headerMs: Long = 0,
        /** 其中「把请求发出去」这一段（建连 + 上传），用来区分握手和预填充。 */
        val connectMs: Long = 0,
        /** 从开始发请求到第一个增量（首字延迟）。 */
        val firstTokenMs: Long = 0,
        /** 整轮总耗时，含把流读完。 */
        val totalMs: Long = 0,
        /** 发出去的消息体字符数，粗估输入规模。 */
        val inputChars: Int = 0,
        /** 模型思考（reasoning）增量字符数；0 表示上游没单独发思考流。 */
        val reasoningChars: Int = 0,
        /** 从发请求到第一个思考增量的耗时；0 表示没有思考流。 */
        val reasoningFirstMs: Long = 0,
        /**
         * 输入里命中前缀缓存（KV cache）的 token 数。
         *
         * -1 表示上游**没有报**这件事，不是「命中 0」。这两个含义必须分开：
         * 前者是「不知道」，后者是「确实一点没命中」，处理方向完全相反。
         * 各家字段名不一样，见 [parseCache]。
         */
        val cacheHitTokens: Int = -1,
        /** 输入里没命中、需要真正预填充的 token 数。-1 含义同上。 */
        val cacheMissTokens: Int = -1,
        /**
         * 这一轮实际发出去的思考深度（"low"/"high"）；null 表示没带这个参数。
         *
         * 记下来是为了把「思考字数」和「档位」对上：事后看流水账能直接算出
         * 低档到底省了多少思考、换来了多少秒，而不是凭感觉。
         */
        val reasoningEffort: String? = null,
        /**
         * 流里最后一个 `finish_reason`：`stop` / `length` / `tool_calls` / `content_filter`…
         * null 表示上游没报这个字段。
         *
         * 为什么非要读它：`length` 是唯一一个「模型没说完但没报错」的显式信号。
         * 之前完全没读，8192 上限一撞，回答就停在半句上，界面上看起来像正常结束。
         */
        val finishReason: String? = null,
        /** 读流的时候连接断了（IOException）。内容一定是半截的，已收到的部分还在 content 里。 */
        val cut: Boolean = false,
        /**
         * 流既没有 `[DONE]` 也没有 `finish_reason`——上游没给任何结束标记。
         *
         * 单独一个字段而不是直接当 cut：极少数网关两样都不发，把它们一律判成断流
         * 会让每一轮都重试三遍再报错。所以这里只记录，判不判死由调用方按内容是否为空来定。
         */
        val unmarked: Boolean = false,
        /**
         * 思考全文（上游发的 reasoning_content 拼起来）。空串 = 这轮没有思考流。
         *
         * 之前只留了字数，全文随界面刷新就丢了：用户想回看「它当时在想什么」，
         * 退出重进就再也看不到。既然已经收在内存里，就带出去一份落盘。
         */
        val reasoningText: String = "",
    )

    /**
     * 跑一次模型调用，把流收成完整的一轮结果。
     * @param onText 文本增量回调；tool_calls 不会走到这里
     * @param onRetry 要等一会儿再重发（限流 / 断流 / 连不上）。界面拿它解释「为什么停住了」，
     *   不然用户看到的就是一段没有任何动静的空白。
     * @param onRestart 已经吐了半截、这次作废重来。界面必须把这一轮已显示的内容撤掉，
     *   否则重发的内容会接在半截后面，变成一段前后不搭、开头重复的话。
     * @param isCancelled 退避等待期间也要能被「停止」打断，否则点了没反应。
     */
    fun stream(
        messages: JSONArray,
        tools: JSONArray?,
        onText: ((String) -> Unit)? = null,
        /** 思考增量。有的上游把「想」的过程单独发在 reasoning_content 里。 */
        onReasoning: ((String) -> Unit)? = null,
        onRetry: ((String) -> Unit)? = null,
        onRestart: (() -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): Turn {
        var attempt = 0
        // 采样参数不是所有上游都认。第一次被拒就摘掉重试一次——
        // 宁可少一个 temperature，也不能让整个功能因为一个可选参数直接报错。
        var sampling = true
        // 思考深度同理，但要单独摘：只认 temperature 的上游可能认 reasoning_effort，
        // 反过来也成立。两个开关各管各的，才不会因为一个被拒把另一个一起丢掉。
        var effortValue = runCatching { effort() }.getOrNull()
        // 令牌只刷新一次。不能用 attempt 判断：一次断流重试也会把 attempt 推上去，
        // 真到 401 时反而没机会刷新了。
        var refreshed = false
        // 「重发就好」的重试单独计数，不和上面两类降级抢额度——
        // 抢的话，一次限流就把摘参数的机会吃掉了，那才是真的把人卡死。
        var retries = 0
        while (true) {
            attempt++
            // 用户点了停止：别再发下一次请求。用 IO 异常是因为这条线本来就是「网络」，
            // 上层会先看 cancelled 再决定是报停止还是报错误。
            if (isCancelled()) throw java.io.InterruptedIOException("已停止")
            // 所有分支加起来最多走这么多次；只是防上游连环报错把循环拖死。
            if (attempt > MAX_ATTEMPTS) throw IllegalStateException("请求连续失败，已放弃重试")
            val token = tokens.accessToken() ?: throw IllegalStateException("未登录，请先登录 WorkBuddy 账号")
            // 计时起点放在 open 之前：open 里要写完整请求体，上传时间也算进 headerMs。
            val started = System.currentTimeMillis()
            val conn = try {
                open(messages, tools, token, sampling, effortValue)
            } catch (e: java.io.IOException) {
                // 建连/上传阶段就断了（隧道里没信号、Wi-Fi 刚切换）。这时一个字节都还没发出去，
                // 重发完全无副作用，属于最该自动兜住的一类。
                if (retries < MAX_RETRY) {
                    retries++
                    val ms = BACKOFF_MS[retries - 1]
                    log("连不上服务器（${e.message}），${ms}ms 后重试第 $retries 次")
                    onRetry?.invoke("连不上服务器，${secs(ms)} 秒后重试")
                    waitBefore(ms, isCancelled)
                    continue
                }
                throw IllegalStateException("连不上服务器：${e.message ?: "网络错误"}", e)
            }
            // open 里已经把请求体写完，所以这一刻建连 + 上传都结束了。
            val connectMs = System.currentTimeMillis() - started
            var completed = false
            try {
                val code = conn.responseCode
                val headerMs = System.currentTimeMillis() - started
                if (code == 401 || code == 403) {
                    conn.errorStream?.close()
                    if (!refreshed && tokens.refresh()) {
                        refreshed = true
                        log("令牌过期，已刷新后重试")
                        continue
                    }
                    throw IllegalStateException("账号令牌被拒绝（HTTP $code），请重新登录")
                }
                // 限流和短暂故障：等一会儿再发，一分钱不花就能过去。
                // 之前一律直接抛给用户，可 docs/troubleshooting.md 里写的却是「等几分钟」——
                // 应用自己不等，等于把一个能自愈的问题丢给人。
                if (code == 429 || code == 408 || code in 500..599) {
                    val body = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    if (retries < MAX_RETRY) {
                        retries++
                        // 服务端给了 Retry-After 就听它的（上限 10 秒，别让人对着空屏干等）
                        val ms = retryAfterMs(conn.getHeaderField("Retry-After")) ?: BACKOFF_MS[retries - 1]
                        log("HTTP $code，${ms}ms 后重试第 $retries 次：${body.take(120)}")
                        onRetry?.invoke(
                            if (code == 429) "触发限流，${secs(ms)} 秒后重试"
                            else "服务器忙（HTTP $code），${secs(ms)} 秒后重试"
                        )
                        waitBefore(ms, isCancelled)
                        continue
                    }
                    throw IllegalStateException(
                        if (code == 429) "触发限流，已重试 $retries 次；等几分钟再发"
                        else "服务器故障（HTTP $code），已重试 $retries 次"
                    )
                }
                if (code !in 200..299) {
                    val body = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    if (effortValue != null && isEffortRejected(code, body)) {
                        // 服务端原话必须留痕：它决定了「这个模型到底是不认参数，还是参数值不合法」。
                        // 前者永远别再发，后者换个档位还有救——这两种情况的处置方向相反。
                        val dropped = effortValue
                        effortValue = null
                        log("上游不接受思考深度（reasoning_effort=$dropped），已回落标准重发：${body.take(200)}")
                        runCatching { onEffortRejected?.invoke(body.take(300)) }
                        continue
                    }
                    if (sampling && isSamplingRejected(code, body)) {
                        sampling = false
                        log("上游不接受采样参数，去掉后重试：${body.take(120)}")
                        continue
                    }
                    throw IllegalStateException("HTTP $code：${body.take(300)}")
                }
                // 数一数这次已经往界面上吐了多少字。断流后要不要「先撤掉再重来」全靠它。
                var emitted = 0
                val textSink: (String) -> Unit = { d ->
                    if (d.isNotEmpty()) {
                        emitted += d.length
                        onText?.invoke(d)
                    }
                }
                val thinkSink: (String) -> Unit = { d ->
                    if (d.isNotEmpty()) {
                        emitted += d.length
                        onReasoning?.invoke(d)
                    }
                }
                // 发出去的是 model，回来看的是 turn.model。两者不一致就得留痕——
                // 用户「感觉换了模型没反应」时，这行日志是唯一能分辨是客户端没发还是网关回落的地方。
                val turn = readStream(
                    conn, textSink, started, headerMs, messages.toString().length,
                    connectMs, thinkSink, effortValue,
                )
                // 流死掉的两种样子：
                //  - cut：读到一半连接断了（IOException），内容一定是半截；
                //  - 空回复且没有任何结束标记：多半是网关那头提前掐了。
                // 这两种「重发就好」，但重发前必须把已经吐出去的内容撤掉。
                val dead = turn.cut || (turn.unmarked && turn.content.isEmpty() && turn.toolCalls.isEmpty())
                if (dead) {
                    if (retries < MAX_RETRY) {
                        retries++
                        val ms = BACKOFF_MS[retries - 1]
                        log(
                            "流没读完（${if (turn.cut) "连接断开" else "上游没给结束标记"}），" +
                                "已收 ${emitted} 字，${ms}ms 后重发第 $retries 次"
                        )
                        if (emitted > 0) runCatching { onRestart?.invoke() }
                        onRetry?.invoke("回复传到一半断了，正在重发")
                        waitBefore(ms, isCancelled)
                        continue
                    }
                    throw IllegalStateException(
                        "回复传到一半断了，重试 $retries 次都没成功。网络稳一点再发一次。"
                    )
                }
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

    /** 退避等待。切成小片是为了让「用户点了停止」能在一百毫秒内生效，而不是等满整段。 */
    private fun waitBefore(ms: Long, isCancelled: () -> Boolean) {
        var left = ms
        while (left > 0 && !isCancelled()) {
            val step = if (left > SLEEP_SLICE) SLEEP_SLICE else left
            runCatching { Thread.sleep(step) }
            left -= step
        }
    }

    private fun secs(ms: Long): String = String.format("%.1f", ms / 1000.0)

    /**
     * `Retry-After` 只认秒数（HTTP-date 那种写法没见人用过）。
     * 上限压到 10 秒：真让你等 60 秒，不如直接告诉用户失败了。
     */
    private fun retryAfterMs(v: String?): Long? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = s.toLongOrNull() ?: return null
        return (n * 1000).coerceIn(0L, 10_000L)
    }


    private fun open(
        messages: JSONArray,
        tools: JSONArray?,
        token: String,
        sampling: Boolean = true,
        effort: String? = null,
    ): HttpURLConnection {
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
        // 思考深度：只在这两档加，标准档请求体和旧版逐字节一致。
        if (effort != null) body.put("reasoning_effort", effort)
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

    /**
     * 上游是不是因为 `reasoning_effort` 拒了这次请求。
     *
     * 判定要比采样参数宽一点：这个字段各家叫法不一（reasoning_effort / reasoning /
     * thinking），有的网关干脆回一句「unknown parameter」。所以只要 4xx 且报错里点了这些词，
     * 就认为是我们多带的那一个参数惹的——摘掉它重发一次，代价只有一次往返。
     *
     * 仍然排除 11128：那是渠道校验失败（role=system 触发），摘参数一点用没有。
     */
    private fun isEffortRejected(code: Int, body: String): Boolean {
        if (code !in 400..499) return false
        val b = body.lowercase()
        if (b.contains("11128")) return false
        return listOf("reasoning_effort", "reasoning effort", "reasoning", "thinking")
            .any { b.contains(it) }
    }

    private fun readStream(
        conn: HttpURLConnection,
        onText: ((String) -> Unit)?,
        started: Long = System.currentTimeMillis(),
        headerMs: Long = 0,
        inputChars: Int = 0,
        connectMs: Long = 0,
        onReasoning: ((String) -> Unit)? = null,
        effort: String? = null,
    ): Turn {
        val content = StringBuilder()
        val calls = HashMap<Int, MutableCall>()
        var usage: JSONObject? = null
        var servedModel: String? = null
        // 首字延迟：第一个带内容或工具调用的增量到达的时刻。纯工具调用轮没有文字，
        // 所以 content 和 tool_calls 都要算，否则这类轮次会显示 0。
        var firstTokenMs = 0L
        // 思考流：有的上游把「想」的过程单独放在 reasoning_content / reasoning 里，
        // 正文要等思考结束才开始。不单独统计的话，用户看到的就是一段莫名长达数秒的空白。
        val reasoning = StringBuilder()
        var reasoningFirstMs = 0L
        // 结束标记。两者都没有 = 这条流没正常收尾，内容可能是半截的。
        var sawDone = false
        var finishReason: String? = null
        var cut = false

        try {
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") {
                        sawDone = true
                        break
                    }
                    if (payload.isEmpty()) continue

                    val obj = try {
                        JSONObject(payload)
                    } catch (e: Exception) {
                        continue // 心跳帧或非 JSON，忽略
                    }
                    obj.optJSONObject("usage")?.let { usage = it }
                    obj.optString("model").takeIf { it.isNotEmpty() }?.let { servedModel = it }

                    val choices = obj.optJSONArray("choices") ?: continue
                    val choice = choices.optJSONObject(0) ?: continue
                    // finish_reason 通常单独发在最后一帧，那一帧的 delta 是空的——
                    // 所以必须在取 delta 之前读，先取 delta 再 `?: continue` 会把它整个跳过。
                    choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { finishReason = it }
                    val delta = choice.optJSONObject("delta") ?: continue

                    if (firstTokenMs == 0L) {
                        val hasText = delta.optString("content").isNotEmpty()
                        val hasCalls = (delta.optJSONArray("tool_calls")?.length() ?: 0) > 0
                        if (hasText || hasCalls) firstTokenMs = System.currentTimeMillis() - started
                    }
                    // 思考增量：OpenAI 兼容实现里字段名不统一，两种都认。
                    val think = delta.optString("reasoning_content")
                        .ifEmpty { delta.optString("reasoning") }
                    if (think.isNotEmpty()) {
                        if (reasoningFirstMs == 0L) reasoningFirstMs = System.currentTimeMillis() - started
                        reasoning.append(think)
                        onReasoning?.invoke(think)
                    }

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

                // [DONE] 只是「逻辑上结束了」，不代表这条连接结束了。
                //
                // 看到 [DONE] 就 break、剩下的字节不读，JDK 会认为这次请求没读完，直接关掉
                // socket —— keep-alive 池永远空着，下一轮还是重新握手。实测每轮「连上」时间
                // 恒定在 1.6~1.8 秒、跟输入从 9.3k 涨到 11.8k 完全无关，就是它。
                //
                // 这里把剩余字节读到 EOF，连接才会被归还。万一服务端 [DONE] 之后不关连接，
                // 300ms 读超时兜底：最坏情况退回今天的行为，不拖慢任务。
                runCatching {
                    conn.readTimeout = 300
                    var guard = 0
                    while (guard++ < 200 && reader.readLine() != null) {
                        // 尾帧丢弃
                    }
                }
            }
        } catch (e: Exception) {
            // 读到一半连接断了（切网、走进电梯、服务端把连接掐了）。
            // 已经收到的内容还在 content 里——别丢掉，由调用方决定是重发还是交代实情。
            if (e is java.io.IOException) {
                cut = true
            } else {
                throw e
            }
        }

        val toolCalls = calls.entries
            .sortedBy { it.key }
            .map { it.value }
            .filter { it.name.isNotEmpty() }
            .mapIndexed { i, c -> ToolCall(c.id.ifEmpty { "call_$i" }, c.name, c.args.ifEmpty { "{}" }) }

        val (cacheHit, cacheMiss) = parseCache(usage)

        return Turn(
            content.toString(),
            toolCalls,
            usage,
            servedModel,
            headerMs = headerMs,
            connectMs = connectMs,
            firstTokenMs = firstTokenMs,
            totalMs = System.currentTimeMillis() - started,
            inputChars = inputChars,
            reasoningChars = reasoning.length,
            reasoningFirstMs = reasoningFirstMs,
            cacheHitTokens = cacheHit,
            cacheMissTokens = cacheMiss,
            reasoningEffort = effort,
            finishReason = finishReason,
            cut = cut,
            // 两条结束标记一条都没见到：这条流不算正常收尾。
            // 但只有 cut（真断线）才一定有问题，这个标记交给调用方按内容判。
            unmarked = !sawDone && finishReason == null,
            reasoningText = reasoning.toString(),
        )
    }

    /**
     * 从 usage 里读「缓存命中了多少输入 token」。
     *
     * 为什么非读不可：9k 上下的固定前缀（系统提示 + 工具 schema）如果命中缓存，
     * 预填充只算新增的一两百 token，那「砍提示词」就是纯损失稳定性换一个已经被
     * 优化掉的成本；如果一点都不命中，该修的是缓存而不是提示词。不看这个数就动手，
     * 等于蒙着眼睛改。
     *
     * 字段名各家不同，按出现概率依次试：
     *  - DeepSeek：prompt_cache_hit_tokens / prompt_cache_miss_tokens
     *  - OpenAI：prompt_tokens_details.cached_tokens
     *  - Anthropic 及部分网关：cache_read_input_tokens / cached_tokens / cache_hit_tokens
     * 都不认识就返回 -1（不知道），不要在客户端瞎猜一个 0 出来。
     */
    private fun parseCache(usage: JSONObject?): Pair<Int, Int> {
        if (usage == null) return -1 to -1
        val prompt = usage.optInt("prompt_tokens", -1)
        fun pair(hit: Int): Pair<Int, Int> =
            hit to (if (prompt >= 0) prompt - hit else usage.optInt("prompt_cache_miss_tokens", -1))

        if (usage.has("prompt_cache_hit_tokens")) {
            return pair(usage.optInt("prompt_cache_hit_tokens", 0))
        }
        usage.optJSONObject("prompt_tokens_details")?.let { d ->
            if (d.has("cached_tokens")) return pair(d.optInt("cached_tokens", 0))
        }
        for (key in listOf("cache_read_input_tokens", "cached_tokens", "cache_hit_tokens")) {
            if (usage.has(key)) return pair(usage.optInt(key, 0))
        }
        return -1 to -1
    }

    private class MutableCall {
        var id: String = ""
        var name: String = ""
        var args: String = ""
    }

    private companion object {
        /**
         * 「过一会儿重发就好」类错误的重试上限：限流、5xx、连不上、流断在半路。
         *
         * 定成 2 而不是更多：重试期间界面是停着的，等太久人会以为卡死。
         * 两次退避加起来 2.7 秒，覆盖绝大多数抖动，又不会让人等到怀疑人生。
         */
        const val MAX_RETRY = 2

        /** 退避时长（毫秒）：第 1 次、第 2 次。指数式但不夸张——网络抖动多是百毫秒级的。 */
        val BACKOFF_MS = longArrayOf(700L, 2_000L)

        /** 退避等待的切片长度：「停止」要在一次切片内生效，不能等满整段。 */
        const val SLEEP_SLICE = 100L

        /** 单次调用最多尝试几次（含各类降级与重试）。防上游连环报错把循环拖死。 */
        const val MAX_ATTEMPTS = 8
    }
}

/** 令牌来源。App 里是 Auth；测试里是假实现。 */
interface TokenSource {
    fun accessToken(): String?

    /** 刷新令牌，成功返回 true。 */
    fun refresh(): Boolean
}
