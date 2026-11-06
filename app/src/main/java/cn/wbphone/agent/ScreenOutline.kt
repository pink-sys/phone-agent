package cn.wbphone.agent

import org.json.JSONObject

/**
 * 把无障碍树压成模型友好的紧凑大纲。
 *
 * 这一层是「省积分」的关键。原始无障碍树一棵能到上万 token，压成大纲后一屏约 50~100 token：
 *  - 只留带文本或可操作的元素；
 *  - 长文本截断到 48 字；
 *  - **默认不带坐标**，模型点按时报序号，坐标由手机自己从 bounds 算中心点。
 *
 * 最后一条不只是为了省 token：模型算坐标经常算错，而序号是它天然会用的东西。
 */
object ScreenOutline {

    private const val MAX_TEXT = 48
    private const val DEFAULT_MAX_NODES = 120

    /**
     * 序号 → 元素 bounds（left, top, right, bottom）+ 序号 → 元素文本。
     *
     * 文本也要留着：点按前的风险判定得知道"要点的是什么"，
     * 否则只能看坐标，那和闭着眼点没区别。
     */
    data class Rendered(
        val text: String,
        val boxes: Map<Int, IntArray>,
        val labels: Map<Int, String>,
        val count: Int,
        val truncated: Boolean,
    ) {
        fun center(index: Int): IntArray? {
            val b = boxes[index] ?: return null
            return intArrayOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
        }

        fun label(index: Int): String = labels[index] ?: ""

        /** 整屏文本，给「这个界面像不像付款页」这类粗判用。 */
        fun allText(): String = labels.values.joinToString(" / ")
    }

    /**
     * @param payload readScreen() 的返回：{ ok, screen:{width,height,package}, tree:{...} }
     */
    fun render(payload: JSONObject, maxNodes: Int = DEFAULT_MAX_NODES, withBounds: Boolean = false): Rendered {
        val screen = payload.optJSONObject("screen") ?: JSONObject()
        val root = payload.optJSONObject("tree")
        val lines = ArrayList<String>()
        val boxes = HashMap<Int, IntArray>()
        val labels = HashMap<Int, String>()
        var counter = 0
        var truncated = false

        fun walk(node: JSONObject?, depth: Int) {
            if (truncated || node == null) return
            if (counter >= maxNodes) {
                truncated = true
                return
            }
            val bounds = boundsOf(node)
            if (bounds != null && (bounds[2] - bounds[0] <= 0 || bounds[3] - bounds[1] <= 0)) return

            val label = textOf(node)
            val clickable = node.optBoolean("clickable")
            val editable = node.optBoolean("editable")
            val scrollable = node.optBoolean("scrollable")
            val role = node.optString("role").ifEmpty { "View" }

            // 多窗口包装节点：不占序号，只画一条来源分隔线。
            // 键盘候选条（联想词）就住在键盘窗口里，分隔线让模型知道「这一段是键盘，
            // 里面的候选词可以直接点」。
            if (role == "window") {
                val p = node.optString("package")
                if (p.isNotEmpty()) lines.add("── $p ──")
                val kids = node.optJSONArray("children") ?: return
                for (i in 0 until kids.length()) walk(kids.optJSONObject(i), depth)
                return
            }

            var printed = false

            if (label.isNotEmpty() || ((clickable || editable) && role.isNotEmpty())) {
                counter += 1
                val marks = StringBuilder()
                if (clickable) marks.append('›')
                if (editable) marks.append('✎')
                if (scrollable) marks.append('⇕')
                val indent = "  ".repeat(depth.coerceAtMost(6))
                val suffix = if (marks.isEmpty()) "" else " $marks"
                val tail = if (withBounds && bounds != null) " (${bounds.joinToString(",")})" else ""
                val shown = label.ifEmpty { "<$role>" }
                lines.add("[$counter] $indent$shown$suffix$tail")
                if (bounds != null) boxes[counter] = bounds
                labels[counter] = shown
                printed = true
            }

            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                walk(children.optJSONObject(i), if (printed) depth + 1 else depth)
            }
        }

        walk(root, 0)

        val width = screen.optInt("width")
        val height = screen.optInt("height")
        val pkg = screen.optString("package")
        val header = buildString {
            append("屏幕 ").append(width).append('×').append(height)
            if (pkg.isNotEmpty()) append(" · ").append(pkg)
            append(" · ").append(counter).append(" 个可定位元素")
        }

        val parts = ArrayList<String>()
        parts.add(header)
        parts.addAll(lines)
        if (truncated) parts.add("… 已截断（超过 $maxNodes 个元素，先滚动或缩小范围）")
        if (counter == 0) {
            parts.add("（没读到可定位元素：界面可能是自绘的，也可能正在启动/切换动画中。等 1~2 秒重读一次，" +
                "还不行就截屏看真实画面。如果上面标着「月溯自己」，说明前台还是聊天界面，先启动目标 App）")
        }

        return Rendered(parts.joinToString("\n"), boxes, labels, counter, truncated)
    }

    private fun textOf(node: JSONObject): String {
        val raw = node.optString("text").trim().ifEmpty { node.optString("desc").trim() }
        if (raw.isEmpty()) return ""
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > MAX_TEXT) oneLine.take(MAX_TEXT - 1) + "…" else oneLine
    }

    private fun boundsOf(node: JSONObject): IntArray? {
        val arr = node.optJSONArray("bounds") ?: return null
        if (arr.length() != 4) return null
        return intArrayOf(arr.optInt(0), arr.optInt(1), arr.optInt(2), arr.optInt(3))
    }
}
