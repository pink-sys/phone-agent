package cn.wbphone.agent

/**
 * 聊天气泡的轻量 Markdown 解析。
 *
 * 模型的回答天然是 Markdown：`**粗体**`、`# 标题`、`- 列表`、
 * ``` 代码块。之前直接把原文塞 TextView，星号井号全裸奔；
 * 这里把它解析成与 Android 无关的结构（块 → 行 → 段），
 * ChatUi 再用 Span 着色——解析逻辑留在纯 JVM，能进 harness 断言。
 *
 * 刻意不做的：斜体（单星号和乘法、列表项太容易误伤）、链接自动识别、图片。
 * 表格原来是刻意不做的，2026-11-19 真机翻车：GitHub 榜单一张四列表格
 * 每行裸奔成段落，管道符和 `---:` 铺满气泡。现在收成窄屏友好的紧凑行：
 * 表头一行加粗，数据行「首列加粗 · 后续列」用 `·` 串起来，不再画网格。
 * markdown 显式链接 `[文字](url)` 同一轮补上：文字上色可点，裸 URL 不再满屏。
 */
object Marked {

    /** 行内一段：普通文本，或带粗体 / 行内代码样式；link 非空表示是 `[文字](url)` 里的文字。 */
    class Seg(val text: String, val bold: Boolean = false, val code: Boolean = false, val link: String? = null)

