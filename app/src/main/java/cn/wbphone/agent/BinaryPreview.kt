package cn.wbphone.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 二进制产物的真预览：PDF 逐页渲染成图、图片直接显示。
 *
 * 之前二进制产物点开只有一张「这是 PDF，去分享」的说明卡——模型明明已经把
 * 排版做出来了，预览里却看不到，还得跳出去借 WPS 的手。Android 自带
 * PdfRenderer（API 21+），零依赖就能把每页画成位图；图片用 BitmapFactory。
 * PPTX/DOCX 这类没有系统渲染器，仍走说明卡 + 分享，不硬造轮子。
 */
object BinaryPreview {

    /** 能直接解码显示的图片格式。 */
    val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    /** PDF 最多渲染多少页——防一份几百页的文档把内存吃光。 */
    private const val MAX_PDF_PAGES = 20

    fun build(
        act: android.app.Activity,
        f: File,
        path: String,
        onClose: () -> Unit,
        onShare: () -> Unit,
    ): View {
        val page = Ui.column(act)
        page.background = Ui.screen(act)

        // ---- 顶栏：← | 文件名 | ↗（照 ArtifactReader 的版式）
        val head = Ui.row(act)
        head.setPadding(Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 14), Ui.dp(act, 6))
        head.addView(circle(act, "←", "返回", onClose))
        head.addView(
            Ui.text(act, ArtifactReader.shortName(path), 15f, Ui.INK, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(
            circle(act, "↗", "用其他应用打开", onShare),
            LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)).apply {
                leftMargin = Ui.dp(act, 8)
            }
        )
        page.addView(head)

        // ---- 正文：滚动容器，渲染线程往里贴页
        val scroll = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = Ui.column(act)
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 30))
        val status = Ui.text(act, "正在渲染…", 12f, Ui.INK3)
        body.addView(status)
        scroll.addView(body)
        // weight=1 占满顶栏以下的全部空间（column 是竖向 LinearLayout）
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        Thread {
            val ext = path.substringAfterLast('.', "").lowercase()
            try {
                if (ext == "pdf") renderPdf(act, f, body, status)
                else if (ext in IMAGE_EXT) renderImage(act, f, body, status)
                else act.runOnUiThread { status.text = "这个格式预览不了，用 ↗ 分享给其他应用打开" }
            } catch (e: Exception) {
                act.runOnUiThread {
                    status.text = "渲染失败：${e.message ?: "文件打不开"}，可以用 ↗ 用其他应用打开"
                }
            }
        }.start()
        return page
    }

    /** PDF 逐页渲染。白底位图 + 细边框，看起来像一页纸。 */
    private fun renderPdf(act: android.app.Activity, f: File, body: LinearLayout, status: TextView) {
        val targetW = act.resources.displayMetrics.widthPixels - Ui.dp(act, 28)
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val rendered = ArrayList<Bitmap>()
        var total = 0
        try {
            val renderer = PdfRenderer(pfd)
            try {
                total = renderer.pageCount
                for (i in 0 until minOf(total, MAX_PDF_PAGES)) {
                    val pg = renderer.openPage(i)
                    try {
                        val w = targetW
                        val h = (pg.height.toLong() * w / pg.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        rendered.add(bmp)
                    } finally {
                        pg.close()
                    }
                }
            } finally {
                renderer.close()
            }
        } finally {
            pfd.close()
        }
        act.runOnUiThread {
            body.removeAllViews()
            rendered.forEachIndexed { idx, bmp ->
                body.addView(Ui.text(act, "第 ${idx + 1} / $total 页", 10.5f, Ui.INK4).apply {
                    setPadding(0, Ui.dp(act, if (idx == 0) 0 else 8), 0, Ui.dp(act, 4))
                })
                body.addView(ImageView(act).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = Ui.dp(act, 6).toFloat()
                        setStroke(1, Ui.LINE)
                    }
                    clipToOutline = true
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (total > rendered.size) {
                body.addView(Ui.text(act, "只渲染了前 $MAX_PDF_PAGES 页，全文用 ↗ 打开看", 11f, Ui.INK3)
                    .apply { setPadding(0, Ui.dp(act, 10), 0, 0) })
            }
        }
    }

    /** 图片：先量尺寸再按需降采样，防一张 8000 像素的图直接 OOM。 */
    private fun renderImage(act: android.app.Activity, f: File, body: LinearLayout, status: TextView) {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0) throw IllegalStateException("图片格式不支持")
        val maxDim = 2048
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxDim) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(
            f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("解码失败")
        act.runOnUiThread {
            body.removeAllViews()
            body.addView(ImageView(act).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 圆钮：和 ArtifactReader 顶栏的 ← / ↗ 同款（玻璃胶囊 + 居中字符）。 */
    private fun circle(act: android.app.Activity, glyph: String, desc: String, onClick: () -> Unit): View =
        Ui.text(act, glyph, 15f, Ui.INK).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            background = Ui.solid(act, Ui.GLASS, 20)
            setOnClickListener { onClick() }
        }
}
