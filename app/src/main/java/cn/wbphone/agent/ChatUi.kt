package cn.wbphone.agent

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 对话页：消息流 + 输入栏。
 *
 * 版式照 dsh-Go 真机截图逐处对的：
 *
 * ```
 *  新会话： (☰) (新对话 ·●本机)        (✦ hy3 · 免费 ⌄)
 *  会话中： (☰) (学习通 ·●本机)        (✦ hy3 · 免费 ⌄) (✎)
 *
 *   ✓ 已完成 · 6 次工具调用                 ›      ← 过程收成一行素排
 *   答案正文直接排在页面上，不套卡片
 *   (⧉ 复制) (↗ 分享)            5 分 54 秒 · 72 步  ← 轮末操作行
 *   ┌ 📄 notes.md            预览 › ┐              ← 产物卡
 *
 *  ( (＋)  给月溯发消息…              (↑) )       ← 一整根大弧度胶囊
 * ```
 *
 * 顶栏照 dsh-Go：模型 chip 永远贴最右；会话中它右边再挂一枚 ✎ 新会话，
 * 新会话（空状态）时收起。会话胶囊里显示的是**当前会话标题**，不是写死的 App 名。
 *
 * 质感的三条来源，都是跟 dsh-Go 学的：
 * 1. **半透明玻璃面**。胶囊和 chip 不用实心白，用 78%–90% 的白铺在页面渐变上，
 *    底色透出来，整屏是一块玻璃而不是一堆贴片。实心白只留给弹层（会话面板）。
 * 2. **大弧度**。输入条是整根胶囊（28dp 半径），顶栏按钮是 14dp 圆角方块，
 *    没有直角矩形。
 * 3. **素排的过程行**。完成的轮次不装卡，一行灰字「✓ 已完成 · N 次工具调用」，
 *    点开才出现工具 chip——过程是附属信息，不配占视觉重量。
 */
