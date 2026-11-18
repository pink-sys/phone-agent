package cn.wbphone.agent

/**
 * 文本截图的排版层（纯 JVM，零 android import——能在 tools/test_agent.py 里跑真断言）。
 *
 * 「运行结果截图」这件事拆成两层：这里决定**画什么、画多宽**（折行、列数、
 * 宽度丈量），像素层在 TextShot（Android Bitmap/Canvas）。拆开的原因和
 * DocxWriter 一样：最容易被忽略的坑都在逻辑层，真机才有 Bitmap 的地方
 * 测不到它们。
 */
object ShotText {

    /** 自适应列数的上限。手机屏竖过来 90 列等宽字已经快看不清了，再宽就该摘关键输出。 */
    const val MAX_COLS = 90

    /** 下限：太窄的截图每行都断得七零八落，反而难读。 */
    const val MIN_COLS = 28

    /**
     * 一个字符在等宽字体下占几列：CJK 全角算 2，其它算 1。
     * 范围覆盖韩文谚文、日文假名、CJK 统一表意及扩展、全角形式、兼容表意——
     * 少一块，中文日志的折行就会在错位处断开。
     */
    fun charWidth(c: Char): Int = if (
        c in '\u1100'..'\u115F' || c in '\u2E80'..'\u303E' || c in '\u3041'..'\u33FF' ||
        c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF' || c in '\uA000'..'\uA4CF' ||
        c in '\uAC00'..'\uD7A3' || c in '\uF900'..'\uFAFF' || c in '\uFE30'..'\uFE4F' ||
        c in '\uFF00'..'\uFF60' || c in '\uFFE0'..'\uFFE6'
    ) 2 else 1

    fun strWidth(s: String): Int {
        var w = 0
        for (c in s) w += charWidth(c)
        return w
    }

    /** 截图前的清理：tab 展开成 4 空格、\r 去掉、其它控制字符转空格（画出来是方块，难看）。 */
    fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '\t' -> repeat(4) { sb.append(' ') }
                c == '\r' -> {}
                c == '\n' || c.code >= 0x20 -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        return sb.toString()
    }

    /**
     * 按显示宽度折行。逐字符累计宽度，超过 maxCols 就断——终端输出不是英文散文，
     * 不做「空格断词」那套，路径/URL 长了宁可硬断也不能超出画布。
     * 只钳上限不钳下限：显式给的小列数是调用者的决定（自适应的下限在 [pickCols]）。
     */
    fun wrap(text: String, maxCols: Int): List<String> {
        val cols = maxCols.coerceAtMost(MAX_COLS)
        val out = ArrayList<String>()
        for (raw in sanitize(text).split('\n')) {
            if (raw.isEmpty()) {
                out.add("")
                continue
            }
            var cur = StringBuilder()
            var w = 0
            for (c in raw) {
                val cw = charWidth(c)
                if (w + cw > cols && cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur = StringBuilder()
                    w = 0
                }
                cur.append(c)
                w += cw
            }
            out.add(cur.toString())
        }
        return out
    }

    /**
     * 自适应列数：按最长行的显示宽取，最长不超过 [MAX_COLS]，
     * 下限 [MIN_COLS]——内容只有「ok」两个字母时也别画一条 28 列都不到的窄条。
     */
    fun pickCols(text: String): Int {
        var max = MIN_COLS
        for (line in sanitize(text).split('\n')) max = maxOf(max, strWidth(line))
        return minOf(max, MAX_COLS)
    }
}
