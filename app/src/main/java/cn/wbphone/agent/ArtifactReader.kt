package cn.wbphone.agent

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 产物阅读页：点开产物卡片后的整页预览，照 dsh-Go 的阅读版式。
 *
 * 之前是 AlertDialog 塞一坨原文——Markdown 的 `##`、`|---|`、`**` 原样印出来，
 * 用户根本读不了。这里把 Markdown 渲染成排版：
 *
 * - 标题分级（# 到 ####，字号递减），正文直接排在页面渐变上，不套卡；
 * - 引用块 = 左侧主色竖条 + 灰字；表格 = 真表格（表头浅底、列宽按内容分、发丝分隔）；
 * - 列表带主色符号；代码块 = 浅灰井 + 等宽；行内 `code` / **加粗** / [链接](..) 上 span；
 * - 不是 Markdown 的文件（.py/.json/.log）整篇退回代码井，等宽字好读。
 *
 * 纯 android.widget + Span，工程不引任何依赖。
 */
object ArtifactReader {

    // ══ 行内语法 ══════════════════════════════════════════
    private val CODE = Regex("`([^`\\n]+)`")
    private val BOLD = Regex("\\*\\*([^*\\n]+)\\*\\*")
    private val LINK = Regex("\\[([^\\]\\n]+)]\\(([^)\\n]+)\\)")
    private val EM = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UL = Regex("^(\\s*)[-*•]\\s+(.*)$")
    private val OL = Regex("^(\\s*)(\\d+)[.、)]\\s+(.*)$")

    /** 内容像不像 Markdown：像才渲染，不像（纯代码/日志）整篇进代码井。 */
    fun looksMarkdown(s: String): Boolean {
        val head = s.take(6000)
        return head.contains("```") ||
            Regex("(?m)^#{1,6}\\s").containsMatchIn(head) ||
            Regex("(?m)^\\s*\\|.+\\|\\s*$").containsMatchIn(head) ||
            Regex("(?m)^\\s*[-*•]\\s+").containsMatchIn(head) ||
            head.contains("**")
    }

    /** 代码/数据类扩展名：内容一律进等宽代码井，不做 Markdown 猜测。 */
    private val CODE_EXT = setOf(
        "py", "js", "ts", "kt", "java", "kts", "c", "h", "cpp", "hpp", "cs", "go", "rs",
        "rb", "php", "swift", "sh", "bat", "ps1", "sql", "json", "xml", "yml", "yaml",
        "toml", "ini", "csv", "tsv", "log", "properties", "gradle", "html", "css",
    )

    fun isCodeFile(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in CODE_EXT

    /** 文件名只取末段，别拿整条路径当大标题。 */
    fun shortName(path: String): String = path.trim().trimStart('/').substringAfterLast('/')

    // ══ 页面骨架 ══════════════════════════════════════════

    /**
     * 整页阅读视图。
     * @param tailNote 截断提示（超长文件只读了前一段时给一句「后面还有」）
     */
    fun build(
        act: android.app.Activity,
        path: String,
        content: String,
        tailNote: String?,
        onClose: () -> Unit,
        onCopy: () -> Unit,
        onShare: () -> Unit,
    ): View {
        val page = Ui.column(act)
        page.background = Ui.screen(act)

        // ---- 顶栏：← | 文件名 | ⧉ ↗（照 dsh-Go 预览页的 ← / ↑ / ⋯）
        val head = Ui.row(act)
        head.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 6))
        head.addView(circleBtn(act, "←", "返回", onClose))
        head.addView(
            Ui.text(act, shortName(path), 15f, Ui.INK, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(circleBtn(act, "⧉", "复制全文", onCopy))
        head.addView(
            circleBtn(act, "↗", "用其他应用打开", onShare),
            LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)).apply { leftMargin = Ui.dp(act, 8) }
        )
        page.addView(head)

