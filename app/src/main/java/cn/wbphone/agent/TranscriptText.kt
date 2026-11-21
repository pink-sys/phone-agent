package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 流水账的「格式」与「给人看」的那一半：[Transcript] 负责存，这里负责长什么样。
 *
 * 为什么单独一个文件：这些逻辑一行 Android API 都不需要，拆出来才能进 JVM 测试。
 * 渲染这件事必须能测——耗时怎么拆、工具参数显不显示、失败有没有标出来，
 * 改一版格式就悄悄坏一处的话没人会发现，而它偏偏是出问题时唯一能查的东西。
 */
object TranscriptText {
    // ------------------------------------------------------------ 快捷构造

    /**
     * 用户消息。流水账是「全量历史」的唯一数据源（模型视角的 history 会被压缩截断），
     * 所以这里的上限按**聊天界面能完整显示**来定：长 prompt、贴的文件说明都不该被腰斩。
     * [photos] 是随消息落盘的照片文件名（filesDir/photos/ 下），恢复会话时按名回显图片。
     */
    fun user(text: String, withImage: Boolean = false, photos: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("type", "user")
            .put("text", text.take(6000))
            .put("withImage", withImage)
            .apply { if (photos.isNotEmpty()) put("photos", JSONArray(photos)) }

    fun assistant(text: String): JSONObject =
        JSONObject().put("type", "assistant").put("text", text.take(16000))

    fun tool(name: String, args: String, ok: Boolean, resultBrief: String): JSONObject =
        JSONObject()
            .put("type", "tool")
            .put("name", name)
            .put("args", args.take(600))
            .put("ok", ok)
            .put("result", resultBrief.take(300))

    fun compact(removed: Int, kept: Int, goal: String): JSONObject =
        JSONObject()
            .put("type", "compact")
            .put("removed", removed)
            .put("kept", kept)
            .put("goal", goal.take(200))

    /**
     * 一轮请求的耗时拆解。
     *
     * header：发完请求到拿到响应头（连接 + 上传 + 服务端预填充）
     * first：到第一个增量（首字延迟，模型开始出字）
     * total：把流读完的总时长
     * in/out：输入输出 token（服务端 usage 给的，没有就是 0）
     *
     * 三者相减就能看出时间花在哪：header 大 = 网络或预填充；first-header 大 = 模型在想；
     * total-first 大 = 出字太慢（模型生成速度）。
     */
    fun turn(
        headerMs: Long,
        firstTokenMs: Long,
        totalMs: Long,
        inChars: Int,
        inTokens: Int,
        outTokens: Int,
        toolCalls: Int,
        model: String?,
        reasoningChars: Int = 0,
        reasoningFirstMs: Long = 0,
        connectMs: Long = 0,
        cacheHit: Int = -1,
        cacheMiss: Int = -1,
        /** 本轮实际发的思考深度（"low"/"high"），空串 = 没带这个参数。 */
        effort: String = "",
        /** usage 原文。服务端换字段名时，靠这一条能直接看出它到底报了些什么。 */
        usageRaw: String = "",
    ): JSONObject =
        JSONObject()
            .put("type", "turn")
            .put("header", headerMs)
            .put("connect", connectMs)
            .put("first", firstTokenMs)
            .put("total", totalMs)
            .put("inChars", inChars)
            .put("in", inTokens)
            .put("out", outTokens)
            .put("calls", toolCalls)
            .put("model", model ?: "")
            // 只有上游真的发了思考流才写这两个字段，读的时候"有思考"= reasoning > 0
            .put("reasoning", reasoningChars)
            .put("reasoningAt", reasoningFirstMs)
            // -1 = 上游没报缓存信息（不等于没命中）。分析脚本要按这个区分。
            .put("cacheHit", cacheHit)
            .put("cacheMiss", cacheMiss)
            // 空串 = 本轮没带思考深度参数。要和「带了 low」分开，才能算出低档省了多少。
            .put("effort", effort)
            .apply { if (usageRaw.isNotEmpty()) put("usage", usageRaw.take(500)) }

    fun error(message: String): JSONObject =
        JSONObject().put("type", "error").put("message", message.take(600))

    /**
     * 上游拒绝了 `reasoning_effort`。
     *
     * 单独记一条的理由：这种拒绝只在你选了低/高档时发生一次，然后在日志里被后面的行淹没。
     * 事后想判断「低档为什么没变快」，需要知道它到底有没有生效过——被拒过一次就意味着
     * 那一轮实际是标准档，还白跑了一次往返（首字会明显偏大）。
     */
    fun effortReject(body: String): JSONObject =
        JSONObject().put("type", "effort_reject").put("body", body.take(300))

    /**
     * 这一轮随请求附带的背景资料（长期记忆 + 技能索引）变了。
     *
     * 记的是**注入的原文开头**，不是用户说的话。两者混在一起是致命的那种混淆：
     * 用户问「主题四」，模型答「主题三」，只看用户原文会以为是模型瞎编——
     * 实际上诱因往往就躺在这段被当成「已知条件」的背景里。
     */
    fun extras(chars: Int, preview: String): JSONObject =
        JSONObject().put("type", "extras").put("chars", chars).put("preview", preview.take(200))

    /**
     * 网络层的自我修复：退避重发、或断了之后把半截撤掉重来。
     *
     * 记它的理由很实际：这两种情况在界面上只表现为「停了一会儿」，事后看总耗时对不上，
     * 会误以为是模型慢了。有这一行，就能把「等网络」和「等模型」分开。
     */
    fun retry(why: String): JSONObject =
        JSONObject().put("type", "retry").put("why", why.take(200))

    // ------------------------------------------------------------ 给人看

    /**
     * 把流水账渲染成人能读的文本（也当导出的 Markdown 用）。
     *
     * 为什么必须有个渲染器，而不是让用户直接看 JSONL：这份东西的用途是「查它当时到底做了什么」，
     * 一行 JSON 里塞着转义引号和毫秒时间戳，看三行就放弃了，等于没做。
     *
     * 时间戳按天分组、只显示时分秒：跨天时知道是哪天，同一天里不被重复的日期刷屏。
     */
    fun render(entries: List<JSONObject>): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd EEEE", java.util.Locale.CHINA)
        val clockFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
        var day = ""
        var turnNo = 0

        for (e in entries) {
            val at = java.util.Date(e.optLong("ts", 0L))
            val today = dayFmt.format(at)
            if (today != day) {
                if (day.isNotEmpty()) sb.append('\n')
                sb.append("## ").append(today).append("\n\n")
                day = today
            }
            val clock = clockFmt.format(at)
            when (e.optString("type")) {
                "user" -> {
                    sb.append(clock).append("  ")
                        .append(if (e.optBoolean("withImage")) "你（附了一张图）" else "你")
                        .append('\n')
                    quote(sb, e.optString("text"))
                }
                "assistant" -> {
                    sb.append(clock).append("  月溯\n")
                    quote(sb, e.optString("text"))
                }
                "tool" -> {
                    sb.append(clock).append("  ").append(e.optString("name")).append('\n')
                    val args = e.optString("args")
                    if (args.isNotEmpty()) sb.append("  参数 ").append(args).append('\n')
                    sb.append("  → ")
                        .append(if (e.optBoolean("ok")) "ok" else "失败")
                        .append(" · ").append(oneLine(e.optString("result"))).append('\n')
                }
                "turn" -> {
                    // 轮次不落盘（写入侧没有这个字段），按出现顺序数出来即可
                    turnNo += 1
                    sb.append(clock).append("  第 ").append(turnNo).append(" 轮 · ")
                        .append(turnLine(e)).append('\n')
                }
                "compact" -> sb.append(clock).append("  上下文压缩：把前 ")
                    .append(e.optInt("removed")).append(" 条压成摘要，保留 ")
                    .append(e.optInt("kept")).append(" 条\n")
                "retry" -> sb.append(clock).append("  网络自愈：")
                    .append(oneLine(e.optString("why"))).append('\n')
                "error" -> sb.append(clock).append("  出错：")
                    .append(oneLine(e.optString("message"))).append('\n')
                "effort_reject" -> sb.append(clock).append("  思考深度被上游拒绝：")
                    .append(oneLine(e.optString("body"))).append('\n')
                "extras" -> sb.append(clock).append("  随请求附带背景资料 ")
                    .append(e.optInt("chars")).append(" 字：")
                    .append(oneLine(e.optString("preview"))).append('\n')
                else -> sb.append(clock).append("  ").append(e.optString("type")).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** 一轮请求的耗时拆解，压成一行。 */
    private fun turnLine(e: JSONObject): String {
        val total = e.optLong("total")
        val header = e.optLong("header")
        val first = e.optLong("first", 0)
        val parts = ArrayList<String>()
        if (header > 0) parts.add("预填 " + seconds(header) + "s")
        // first - header 才是模型真正「在想」的时间；total - first 是出字速度
        if (first > header) parts.add("想 " + seconds(first - header) + "s")
        if (total > first) parts.add("出字 " + seconds(total - first) + "s")
        val head = "${e.optString("model").ifEmpty { "?" }} · ${seconds(total)}s" +
            if (parts.isEmpty()) "" else "（" + parts.joinToString(" + ") + "）"
        val tail = ArrayList<String>()
        val inTok = e.optInt("in", 0)
        if (inTok > 0) {
            val hit = e.optInt("cacheHit", -1)
            tail.add("输入 " + thousands(inTok) + " token" + if (hit > 0) "（缓存 $hit）" else "")
        }
        val outTok = e.optInt("out", 0)
        if (outTok > 0) tail.add("输出 " + thousands(outTok))
        if (e.optInt("reasoning", 0) > 0) tail.add("思考 " + thousands(e.optInt("reasoning")) + " 字")
        val calls = e.optInt("calls", 0)
        if (calls > 0) tail.add("工具 $calls 次")
        val effort = e.optString("effort")
        if (effort.isNotEmpty()) tail.add("深度 $effort")
        return head + if (tail.isEmpty()) "" else " · " + tail.joinToString(" · ")
    }

    /** 多行正文：每行缩两格，空行留白。 */
    private fun quote(sb: StringBuilder, text: String) {
        if (text.isEmpty()) {
            sb.append('\n')
            return
        }
        for (line in text.split('\n')) {
            sb.append("  ").append(line.trimEnd()).append('\n')
        }
        sb.append('\n')
    }

    /** 单行化：结果和错误里常有换行，一行一条才扫得动。 */
    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun seconds(ms: Long): String = "%.1f".format(ms / 1000.0)

    private fun thousands(n: Int): String = "%,d".format(n)
}
