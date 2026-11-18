package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 图表的布局层：数据 → 一份「绘制计划」（JSON）。纯 JVM，测试里直接断言坐标。
 *
 * 为什么拆两层（和 [ShotText] 同一个理由）：画像素要 android.graphics，桌面测试
 * 没有；布局（刻度怎么取整、柱子摆哪儿）恰恰是最容易算错的部分。计划里的元素
 * 只有五种基本图元（rect/line/circle/arc/text），像素层照单画，不做任何计算——
 * 两层之间的契约越笨，越不会出「测试过、真机歪」的事。
 *
 * 设计口径：
 * - 柱状/折线 y 轴从 0 起。图表撒谎最常见的方式就是截断 y 轴，工具替用户防这个。
 * - 刻度取「nice number」（1/2/2.5/5 × 10^k），4-6 根，不出现 33.333 这种刻度。
 * - 颜色固定六色调色板，白底可读；系列多于 6 个循环取色。
 */
object ChartText {

    /** 画布默认尺寸。1080 宽是手机截图的常见宽，插进文档不至于糊。 */
    const val W = 1080
    const val H = 720

    /** 白底。像素层铺这个色，计划里不重复写。 */
    const val BG = "#FFFFFF"

    /** 系列色板（顺序即取色顺序）：蓝红绿橙紫青，白底上都够对比。 */
    val PALETTE = listOf("#378ADD", "#E24B4A", "#639922", "#EF9F27", "#7F77DD", "#1D9E75")

    /** 网格线/轴线的固定色。 */
    private const val GRID = "#D3D1C7"
    private const val INK = "#444441"
    private const val INK2 = "#888780"

    /** 绘图区内边距：左留 y 轴刻度，上留标题和图例，下留 x 轴标签。 */
    private const val PAD_L = 130
    private const val PAD_T = 96
    private const val PAD_R = 40
    private const val PAD_B = 84

    /** 单类目标签数上限。再多塞一张图，字都挤没了，改 CSV 让用户自己看。 */
    const val MAX_POINTS = 40

    /** 饼图的扇区上限。12 块往上的饼图没人能读。 */
    const val MAX_SLICES = 12

    /** 一条曲线的输入：名字 + 一串数。 */
    data class Series(val name: String, val values: List<Double>)

    /** 校验错误：message 是给模型看的人话。 */
    class Bad(message: String) : Exception(message)

    /**
     * 生成绘制计划。
     * @param type bar / line / pie
     * @param labels 类目名（bar/line 是 x 轴，pie 是扇区名）
     * @param series bar/line 的数据系列；pie 时为空
     * @param values pie 专用：每个扇区的值
     */
    fun plan(
        type: String,
        title: String,
        labels: List<String>,
        series: List<Series>,
        values: List<Double>?,
    ): JSONObject {
        return when (type) {
            "bar" -> axesChart(title, labels, series, bars = true)
            "line" -> axesChart(title, labels, series, bars = false)
            "pie" -> pie(title, labels, values ?: throw Bad("pie 要给 values（每个扇区的值）"))
            else -> throw Bad("type 要是 bar、line 或 pie（收到：$type）")
        }
    }

    // ------------------------------------------------------------ 柱状 / 折线

