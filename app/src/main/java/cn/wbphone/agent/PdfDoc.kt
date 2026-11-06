package cn.wbphone.agent

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PDF 生成引擎（Android 原生 PdfDocument，无依赖）。
 *
 * 为什么不用 WebView 渲染 HTML 再打印：离屏分页要跟 WebView 的异步加载、滚动状态缠斗，
 * 在后台线程上跑还容易被系统杀。PdfDocument + StaticLayout 是同步的、确定的、可测的。
 *
 * 版式口径：A4，页边距 48pt；标题 20pt 雾蓝深色、h2 15pt、正文 11pt、
 * 引用块灰字缩进、要点带圆点。中英文都走系统默认字体，中文渲染零问题。
 *
 * 模型给的 blocks：[{type:"h1|h2|h3|p|bullet|quote|spacer|pagebreak", text:"..."}]。
 * 刻意不接受任意 HTML——那是把排版难题原样丢回来；结构化块分页才算得准。
 */
object PdfDoc {

    /** 挂到 Workbench.DocEngine 上：Workbench 是纯 JVM，Android 实现在这里注入。 */
    object Engine : Workbench.DocEngine {
        override fun writePdf(out: File, title: String, blocks: JSONArray): String =
            write(out, title, blocks)
    }

    private const val W = 595      // A4 宽，pt（1/72 英寸）
    private const val H = 842
    private const val ML = 48f
    private const val MR = 48f
    private const val MT = 56f
    private const val MB = 56f
    private const val CONTENT_W = (W - ML - MR).toInt()

    private const val INK = 0xFF1F2933.toInt()
    private const val BRAND = 0xFF14395B.toInt()
    private const val GRAY = 0xFF6B7280.toInt()

    fun write(out: File, title: String, blocks: JSONArray): String {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo += 1
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page = p
            canvas = p.canvas
            y = MT
            // 页脚页码
            val foot = TextPaint().apply {
                textSize = 8f; color = GRAY; isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            p.canvas.drawText("$pageNo", W / 2f, H - 28f, foot)
        }

        fun ensure(h: Float) {
            if (page == null || y + h > H - MB) newPage()
        }

        fun draw(text: String, size: Float, bold: Boolean, color: Int, indent: Float = 0f, extraTop: Float = 0f) {
            val paint = TextPaint().apply {
                textSize = size; this.color = color; isAntiAlias = true
                if (bold) typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            }
            val width = (CONTENT_W - indent).toInt()
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .build()
            val h = layout.height.toFloat()
            // 单块比整页还高：新开一页画（画不下的部分舍弃并继续）——正文按段落给，这几乎不会发生
            if (h > H - MT - MB) newPage()
            else ensure(h + extraTop)
            canvas!!.save()
            canvas!!.translate(ML + indent, y)
            layout.draw(canvas)
            canvas!!.restore()
            y += h + extraTop + 4f
        }

        try {
            if (title.isNotBlank()) {
                draw(title, 22f, true, BRAND, extraTop = 6f)
                // 标题下一条雾蓝分隔线
                ensure(10f)
                canvas!!.drawRect(ML, y, W - MR, y + 2.5f, Paint().apply { color = BRAND })
                y += 18f
            }

            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                val type = b.optString("type", "p").lowercase()
                val text = b.optString("text").trim()
                when (type) {
                    "pagebreak" -> newPage()
                    "spacer" -> y += 12f
                    "h1" -> if (text.isNotEmpty()) draw(text, 18f, true, BRAND, extraTop = 8f)
                    "h2" -> if (text.isNotEmpty()) draw(text, 14.5f, true, BRAND, extraTop = 6f)
                    "h3" -> if (text.isNotEmpty()) draw(text, 12f, true, INK, extraTop = 5f)
                    "bullet" -> if (text.isNotEmpty()) {
                        draw("•  $text", 10.5f, false, INK, indent = 14f)
                    }
                    "quote" -> if (text.isNotEmpty()) draw(text, 10.5f, false, GRAY, indent = 14f)
                    else -> if (text.isNotEmpty()) draw(text, 11f, false, INK)
                }
            }
            page?.let { doc.finishPage(it) }

            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
        } catch (e: Exception) {
            return JSONObject()
                .put("ok", false).put("code", "PDF_FAILED").put("error", e.message ?: "生成失败")
                .toString()
        } finally {
            doc.close()
        }

        return JSONObject()
            .put("ok", true)
            .put("path", out.name)
            .put("pages", pageNo)
            .put("bytes", out.length())
            .toString()
    }
}
