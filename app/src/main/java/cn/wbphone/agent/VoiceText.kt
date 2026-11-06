package cn.wbphone.agent

/**
 * 把 Markdown 回答加工成「听得懂」的话。
 *
 * 朗读最怕念出记号：`**粗体**` 会念成「星号星号粗体星号星号」，
 * `#` 念成「井号」，代码块更是灾难。所以读之前先过一遍 [Marked]，
 * 只留字，并按块补上停顿标点——TTS 靠标点断句。
 *
 * 纯 JVM，没有 android 依赖：断句规则能离线断言，不用真机试听。
 */
object VoiceText {

    /** 一次最多念多少字。超过就截断——长回答全念完没人等得及。 */
    const val MAX_CHARS = 900

    /** 代码块的占位：念出来比不念好，用户知道这里跳过了东西。 */
    private const val CODE_NOTE = "（这里有段代码，我就不念了）"

    /**
     * Markdown → 口语句子。
     *
     * 标题单独成句；列表项之间用逗号连，读起来是一串而不是一句一顿；
     * 段落之间靠句号断开。分隔线直接跳过——那点静音没有信息量。
     */
    fun toSpeech(markdown: String): String {
        if (markdown.isBlank()) return ""
        val sb = StringBuilder()
        for (b in Marked.parse(markdown)) {
            when (b.kind) {
                "hr" -> continue
                "code" -> sb.append(CODE_NOTE)
                else -> {
                    val lines = b.lines
                        .map { line -> line.joinToString("") { it.text }.trim() }
                        .filter { it.isNotEmpty() }
                    if (lines.isEmpty()) continue
                    sb.append(lines.joinToString("，"))
                }
            }
            endSentence(sb)
        }
        return sb.toString().trim()
    }

    /** 块末补一个句号；已经有标点就不叠。 */
    private fun endSentence(sb: StringBuilder) {
        if (sb.isEmpty()) return
        val last = sb.last()
        // 括号收尾的（代码占位）也算说完，不再叠句号
        if (last !in "。！？!?；;，,、：:）)") sb.append('。')
    }

    /**
     * 截断到 max 字，尽量切在句号上——半句被砍断听着最难受。
     * 截了就在尾巴明说还有，别让用户以为回答就这么多。
     */
    fun clip(text: String, max: Int = MAX_CHARS): String {
        if (text.length <= max) return text
        val head = text.substring(0, max)
        val cut = head.indexOfLast { it in "。！？!?；;" }
        val body = if (cut > max / 2) head.substring(0, cut + 1) else head
        if (body.isEmpty()) return "后面还有一段，我先念到这儿。"
        // 切在句号上就接着往下说，切在句中才补一个句号，别出现「。。」
        val stop = if (body.last() in "。！？!?；;") "" else "。"
        return body + stop + "后面还有一段，我先念到这儿。"
    }

    /**
     * 从系统已装的语音里挑一个来念。
     *
     * 优先普通话（zh-CN / zh-Hans-CN），退而求其次任意中文，再退英文。
     * 返回 null 表示没有可用的，调用方保持引擎默认语言即可。
     */
    fun pickLanguage(available: List<String>): String? {
        if (available.isEmpty()) return null
        val norm = available.map { it.lowercase().replace('_', '-') }
        for (want in listOf("zh-cn", "zh-hans-cn", "zh-sg", "zh-hans")) {
            val hit = norm.indexOf(want)
            if (hit >= 0) return available[hit]
        }
        val zh = norm.indexOfFirst { it == "zh" || it.startsWith("zh-") }
        if (zh >= 0) return available[zh]
        val en = norm.indexOfFirst { it.startsWith("en") }
        return if (en >= 0) available[en] else null
    }
}
