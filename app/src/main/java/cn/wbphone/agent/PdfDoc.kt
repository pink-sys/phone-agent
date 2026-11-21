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
 * 版式口径：A4，页边距 48pt；标题 20pt 黑、h2 15pt、正文 11pt、
 * 引用块灰字缩进、要点带圆点、code 等宽浅灰底。中英文都走系统默认字体，中文渲染零问题。
 *
 * 生成完把前 3 页渲染成一张竖排预览图（PDF 旁临时落一个 .preview.jpg），
 * PhoneToolbox 会把它编码进 ToolResult.imageDataUrl 回传——模型能亲眼看到排出来的样子。
 * 这一步是血的教训（2026-11-20 ACM 押题）：模型以前只能用 pypdf 抽文本来「验证」，
 * 而 Android PdfDocument 的 CJK 文本层抽取常见假乱码（NUL、错标点），模型一看就认定
 * 引擎坏了，弃用内置工具走上 pip 装 reportlab + 下载 73MB 字体包的路，
 * 12 分钟烧出一份第 3 页全是缺字豆腐块的 PDF。看得见，才不会瞎猜。
 *
 * 模型给的 blocks：[{type:"h1|h2|h3|p|bullet|quote|code|spacer|pagebreak", text:"..."}]。
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
    private const val BRAND = 0xFF1F2933.toInt()   // 文档正文不玩色：真机反馈彩色标题突兀
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

        /**
         * 代码块：等宽、浅灰底、保留缩进换行。逐行画——一段代码往往比一页高，
         * 整块画会在页尾被截断，按行流式排能自然跨页（底色随行分块补）。
         */
        fun drawCode(text: String) {
            val paint = TextPaint().apply {
                textSize = 8.5f; color = 0xFF3D4455.toInt(); isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val bg = Paint().apply { color = 0xFFF2F4F8.toInt() }
            ensure(24f)
            var i = 0
            val lines = text.split('\n')
            while (i < lines.size) {
                if (y + 20f > H - MB) newPage()
                // 尽量把装得下的行凑成一页里的一个色块
                val chunk = ArrayList<StaticLayout>()
                var h = 0f
                while (i < lines.size) {
                    val l = lines[i]
                    val layout = StaticLayout.Builder
                        .obtain(l.ifEmpty { " " }, 0, maxOf(l.length, 1), paint, CONTENT_W - 16)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                    if (chunk.isNotEmpty() && y + h + layout.height + 8f > H - MB) break
                    chunk.add(layout); h += layout.height; i++
                }
                canvas!!.drawRect(ML, y - 4f, W - MR, y + h + 6f, bg)
                canvas!!.save()
                canvas!!.translate(ML + 8f, y)
                for (l in chunk) {
                    l.draw(canvas)
                    canvas!!.translate(0f, l.height.toFloat())
                }
                canvas!!.restore()
                y += h + 10f
            }
        }

        try {
            if (title.isNotBlank()) {
                draw(title, 22f, true, BRAND, extraTop = 6f)
                // 标题下一条浅灰分隔线
                ensure(10f)
                canvas!!.drawRect(ML, y, W - MR, y + 2.5f, Paint().apply { color = 0xFFBFBFBF.toInt() })
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
                    "code", "pre", "codeblock" -> if (text.isNotEmpty()) drawCode(text)
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

        // 预览图：前 3 页竖排一张 JPEG，落在 PDF 旁边（.preview.jpg 后缀），
        // PhoneToolbox 编码回传后立刻删掉——不会出现在工作台列表里
        val previewPath = runCatching { renderPreview(out) }.getOrNull()

        return JSONObject()
            .put("ok", true)
            .put("path", out.name)
            .put("pages", pageNo)
            .put("bytes", out.length())
            .apply { if (previewPath != null) put("preview_path", previewPath) }
            .toString()
    }

    /**
     * 用系统 PdfRenderer 把前 3 页渲染成一张竖排预览图。
     * 独立 runCatching 包裹：预览失败不影响 PDF 本身（它是给模型看的，不是交付物）。
     */
    private fun renderPreview(pdf: File): String? {
        val pfd = android.os.ParcelFileDescriptor.open(
            pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        try {
            val count = minOf(renderer.pageCount, 3)
            val pages = (0 until count).map { idx ->
                renderer.openPage(idx).use { page ->
                    val scale = 640f / page.width
                    val bmp = android.graphics.Bitmap.createBitmap(
                        640, (page.height * scale).toInt().coerceAtLeast(1),
                        android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(0xFFFFFFFF.toInt())
                    page.render(bmp, null, android.graphics.Matrix().apply { setScale(scale, scale) },
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
            val totalH = pages.sumOf { it.height } + (pages.size - 1) * 8
            val combined = android.graphics.Bitmap.createBitmap(640, totalH, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(combined)
            c.drawColor(0xFFE8EAF0.toInt())
            var ty = 0f
            for ((i, bmp) in pages.withIndex()) {
                if (i > 0) ty += 8f
                c.drawBitmap(bmp, 0f, ty, null)
                ty += bmp.height
            }
            val f = File(pdf.parentFile, pdf.name + ".preview.jpg")
            f.outputStream().use { combined.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, it) }
            return f.absolutePath
        } finally {
            renderer.close()
            pfd.close()
        }
    }
}