class ChatUi(
    private val act: Activity,
    /** 发送：显示文本 + 可选的图片 data URL 列表（多选）+ 可选的文件正文（带文件的完整 payload） */
    private val onSend: (String, List<String>?, String?) -> Unit,
    private val onStop: () -> Unit,
    private val onNewChat: () -> Unit,
    private val onSettings: () -> Unit,
    private val onReconnect: () -> Unit,
    private val onPickModel: () -> Unit,
    /** 点产物卡片时回调，参数是工作台内的相对路径 */
    private val onOpenArtifact: (String) -> Unit = {},
    /** 点会话按钮 */
    private val onSessions: () -> Unit = {},
    /** 空状态页的「工作台」胶囊 */
    private val onWorkspace: () -> Unit = {},
    /** 附件底栏里的「拍照」 */
    private val onCamera: () -> Unit = {},
    /** 附件底栏里的「相册」 */
    private val onGallery: () -> Unit = {},
    /** 附件底栏里的「文件」：系统文件选择器（md/docx/pptx/pdf…） */
    private val onFile: () -> Unit = {},
    /** 点麦克风：开始或取消语音输入（要不要权限、当前是不是在听，由 MainActivity 判） */
    private val onVoice: () -> Unit = {},
    /** 轮末「朗读」：把这一轮的回答念出来 */
    private val onSpeak: (String) -> Unit = {},
    /** 停掉正在念的 */
    private val onStopSpeak: () -> Unit = {},
    /** 长按菜单里的「从这里分叉」：参数是这条回答在会话历史里的下标 + 原文 */
    private val onFork: (Int, String) -> Unit = { _, _ -> },
) {

    val root: View

    private lateinit var feed: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var offlineBar: LinearLayout
    private lateinit var runningBar: LinearLayout
    private lateinit var runningText: TextView
    private lateinit var modelChip: TextView
    private lateinit var stateText: TextView
    private lateinit var stateDot: View
    private lateinit var footer: TextView
    private lateinit var sessionName: TextView

    /** 会话中的 ✎ 新会话钮：空状态收起，发第一句话后出现（照 dsh-Go 的顶栏）。 */
    private lateinit var newChatChip: TextView

    /** 附件底栏（＋ 展开）：拍照 / 相册 / 文件。 */
    private lateinit var attachPanel: LinearLayout
    private lateinit var plusButton: TextView

    /** 输入条里的麦克风：点一下开始听，再点一下结束。 */
    private lateinit var micButton: TextView

    /** 轮末的朗读按钮 + 是否在念（文案在「朗读/停止」之间切）。 */
    private var speakChip: TextView? = null
    private var speaking = false

    /** 待发附件：缩略图（图片，可多张）或类型徽标（文件）+ 文件名 + ✕，发送或取消后收起。 */
    private lateinit var pendingRow: LinearLayout
    private lateinit var pendingThumb: android.widget.ImageView
    private lateinit var pendingThumbBox: android.widget.FrameLayout
    private lateinit var pendingCount: TextView
    private lateinit var pendingBadge: TextView
    private lateinit var pendingName: TextView
    private val pendingImages = ArrayList<AttachPicker.Picked>()
    private var pendingFileText: String? = null
    private var pendingFileName: String? = null

    /** 当前正在流式输出的助手正文；换新一轮就置空。 */
    private var openBubble: TextView? = null

    /** 最后一轮说完整的答案，给轮末「复制/分享」用。 */
    private var lastAnswer: String? = null

    /** 空状态（问候 + 建议），发第一句话后移除。 */
    private var emptyView: View? = null

    /** 空状态页底部的「当前模型 xx」一行。切模型时同步刷新，别显示一个写死的名字。 */
    private var modelHint: TextView? = null

    /** 本轮的工具折叠组。跑的时候展开，结束时收起成一行素排。 */
    private var process: ProcessGroup? = null

    private val toolStack = ArrayList<ToolCard>()

    /** 产物卡片：路径 → 卡片。同一文件再写就原地更新，不刷一屏重复卡。 */
    private val artifactCards = LinkedHashMap<String, LinearLayout>()

    init {
        root = build()
    }

    private fun build(): View {
        val col = Ui.column(act)
        col.background = Ui.screen(act)

        // ---------------------------------------------------------- 顶栏：一排悬浮胶囊
        val bar = Ui.row(act)
        bar.setPadding(dp(10), dp(10), dp(10), dp(8))

        bar.addView(squareChip("☰", "历史会话") { onSessions() })

        // 会话胶囊：当前会话的标题 + 状态点（dsh-Go 的放法，不写死 App 名）。
        // 宽度用权重限定在 ☰ 和模型 chip 之间：标题长了在这里截断，
        // 模型 chip 永远贴最右——之前标题一长就把模型挤下屏幕（2026-11-03 修）。
        // 高度统一 40dp：顶栏四个元素（☰ / 会话 / 模型 / ✎）一样高，不再参差。
        val session = Ui.column(act)
        session.gravity = Gravity.CENTER_VERTICAL
        session.setPadding(dp(13), 0, dp(13), 0)
        session.background = Ui.solid(act, Ui.GLASS, 20)
        sessionName = Ui.text(act, "新对话", 12.5f, Ui.INK, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        session.addView(sessionName)
        val sub = Ui.row(act)
        sub.setPadding(0, dp(2), 0, 0)
        stateDot = View(act).apply { background = Ui.circle(act, Ui.OK, 3) }
        sub.addView(stateDot, LinearLayout.LayoutParams(dp(3), dp(3)).apply { rightMargin = dp(4) })
        stateText = Ui.text(act, "已连接", 9.5f, Ui.INK3)
        sub.addView(stateText)
        session.addView(sub)
        bar.addView(session, LinearLayout.LayoutParams(
            0, dp(40), 1f
        ).apply { leftMargin = dp(6) })

        // 模型胶囊：「✦ 名字 · 倍率 ⌄」，是按钮不是状态文字；高度同样钉在 40dp
        modelChip = Ui.text(act, "", 12f, Ui.INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = Ui.solid(act, Ui.GLASS, 20)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 模型名可能很长，不截断会把顶栏顶破
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.42f).toInt()
            setOnClickListener { onPickModel() }
        }
        bar.addView(modelChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
        ).apply { leftMargin = dp(6) })

        // 会话中的 ✎ 新会话钮：空状态收起，发第一句话后出现（照 dsh-Go 顶栏）
        newChatChip = squareChip("✎", "新会话") { onNewChat() }
        newChatChip.visibility = View.GONE
        bar.addView(newChatChip, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
        col.addView(bar, Ui.margin(act))

        // ---------------------------------------------------------- 断线提示
        offlineBar = Ui.row(act)
        offlineBar.setPadding(dp(14), dp(9), dp(14), dp(9))
        offlineBar.background = Ui.solid(act, Ui.WARN_BG, 14, Ui.WARN_LINE, 1)
        offlineBar.visibility = View.GONE
        offlineBar.addView(Ui.text(act, "!", 11f, Ui.WARN, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(8) })
        offlineBar.addView(
            Ui.text(act, "无障碍已断开，暂时不能操作手机", 12.5f, 0xFF7A5620.toInt()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        offlineBar.addView(Ui.text(act, "重新开启", 12.5f, Ui.WARN, true).apply {
            setPadding(dp(10), dp(2), dp(2), dp(2))
            setOnClickListener { onReconnect() }
        })
        col.addView(offlineBar, Ui.margin(act, left = 14, right = 14))

        // ---------------------------------------------------------- 消息流
        feed = Ui.column(act)
        feed.setPadding(dp(16), dp(8), dp(16), dp(10))
        scroll = ScrollView(act).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(feed, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------------------------------------------------------- 输入区
        val composer = Ui.column(act)
        composer.setPadding(dp(12), dp(6), dp(12), dp(16))

        runningBar = Ui.row(act)
        runningBar.setPadding(dp(13), dp(8), dp(7), dp(8))
        runningBar.background = Ui.solid(act, Ui.GLASS, 17)
        runningBar.visibility = View.GONE
        runningBar.addView(View(act).apply { background = Ui.circle(act, Ui.BRAND, 6) },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(9) })
        runningText = Ui.text(act, "正在读屏…", 12.5f, Ui.INK2)
        runningBar.addView(runningText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        runningBar.addView(Ui.text(act, "停止", 12.5f, Ui.INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(5), dp(13), dp(5))
            background = Ui.solid(act, Ui.GLASS_SOFT, 11)
            setOnClickListener { onStop() }
        })
        composer.addView(runningBar, Ui.margin(act, bottom = 8))

        // 待发附件：输入条上方一条小卡（缩略图/类型徽标 + 文件名 + ✕），没选时整行收起
        pendingRow = Ui.row(act)
        pendingRow.visibility = View.GONE
        pendingRow.setPadding(dp(8), dp(8), dp(8), dp(8))
        pendingRow.background = Ui.solid(act, Ui.GLASS, 18, Ui.GLASS_LINE, 1)
        // 缩略图装进一个圆角框：图片垫底，右上角一枚「×N」角标标张数（多选时出现）
        pendingThumbBox = android.widget.FrameLayout(act).apply {
            background = Ui.solid(act, Ui.GLASS_SOFT, 10)
            clipToOutline = true
        }
        pendingThumb = android.widget.ImageView(act).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        pendingThumbBox.addView(pendingThumb, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pendingCount = Ui.text(act, "", 9f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = Ui.circle(act, Ui.BRAND, 9)
        }
        pendingThumbBox.addView(pendingCount, android.widget.FrameLayout.LayoutParams(
            dp(26), dp(15), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
        pendingRow.addView(pendingThumbBox, LinearLayout.LayoutParams(dp(42), dp(42)))
        // 文件附件不产缩略图，用一枚类型徽标占同一格（MD/PDF/DOCX…）
        pendingBadge = Ui.text(act, "", 9.5f, Ui.BRAND, true).apply {
            gravity = Gravity.CENTER
            background = Ui.solid(act, Ui.BRAND_BG, 10)
        }
        pendingRow.addView(pendingBadge, LinearLayout.LayoutParams(dp(42), dp(42)))
        pendingName = Ui.text(act, "", 12.5f, Ui.INK2).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(10), 0, dp(8), 0)
        }
        pendingRow.addView(pendingName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // ✕ 视觉只有 30dp（贴着缩略图的尺寸），但触控目标垫到 44dp：
        // 外圈透明区域也吃点击，指尖不用精确对准那个小圈。
        pendingRow.addView(android.widget.FrameLayout(act).apply {
            contentDescription = "取消这个附件"
            setOnClickListener { clearPendingImage() }
            addView(Ui.text(act, "✕", 13f, Ui.INK3).apply {
                gravity = Gravity.CENTER
                background = Ui.solid(act, Ui.GLASS_SOFT, 14)
                isClickable = false
            }, android.widget.FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        composer.addView(pendingRow, Ui.margin(act, bottom = 8))

        // 整根胶囊：＋ ｜ 输入 ｜ 发送。28dp 半径 ≈ 高度的一半，两端全圆。
        val inputRow = Ui.row(act)
        inputRow.setPadding(dp(6), dp(6), dp(6), dp(6))
        inputRow.background = Ui.solid(act, Ui.GLASS, 28, Ui.GLASS_LINE, 1)

        // ＋：展开附件底栏（拍照 / 相册 / 文件）。展开时变成 ✕，再点收起。
        // 字号故意放大：44dp 的格子里 16f 的「＋」小得像标点，撑不满自己的格子。
        plusButton = Ui.text(act, "＋", 22f, Ui.INK).apply {
            gravity = Gravity.CENTER
            contentDescription = "添加附件"
            background = Ui.solid(act, Ui.GLASS_SOFT, 19)
            setOnClickListener { toggleAttach() }
        }
        val plus = plusButton
        inputRow.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(4) })

        input = EditText(act).apply {
            hint = "给月溯发消息…"
            textSize = 14.5f
            setTextColor(Ui.INK)
            setHintTextColor(Ui.INK4)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = null
            setPadding(dp(6), dp(11), 0, dp(11))
            minLines = 1
            maxLines = 4
            includeFontPadding = false
            // 开始打字就收起附件栏，别让底栏挡住键盘上方的空间
            setOnFocusChangeListener { _, has -> if (has) hideAttach() }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 麦克风：语音输入。放在输入框和发送键之间，手指够得到、不挤输入区。
        micButton = Ui.text(act, "🎤", 15f, Ui.INK2).apply {
            gravity = Gravity.CENTER
            contentDescription = "语音输入"
            background = Ui.solid(act, Ui.GLASS_SOFT, 19)
            setOnClickListener { onVoice() }
        }
        inputRow.addView(micButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(4) })

        sendButton = Ui.text(act, "↑", 17f, Color.WHITE, true).apply {
            contentDescription = "发送"
            gravity = Gravity.CENTER
            background = Ui.circle(act, Ui.BRAND, 19)
            setOnClickListener { submit() }
        }
        inputRow.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        composer.addView(inputRow, Ui.margin(act))

        // 附件底栏：点 ＋ 才出现，整行铺在输入条下面（像微信的 ＋ 面板）。
        // 出现时输入区变高，消息区（weight=1）自然往上收——这就是「对话框上移」。
        attachPanel = Ui.row(act)
        attachPanel.visibility = View.GONE
        attachPanel.setPadding(0, dp(8), 0, 0)
        fun attachChip(label: String, onClick: () -> Unit): View =
            Ui.text(act, label, 12.5f, Ui.INK2, true).apply {
                gravity = Gravity.CENTER
                background = Ui.solid(act, Ui.GLASS, 14, Ui.GLASS_LINE, 1)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener {
                    hideAttach()
                    onClick()
                }
            }
        attachPanel.addView(attachChip("拍照", onCamera),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        attachPanel.addView(attachChip("相册", onGallery),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        attachPanel.addView(attachChip("文件", onFile),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(attachPanel)

        // 平时是操作提示，跑完一轮换成「这一轮到底是谁答的」——
        // 取的是服务端回传的 model，不是我们请求时填的那个。
        footer = Ui.text(act, HINT, 10.5f, Ui.INK3).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        composer.addView(footer)
        col.addView(composer, Ui.margin(act))

        // 建完视图立刻按当前选中值填一次，避免闪一下空胶囊。
        setModel()

        return col
    }

    /** 顶栏的圆角方块小按钮（☰ / ✎），玻璃底 + 轻阴影悬浮。 */
    private fun squareChip(label: String, desc: String, onClick: () -> Unit): TextView =
        Ui.text(act, label, 16f, Ui.INK).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            background = Ui.solid(act, Ui.GLASS, 14)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }

    /** ＋ / ✕：展开或收起附件底栏。 */
    private fun toggleAttach() {
        if (attachPanel.visibility == View.VISIBLE) hideAttach() else showAttach()
    }

    private fun showAttach() {
        attachPanel.visibility = View.VISIBLE
        plusButton.text = "✕"
        // 底栏出现时把提示行让出来，不然两行小字挤在一起
        footer.visibility = View.GONE
    }

    private fun hideAttach() {
        attachPanel.visibility = View.GONE
        plusButton.text = "＋"
        footer.visibility = View.VISIBLE
    }

    /** 选好图（可多张）：第一张当封面，多选时右上角标「×N」，等用户补一句话再发。 */
    fun setPendingImages(imgs: List<AttachPicker.Picked>) {
        pendingImages.clear()
        pendingImages.addAll(imgs)
        pendingFileText = null
        pendingFileName = null
        pendingThumb.setImageBitmap(imgs.first().thumb)
        pendingThumbBox.visibility = View.VISIBLE
        pendingBadge.visibility = View.GONE
        pendingCount.visibility = if (imgs.size > 1) View.VISIBLE else View.GONE
        pendingCount.text = "×${imgs.size}"
        pendingName.text = if (imgs.size == 1) imgs[0].name
            else "${imgs.size} 张图片：${imgs.joinToString("、") { it.name }}"
        pendingRow.visibility = View.VISIBLE
        hideAttach()
        scrollDown()
    }

    /** 选好文件：不产缩略图，用类型徽标占位；内容已抽好挂在内存里，发送时随 payload 一起走。 */
    fun setPendingFile(name: String, extractedText: String) {
        pendingFileText = extractedText
        pendingFileName = name
        pendingImages.clear()
        pendingThumb.setImageDrawable(null)
        pendingThumbBox.visibility = View.GONE
        pendingBadge.visibility = View.VISIBLE
        pendingBadge.text = name.substringAfterLast('.', "文件").take(5).uppercase()
        pendingName.text = name
        pendingRow.visibility = View.VISIBLE
        hideAttach()
        scrollDown()
    }

    /** 发送成功或用户点 ✕：收起待发附件（图片和文件共用这一条路）。 */
    fun clearPendingImage() {
        pendingImages.clear()
        pendingFileText = null
        pendingFileName = null
        pendingRow.visibility = View.GONE
        pendingThumb.setImageDrawable(null)
        pendingCount.visibility = View.GONE
    }

    fun hasPendingImage(): Boolean = pendingImages.isNotEmpty() || pendingFileText != null

    private fun submit() {
        val text = input.text.toString().trim()
        val images = pendingImages.map { it.dataUrl }
        val fileText = pendingFileText
        val fileName = pendingFileName
        if (text.isEmpty() && images.isEmpty() && fileText == null) return
        input.setText("")
        hideAttach()
        // 只发附件不打字也允许：给气泡一句占位说明，界面上就没别的字可显示了
        val display = text.ifEmpty {
            when {
                fileText != null -> "（我发了一个文件：$fileName）"
                images.size > 1 -> "（我发了 ${images.size} 张图片）"
                else -> "（我发了一张图片）"
            }
        }
        // 文件正文只进模型的历史，不进气泡——几万字贴在对话里没法看。
        // 用分隔标记包住，恢复历史时按标记裁掉正文、留一句「含文件 xxx」。
        val payload = if (fileText != null && fileName != null) {
            "$display\n\n---\n【用户附上文件：$fileName】\n$fileText"
        } else display
        val hasFile = fileText != null
        if (images.isNotEmpty() || hasFile) clearPendingImage()
        onSend(display, images.ifEmpty { null }, payload.takeIf { hasFile })
    }

    // ------------------------------------------------------------ 语音

    /** 麦克风状态：在听时按钮变实心、提示文案换掉，用户一眼知道它在听。 */
    fun setListening(on: Boolean) {
        micButton.text = if (on) "■" else "🎤"
        micButton.setTextColor(if (on) Color.WHITE else Ui.INK2)
        micButton.background = if (on) Ui.circle(act, Ui.BRAND, 19) else Ui.solid(act, Ui.GLASS_SOFT, 19)
        input.hint = if (on) "正在听…说完点 ■" else "给月溯发消息…"
    }

    /** 识别结果填进输入框（中间结果整段替换），不自动发送——改一个字再发。 */
    fun setVoiceText(text: String) {
        input.setText(text)
        input.setSelection(input.text.length)
    }

    /**
     * 把光标放进输入框并弹出键盘。设备没有识别服务时用它引导用户改用
     * 输入法自带的话筒——那套识别跟着输入法走，结果同样落在输入框里。
     */
    fun focusInput() {
        input.requestFocus()
        input.post {
            val imm = act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 讲完/停掉时复位朗读按钮。 */
    fun setSpeaking(on: Boolean) {
        speaking = on
        speakChip?.text = if (on) "⏹ 停止" else "🔊 朗读"
    }

    /** 这一轮的答案原文，给自动朗读用。 */
    fun lastAnswerText(): String? = lastAnswer

    private fun toggleSpeak(ans: String) {
        if (speaking) {
            onStopSpeak()
            setSpeaking(false)
        } else {
            // 先亮成「停止」再启动：TTS 引擎初始化要几百毫秒，不先给反馈就像没点上
            setSpeaking(true)
            onSpeak(ans)
        }
    }

    // ------------------------------------------------------------ 空状态

    /**
     * 新会话的第一屏：一句问候 + 四条起点。
     *
     * 空白页最浪费——用户不知道这个 App 能干什么，只能自己猜。
     * 建议不是「示例文案」，点一下直接填进输入框，改两个字就能发。
     */
    fun welcome(model: String = AgentHost.modelLabel()) {
        clear()
        emptyView = buildEmpty(model)
        feed.addView(emptyView, Ui.margin(act))
        // 新会话：✎ 收起（右上角没有新会话钮），胶囊标题回到「新对话」
        newChatChip.visibility = View.GONE
        sessionName.text = "新对话"
        scrollDown()
    }

    /** 会话胶囊的标题：进会话/发完一轮后由 [MainActivity] 回填当前会话名。 */
    fun setSessionTitle(name: String) {
        sessionName.text = if (name.isBlank()) "新对话" else name
    }

    private fun buildEmpty(model: String): View {
        val col = Ui.column(act)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(0, dp(28), 0, dp(8))

        // 一尾很淡的鲸尾替代大图标：形状记忆点足够，又不抢字。
        col.addView(Ui.text(act, "⌒)", 26f, Ui.BRAND).apply { alpha = 0.35f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) })

        col.addView(Ui.text(act, greeting(), 22f, Ui.INK, true))
        col.addView(Ui.text(act, "今天想让手机替你做什么？", 13f, Ui.INK3).apply {
            setPadding(0, dp(7), 0, 0)
        })

        // 工作台胶囊：新会话默认落在应用私有工作台，点开能看目录里有什么。
        val chip = Ui.text(act, "工作台 · 应用私有目录  ›", 12f, Ui.INK2, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Ui.solid(act, Ui.GLASS, 20)
            setOnClickListener { onWorkspace() }
        }
        col.addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        // 四条建议竖着排满宽（照 dsh-Go 的空状态：一根一根全宽胶囊）
        // 只留文字——前面的小图标是装饰，信息量为零，去掉更干净（用户要求）。
        val list = Ui.column(act)
        list.setPadding(0, dp(24), 0, 0)
        val items = listOf(
            "看看现在的屏幕" to "看看我现在的屏幕，告诉我这是什么界面、有哪些能点的",
            "打开设置看版本" to "打开设置，看看 Android 版本是多少",
            "写个待办清单" to "在工作台写一个 todo.md，列出三条今天要做的事",
            "整理成一份笔记" to "把刚才聊的内容整理成一份 Markdown 笔记存到工作台",
        )
        for ((label, prompt) in items) {
            val cell = Ui.row(act)
            cell.gravity = Gravity.CENTER
            cell.setPadding(dp(10), dp(13), dp(10), dp(13))
            cell.background = Ui.solid(act, Ui.GLASS_SOFT, 22, Ui.GLASS_LINE, 1)
            cell.addView(Ui.text(act, label, 13.5f, Ui.INK2))
            cell.setOnClickListener { fill(prompt) }
            list.addView(cell, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) })
        }
        col.addView(list, Ui.margin(act))
        modelHint = Ui.text(act, "当前模型 $model · 点顶部模型名可切换", 11f, Ui.INK4).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        col.addView(modelHint)
        return col
    }

    private fun greeting(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h in 5..10 -> "早上好"
            h in 11..12 -> "中午好"
            h in 13..17 -> "下午好"
            h in 18..22 -> "晚上好"
            else -> "夜深了"
        }
    }

    private fun fill(text: String) {
        input.setText(text)
        input.setSelection(text.length)
        input.requestFocus()
    }

    fun clear() {
        feed.removeAllViews()
        openBubble = null
        openRaw = null
        openRef = null
        lastAnswer = null
        emptyView = null
        modelHint = null
        process = null
        toolStack.clear()
        artifactCards.clear()
    }

    fun isEmpty(): Boolean = emptyView != null || feed.childCount == 0

    private fun dropEmpty() {
        val v = emptyView ?: return
        feed.removeView(v)
        emptyView = null
        modelHint = null
    }

    // ------------------------------------------------------------ 消息

    fun addUser(text: String) {
        dropEmpty()
        finishProcess()
        openBubble = null
        // 进入会话态：顶栏右侧出现 ✎ 新会话（dsh-Go 的放法）
        newChatChip.visibility = View.VISIBLE

        val wrap = Ui.column(act)
        // 带图的消息：气泡上方先放缩略图（图片本身在历史里，气泡只做视觉提示）。
        // 单张放大图；多选时一排小方块，挤不下的说明本来就贴在气泡文字里。
        if (pendingImages.isNotEmpty()) {
            if (pendingImages.size == 1) {
                wrap.addView(android.widget.ImageView(act).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(pendingImages[0].thumb)
                    background = Ui.solid(act, Ui.GLASS_SOFT, 14)
                    clipToOutline = true
                }, LinearLayout.LayoutParams(dp(160), dp(120)).apply { gravity = Gravity.END })
            } else {
                val strip = Ui.row(act).apply { gravity = Gravity.END }
                for (img in pendingImages) {
                    strip.addView(android.widget.ImageView(act).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(img.thumb)
                        background = Ui.solid(act, Ui.GLASS_SOFT, 10)
                        clipToOutline = true
                    }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(4) })
                }
                wrap.addView(strip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.END })
            }
        }
        val bubble = Ui.text(act, text, 14.5f, Ui.INK).apply {
            setPadding(dp(14), dp(10), dp(14), dp(11))
            setLineSpacing(dp(4).toFloat(), 1f)
            // 右下角收一个角，指向「我」这一侧
            background = Ui.solidRadii(act, Ui.BUBBLE, intArrayOf(dp(18), dp(18), dp(5), dp(18)))
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        wrap.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END })
        feed.addView(wrap, Ui.margin(act, top = 8, bottom = 4))
        scrollDown()
    }

    /**
     * 注记行：不是用户说的话、也不是模型答的内容（压缩摘要这类系统消息）。
     * 居中一行小灰字，视觉上明确「这是系统自己写的」，不会和真正的对话混在一起。
     * 之前压缩摘要是原样 addUser 画成用户气泡——用户看到一整块「目标：[早期步骤摘要…]」
     * 蓝泡，以为是自己发出去的消息，看着像界面出了 bug。
     */
    fun addNote(text: String) {
        dropEmpty()
        finishProcess()
        openBubble = null
        val note = Ui.text(act, text, 12f, Ui.INK3).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.86f).toInt()
        }
        feed.addView(note, Ui.margin(act, top = 10, bottom = 6).apply { gravity = Gravity.CENTER })
        scrollDown()
    }

    /**
     * 助手正文：直接排在页面上，不套卡片。
     *
     * 之前每一句都装进白色圆角卡，一屏下来卡片套卡片，正文反而被压成小块；
     * 现在只有「工具」「产物」「状态」这些真需要边界的东西才配一张卡。
     *
     * 正文按 Markdown 着色（Marked 解析 + 下面的 Span 渲染）：
     * 标题放大加粗、列表挂缩进、**粗体** 加粗、`代码` 变等宽。
     * 原文挂在 tag 上——复制、分享、引用、长按菜单用的都是它。
     */
    private var openRaw: StringBuilder? = null

    /**
     * 一条回答在界面上的身份：原文（复制/分享/引用用）+ 它在会话历史里的下标（分叉用）。
     * 下标 -1 表示还不知道（正在流式的那一轮），跑完由 bindAnswerIndex 补上。
     */
    private class AnswerRef(val raw: StringBuilder, var index: Int)

    private var openRef: AnswerRef? = null

    fun addAssistant(text: String = "", historyIndex: Int = -1) {
        dropEmpty()
        finishProcess()
        val body = Ui.text(act, "", 14.5f, Ui.INK).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        val ref = AnswerRef(StringBuilder(text), historyIndex)
        openRef = ref
        openRaw = ref.raw
        if (text.isNotEmpty()) renderMarked(body, text)
        body.tag = ref
        body.setOnLongClickListener { showAnswerMenu(body); true }
        feed.addView(body, Ui.margin(act, top = 2, bottom = 2))
        openBubble = body
        scrollDown()
    }

    fun appendAssistant(delta: String) {
        val body = openBubble ?: run {
            addAssistant(delta)
            return
        }
        val ref = openRef
        val raw = ref?.raw ?: StringBuilder().also { openRaw = it }
        raw.append(delta)
        // 流式期间整段重渲染：正文撑死了几 KB，解析是微秒级的事，
        // 换来的是星号一对上就变粗体，不用等这轮说完。
        renderMarked(body, raw.toString())
        scrollDown()
    }

    /**
     * 这一轮说完后由外部告诉界面：这条回答是会话历史的第几条。
     * 分叉要按它切历史——界面自己猜不出来，也不能靠正文内容去比对（会有重复）。
     */
    fun bindAnswerIndex(index: Int) {
        val ref = openBubble?.tag as? AnswerRef ?: return
        if (index >= 0) ref.index = index
    }

    fun closeAssistant() {
        openRaw?.toString()?.takeIf { it.isNotBlank() }?.let { lastAnswer = it }
        openBubble = null
        openRaw = null
    }

    // ------------------------------------------------------------ 长按回答（照 dsh-Go）

    /**
     * 长按回答正文：底部升起操作面板，五项竖着排——
     * 复制 / 选择文字 / 引用 / 分享 / 从这里分叉。
     *
     * 形态照模型选择器那套：浅色大面板（顶部两角 22dp 圆角）、标题行 + 关闭、
     * 下面一行一个独立的圆弧气泡（20dp 圆角、细描边、行距舒展），
     * 每枚气泡里左边是动作名、右边是灰色小字说明。
     *
     * 为什么不直接把正文 TextView 设成可选中：流式期间整段重渲染会把选区打断，
     * 长按手势也一直空着，正好给菜单。「选择文字」单开一个静态弹层，那里随便拖选。
     */
    private fun showAnswerMenu(tv: TextView) {
        val ref = tv.tag as? AnswerRef ?: return
        val raw = ref.raw.toString()
        if (raw.isBlank()) return
        val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val scrim = Ui.column(act)
        scrim.setBackgroundColor(0x660B111F)
        scrim.gravity = Gravity.BOTTOM

        val card = Ui.column(act)
        card.background = Ui.solidRadii(act, Ui.CARD, intArrayOf(dp(22), dp(22), 0, 0))
        card.setPadding(dp(16), dp(14), dp(16), dp(24))

        // ---- 标题行：操作 + 关闭
        val head = Ui.row(act)
        head.addView(Ui.text(act, "回答操作", 16f, Ui.INK, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(act, "关闭", 13f, Ui.INK3).apply {
            setPadding(dp(10), dp(6), dp(2), dp(6))
            minimumHeight = dp(36)
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(head)
        card.addView(Ui.text(act, "长按这段回答弹出的操作，只对这一轮生效。", 11.5f, Ui.INK3)
            .apply { setPadding(0, dp(4), 0, dp(12)) })

        // ---- 五枚圆弧气泡，竖着排，间距舒展
        val items = listOf(
            Triple("复制", "整段回答进剪贴板") { copyAnswer(raw) },
            Triple("选择文字", "长按拖选一段，只复制那一段") { showSelectableText(raw) },
            Triple("引用", "填进输入框，接着问这段的事") { quoteAnswer(raw) },
            Triple("分享", "交给微信、备忘录这些应用") { shareAnswer(raw) },
            Triple("从这里分叉", "以这条回答为界，复制进一个新对话") { onFork(ref.index, raw) },
        )
        items.forEachIndexed { i, (label, sub, action) ->
            card.addView(menuBubble(label, sub) {
                dialog.dismiss()
                action()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) topMargin = dp(10) })
        }

        scrim.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scrim.setOnClickListener { dialog.dismiss() }
        card.setOnClickListener { }

        dialog.setContentView(scrim)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }

    /**
     * 一枚圆弧气泡：20dp 大圆角、玻璃底 + 细描边，里面动作名在上、灰色说明在下
     * （和模型选择器的行一个结构），高度不低于 52dp，行距舒展显圆润。
     */
    private fun menuBubble(label: String, sub: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.column(act)
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.minimumHeight = dp(52)
        row.background = Ui.solid(act, Ui.GLASS, 20, Ui.GLASS_LINE, 1)
        row.addView(Ui.text(act, label, 14.5f, Ui.INK, true))
        row.addView(Ui.text(act, sub, 11.5f, Ui.INK3).apply {
            setPadding(0, dp(3), 0, 0)
        })
        row.setOnClickListener { onClick() }
        return row
    }

    private fun copyAnswer(raw: String) {
        val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("月溯", raw))
        android.widget.Toast.makeText(act, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shareAnswer(raw: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, raw)
        }
        act.startActivity(Intent.createChooser(intent, "分享这段回答"))
    }

    /** 引用：原文压成 > 引用块填进输入框，光标落在后面；太长就截断，别把输入框撑成一篇文章。 */
    private fun quoteAnswer(raw: String) {
        var body = raw
        if (body.length > 1200) {
            body = body.take(1200) + "…（引用太长，截断了）"
        }
        val quoted = body.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
        fill("$quoted\n\n")
    }

    /** 「选择文字」：静态弹层里给一份渲染好的正文，长按拖选，系统工具条负责复制。 */
    private fun showSelectableText(raw: String) {
        val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val page = Ui.column(act)
        page.background = Ui.solidRadii(act, Ui.CARD, intArrayOf(dp(20), dp(20), dp(20), dp(20)))
        page.setPadding(dp(16), dp(14), dp(16), dp(10))

        page.addView(Ui.text(act, "长按文字拖出选区，点系统的「复制」", 12f, Ui.INK3)
            .apply { setPadding(dp(2), 0, dp(2), dp(8)) })

        val scroll = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val body = Ui.text(act, "", 14.5f, Ui.INK).apply {
            setLineSpacing(dp(5).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        renderMarked(body, raw)
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        page.addView(Ui.textButton(act, "完成", Ui.BRAND, 14f) { dialog.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })

        // 外面再垫一层留边距：卡是浮在页面中间的，不是贴边的底板
        val holder = android.widget.FrameLayout(act)
        holder.addView(page, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(14), dp(28), dp(14), dp(28)) })

        dialog.setContentView(holder)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.38f)
        }
        dialog.show()
    }

    // ------------------------------------------------------------ Markdown 渲染

    /**
     * Marked 解析结果 → Span。字号用相对倍数（挂在 14.5f 的正文上），
     * 以后调正文字号，标题层级跟着走。
     */
    private fun renderMarked(tv: TextView, src: String) {
        val sb = SpannableStringBuilder()
        for (b in Marked.parse(src)) {
            if (sb.isNotEmpty()) sb.append(if (b.gap > 0) "\n\n" else "\n")
            when (b.kind) {
                "h" -> {
                    val start = sb.length
                    appendSegs(sb, b.lines[0])
                    sb.setSpan(RelativeSizeSpan(
                        if (b.level <= 1) 1.24f else if (b.level == 2) 1.14f else 1.07f),
                        start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "ul", "ol" -> for ((i, segs) in b.lines.withIndex()) {
                    if (i > 0) sb.append("\n")
                    val start = sb.length
                    if (b.kind == "ul") sb.append("•  ")
                    appendSegs(sb, segs)
                    // 悬挂缩进：折行后对齐文字而不是对齐圆点
                    sb.setSpan(LeadingMarginSpan.Standard(0, dp(15)), start, sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "quote" -> for ((i, segs) in b.lines.withIndex()) {
                    if (i > 0) sb.append("\n")
                    val start = sb.length
                    sb.append("▎ ")
                    appendSegs(sb, segs, Ui.INK2)
                    sb.setSpan(ForegroundColorSpan(Ui.INK2), start, sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "code" -> {
                    val start = sb.length
                    // 每行代码一条：行间补换行，缩进和空行都原样活着
                    for ((i, segs) in b.lines.withIndex()) {
                        if (i > 0) sb.append("\n")
                        appendSegs(sb, segs, Ui.INK)
                    }
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(Ui.SOFT), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.92f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // 整块左缩进一点，跟正文分开；折行后也对齐缩进线
                    sb.setSpan(LeadingMarginSpan.Standard(dp(8), dp(8)), start, sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "hr" -> {
                    val start = sb.length
                    sb.append("──────────")
                    sb.setSpan(ForegroundColorSpan(Ui.INK4), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> for ((i, segs) in b.lines.withIndex()) {
                    if (i > 0) sb.append("\n")
                    appendSegs(sb, segs)
                }
            }
        }
        tv.text = sb
    }

    /** 一行里的段：粗体、行内代码各自上 span；baseColor 供引用行整体降调。 */
    private fun appendSegs(sb: SpannableStringBuilder, segs: List<Marked.Seg>, baseColor: Int = Ui.INK) {
        for (seg in segs) {
            val start = sb.length
            sb.append(seg.text)
            if (baseColor != Ui.INK) {
                sb.setSpan(ForegroundColorSpan(baseColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (seg.bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (seg.code) {
                sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(Ui.SOFT), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // ------------------------------------------------------------ 过程折叠

    /**
     * 一整轮的工具调用收成的一行。素排：完成态是灰字一行（dsh-Go 的
     * 「✓ 已完成 · 71 次工具调用」），跑的时候换主色点 + 步数，展开才出 chip。
     */
    inner class ProcessGroup(
        val view: LinearLayout,
        val head: LinearLayout,
        val label: TextView,
        val meta: TextView,
        val chevron: TextView,
        val dot: View,
        val body: LinearLayout,
    ) {
        var steps = 0
        var expanded = true
    }

    private fun ensureProcess(): ProcessGroup {
        process?.let { return it }

        val card = Ui.column(act)

        val head = Ui.row(act)
        head.setPadding(dp(2), dp(10), dp(2), dp(10))
        val dot = View(act).apply { background = Ui.circle(act, Ui.BRAND, 6) }
        head.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(8) })

        val label = Ui.text(act, "正在干活", 12.5f, Ui.INK2, true)
        head.addView(label)

        val meta = Ui.text(act, "", 12f, Ui.INK3).apply { setPadding(dp(6), 0, 0, 0) }
        head.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val chevron = Ui.text(act, "⌄", 13f, Ui.INK4)
        head.addView(chevron)

        val body = Ui.column(act)
        body.setPadding(dp(2), 0, 0, dp(4))

        card.addView(head)
        card.addView(body)

        val group = ProcessGroup(card, head, label, meta, chevron, dot, body)
        head.setOnClickListener {
            group.expanded = !group.expanded
            body.visibility = if (group.expanded) View.VISIBLE else View.GONE
            chevron.text = if (group.expanded) "⌄" else "›"
        }

        feed.addView(card, Ui.margin(act, top = 2, bottom = 2))
        process = group
        return group
    }

    /** 一轮结束/开始说结论时收起过程：结果留在外面，过程缩成一行灰字。 */
    fun finishProcess() {
        val g = process ?: return
        if (g.steps == 0) {
            feed.removeView(g.view)
            process = null
            return
        }
        g.expanded = false
        g.body.visibility = View.GONE
        g.chevron.text = "›"
        g.dot.background = Ui.circle(act, Ui.OK, 6)
        g.label.text = "✓ 已完成"
        g.meta.text = "· ${g.steps} 次工具调用"
        process = null
    }

    // ------------------------------------------------------------ 工具卡

    inner class ToolCard(
        val view: LinearLayout,
        val metaView: TextView,
        val detailView: TextView,
        val dot: View,
    )

    fun addTool(name: String, meta: String): ToolCard {
        closeAssistant()
        val group = ensureProcess()
        group.steps++
        group.meta.text = "· ${group.steps} 步"
        group.expanded = true
        group.body.visibility = View.VISIBLE
        group.chevron.text = "⌄"

        val card = Ui.column(act)
        card.background = Ui.solid(act, Ui.GLASS_SOFT, 14)

        val head = Ui.row(act)
        head.setPadding(dp(11), dp(8), dp(11), dp(8))
        val dot = View(act).apply { background = Ui.circle(act, Ui.INK4, 6) }
        head.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(8) })
        head.addView(Ui.text(act, name, 12f, Ui.INK, true))
        val metaView = Ui.text(act, meta, 11.5f, Ui.INK3).apply {
            setPadding(dp(8), 0, dp(6), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        head.addView(metaView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(act, "›", 13f, Ui.INK4))

        val detail = Ui.text(act, "", 11f, Ui.INK2).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(11), dp(7), dp(11), dp(9))
            background = Ui.solid(act, Ui.GLASS_FAINT, 10)
            visibility = View.GONE
        }

        card.addView(head)
        card.addView(detail, Ui.margin(act, bottom = 7, left = 7, right = 7))

        head.setOnClickListener {
            detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        group.body.addView(card, Ui.margin(act, top = 4, bottom = 4))

        val tc = ToolCard(card, metaView, detail, dot)
        toolStack.add(tc)
        scrollDown()
        return tc
    }

    fun finishTool(ok: Boolean, meta: String, detail: String) {
        if (toolStack.isEmpty()) return
        val card = toolStack.removeAt(toolStack.size - 1)
        card.dot.background = Ui.circle(act, if (ok) Ui.OK else Ui.DANGER, 6)
        card.metaView.text = meta
        card.detailView.text = detail
        scrollDown()
    }

    /**
     * 产物卡片：模型写出的文件，点开预览。
     *
     * 「产物要送到眼前」——用户不应该去 Android/data 里翻目录找它写的东西。
     * 产物卡留在过程折叠**外面**（折叠一收，交付物不能跟着消失），
     * 同一个文件重复写入（先 write 后 edit 很常见）复用同一张卡并标注「已更新」。
     */
    fun addArtifact(path: String) {
        dropEmpty()
        finishProcess()
        closeAssistant()

        val again = artifactCards.containsKey(path)
        val card = artifactCards[path] ?: Ui.column(act).also { card ->
            card.background = Ui.solid(act, Ui.GLASS, 16, 0x99A9C4E8.toInt(), 1)
            val head = Ui.row(act)
            head.setPadding(dp(13), dp(11), dp(13), dp(11))
            head.addView(Ui.text(act, "▤", 13f, Ui.BRAND))
            head.addView(
                Ui.text(act, path, 12.5f, Ui.INK, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(7) }
            )
            head.addView(Ui.text(act, "预览 ›", 12f, Ui.BRAND, true))
            card.addView(head)
            val meta = Ui.text(act, "", 11f, Ui.INK3).apply {
                setPadding(dp(13), 0, dp(13), dp(11))
            }
            card.addView(meta)
            card.tag = meta
            head.setOnClickListener { onOpenArtifact(path) }
            card.setOnClickListener { onOpenArtifact(path) }
        }

        (card.tag as TextView).text =
            (if (again) "已更新 · " else "写好了 · 点开看内容") + fileMeta(path)
        // 再写同一个文件：把卡片挪到底部，保证「最新产物」总在视线下方
        if (again) {
            card.parent?.let { (it as ViewGroup).removeView(card) }
        }
        feed.addView(card, Ui.margin(act, top = 5, bottom = 5))
        artifactCards[path] = card
        scrollDown()
    }

    /** 这轮是否已经为这个文件出过卡（给「答案里提到的产物」兜底用，避免重复出卡）。 */
    fun hasArtifact(path: String): Boolean = artifactCards.containsKey(path)

    /** 工作台文件的大小，给卡片副标题用。路径越界或不存在就不显示。 */
    private fun fileMeta(path: String): String {
        val dir = act.getExternalFilesDir(null) ?: act.filesDir
        val f = java.io.File(dir, path)
        if (!f.exists() || !f.isFile) return ""
        return " · ${f.length()} 字节"
    }

    fun addImage(bitmap: Bitmap) {
        val holder = ImageView(act).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            clipToOutline = true
            background = Ui.solid(act, Ui.GLASS_SOFT, 14)
        }
        feed.addView(holder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(5)
            height = dp(190)
        })
        scrollDown()
    }

    // ------------------------------------------------------------ 状态

    fun setRunning(on: Boolean, text: String = "") {
        runningBar.visibility = if (on) View.VISIBLE else View.GONE
        if (text.isNotEmpty()) runningText.text = text
        sendButton.alpha = if (on) 0.4f else 1f
        if (!on) {
            closeAssistant()
            finishProcess()
        }
    }

    fun setRunningText(text: String) {
        runningText.text = text
    }

    fun setOffline(offline: Boolean) {
        offlineBar.visibility = if (offline) View.VISIBLE else View.GONE
        stateText.text = if (offline) "无障碍已断开" else "已连接"
        stateText.setTextColor(if (offline) Ui.WARN else Ui.INK3)
        stateDot.background = Ui.circle(act, if (offline) Ui.WARN else Ui.OK, 4)
    }

    /**
     * 顶栏胶囊上的模型名。切换后由 [MainActivity] 回填，别再让界面显示一个
     * 写死的常量——那是用户「看不到模型」的直接原因。
     *
     * 文字统一由 [ModelRegistry.Option.summary] 生成（"Hy3 · 免费"），
     * 界面上不会再出现第二种拼法。
     */
    fun setModel(option: ModelRegistry.Option = ModelRegistry.resolve(AgentHost.modelId())) {
        modelChip.text = "✦ ${option.summary()} ⌄"
        // 空状态页还挂着的话，底部的「当前模型 xx」也要跟着换——
        // 切完模型回到新会话第一屏，看到的还是旧名字就很迷惑。
        modelHint?.text = "当前模型 ${option.label} · 点顶部模型名可切换"
    }

    fun setEnabled(enabled: Boolean) {
        input.isEnabled = enabled
        sendButton.alpha = if (enabled) 1f else 0.4f
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 轮末操作行 + 底栏小结。
     *
     * 操作行照 dsh-Go：答案下面一排轻按钮（复制/分享），右边是这轮的用量——
     * 「换模型到底生效没有」看底栏，想留下这轮结果看操作行。
     */
    fun showRoundSummary(servedModel: String?, fallback: String, usage: String) {
        val who = servedModel ?: "$fallback（服务端未回传）"
        footer.text = "上一轮由 $who 回答" + if (usage.isEmpty()) "" else " · $usage"
        footer.setTextColor(if (servedModel == null) Ui.WARN else Ui.INK3)

        val ans = lastAnswer ?: return
        if (ans.length < 8) return  // 一句话的回答不值得配一排按钮

        val actions = Ui.row(act)
        actions.setPadding(dp(2), dp(2), dp(2), dp(2))
        // 朗读排在最前：耳机/开车时「听」比「看」顺手，这是这排按钮里唯一不用眼睛的
        speakChip = actionChip("🔊 朗读") { toggleSpeak(ans) }
        actions.addView(speakChip)
        actions.addView(actionChip("⧉ 复制") {
            val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("月溯", ans))
            android.widget.Toast.makeText(act, "已复制这一轮的回答", android.widget.Toast.LENGTH_SHORT).show()
        })
        actions.addView(actionChip("↗ 分享") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, ans)
            }
            act.startActivity(Intent.createChooser(intent, "分享这一轮的回答"))
        })

        if (usage.isNotEmpty()) {
            actions.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            actions.addView(Ui.text(act, usage, 11f, Ui.INK4).apply { gravity = Gravity.CENTER })
        }
        feed.addView(actions, Ui.margin(act, top = 2, bottom = 6))
        scrollDown()
    }

    /** 轮末操作行里的轻按钮：无底色，点按区放大。 */
    private fun actionChip(label: String, onClick: () -> Unit): TextView =
        Ui.text(act, label, 12.5f, Ui.INK2, true).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

    fun resetFooter() {
        footer.text = HINT
        footer.setTextColor(Ui.INK3)
    }

    private fun dp(value: Int): Int = Ui.dp(act, value)

    companion object {
        private const val HINT = "它会在动手前先读屏 · 付款、密码这类操作会先问你"
    }
}