        // ---- 正文
        val scroll = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = Ui.column(act)
        body.setPadding(Ui.dp(act, 18), Ui.dp(act, 10), Ui.dp(act, 18), Ui.dp(act, 30))

        // 大标题 + 一行元信息（照 dsh-Go：文件名很大，下面一行说明）
        body.addView(
            Ui.text(act, shortName(path), 22f, Ui.INK, true).apply {
                setLineSpacing(0f, 1.1f)
            }
        )
        body.addView(
            Ui.text(act, metaLine(content, isCodeFile(path)), 11.5f, Ui.INK3)
                .apply { setPadding(0, Ui.dp(act, 7), 0, 0) },
            Ui.margin(act, bottom = 14)
        )
        body.addView(
            View(act).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 1))
                .apply { bottomMargin = Ui.dp(act, 16) }
        )

        if (isCodeFile(path)) {
            // 代码文件不猜 Markdown：# 注释、** 幂运算这些会被误判，直接等宽排。
            body.addView(codeBlock(act, content))
        } else if (looksMarkdown(content)) {
            renderMarkdown(act, content, body)
        } else {
            body.addView(codeBlock(act, content))
        }
        if (tailNote != null) {
            body.addView(
                Ui.text(act, tailNote, 12f, Ui.INK3)
                    .apply { setPadding(0, Ui.dp(act, 14), 0, 0) }
            )
        }

        scroll.addView(body)
        page.addView(
            scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
        )
        return page
    }

    /** 元信息一行：多少字 · 类型。 */
    private fun metaLine(content: String, asCode: Boolean): String {
        val n = content.length
        val size = when {
            n >= 10_000 -> String.format(java.util.Locale.CHINESE, "%.1f 万字", n / 10000.0)
            n >= 1000 -> String.format(java.util.Locale.CHINESE, "%.1f 千字", n / 1000.0)
            else -> "$n 字"
        }
        val kind = when {
            asCode -> "代码 · 等宽排版"
            looksMarkdown(content) -> "Markdown"
            else -> "纯文本"
        }
        return "$size · $kind"
    }

    private fun circleBtn(act: android.app.Activity, label: String, desc: String, onClick: () -> Unit): TextView =
        Ui.text(act, label, 16f, Ui.INK).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            background = Ui.solid(act, Ui.GLASS, 20, Ui.GLASS_LINE, 1)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40))
        }

    // ══ Markdown 分块 ═════════════════════════════════════

    fun renderMarkdown(act: Context, md: String, into: LinearLayout) {
        // 模型早年写坏的文件里躺着字面 \/（JSON 过度转义落了盘）。CommonMark 把 \/
        // 当成 / 的合法转义来渲染，微信等阅读器都这么做——这里对齐口径。
        // 工作台预览在读文件入口已归一，这里兜住其他调用方（office 提取等）。
        val lines = md.replace("\\/", "/").replace("\r\n", "\n").split('\n')
        val n = lines.size
        var i = 0
        var first = true
        while (i < n) {
            val t = lines[i].trim()
            when {
                t.isEmpty() -> { i++; continue }

                t.startsWith("```") -> {
                    val code = StringBuilder()
                    i++
                    while (i < n && !lines[i].trim().startsWith("```")) {
                        code.append(lines[i]).append('\n'); i++
                    }
                    if (i < n) i++   // 吃掉收尾的 ```
                    into.addView(codeBlock(act, code.toString().trimEnd()), blockMargin(act, first))
                }

                t.startsWith("#") && HEADING.containsMatchIn(t) -> {
                    val m = HEADING.find(t)!!
                    val level = m.groupValues[1].length.coerceIn(1, 4)
                    into.addView(heading(act, level, m.groupValues[2]), blockMargin(act, first))
                    i++
                }

                t.startsWith(">") -> {
                    val buf = StringBuilder()
                    while (i < n && lines[i].trim().startsWith(">")) {
                        buf.append(lines[i].trim().drop(1).trim()).append('\n'); i++
                    }
                    into.addView(quoteBlock(act, buf.toString().trim()), blockMargin(act, first))
                }

                isTableStart(lines, i) -> {
                    val rows = ArrayList<List<String>>()
                    rows.add(cells(t))
                    i += 2   // 跳过表头下的 |---|---|
                    while (i < n && lines[i].trim().startsWith("|")) {
                        rows.add(cells(lines[i].trim())); i++
                    }
                    into.addView(tableBlock(act, rows), blockMargin(act, first))
                }

                t.startsWith("---") && t.all { it == '-' } -> {
                    into.addView(
                        View(act).apply { setBackgroundColor(Ui.LINE) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 1)
                        ).apply { topMargin = Ui.dp(act, if (first) 0 else 8); bottomMargin = Ui.dp(act, 8) }
                    )
                    first = false
                    i++
                }

                UL.containsMatchIn(lines[i]) || OL.containsMatchIn(lines[i]) -> {
                    val items = ArrayList<Pair<String, String>>()   // (marker, text)
                    while (i < n) {
                        val lm = UL.find(lines[i])
                        val om = OL.find(lines[i])
                        if (lm != null) {
                            items.add("•" to lm.groupValues[2]); i++
                        } else if (om != null) {
                            items.add(om.groupValues[2] + "." to om.groupValues[3]); i++
                        } else break
                    }
                    into.addView(listBlock(act, items), blockMargin(act, first))
                }

                else -> {
                    val buf = StringBuilder()
                    while (i < n) {
                        val lt = lines[i].trim()
                        val blockish = lt.isEmpty() || lt.startsWith("#") || lt.startsWith(">") ||
                            lt.startsWith("|") || lt.startsWith("```") ||
                            UL.containsMatchIn(lines[i]) || OL.containsMatchIn(lines[i])
                        // 第一行无条件收进来：否则「#!/usr/bin/env」这种以 # 开头却不是标题的行
                        // 一个字都收不进 buf，i 也不前进——外层 while 原地打转，主线程卡死。
                        // 真机实测：预览 .py 直接把 App 卡死（2026-11-03 修）。
                        if (blockish && buf.isNotEmpty()) break
                        buf.append(lt).append('\n'); i++
                    }
                    into.addView(paragraph(act, buf.toString().trim()), blockMargin(act, first))
                }
            }
            first = false
        }
    }

    /** 每块的上边距：首块贴住分隔线，其余留呼吸。 */
    private fun blockMargin(act: Context, first: Boolean): LinearLayout.LayoutParams =
        Ui.margin(act, top = if (first) 0 else 10, bottom = 6)

    // ══ 各类块 ════════════════════════════════════════════

    private fun heading(act: Context, level: Int, raw: String): TextView {
        val text = stripInlineMarks(raw)
        val size = when (level) {
            1 -> 19f; 2 -> 16.5f; 3 -> 15f; else -> 14f
        }
        return Ui.text(act, text, size, Ui.INK, bold = true).apply {
            if (level <= 2) setLineSpacing(0f, 1.15f)
        }
    }

    private fun paragraph(act: Context, raw: String): TextView =
        Ui.text(act, inline(raw), 14.5f, Ui.INK).apply {
            setLineSpacing(Ui.dp(act, 3).toFloat(), 1f)
        }

    /** 引用块：左主色竖条 + 灰字，不套底色（照 dsh-Go）。 */
    private fun quoteBlock(act: Context, raw: String): View {
        val row = Ui.row(act)
        row.addView(
            View(act).apply { background = Ui.solid(act, Ui.BRAND, 2) },
            LinearLayout.LayoutParams(Ui.dp(act, 3), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        row.addView(
            Ui.text(act, inline(raw), 13.5f, Ui.INK2).apply {
                setLineSpacing(Ui.dp(act, 3).toFloat(), 1f)
                setPadding(Ui.dp(act, 12), Ui.dp(act, 2), 0, Ui.dp(act, 2))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    /** 代码井：浅灰底 + 等宽。 */
    private fun codeBlock(act: Context, code: String): TextView =
        Ui.text(act, code, 12.5f, Ui.INK2).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(Ui.dp(act, 2).toFloat(), 1f)
            background = Ui.solid(act, Ui.SOFT, 10)
            setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
        }

    /** 列表：主色符号 + 内容，二级缩进。 */
    private fun listBlock(act: Context, items: List<Pair<String, String>>): LinearLayout {
        val box = Ui.column(act)
        for ((marker, raw) in items) {
            val row = Ui.row(act)
            val indent = Ui.dp(act, 0)
            row.setPadding(indent, 0, 0, 0)
            row.addView(
                Ui.text(act, marker, 14f, Ui.BRAND, bold = true).apply {
                    gravity = Gravity.TOP
                    setPadding(0, Ui.dp(act, 1), Ui.dp(act, 8), 0)
                }
            )
            row.addView(
                Ui.text(act, inline(raw), 14f, Ui.INK).apply {
                    setLineSpacing(Ui.dp(act, 2).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            box.addView(row, Ui.margin(act, bottom = 5))
        }
        return box
    }

    // ══ 表格 ══════════════════════════════════════════════

    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (!lines[i].trim().startsWith("|")) return false
        if (i + 1 >= lines.size) return false
        val sep = lines[i + 1].trim()
        return sep.startsWith("|") && sep.contains("-")
    }

    /** 一行 `| a | b |` 切成格子，空首尾竖线容忍。 */
    private fun cells(line: String): List<String> {
        val s = line.trim().trimStart('|').trimEnd('|')
        return s.split('|').map { it.trim() }
    }

    /**
     * 真表格：外层圆角卡 + 表头浅底，列宽按每列最长内容分权重，
     * 格与格之间发丝竖线，行与行之间发丝横线。
     *
     * 列宽两条硬规则（github-trending 那张表翻过车：「简介」列内容长、权重巨大，
     * 星数列被挤成一条竖缝，字竖着排，还没有横向滚动可救）：
     *  - 任何列不得低于 [MIN_COL_DP]；窄列抬到下限的差价，从宽列按比例扣；
     *  - 宽列扣无可扣（列多到下限都放不下）时，整卡进横向滚动，列按内容估宽，绝不压扁。
     */
    private fun tableBlock(act: Context, rows: List<List<String>>): View {
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val maxLens = IntArray(cols) { c -> rows.maxOf { r -> (r.getOrNull(c) ?: "").length } }
        val dm = act.resources.displayMetrics
        val density = dm.density
        // 正文左右 padding 18dp×2，再留一点圆角描边的余量
        val availDp = dm.widthPixels / density - 42f
        val MIN_COL_DP = 72f

        val widthsDp: FloatArray = if (cols * MIN_COL_DP > availDp) {
            // 列太多，屏宽连下限都摆不下：按内容估宽（10dp/字，上限 240dp），交给横向滚
            FloatArray(cols) { c ->
                maxOf(MIN_COL_DP, maxLens[c].coerceIn(4, 22) * 10f + 24f).coerceAtMost(240f)
            }
        } else {
            val w = FloatArray(cols) { c ->
                availDp * maxLens[c].coerceAtLeast(1) / maxLens.sumOf { it }.coerceAtLeast(1)
            }
            val deficit = w.sumOf { if (it < MIN_COL_DP) (MIN_COL_DP - it).toDouble() else 0.0 }.toFloat()
            // 宽列能捐的宽度上限：自己也不能跌破下限
            val capacity = w.sumOf { if (it > MIN_COL_DP) (it - MIN_COL_DP).toDouble() else 0.0 }.toFloat()
            if (deficit <= 0f || capacity <= deficit) {
                // 捐不够：全员顶到自然宽度，超出的部分交给横向滚动兜底
                FloatArray(cols) { maxOf(MIN_COL_DP, w[it]) }
            } else {
                FloatArray(cols) { c ->
                    if (w[c] < MIN_COL_DP) MIN_COL_DP
                    else w[c] - deficit * ((w[c] - MIN_COL_DP) / capacity)
                }
            }
        }
        fun px(dp: Float) = (dp * density + 0.5f).toInt()

        val card = Ui.column(act)
        card.background = Ui.solid(act, Ui.CARD, 12, Ui.LINE, 1)

        rows.forEachIndexed { ri, row ->
            val r = Ui.row(act)
            if (ri == 0) {
                r.background = Ui.solidRadii(act, Ui.SOFT, intArrayOf(12, 12, 0, 0))
            }
            for (c in 0 until cols) {
                val cell = row.getOrNull(c) ?: ""
                r.addView(
                    Ui.text(act, inline(cell), 12.5f, if (ri == 0) Ui.INK else Ui.INK2, ri == 0).apply {
                        setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8))
                    },
                    // 列宽是算出来的定值，不再用 weight——weight 只会按比例瓜分屏宽，
                    // 长内容列一多，短内容列就沦为一条缝
                    LinearLayout.LayoutParams(px(widthsDp[c]), ViewGroup.LayoutParams.WRAP_CONTENT)
                )
                if (c < cols - 1) {
                    r.addView(
                        View(act).apply { setBackgroundColor(Ui.LINE) },
                        LinearLayout.LayoutParams(Ui.dp(act, 1), ViewGroup.LayoutParams.MATCH_PARENT)
                    )
                }
            }
            card.addView(r)
            if (ri < rows.size - 1) {
                card.addView(
                    View(act).apply { setBackgroundColor(Ui.LINE) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 1))
                )
            }
        }
        // 算出的总宽超出屏宽（列太多 / 下限抬不动）：整卡横向滚，左右滑着看
        if (widthsDp.sum() <= availDp + 0.5f) return card
        return android.widget.HorizontalScrollView(act).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    // ══ 行内 span ═════════════════════════════════════════

    /** 表格里再排一层版太挤：只剥符号不加上色。 */
    private fun stripInlineMarks(s: String): String =
        s.replace(BOLD) { it.groupValues[1] }
            .replace(CODE) { it.groupValues[1] }
            .replace(LINK) { it.groupValues[1] }
            .replace(EM) { it.groupValues[1] }

    /**
     * 行内渲染：`code` 上浅底等宽、**加粗**、[文字](链接) 主色、*斜体*。
     * 从左到右找最先出现的语法，逐段拼 SpannableStringBuilder。
     */
    fun inline(src: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        var rest = src
        while (true) {
            val m = listOfNotNull(CODE.find(rest), BOLD.find(rest), LINK.find(rest), EM.find(rest))
                .minByOrNull { it.range.first } ?: break
            ssb.append(rest.substring(0, m.range.first))
            val v = m.value
            val start = ssb.length
            when {
                v.startsWith("`") -> {
                    val piece = m.groupValues[1]
                    ssb.append(piece)
                    ssb.setSpan(Typeface.MONOSPACE, start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(
                        BackgroundColorSpan(Ui.SOFT), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                v.startsWith("[") -> {
                    ssb.append(m.groupValues[1])
                    ssb.setSpan(
                        ForegroundColorSpan(Ui.BRAND), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                v.startsWith("**") -> {
                    ssb.append(m.groupValues[1])
                    ssb.setSpan(
                        StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                else -> {
                    ssb.append(m.groupValues[1])
                    ssb.setSpan(
                        StyleSpan(Typeface.ITALIC), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            rest = rest.substring(m.range.last + 1)
        }
        ssb.append(rest)
        return ssb
    }
}
