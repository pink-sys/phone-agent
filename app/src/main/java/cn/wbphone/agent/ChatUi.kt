package cn.wbphone.agent

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.MotionEvent
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
 *  新会话： (☰) (新对话 ·●本机)        (✦ Hy4 flash · 免费 ⌄)
 *  会话中： (☰) (学习通 ·●本机)        (✦ Hy4 flash · 免费 ⌄) (✎)
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
 * 3. **素排的过程行，整轮只留一行**。跑的过程一切展开（工具 chip、旁白、思考都看得见），
 *    整轮跑完 collapseTurn 把工具行、中途说明和思考小结收进**一个**折叠行——
 *    「✓ 已完成 · N 次工具调用 · M 段说明 · K 次思考」，点开才出现。收完眼前只剩
 *    用户的问题、这一行、最终回答和产物卡（2026-11-17 反馈：中途穿插
 *    各种工具调用看着不舒服，照 dsh-Go / WorkBuddy 收起来）。
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
    private lateinit var micButton: MicIconView

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

    /** 当前正在流式输出的助手回答卡片（容器）；换新一轮就置空。 */
    private var openBubble: LinearLayout? = null

    /** 最后一轮说完整的答案，给轮末「复制/分享」用。 */
    private var lastAnswer: String? = null

    /** 空状态（问候 + 建议），发第一句话后移除。 */
    private var emptyView: View? = null

    /** 空状态页底部的「当前模型 xx」一行。切模型时同步刷新，别显示一个写死的名字。 */
    private var modelHint: TextView? = null

    /** 本轮的工具折叠组。跑的时候展开，结束时收起成一行素排。 */
    private var process: ProcessGroup? = null

    /**
     * 本轮出现在界面上的「过程件」：过程组（工具 chips 的容器）+ 回答气泡，按创建顺序。
     *
     * 整轮结束时 [collapseTurn] 按这份清单把除最终回答外的全部东西——工具 chips、
     * 中途的说明段——收进一个折叠行。中途的说明文字也要收：它们是干活的旁白，
     * 留在外面用户还是得在工具行和旁白之间来回扫（2026-11-17 反馈：「回答中间
     * 穿插各种工具调用，看着不舒服」）。最终回答和产物卡永远留在折叠外面。
     */
    private val turnPieces = ArrayList<Any>()

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
        plusButton = Ui.text(act, "＋", 24f, Ui.INK).apply {
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
        // 图标是 Canvas 描边话筒——emoji 版是彩色位图，跟白灰底条完全不搭（真机反馈）
        micButton = MicIconView(act).apply {
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

    /** 麦克风状态：在听时图标换停字块、底变实心品牌色，用户一眼知道它在听。 */
    fun setListening(on: Boolean) {
        micButton.listening = on
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
        turnPieces.clear()
        toolStack.clear()
        artifactCards.clear()
        // 思考块也要跟着断引用：残留的 thinkView 指向已脱离 feed 的 view，
        // 下轮思考会往一个不在页面上的 view 里写字，看起来就是「思考不见了」。
        thinkView = null
        thinkText.setLength(0)
        collapsedThink = null
    }

    fun isEmpty(): Boolean = emptyView != null || feed.childCount == 0

    private fun dropEmpty() {
        val v = emptyView ?: return
        feed.removeView(v)
        emptyView = null
        modelHint = null
    }

    // ------------------------------------------------------------ 消息

    /**
     * 一条用户气泡。[photos] 是恢复会话时从磁盘解码回来的照片（发送时仍走 pendingImages）。
     * 两个来源共用同一套渲染：单张放大图、多张一排小方块。
     */
    fun addUser(text: String, photos: List<Bitmap> = emptyList()) {
        dropEmpty()
        finishProcess()
        // 新的一轮：上一轮的过程件已经收过了（或轮到收），清掉再记本轮的
        turnPieces.clear()
        openBubble = null
        // 进入会话态：顶栏右侧出现 ✎ 新会话（dsh-Go 的放法）
        newChatChip.visibility = View.VISIBLE

        val wrap = Ui.column(act)
        // 带图的消息：气泡上方先放缩略图（图片本身在历史里，气泡只做视觉提示）。
        // 单张放大图；多选时一排小方块，挤不下的说明本来就贴在气泡文字里。
        val shown = if (photos.isNotEmpty()) photos else pendingImages.map { it.thumb }
        if (shown.isNotEmpty()) {
            if (shown.size == 1) {
                wrap.addView(android.widget.ImageView(act).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(shown[0])
                    background = Ui.solid(act, Ui.GLASS_SOFT, 14)
                    clipToOutline = true
                }, LinearLayout.LayoutParams(dp(160), dp(120)).apply { gravity = Gravity.END })
            } else {
                val strip = Ui.row(act).apply { gravity = Gravity.END }
                for (img in shown) {
                    strip.addView(android.widget.ImageView(act).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(img)
                        background = Ui.solid(act, Ui.GLASS_SOFT, 10)
                        clipToOutline = true
                    }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(4) })
                }
                wrap.addView(strip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.END })
            }
        }
        val bubble = Ui.text(act, text, 14.5f, 0xFFFFFFFF.toInt()).apply {
            // 行距比旧版松一档：白字在深底上更要留出呼吸感，挤着读特别累
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(14), dp(10), dp(14), dp(11))
            // 右下角收一个角，指向「我」这一侧；深蓝实底，和雾蓝底拉开对比
            background = Ui.solidRadii(act, Ui.BUBBLE_ME, intArrayOf(dp(18), dp(18), dp(5), dp(18)))
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.78f).toInt()
            // 自己发的话也能复制 / 拖选 / 引用回来改：之前只有回答能选，用户想复用自己的
            // 那句提示词只能重新手打一遍。长按走和回答同一套弹层，手感一致。
            setOnLongClickListener { showUserMenu(text); true }
        }
        // 只有图没打字的 message：bubble 高度压到贴着图，别留一整条空白泡
        if (text.isNotEmpty()) {
            wrap.addView(bubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END })
        }
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
        endThinking()
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

    // ------------------------------------------------------------ 思考过程

    /** 正在显示的思考块；正文一开始就收掉，避免和回答抢视线。 */
    private var thinkView: android.widget.TextView? = null
    private val thinkText = StringBuilder()
    private var thinkStartAt = 0L

    /**
     * 收起后的思考行挂在 view 上的完整内容。收起不等于销毁——用户想回看
     * 「它当时到底在想什么」时就靠这份底稿。
     */
    private class ThinkRef(val full: String, val summary: String) {
        var expanded = false
    }

    /**
     * 模型「想」的增量。
     *
     * 上游把思考单独发在 reasoning_content 里，正文要等思考结束才开始。之前这段完全被
     * 丢掉，用户面对的是 2~5 秒的纯空白——「感觉最开始特别慢」就是这么来的，其实模型
     * 一直在干活。压暗、缩小、只留最近一段：它是过程不是结论，不能抢正文的位置。
     */
    fun appendThinking(delta: String) {
        val v = thinkView ?: run {
            dropEmpty()
            finishProcess()
            openBubble = null
            thinkText.setLength(0)
            // 新一段思考开始 = 上一轮彻底翻篇，它的小结不再属于「当前这一轮」
            collapsedThink = null
            thinkStartAt = System.currentTimeMillis()
            val t = Ui.text(act, "", 12.5f, Ui.INK3).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(2), dp(4), dp(2), dp(4))
                maxWidth = (act.resources.displayMetrics.widthPixels * 0.84f).toInt()
            }
            feed.addView(t, Ui.margin(act, top = 6, bottom = 2))
            thinkView = t
            t
        }
        thinkText.append(delta)
        val shown = thinkText.toString()
        v.text = if (shown.length > 400) "…" + shown.takeLast(400) else shown
        scrollDown()
    }

    /**
     * 正文要开始了（或者这一轮结束了）：思考块收成一行「已思考」，点击可展开回看全文。
     *
     * 不直接删掉是有原因的：有的轮思考只有一两句话、一闪而过，用户根本注意不到，
     * 然后「怎么没见思考流」。留一行灰色小结，既不占地方，又能确认它真的发生过。
     * 全文留在 ThinkRef 里，默认收起——排查「它为什么答成这样」时展开看过程，
     * 平时不动它，不抢正文的位置。没有思考增量的轮（模型这轮没发 reasoning）什么都不留。
     */
    fun endThinking() {
        val v = thinkView ?: return
        val n = thinkText.length
        val ms = System.currentTimeMillis() - thinkStartAt
        if (n > 0) {
            v.tag = ThinkRef(
                thinkText.toString(),
                "已思考 %d 字 · %.1fs".format(n, ms / 1000.0),
            )
            collapseThinking(v)
            v.setOnClickListener { toggleThinking(v) }
            // 记入本轮过程件：整轮收工时和工具 chips 一起折进「已完成」那一行
            turnPieces.add(v)
        } else {
            feed.removeView(v)
        }
        thinkView = null
        thinkText.setLength(0)
        // 收起后的那一行也算「这一轮的痕迹」：网络断了要重来时得一起撤，
        // 否则屏幕上会留着一条「▸ 已思考 2579 字」，而那份思考已经被重试覆盖了。
        collapsedThink = if (n > 0) v else null
    }

    /**
     * 从历史里还原一段思考：和实时收起的那行长得一模一样，点一下照样能展开。
     *
     * 为什么要把思考存下来：不存的话「已思考 2579 字」只活到关掉 App 为止——
     * 回看一段旧对话时，最想知道的恰恰是「它当时怎么想的」，而那部分偏偏没了。
     * 只有字数、没有全文的展开按钮是假的，所以存的是全文（截断上限见 AgentLoop.THINK_KEEP）。
     */
    fun addThinkingHistory(full: String) {
        if (full.isBlank()) return
        dropEmpty()
        finishProcess()
        val v = Ui.text(act, "", 12.5f, Ui.INK3).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), dp(4), dp(2), dp(4))
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.84f).toInt()
        }
        // 历史里没有耗时（当时没存），就只报字数——不编一个假的时间出来
        v.tag = ThinkRef(full, "已思考 %d 字".format(full.length))
        collapseThinking(v)
        v.setOnClickListener { toggleThinking(v) }
        feed.addView(v, Ui.margin(act, top = 6, bottom = 2))
        // 同实时路径：历史里的思考行也归入本轮过程件，收拢时一起折
        turnPieces.add(v)
    }

    /** 上一次「收起成一行」的思考小结。只在当前这一轮内有效，重来或下一轮都会换。 */
    private var collapsedThink: android.widget.TextView? = null

    /**
     * 这一轮作废重来（网络断了，客户端要重发同一条请求）。
     *
     * 已经流出来的半截回答、思考块（连同收起后的那一行小结）一起撤掉。
     * 撤的时候把 view 也从 feed 里摘掉，不留空壳——留着空壳，重试的内容会落在它下面，
     * 看起来就像两条回答。只撤「正在流」的那条，前面几轮答完的气泡一律不动。
     */
    fun restartTurn() {
        collapsedThink?.let { feed.removeView(it) }
        collapsedThink = null
        thinkView?.let { feed.removeView(it) }
        thinkView = null
        thinkText.setLength(0)
        thinkStartAt = 0L
        openBubble?.let { feed.removeView(it) }
        openBubble = null
        openRaw = null
        openRef = null
    }

    /** 收起：一行小结居中，带 ▸ 提示可展开。 */
    private fun collapseThinking(v: android.widget.TextView) {
        val ref = v.tag as? ThinkRef ?: return
        ref.expanded = false
        v.text = "▸ " + ref.summary
        v.gravity = Gravity.CENTER
    }

    /** 展开：小结挪到第一行（保持时间信息），全文左对齐铺在下面。 */
    private fun expandThinking(v: android.widget.TextView) {
        val ref = v.tag as? ThinkRef ?: return
        ref.expanded = true
        v.text = "▾ " + ref.summary + "\n\n" + ref.full
        v.gravity = Gravity.START
    }

    /**
     * 展开 / 收起思考块。
     *
     * **不能跟着滚到底**：这块一展开就是几十行，滚到底等于把用户从他自己点的那一行
     * 直接甩到页面末尾，还得手动翻回去找——实测反馈原话是「点展开后直接滑到最底，不方便」。
     * 做法是记下这一行在视口里的位置，布局完成后按同一位置还原：眼睛停在原地，
     * 展开的全文从那行往下长，收起时同理（总高度变短，scrollTo 会自己夹到合法范围）。
     */
    private fun toggleThinking(v: android.widget.TextView) {
        val ref = v.tag as? ThinkRef ?: return
        val anchor = v.top - scroll.scrollY
        if (ref.expanded) collapseThinking(v) else expandThinking(v)
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                view.removeOnLayoutChangeListener(this)
                scroll.scrollTo(0, (view.top - anchor).coerceAtLeast(0))
            }
        })
    }

    /**
     * 一条回答在界面上的身份：原文（复制/分享/引用用）+ 它在会话历史里的下标（分叉用）。
     * 下标 -1 表示还不知道（正在流式的那一轮），跑完由 bindAnswerIndex 补上。
     *
     * [card] 是白卡容器（feed 的直接子节点，折叠逻辑按 tag 认它），[inner] 是卡里的
     * 内容列：流式阶段只有 [body] 一个 TextView（span 渲染快），跑完 [collapseTurn]
     * 调 [renderRich] 换成多视图——分块清晰的收子卡，分不出块的靠浅灰分节线，
     * 这是一张 TextView 拼不出来的层次（2026-11-20 对照 Muse 改版）。
     */
    private class AnswerRef(
        val raw: StringBuilder,
        var index: Int,
        val card: LinearLayout,
        val inner: LinearLayout,
        val body: TextView,
        var rich: Boolean = false,
    )

    private var openRef: AnswerRef? = null

    fun addAssistant(text: String = "", historyIndex: Int = -1) {
        dropEmpty()
        // 正文来了，思考过程让位
        endThinking()
        finishProcess()
        val card = Ui.column(act)
        val inner = Ui.column(act)
        card.addView(inner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = Ui.text(act, "", 14.5f, Ui.INK).apply {
            // 行距从约 1.55 拉到约 1.72：同样的字数眼睛不用逐行硬追，
            // 长回复也不容易串行。这是「读着舒服」的三分之一，另两份在卡片和间距里。
            setLineSpacing(dp(8).toFloat(), 1f)
        }
        val ref = AnswerRef(StringBuilder(text), historyIndex, card, inner, body)
        openRef = ref
        openRaw = ref.raw
        card.tag = ref
        // 长按挂在整个卡上：正文/子卡/代码块任何位置长按都出回答菜单
        card.setOnLongClickListener { showAnswerMenu(card); true }
        if (text.isNotEmpty()) {
            // 整段直达（恢复会话、报错提示）：直接进富渲染，不用等收工
            renderMarked(body, text)
            dressCard(card)
            renderRich(ref)
        }
        feed.addView(card, Ui.margin(act, top = 4, bottom = 5))
        turnPieces.add(card)
        openBubble = card
        scrollDown()
    }

    /** 给回答卡穿上白底 + 描边 + 内边距。空气泡（流式没出字）不穿，免得先闪一枚白豆。 */
    private fun dressCard(card: LinearLayout) {
        card.background = Ui.solid(act, Ui.CARD, 18, Ui.CARD_LINE, 1)
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun appendAssistant(delta: String) {
        if (openBubble == null) {
            addAssistant(delta)
            return
        }
        val ref = openRef ?: return
        if (ref.rich) {
            // 极罕见：富渲染后又来了增量（收工补发）。拆掉富视图回到流式态，别把字丢进真空。
            ref.rich = false
            ref.inner.removeAllViews()
            ref.inner.addView(ref.body)
        }
        ref.raw.append(delta)
        // 流式期间整段重渲染：正文撑死了几 KB，解析是微秒级的事，
        // 换来的是星号一对上就变粗体，不用等这轮说完。
        renderMarked(ref.body, ref.raw.toString())
        dressCard(ref.card)
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
        endThinking()
        // 这一轮已经收尾了，思考小结属于「已完成」的痕迹——之后哪怕网络断了要重来，
        // 也只该撤正在流的那一轮，不能把这条已完成的记录一起带走。
        collapsedThink = null
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
    private fun showAnswerMenu(v: View) {
        val ref = v.tag as? AnswerRef ?: return
        val raw = ref.raw.toString()
        if (raw.isBlank()) return
        showActionSheet("回答操作", "长按这段回答弹出的操作，只对这一轮生效。", listOf(
            Triple("复制", "整段回答进剪贴板") { copyAnswer(raw) },
            Triple("选择文字", "长按拖选一段，只复制那一段") { showSelectableText(raw) },
            Triple("引用", "填进输入框，接着问这段的事") { quoteAnswer(raw) },
            Triple("分享", "交给微信、备忘录这些应用") { shareAnswer(raw) },
            Triple("从这里分叉", "以这条回答为界，复制进一个新对话") { onFork(ref.index, raw) },
        ))
    }

    /**
     * 我的消息：自己提的问题同样能复制、拖选、引用。
     *
     * 少了「分享」和「分叉」——那两件事只对回答有意义（分享出去的是一份结论，
     * 分叉是拿回答当新对话的起点），自己的问题分享给别人没有意义。
     */
    private fun showUserMenu(text: String) {
        if (text.isBlank()) return
        showActionSheet("我的消息", "长按自己说的话弹出的操作，只对这一条生效。", listOf(
            Triple("复制", "整条消息进剪贴板") { copyAnswer(text) },
            Triple("选择文字", "长按拖选一段，只复制那一段") { showSelectableText(text) },
            Triple("引用", "填回输入框，改几个字再发一遍") { quoteAnswer(text) },
        ))
    }

    /**
     * 底部操作面板：一列圆弧气泡，回答和自己的消息共用这一套。
     *
     * 抽出来是因为两边都要一模一样的手感；之前只有回答有面板，用户想复用自己的
     * 那句话没有任何入口。
     */
    private fun showActionSheet(
        title: String,
        hint: String,
        items: List<Triple<String, String, () -> Unit>>,
    ) {
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
        head.addView(Ui.text(act, title, 16f, Ui.INK, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(act, "关闭", 13f, Ui.INK3).apply {
            setPadding(dp(10), dp(6), dp(2), dp(6))
            minimumHeight = dp(36)
            setOnClickListener { dialog.dismiss() }
        })
        card.addView(head)
        card.addView(Ui.text(act, hint, 11.5f, Ui.INK3)
            .apply { setPadding(0, dp(4), 0, dp(12)) })

        // ---- 圆弧气泡，竖着排，间距舒展
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
            sb.append(spansOf(b))
        }
        tv.text = sb
        // 有链接才挂 movement method——不挂的话普通点击/长按原样走气泡自己的逻辑
        if (sb.getSpans(0, sb.length, ClickableSpan::class.java).isNotEmpty()) {
            tv.movementMethod = LinkOnlyMovement()
        }
    }

    /** 单个 Markdown 块 → span 序列。流式（整段一个 TextView）和富渲染共用。 */
    private fun spansOf(b: Marked.Block): CharSequence {
        val sb = SpannableStringBuilder()
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
            "table" -> for ((i, segs) in b.lines.withIndex()) {
                if (i > 0) sb.append("\n")
                val start = sb.length
                appendSegs(sb, segs)
                // 表格在窄气泡里画网格必炸：Marked 已收成「首列 · 后续列」，
                // 这里只管悬挂缩进，折行后对齐文字，跟列表一个节奏
                sb.setSpan(LeadingMarginSpan.Standard(dp(2), dp(12)), start, sb.length,
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
        return sb
    }

    /**
     * 富渲染：把一条回答从「一坨 span」升级成多视图。
     *
     * 分两种长相（2026-11-20 对照 Muse 第三轮改）：
     * - 分块清晰的（小标题 + 列表/引用）：小标题独立加粗、紧跟门牌的列表收进
     *   浅灰子卡，一节一目了然；
     * - 分不出块的叙述型：不再硬塞子卡，靠 Muse 式浅灰细线分节——门牌前一条线，
     *   节尾回到叙述再一条，纯叙述就只有段落留白。
     *
     * 代码块深底浅字、引用浅底。流式期间仍是单 TextView（快），
     * 跑完由 [collapseTurn] 调这里换装，恢复会话/报错直达时在 [addAssistant] 直接换。
     */
    private fun renderRich(ref: AnswerRef) {
        if (ref.rich) return
        val src = ref.raw.toString()
        if (src.isBlank()) return
        ref.rich = true
        ref.inner.removeAllViews()
        val blocks = Marked.parse(src)
        var first = true
        var prevKind = ""
        var prevWasHead = false     // 上一块是不是一节的门牌
        var prevWasGrouped = false  // 上一块是不是门牌带进子卡的列表（节的主体）
        var drewLine = false        // 这一块前刚垫过分节线，自己的 top margin 让位
        for ((bi, b) in blocks.withIndex()) {
            val next = blocks.getOrNull(bi + 1)
            val isHead = isSectionHead(b, next)
            // 出节线：上一节的子卡列表完了、这一块回到普通叙述——节的边界两头都齐。
            // 进下一节的情况由下面的进节线管，这里只管关门。
            if (bi > 0 && prevWasGrouped && !isHead &&
                b.kind != "hr" && b.kind != "ul" && b.kind != "ol") {
                drawSectionLine(ref.inner)
                drewLine = true
            }
            // 进节线：新一节的门牌前垫一条浅灰细线（首块前不垫、紧跟 hr 不垫）
            if (bi > 0 && isHead && prevKind != "hr") {
                drawSectionLine(ref.inner)
                drewLine = true
            }
            val grouped = (b.kind == "ul" || b.kind == "ol") && prevWasHead
            val v: View = when (b.kind) {
                "h" -> Ui.text(act, "", 14.5f, Ui.INK).apply {
                    text = spansOf(b)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = if (b.level <= 1) 16.5f else if (b.level == 2) 15f else 14.5f
                    setLineSpacing(dp(3).toFloat(), 1f)
                }
                "code" -> TextView(act).apply {
                    // 围栏内原样保留：行间补换行，缩进和空行都活着
                    text = b.lines.joinToString("\n") { l -> l.joinToString("") { it.text } }
                    setTextColor(Ui.CODE_FG)
                    textSize = 12.5f
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(dp(2).toFloat(), 1f)
                    background = Ui.solid(act, Ui.CODE_BG, 12)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                "ul", "ol" -> listBlock(b, grouped)
                "quote" -> Ui.text(act, "", 14f, Ui.INK2).apply {
                    text = spansOf(b)
                    background = Ui.solid(act, Ui.SOFT, 12)
                    setLineSpacing(dp(5).toFloat(), 1f)
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                }
                "hr" -> View(act).apply { background = Ui.solid(act, Ui.CARD_LINE, 1) }
                // p / table：span 渲染照旧（表格收成的紧凑行挂悬挂缩进）
                else -> Ui.text(act, "", 14.5f, Ui.INK).apply {
                    text = spansOf(b)
                    setLineSpacing(dp(7).toFloat(), 1f)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 块间空隙用 margin 给：比 \n\n 的自然行距稳，富渲染里行高不再被 span 撑乱
            val top = when {
                first || drewLine -> 0
                b.gap >= 2 -> dp(10)
                else -> dp(4)
            }
            lp.setMargins(0, top, 0, if (b.kind == "hr") dp(6) else 0)
            ref.inner.addView(v, lp)
            first = false
            drewLine = false
            prevWasHead = isHead
            prevWasGrouped = grouped
            prevKind = b.kind
        }
    }

    /**
     * 一节的门牌：`#` 标题，或下面三种段落写法——模型笔下都在承担「这里开始新的一节」：
     * - 整行加粗的短段（提示词要求的「加粗小标题」）；
     * - 加粗领句收在冒号上、正文接在同一行（「**购汇费：**蓝葵花主打…」问答体），
     *   领句加粗 + 冒号收尾是极强的分节信号，后面带多少正文都算；
     * - 短促带冒号且下面紧跟列表的素字领句（「国内能正常用的替代：」）。
     */
    private fun isSectionHead(b: Marked.Block, next: Marked.Block?): Boolean {
        if (b.kind == "h") return true
        if (b.kind != "p" || b.lines.size != 1) return false
        val segs = b.lines[0]
        if (segs.isEmpty()) return false
        val text = segs.joinToString("") { it.text }.trim()
        if (text.length < 2) return false
        if (segs.all { it.bold && it.text.isNotBlank() } && text.length <= 60) return true
        // 加粗领句收在冒号上，冒号在加粗里或紧随其后都算：
        // 「**购汇费：**…」「**支付宝的钱能不能转进去**：可以。」
        if (segs[0].bold) {
            val lead = segs[0].text.trimEnd()
            val after = if (text.startsWith(lead)) text.removePrefix(lead).trimStart() else ""
            if (lead.endsWith("：") || lead.endsWith(":") ||
                after.startsWith("：") || after.startsWith(":")) return true
        }
        return (text.endsWith("：") || text.endsWith(":")) && text.length <= 30 &&
            (next?.kind == "ul" || next?.kind == "ol")
    }

    /**
     * 一个列表块：[grouped] 时是门牌带的正文，收进浅灰子卡（分块清晰那一类）；
     * 散落在叙述里的列表不穿底色，素净的圆点 + 留白，跟 Muse 的平铺一个气质。
     */
    private fun listBlock(b: Marked.Block, grouped: Boolean): View = Ui.column(act).apply {
        if (grouped) {
            background = Ui.solid(act, Ui.SOFT, 14)
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        for (segs in b.lines) {
            addView(Ui.text(act, "", 14f, Ui.INK).apply {
                val item = SpannableStringBuilder()
                if (b.kind == "ul") item.append("•  ")
                val start = item.length
                appendSegs(item, segs)
                // 悬挂缩进：折行后对齐文字而不是对齐圆点
                item.setSpan(LeadingMarginSpan.Standard(0, dp(15)), start, item.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = item
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, 0, 0, dp(6))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 一条 Muse 式分节线：1dp 浅灰细线，上下各留一口呼吸。 */
    private fun drawSectionLine(parent: LinearLayout) {
        parent.addView(View(act).apply { background = Ui.solid(act, Ui.CARD_LINE, 1) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, dp(11), 0, dp(11))
            })
    }

    /**
     * 只在手指真压住链接时接管触摸。
     *
     * LinkMovementMethod 会把整个 TextView 的 DOWN/MOVE/UP 都吃掉，气泡的
     * 长按菜单（复制/分享/引用）跟着一起废——这个是 2026-11-19 加链接时差点踩的坑。
     * 这里先做命中测试：压的不是链接就返回 false，事件原样落回 TextView 自己的手势。
     */
    private class LinkOnlyMovement : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val layout = widget.layout ?: return false
            val x = event.x.toInt() + widget.scrollX - widget.totalPaddingLeft
            val y = event.y.toInt() + widget.scrollY - widget.totalPaddingTop
            if (x < 0 || y < 0 || x > layout.width || y > layout.height) return false
            val line = layout.getLineForVertical(y)
            val off = layout.getOffsetForHorizontal(line, x.toFloat())
            val hit = buffer.getSpans(off, off, ClickableSpan::class.java)
            return if (hit.isEmpty()) false else super.onTouchEvent(widget, buffer, event)
        }
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
            if (seg.link != null) {
                // 主色 + 可点。ForegroundColorSpan 兜底给「选择文字」那类没挂
                // movement method 的视图，不然链接文字还是黑的看不出是链接
                val url = seg.link
                sb.setSpan(ForegroundColorSpan(Ui.BRAND), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) { openLink(url) }
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = Ui.BRAND
                        ds.isUnderlineText = false
                    }
                }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** 点正文里的链接：交给浏览器。没装浏览器或链接非法就弹个提示，别无声无息。 */
    private fun openLink(url: String) {
        val u = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(act, "打不开这个链接", android.widget.Toast.LENGTH_SHORT).show()
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

    private fun buildProcessGroup(): ProcessGroup {
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
        return group
    }

    private fun ensureProcess(): ProcessGroup {
        process?.let { return it }
        val group = buildProcessGroup()
        feed.addView(group.view, Ui.margin(act, top = 2, bottom = 2))
        turnPieces.add(group)
        process = group
        return group
    }

    /** 一轮结束/开始说结论时收起过程：结果留在外面，过程缩成一行灰字。 */
    fun finishProcess() {
        val g = process ?: return
        if (g.steps == 0) {
            feed.removeView(g.view)
            turnPieces.remove(g)
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

    /**
     * 整轮收工：把这一轮的所有过程——工具 chips、中途的说明气泡、思考小结行——
     * 收进**一个**折叠行，折叠行插在本轮内容的开头位置。
     *
     * 跑的过程中一切照常展开（看得见它在干什么），只有真跑完了才收：
     * 收完用户眼前只剩「他的问题 → ✓ 已完成一行 → 最终回答 → 产物卡」，
     * 想查它当时点了哪儿就点开那一行（chips 原样都在里面，点单个 chip 还能看详情，
     * 思考行点开还是原来的全文展开）。
     *
     * 最后一枚回答气泡是最终回答，永远留在折叠外面；思考行靠 tag 里的 ThinkRef
     * 认出来，全部收进去——它是过程，不配留在结果旁边。会话恢复（restoreTranscript）
     * 也走这一个入口，历史会话打开就是收好的形态。
     */
    fun collapseTurn() {
        finishProcess()
        if (turnPieces.isEmpty()) return
        // 最后一枚**回答**卡片 = 最终回答（tag=AnswerRef；思考行是 TextView+ThinkRef，
        // 不能算进去）；一枚都没有（纯工具轮 + 报错）就不留
        var lastBubble = -1
        for (i in turnPieces.indices) {
            val p = turnPieces[i]
            if ((p is TextView && p.tag !is ThinkRef) ||
                (p is ViewGroup && p.tag is AnswerRef)) lastBubble = i
        }

        var steps = 0
        var notes = 0
        var thinks = 0
        val removed = ArrayList<View>()
        val entries = ArrayList<View>()   // 收进折叠体的内容，按时间顺序
        var answerRef: AnswerRef? = null
        for ((i, piece) in turnPieces.withIndex()) {
            when (piece) {
                is ProcessGroup -> {
                    steps += piece.steps
                    removed.add(piece.view)
                    for (j in 0 until piece.body.childCount) entries.add(piece.body.getChildAt(j))
                }
                is TextView -> {
                    if (piece.tag is ThinkRef) {
                        // 思考小结行：全文还在 ThinkRef 里，折进去点开照样能展开
                        thinks++
                        removed.add(piece)
                        entries.add(piece)
                    } else if (i != lastBubble) {
                        notes++
                        removed.add(piece)
                        entries.add(piece)
                    }
                }
                is ViewGroup -> {
                    // 回答卡（中途的说明段也是一张卡）：最后一枚留在折叠外，其余收进去
                    if (i == lastBubble) answerRef = piece.tag as? AnswerRef
                    else {
                        notes++
                        removed.add(piece)
                        entries.add(piece)
                    }
                }
            }
        }
        turnPieces.clear()
        // 最终回答换装成富渲染：小标题/列表子卡/代码深块（流式阶段只有一坨 span）。
        // 放在收拢之后——此刻它就是折叠行下面那张留住的卡。
        answerRef?.let { renderRich(it) }
        if (entries.isEmpty()) return

        // 插入点 = 被收内容末尾的下一个兄弟（产物卡 / 思考行 / 最终回答），保持它们在折叠行下面
        var anchor: View? = null
        for (v in removed.asReversed()) {
            val idx = feed.indexOfChild(v)
            if (idx >= 0) {
                anchor = if (idx + 1 < feed.childCount) feed.getChildAt(idx + 1) else null
                break
            }
        }
        for (v in removed) feed.removeView(v)

        val g = buildProcessGroup()
        for (e in entries) {
            // 这些 chip 原来挂在旧的过程组里，父容器还是它；直接 addView 会抛
            // IllegalStateException("The specified child already has a parent")——
            // 折叠一上线就闪退（恢复历史必崩）就是漏了这一步。先摘再挂。
            (e.parent as? ViewGroup)?.removeView(e)
            g.body.addView(e)
        }
        g.steps = steps
        g.expanded = false
        g.body.visibility = View.GONE
        g.chevron.text = "›"
        g.dot.background = Ui.circle(act, Ui.OK, 6)
        g.label.text = "✓ 已完成"
        g.meta.text = "· $steps 次工具调用" +
            (if (notes > 0) " · $notes 段说明" else "") +
            (if (thinks > 0) " · $thinks 次思考" else "")
        val at = anchor?.let { feed.indexOfChild(it) } ?: feed.childCount
        feed.addView(g.view, if (at >= 0) at else feed.childCount,
            Ui.margin(act, top = 2, bottom = 2))

        // 收完这条线就断了：下一轮流式必须自己起新气泡。openBubble 若还指着
        // 刚被折进折叠体的旧气泡，deltas 会往一个已经换了父容器的 view 里写字。
        openBubble = null
        openRaw = null
        openRef = null
        // 思考小结行也折进去了，这个引用不能再留给 restartTurn 用（它只管本轮）
        collapsedThink = null
        // 上面那批 chip 已经挪进折叠体，toolStack 里的引用也失效了；
        // 不清掉，下一轮 finishTool 会把「完成态」刷到一个看不见的旧卡片上。
        toolStack.clear()
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
     * 文字统一由 [ModelRegistry.Option.summary] 生成（"Hy4 flash · 免费"），
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

/**
 * 语音输入键的图标：Canvas 手绘的描边话筒（对照 Muse 截图定的稿）。
 * 为什么不用 emoji 话筒：那是彩色位图，跟输入条的白灰底不搭，大小也钉死在字体上。
 * 待机画描边话筒（胶囊 + 弧形支架 + 竖杆 + 底座）；录音中画白色实心停字块。
 * 24×24 的名义坐标盒，实际按 View 尺寸等比缩放，任何分辨率都锐利。
 */
private class MicIconView(context: Context) : View(context) {

    /** 在听中：图标换停字块。set() 里 invalidate 驱动重画。 */
    var listening: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val color = if (listening) Color.WHITE else Ui.INK2
        stroke.color = color
        fill.color = color
        val cx = width / 2f
        val cy = height / 2f
        if (listening) {
            // 录音中：实心圆角小方块 = 停
            val s = width * 8f / 30f
            c.drawRoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2,
                s / 4f, s / 4f, fill)
            return
        }
        // 名义坐标盒 24，但按 30 等分缩放：图标只占按钮的 80%，四周留白，
        // 和「+」「↑」的视觉分量一致——画满 44dp 会顶到边，看着很挤（真机反馈）
        val u = width / 30f
        stroke.strokeWidth = 1.6f * u
        // 图标在盒里实际占 y=4..19.5（视觉中心 11.75u）——直接从盒顶画会整体偏上，
        // 先把视觉中心对齐到按钮中心再落笔（真机反馈：看着不在正中间）
        val yOff = cy - 11.75f * u
        // 胶囊：宽 7 高 10，顶在 y=4
        val r = 3.5f * u
        val left = cx - r
        val top = 4f * u + yOff
        val bottom = 14f * u + yOff
        c.drawRoundRect(left, top, left + 7 * u, bottom, r, r, stroke)
        // 弧形支架：圆心 (cx, 11u) 半径 5.5u 的下半圆（从右侧 0° 扫到 180°）
        val arc = Path()
        arc.addArc(cx - 5.5f * u, 5.5f * u + yOff, cx + 5.5f * u, 16.5f * u + yOff, 0f, 180f)
        c.drawPath(arc, stroke)
        // 竖杆 + 底座
        c.drawLine(cx, 16.5f * u + yOff, cx, 19.5f * u + yOff, stroke)
        c.drawLine(cx - 3.5f * u, 19.5f * u + yOff, cx + 3.5f * u, 19.5f * u + yOff, stroke)
    }
}