    private fun axesChart(title: String, labels: List<String>, series: List<Series>, bars: Boolean): JSONObject {
        if (labels.isEmpty()) throw Bad("labels 不能为空（类目名，如月份/项目）")
        if (labels.size > MAX_POINTS) throw Bad("类目上限 $MAX_POINTS 个，收到 ${labels.size}")
        if (series.isEmpty()) throw Bad("series 不能为空。给 [{name:\"...\", values:[...]}]，至少一条")
        if (series.size > PALETTE.size) throw Bad("系列上限 ${PALETTE.size} 条，收到 ${series.size}。多了图例放不下")
        for ((i, s) in series.withIndex()) {
            if (s.values.size != labels.size) {
                throw Bad("series[$i]「${s.name}」有 ${s.values.size} 个值，labels 有 ${labels.size} 个——数量要一致")
            }
        }
        val all = series.flatMap { it.values }
        if (all.isEmpty()) throw Bad("series 里没有数据")
        if (all.any { it < 0 }) throw Bad("有负值。这张图 y 轴从 0 起（防轴截断撒谎），负值请改用 CSV/表格呈现")
        if (all.all { it == 0.0 }) throw Bad("数据全为 0，画出来是一张空白图。确认一下数据")

        val items = JSONArray()
        val plotL = PAD_L.toDouble()
        val plotT = PAD_T.toDouble()
        val plotR = (W - PAD_R).toDouble()
        val plotB = (H - PAD_B).toDouble()

        // ── 标题与图例
        if (title.isNotBlank()) {
            items.put(text(28.0, 52.0, title.take(40), 34, INK, bold = true))
        }
        if (series.size > 1) {
            var lx = plotR - legendWidth(series)
            if (lx < plotL + 200) lx = plotL + 200
            for ((i, s) in series.withIndex()) {
                val color = PALETTE[i % PALETTE.size]
                items.put(rect(lx, 38.0, 22.0, 22.0, color, r = 4.0))
                items.put(text(lx + 30.0, 49.0, s.name.take(10), 24, INK))
                lx += 30 + approxTextW(s.name.take(10), 24) + 26
            }
        }

        // ── y 轴刻度（nice number）
        val rawMax = all.max()
        val ticks = niceTicks(0.0, ceil(rawMax * 1.08), 5)
        val yTop = ticks.last()
        val fy = { v: Double -> plotB - (v / yTop) * (plotB - plotT) }

        for (t in ticks) {
            val y = fy(t)
            if (y > plotT - 1) items.put(line(plotL, y, plotR, y, GRID, 1f))
            items.put(text((PAD_L - 14).toDouble(), y, fmt(t), 24, INK2, align = "r"))
        }
        items.put(line(plotL, plotT, plotL, plotB, INK2, 1.5f))
        items.put(line(plotL, plotB, plotR, plotB, INK2, 1.5f))

        // ── 数据
        val slot = (plotR - plotL) / labels.size
        if (bars) {
            val group = slot * 0.72
            val barW = group / series.size
            for ((i, label) in labels.withIndex()) {
                for ((si, s) in series.withIndex()) {
                    val v = s.values[i]
                    val h = (v / yTop) * (plotB - plotT)
                    val x = plotL + slot * i + (slot - group) / 2 + barW * si
                    // 高度不到 1px 的值也画 1px，不然 0.5 的柱子看不见
                    items.put(rect(x, plotB - h, barW * 0.86, maxOf(h, 1.0),
                        PALETTE[si % PALETTE.size], r = 4.0))
                }
                items.put(text(plotL + slot * (i + 0.5), plotB + 30.0, ellipsize(label, 8), 24, INK))
            }
        } else {
            for ((si, s) in series.withIndex()) {
                val color = PALETTE[si % PALETTE.size]
                val pts = DoubleArray(labels.size * 2)
                for ((i, _) in labels.withIndex()) {
                    pts[i * 2] = plotL + slot * (i + 0.5)
                    pts[i * 2 + 1] = fy(s.values[i])
                }
                items.put(poly(pts, color, 4f))
                for (p in 0 until labels.size) {
                    items.put(circle(pts[p * 2], pts[p * 2 + 1], 7.0, color))
                }
            }
            for ((i, label) in labels.withIndex()) {
                items.put(text(plotL + slot * (i + 0.5), plotB + 30, ellipsize(label, 8), 24, INK))
            }
        }

        return JSONObject()
            .put("w", W).put("h", H).put("bg", BG)
            .put("items", items)
    }

    // ------------------------------------------------------------ 饼图

    private fun pie(title: String, labels: List<String>, values: List<Double>): JSONObject {
        if (labels.isEmpty()) throw Bad("labels 不能为空（每个扇区的名字）")
        if (labels.size != values.size) {
            throw Bad("labels 有 ${labels.size} 个，values 有 ${values.size} 个——数量要一致")
        }
        if (labels.size > MAX_SLICES) throw Bad("扇区上限 $MAX_SLICES 个，收到 ${labels.size}。小项合并成「其他」")
        if (values.any { it < 0 }) throw Bad("扇区的值不能为负")
        val total = values.sum()
        if (total <= 0) throw Bad("所有扇区的值加起来是 0，画不出饼图。确认一下数据")

        val items = JSONArray()
        if (title.isNotBlank()) items.put(text(28.0, 52.0, title.take(40), 34, INK, bold = true))

        val cx = W * 0.36
        val cy = H * 0.54
        val r = minOf(H / 2.0 - 120, W * 0.28)
        var angle = -90.0
        for ((i, v) in values.withIndex()) {
            val sweep = v / total * 360.0
            if (sweep > 0.01) {
                items.put(arc(cx, cy, r, angle, sweep, PALETTE[i % PALETTE.size]))
            }
            angle += sweep
        }

        // 图例放右侧：色块 + 名字 + 占比。一行放不下的名字截断。
        var ly = cy - labels.size * 22 + 11
        for ((i, label) in labels.withIndex()) {
            val pct = values[i] / total * 100.0
            items.put(rect(W * 0.70, ly - 10.0, 20.0, 20.0, PALETTE[i % PALETTE.size], r = 4.0))
            items.put(text(W * 0.70 + 30.0, ly, ellipsize(label, 10), 24, INK))
            items.put(text(W - 40.0, ly, fmtPct(pct), 24, INK2, align = "r"))
            ly += 44
        }

        return JSONObject()
            .put("w", W).put("h", H).put("bg", BG)
            .put("items", items)
    }

