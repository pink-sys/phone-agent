package cn.wbphone.agent

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import java.io.File

/**
 * HTML 产物的真渲染：WebView 全屏打开，跟真浏览器一模一样。
 *
 * 之前 .html 落在 ArtifactReader 的代码井里——用户精心要一个能用的网页，
 * 看到的却是一屏 <!DOCTYPE>。浏览器跳出去又不认 content://（手机浏览器把它
 * 当搜索词，2026-11-17 实测），所以渲染必须自己做。Android 自带 WebView，
 * 零依赖。
 *
 * 加载走 [ArtifactServer] 的 http://127.0.0.1 回环地址而不是 loadDataWithBaseURL：
 *  - http origin 下 localStorage、IndexedDB 都正常（CET6 词库那种「进度存本机」
 *    的页面直接可用）；file:// 与 null origin 会被 WebView 拒掉或各存一份；
 *  - 同目录的 css/js/图片按相对路径自然解析——多文件网页也能看。
 */
object HtmlPreview {

    @SuppressLint("SetJavaScriptEnabled")
    fun build(
        act: android.app.Activity,
        root: File,
        f: File,
        path: String,
        onClose: () -> Unit,
        onOpenBrowser: () -> Unit,
    ): View {
        // 服务根锁在工作台根目录：path 是相对工作台的，父目录当根会把相对路径全拧歪
        if (!ArtifactServer.start(root)) {
            // 起不来（极端情况）也不至于白屏：让上层退回文本预览
            throw IllegalStateException("本机网页服务启动失败")
        }

        val page = Ui.column(act)
        page.background = Ui.screen(act)

        // ---- 顶栏：← | 文件名 | ↗（和 ArtifactReader / BinaryPreview 同款）
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
            circle(act, "↗", "用系统浏览器打开", onOpenBrowser),
            LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)).apply {
                leftMargin = Ui.dp(act, 8)
            }
        )
        page.addView(head)

        // ---- 状态行：加载完成就隐掉，只在前几秒说明「在干嘛」
        val status = Ui.text(act, "正在加载…", 11.5f, Ui.INK3).apply {
            setPadding(Ui.dp(act, 18), Ui.dp(act, 4), Ui.dp(act, 18), Ui.dp(act, 4))
        }
        page.addView(status)

        // ---- 正文：WebView 吃满剩余空间
        val web = WebView(act)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage：网页自己存进度/词库
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                status.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?, code: Int, description: String?, failingUrl: String?
            ) {
                status.visibility = View.VISIBLE
                status.text = "加载失败：${description ?: "网络错误"}，可以用 ↗ 换系统浏览器打开"
            }
        }
        web.loadUrl(ArtifactServer.url(path))
        page.addView(
            web, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
        )
        return page
    }

    /** 圆钮：和 BinaryPreview 顶栏同款（玻璃胶囊 + 居中字符）。 */
    private fun circle(act: android.app.Activity, glyph: String, desc: String, onClick: () -> Unit): View =
        Ui.text(act, glyph, 15f, Ui.INK).apply {
            gravity = android.view.Gravity.CENTER
            contentDescription = desc
            background = Ui.solid(act, Ui.GLASS, 20)
            setOnClickListener { onClick() }
        }
}
