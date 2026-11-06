package cn.wbphone.agent

/**
 * 聊天气泡的轻量 Markdown 解析。
 *
 * 模型的回答天然是 Markdown：`**粗体**`、`# 标题`、`- 列表`、
 * ``` 代码块。之前直接把原文塞 TextView，星号井号全裸奔；
 * 这里把它解析成与 Android 无关的结构（块 → 行 → 段），
 * ChatUi 再用 Span 着色——解析逻辑留在纯 JVM，能进 harness 断言。
 *
 * 刻意不做的：斜体（单星号和乘法、列表项太容易误伤）、
 * 链接自动识别、图片、表格。聊天气泡要的是清爽，不是完整渲染器。
 */
object Marked {

    /** 行内一段：普通文本，或带粗体 / 行内代码样式。 */
    class Seg(val text: String, val bold: Boolean = false, val code: Boolean = false)

    /**
     * 一个块 = 视觉上一组贴在一起的行。
     * lines 里的每行是段列表；gap 是块前的空行数（渲染时间隔用）。
     * lines 用可变列表：相邻同类块要往里并行。
     */
    class Block(
        val kind: String,          // "h" 标题 / "ul" / "ol" / "quote" / "code" / "hr" / "p" 段落
        val level: Int,            // h 的级别 1-6
        val lines: MutableList<List<Seg>>,
        val gap: Int,
    )

    /** 标题的 `# `、列表的 `- `、编号的 `1. `——块级记号只认行首。 */
    private val HEAD = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^[-*•]\\s+(.*)$")
    private val NUMBER = Regex("^(\\d{1,3})[.、)]\\s*(.*)$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val HR = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")

    fun parse(src: String): List<Block> {
        val out = ArrayList<Block>()
        var gap = 0
        val codeLines = ArrayList<String>()
        var inCode = false

        for (raw in src.lineSequence()) {
            if (inCode) {
                // 围栏内原样保留，直到闭合的 ```。没有闭合就攒到文末一起算。
                // 每行代码独立占块里的一行——渲染器按行补换行，行缩进才能活下来；
                // 之前整块塞一条 line，段间没有分隔，代码全挤成一坨。
                if (raw.trimStart().startsWith("```")) {
                    out.add(Block("code", 0, ArrayList(codeLines.map { listOf(Seg(it)) }), gap))
                    codeLines.clear(); inCode = false; gap = 0
                } else codeLines.add(raw)
                continue
            }
            val t = raw.trimEnd()
            if (t.isBlank()) { gap++; continue }
            if (t.trimStart().startsWith("```")) { inCode = true; continue }

            val h = HEAD.find(t.trimStart())
            if (h != null) {
                out.add(Block("h", h.groupValues[1].length, arrayListOf(inline(h.groupValues[2])), gap))
                gap = 0; continue
            }
            if (HR.matches(t.trim())) {
                out.add(Block("hr", 0, arrayListOf(), gap)); gap = 0; continue
            }
            val b = BULLET.find(t.trimStart())
            if (b != null) {
                merge(out, "ul", inline(b.groupValues[1]), gap); gap = 0; continue
            }
            val n = NUMBER.find(t.trimStart())
            if (n != null) {
                // 编号本身加粗：一眼能扫到「1. 2. 3.」的骨架
                merge(out, "ol", listOf(Seg(n.groupValues[1] + ". ", bold = true)) + inline(n.groupValues[2]), gap)
                gap = 0; continue
            }
            val q = QUOTE.find(t.trimStart())
            if (q != null) {
                merge(out, "quote", inline(q.groupValues[1]), gap); gap = 0; continue
            }
            merge(out, "p", inline(t), gap); gap = 0
        }
        // 没等到闭合围栏：攒下的行照样当代码块渲染
        if (codeLines.isNotEmpty()) out.add(Block("code", 0, ArrayList(codeLines.map { listOf(Seg(it)) }), gap))
        return out
    }

    /** 同类块且中间没有空行 = 一组贴着的行（连着的列表项、没空行的段落），并进上一块。 */
    private fun merge(out: ArrayList<Block>, kind: String, line: List<Seg>, gap: Int) {
        val last = out.lastOrNull()
        if (last != null && last.kind == kind && gap == 0) {
            last.lines.add(line)
            return
        }
        out.add(Block(kind, 0, arrayListOf(line), gap))
    }

    /**
     * 行内记号：`**粗体**`（`__` 同义）和 `` `代码` ``。
     * 没找到闭合的当成普通文本——流式输出打到一半时星号先裸着，
     * 对闭合的瞬间就会变成粗体，不用等整段结束。
     */
    fun inline(s: String): List<Seg> {
        val segs = ArrayList<Seg>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { segs.add(Seg(buf.toString())); buf.setLength(0) } }
        var i = 0
        while (i < s.length) {
            val marker: String? = when {
                s.startsWith("**", i) -> "**"
                s.startsWith("__", i) -> "__"
                s[i] == '`' -> "`"
                else -> null
            }
            if (marker == null) { buf.append(s[i]); i++; continue }
            val close = s.indexOf(marker, i + marker.length)
            if (close >= i + marker.length) {
                flush()
                val body = s.substring(i + marker.length, close)
                segs.add(if (marker == "`") Seg(body, code = true) else Seg(body, bold = true))
                i = close + marker.length
            } else {
                // 没闭合：当普通文本，星号反引号原样留在句子里
                buf.append(marker); i += marker.length
            }
        }
        flush()
        return segs
    }
}
