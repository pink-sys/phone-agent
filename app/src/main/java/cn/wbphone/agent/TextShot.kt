package cn.wbphone.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * text_shot 的像素层：[Workbench.ShotEngine] 的 Android 实现。
 *
 * 布局在 [ShotText]（纯 JVM，有测试）；这里只管画：黑底、等宽字、mac 风格
 * 标题栏（三个圆点 + 标题），输出 PNG。模型把 shell/日志/计算结果丢进来，
 * 得到一张能直接塞进 docx_write image 块的「运行截图」。
 *
 * 宽度封顶：先按列数估像素宽，超过 ~1080（一部手机截图的宽度）就缩字号，
 * 保证产物是一张「像手机上截的图」而不是超宽横幅。
 */
object TextShot : Workbench.ShotEngine {

    override fun write(out: File, text: String, title: String, cols: Int): String = try {
        val n = if (cols >= 20) cols else ShotText.pickCols(text)
        val lines = ShotText.wrap(text, n)
        out.parentFile?.mkdirs()
        out.writeBytes(render(lines, title, n))
        JSONObject()
            .put("ok", true)
            .put("out", out.name)
            .put("lines", lines.size)
            .put("bytes", out.length())
            .put("note", "PNG 已生成。插进 Word：docx_write 的 {type:\"image\", path:\"${out.name}\"}")
            .toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false)
            .put("code", "RENDER_FAILED")
            .put("error", "渲染失败：${e.message ?: e.javaClass.simpleName}")
            .toString()
    }

    // 终端配色（VSCode 暗色系）：背景近黑、正文浅灰、标题栏深一档
    private const val BG = 0xFF1E1E1E.toInt()
    private const val STRIP = 0xFF2A2A2A.toInt()
    private const val FG = 0xFFD4D4D4.toInt()
    private const val TITLE_FG = 0xFF9CDCFE.toInt()

    /** 画一张 PNG。lines 已折好；cols 是布局列数（决定字号）。 */
    private fun render(lines: List<String>, title: String, cols: Int): ByteArray {
        // 字号随列数缩：窄图大字（像截了个小窗口），宽图小字
        val textPx = when {
            cols <= 44 -> 34f
            cols <= 64 -> 28f
            else -> 24f
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = textPx
            color = FG
        }
        val lineH = (textPx * 1.4f).roundToInt()

        // 宽度：最长行的实测像素。超宽就整体缩——大 PNG 没有信息增量，只有内存和卡顿
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, paint.measureText(l.ifEmpty { " " }))
        val pad = (textPx * 0.9f).roundToInt()
        var w = (pad * 2 + maxW).roundToInt()
        if (title.isNotEmpty()) {
            w = maxOf(w, (pad * 2 + paint.measureText(title)).roundToInt())
        }
        if (w > 1080) {
            val scale = 1080f / w
            paint.textSize = textPx * scale
            w = 1080
        }
        val h = pad * 2 + lines.size * lineH + (if (title.isEmpty()) 0 else lineH)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(BG)

        // mac 风格标题栏：三个圆点 + 标题字
        var y = pad
        if (title.isNotEmpty()) {
            canvas.drawRect(0f, 0f, w.toFloat(), (lineH + pad).toFloat(),
                Paint().apply { color = STRIP })
            val dotR = textPx * 0.28f
            val dotY = pad + lineH * 0.5f
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val colors = intArrayOf(0xFFFF5F57.toInt(), 0xFFFEBC2E.toInt(), 0xFF28C840.toInt())
            for (i in 0..2) {
                dotPaint.color = colors[i]
                canvas.drawCircle(pad + dotR + i * dotR * 3.2f, dotY, dotR, dotPaint)
            }
            paint.color = TITLE_FG
            canvas.drawText(title, pad + dotR * 10f, dotY + textPx * 0.35f, paint)
            paint.color = FG
            y = pad + lineH
        }
        for (l in lines) {
            if (l.isNotEmpty()) canvas.drawText(l, pad.toFloat(), y + textPx * 0.9f, paint)
            y += lineH
        }

        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }
}