    // ------------------------------------------------------------ 图元

    private fun rect(x: Double, y: Double, w: Double, h: Double, fill: String, r: Double = 0.0): JSONObject =
        JSONObject().put("op", "rect").put("x", x).put("y", y).put("w", w).put("h", h)
            .put("fill", fill).put("r", r)

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: String, width: Float): JSONObject =
        JSONObject().put("op", "line").put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
            .put("color", color).put("width", width.toDouble())

    private fun poly(pts: DoubleArray, color: String, width: Float): JSONObject {
        val a = JSONArray()
        for (p in pts) a.put(p)
        return JSONObject().put("op", "poly").put("pts", a).put("color", color).put("width", width.toDouble())
    }

    private fun circle(cx: Double, cy: Double, r: Double, fill: String): JSONObject =
        JSONObject().put("op", "circle").put("cx", cx).put("cy", cy).put("r", r).put("fill", fill)

    private fun arc(cx: Double, cy: Double, r: Double, start: Double, sweep: Double, fill: String): JSONObject =
        JSONObject().put("op", "arc").put("cx", cx).put("cy", cy).put("r", r)
            .put("start", start).put("sweep", sweep).put("fill", fill)

    private fun text(x: Double, y: Double, s: String, size: Int, color: String,
                     bold: Boolean = false, align: String = "l"): JSONObject =
        JSONObject().put("op", "text").put("x", x).put("y", y).put("s", s).put("size", size)
            .put("color", color).put("bold", bold).put("align", align)

    // ------------------------------------------------------------ 数字与文字

    /**
     * nice 刻度：步长取 1/2/2.5/5 × 10^k，覆盖 [min, max]，返回含上界的完整刻度。
     * 33.3 这种刻度是图表工具最常见的难看来源，这里一步到位。
     */
    fun niceTicks(min: Double, max: Double, count: Int): List<Double> {
        if (max <= min) return listOf(min)
        val raw = (max - min) / (count - 1).coerceAtLeast(1)
        val mag = 10.0.pow(floor(log10(raw)))
        val norm = raw / mag
        val step = (when {
            norm <= 1 -> 1.0
            norm <= 2 -> 2.0
            norm <= 2.5 -> 2.5
            norm <= 5 -> 5.0
            else -> 10.0
        } * mag)
        val out = ArrayList<Double>()
        var t = ceil(min / step - 1e-9) * step
        if (t == 0.0) t = 0.0   // ceil(-1e-9) 会给 -0.0，规范化掉（Double.equals 区分 ±0）
        while (t <= max + step * 1e-9) {
            out.add(t)
            t += step
        }
        // 刻度必须罩住数据上界：max 落在步长中间时（如 max=36、步长 10），
        // 最后一根刻度若停在 30，柱子会顶出绘图区——补一根到 max 之上
        if (out.last() < max - step * 1e-9) out.add(out.last() + step)
        if (out.size < 2) out.add(max)
        return out
    }

    /** 刻度/数值格式：整数不带小数点，其余一位小数——图表上的数不用太精确。 */
    fun fmt(v: Double): String = when {
        abs(v - v.toLong()) < 1e-9 && abs(v) < 1e15 -> v.toLong().toString()
        else -> "%.1f".format(v)
    }

    /** 百分比：99.98 的边角四舍五入成 100.0% 也认了，别为 0.02% 挤一位。 */
    fun fmtPct(v: Double): String = "%.1f%%".format(v)

    /** 中文按 1.0、半角按 0.55 估宽（px）。布局用，像素层不依赖它。 */
    fun approxTextW(s: String, size: Int): Double =
        s.sumOf { if (it.code > 0x2E7F) size.toDouble() else size * 0.55 }

    /** 长标签截断（中文 8 个字就换行都嫌宽），尾部加 …。 */
    fun ellipsize(s: String, max: Int): String {
        val t = s.trim()
        if (t.length <= max) return t
        return t.take(max - 1) + "…"
    }

    /** 图例总宽（右对齐用）。 */
    private fun legendWidth(series: List<Series>): Double =
        series.sumOf { 30 + approxTextW(it.name.take(10), 24) + 26 }
}