    /**
     * 一个块 = 视觉上一组贴在一起的行。
     * lines 里的每行是段列表；gap 是块前的空行数（渲染时间隔用）。
     * lines 用可变列表：相邻同类块要往里并行。
     */
    class Block(
        val kind: String,          // "h" 标题 / "ul" / "ol" / "quote" / "code" / "hr" / "table" / "p" 段落
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
    private val LINK = Regex("\\[([^\\[\\]]*)\\]\\(([^\\s)]+)\\)")
    private val SEPC = Regex("^:?-{2,}:?$")

    fun parse(src: String): List<Block> {
        val out = ArrayList<Block>()
        var gap = 0
        val codeLines = ArrayList<String>()
        var inCode = false

        // 手动带下标遍历：表格要把连续的 | 行一把吞掉，for-each 给不了「跳到第 j 行」
        val all = src.lineSequence().toList()
        var i = 0
        while (i < all.size) {
            val raw = all[i]
            if (inCode) {
                // 围栏内原样保留，直到闭合的 ```。没有闭合就攒到文末一起算。
                // 每行代码独立占块里的一行——渲染器按行补换行，行缩进才能活下来；
                // 之前整块塞一条 line，段间没有分隔，代码全挤成一坨。
                if (raw.trimStart().startsWith("```")) {
                    out.add(Block("code", 0, ArrayList(codeLines.map { listOf(Seg(it)) }), gap))
                    codeLines.clear(); inCode = false; gap = 0
                } else codeLines.add(raw)
                i++; continue
            }
            val t = raw.trimEnd()
            if (t.isBlank()) { gap++; i++; continue }
            if (t.trimStart().startsWith("```")) { inCode = true; i++; continue }

            // 表格：连续以 | 开头的行，第二行是 |---| 分隔行才算数。
            // 分隔行直接扔掉（对齐信息在窄气泡里没用），其余行收成紧凑行。
            if (t.trimStart().startsWith("|")) {
                var j = i
                val rows = ArrayList<String>()
                while (j < all.size && all[j].trim().startsWith("|")) { rows.add(all[j].trim()); j++ }
                if (rows.size >= 2 && isSepRow(rows[1])) {
                    out.add(table(rows, gap))
                    gap = 0; i = j; continue
                }
                // 单行 | 或没有分隔行：不算表格，接着按普通段落走
            }

            val h = HEAD.find(t.trimStart())
            if (h != null) {
                out.add(Block("h", h.groupValues[1].length, arrayListOf(inline(h.groupValues[2])), gap))
                gap = 0; i++; continue
            }
            if (HR.matches(t.trim())) {
                out.add(Block("hr", 0, arrayListOf(), gap)); gap = 0; i++; continue
            }
            val b = BULLET.find(t.trimStart())
            if (b != null) {
                merge(out, "ul", inline(b.groupValues[1]), gap); gap = 0; i++; continue
            }
            val n = NUMBER.find(t.trimStart())
            if (n != null) {
                // 编号本身加粗：一眼能扫到「1. 2. 3.」的骨架
                merge(out, "ol", listOf(Seg(n.groupValues[1] + ". ", bold = true)) + inline(n.groupValues[2]), gap)
                gap = 0; i++; continue
            }
            val q = QUOTE.find(t.trimStart())
            if (q != null) {
                merge(out, "quote", inline(q.groupValues[1]), gap); gap = 0; i++; continue
            }
            merge(out, "p", inline(t), gap); gap = 0; i++
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

    // ------------------------------------------------------------ 表格

    /**
     * 表格 → 紧凑块。窄气泡画不了网格，一列一列挤完就是灾难；
     * 这里收成每行一条「首列 · 第二列 · 第三列」，表头整行加粗，
     * 数据行首列加粗（键值对里首列通常就是键，榜单里首列就是名字）。
     * 行间由渲染器按 lines 逐条换行，跟列表的节奏一致。
     */
    private fun table(rows: List<String>, gap: Int): Block {
        val lines = ArrayList<List<Seg>>()
        lines.add(rowLine(splitCells(rows[0]), boldAll = true))
        for (k in 2 until rows.size) {   // rows[1] 是 |---| 分隔行，跳过
            val line = rowLine(splitCells(rows[k]), boldAll = false)
            if (line.isNotEmpty()) lines.add(line)
        }
        return Block("table", 0, lines, gap)
    }

    /** 分隔行：去掉首尾管道后，每格都是 `---` / `:--:` / `--:` 这种。要求两杠以上，单杠容易误伤。 */
    private fun isSepRow(line: String): Boolean {
        if (!line.contains("|")) return false
        val cells = splitCells(line)
        return cells.isNotEmpty() && cells.all { SEPC.matches(it) }
    }

    /** 去掉首尾管道再按 | 切格。模型偶尔漏写行尾管道，切出来一样能用。 */
    private fun splitCells(line: String): List<String> {
        var s = line
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }

    /** 一行格子 → 段列表：格子间插 ` · `，空格跳过。boldAll 给表头，数据行只加粗首格。 */
    private fun rowLine(cells: List<String>, boldAll: Boolean): List<Seg> {
        val segs = ArrayList<Seg>()
        var boldNext = true
        for (c in cells) {
            val parts = inline(c)
            if (parts.isEmpty() || parts.all { it.text.isBlank() }) continue
            if (segs.isNotEmpty()) segs.add(Seg(" · "))
            val bold = boldAll || boldNext
            boldNext = false
            for (p in parts) segs.add(Seg(p.text, bold, p.code, p.link))
        }
        return segs
    }

    /**
     * 行内记号：`**粗体**`（`__` 同义）、`` `代码` `` 和显式链接 `[文字](url)`。
     * 链接只认 markdown 写法，裸 URL 不碰——自动识别太容易误伤（路径、时间戳）。
     * 没找到闭合的当成普通文本——流式输出打到一半时星号先裸着，
     * 对闭合的瞬间就会变成粗体，不用等整段结束。
     */
    fun inline(s: String): List<Seg> {
        // \/ 是 CommonMark 里 / 的合法转义，微信等阅读器都显示成 /；模型偶尔
        // 过度转义带出来的字面反斜杠，显示层统一归一，别让用户看见斜线
        val s = s.replace("\\/", "/")
        val segs = ArrayList<Seg>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { segs.add(Seg(buf.toString())); buf.setLength(0) } }
        var i = 0
        while (i < s.length) {
            if (s[i] == '[') {
                val m = LINK.find(s, i)
                if (m != null && m.range.first == i) {
                    flush()
                    // 链接文字里的粗体/代码保留，整段挂上 url
                    val url = m.groupValues[2]
                    for (p in inline(m.groupValues[1])) segs.add(Seg(p.text, p.bold, p.code, url))
                    i = m.range.last + 1
                    continue
                }
            }
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
