package cn.wbphone.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 界面基础件：色板、圆角、按钮。
 *
 * 工程里没有 androidx（手工构建，不引依赖），所以没有 Material 组件，
 * 所有卡片/气泡/按钮都用 GradientDrawable 画。集中在这里，免得每处重复。
 *
 * ## 色板只有一套：雾蓝
 *
 * 照 dsh-Go 的默认主题「雾蓝（mist）」搬过来，不做多套主题色开关——
 * 六套配色是给「用户会去设置里换」的产品用的，这里只需要一套耐看的：
 *
 * - 页面是一条极淡的竖向渐变（顶 #EBF1FE → 底 #F6F7FC），不用纯白铺底；
 * - 文字是深墨蓝 #1E232E，三级灰靠明度分档，不靠纯灰；
 * - 强调色 #4B67B4 只出现在发送键、选中态、链接、进度上，占比 ≤ 5%，
 *   铺多了就不「静」了；
 * - 用户气泡是 #D9E4FD 的浅蓝面 + 深墨字，不是白字渐变块——
 *   白字压在饱和色上，看两行就累。
 */
internal object Ui {

    // ══ 页面与面色阶梯（越高越浅）════════════════════════════
    /** 页面渐变顶 · 晨雾蓝 */
    val BG_TOP = 0xFFEBF1FE.toInt()

    /** 页面渐变底 · 近白微紫 */
    val BG_BOTTOM = 0xFFF6F7FC.toInt()

    /** 渐变中值，铺不了渐变时的兜底色（状态栏、导航栏这类单色位） */
    val BG = 0xFFF0F4FD.toInt()

    /** 卡片面：比页面浅一档，靠 1px 发丝线和留白分层，不靠阴影 */
    val CARD = 0xFFFBFCFF.toInt()

    /** 次级面：工具详情、代码井、输入框内的凹陷块 */
    val SOFT = 0xFFEEF1F9.toInt()

    /** 更深一档的面：选中行的底 */
    val SOFT2 = 0xFFE7EBF4.toInt()

    // ══ 文字 ══════════════════════════════════════════════
    /** 主文字 · 深墨蓝 */
    val INK = 0xFF1E232E.toInt()

    /** 次级文字 */
    val INK2 = 0xFF4F5562.toInt()

    /** 三级文字 · 时间戳、摘要、占位符 */
    val INK3 = 0xFF646974.toInt()

    /** 禁用 / 纯装饰，不承载必要信息 */
    val INK4 = 0xFFA1A5AC.toInt()

    // ══ 线 ════════════════════════════════════════════════
    /** 发丝描边 / 分割线 */
    val LINE = 0xFFDCE0E8.toInt()

    // ══ 玻璃面 ════════════════════════════════════════════
    // dsh-Go 的质感核心：面不是实心白，是铺在渐变上的半透明白——
    // 底下的渐变色透出来一点，界面就是一整块「玻璃」而不是贴上去的卡片。
    // 透明度压得比较狠（80/62/32）：太实就是「白卡片」，透出底下的渐变才是玻璃。
    /** 玻璃·强：顶栏胶囊、输入条（透出渐变约 20%） */
    val GLASS = 0xCCFFFFFF.toInt()

    /** 玻璃·弱：工具 chip、建议胶囊（透出约 38%） */
    val GLASS_SOFT = 0x9EFFFFFF.toInt()

    /** 玻璃·更弱：展开的工具详情井 */
    val GLASS_FAINT = 0x52FFFFFF.toInt()

    /** 玻璃描边：发丝线带透明度，比实线轻一档 */
    val GLASS_LINE = 0x99DCE0E8.toInt()

    // ══ 强调色 ════════════════════════════════════════════
    /** 主色：DeepSeek 色相压过彩度的雾蓝 */
    val BRAND = 0xFF4B67B4.toInt()

    /** 按下态 */
    val BRAND_DEEP = 0xFF3D569D.toInt()

    /** 主色容器：选中底、标签底 */
    val BRAND_BG = 0xFFDEE8FF.toInt()

    /** 主色容器上的字 */
    val ON_BRAND_BG = 0xFF213262.toInt()

    /** 用户气泡 */
    val BUBBLE = 0xFFD9E4FD.toInt()

    /**
     * 我方气泡：深蓝实底配白字（对照 Muse 的分层改法）。
     * 旧版浅蓝灰泡泡贴在雾蓝底上几乎没对比——三层（雾蓝底 / 深蓝气泡 / 白卡片）分明后，
     * 谁在说话一眼就能分清。
     */
    val BUBBLE_ME = 0xFF3F5DC2.toInt()

    /** 回答白卡片的描边：比 GLASS_LINE 实一点的蓝灰细线，白卡才不至于糊进浅色里。 */
    val CARD_LINE = 0xFFE1E6F2.toInt()

    /** 代码块：深底浅字（对照 Muse 的改法——浅灰底代码在白卡里没有「这是代码」的信号）。 */
    val CODE_BG = 0xFF2B3245.toInt()
    val CODE_FG = 0xFFDCE2F5.toInt()

    // ══ 语义色（低饱和，不抢主色）═══════════════════════════
    val OK = 0xFF3E7D5B.toInt()
    val OK_BG = 0xFFE9F2EC.toInt()
    val WARN = 0xFF8D6636.toInt()
    val WARN_BG = 0xFFF7EDE0.toInt()
    val WARN_LINE = 0xFFE4D2BC.toInt()
    val DANGER = 0xFFA54743.toInt()

    fun dp(ctx: Context, value: Number): Int =
        (value.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 页面底：一条从上到下的极淡渐变。
     * 纯色铺底会让整屏显得「平」，加 2% 的明度差就有纸的质感。
     */
    fun screen(ctx: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(BG_TOP, BG_BOTTOM)
    )

    fun solid(ctx: Context, color: Int, radius: Int, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(ctx, radius).toFloat()
            if (stroke != 0) setStroke(dp(ctx, strokeWidth.coerceAtLeast(1)), stroke)
        }

    fun solidRadii(ctx: Context, color: Int, radii: IntArray): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadii = floatArrayOf(
                radii[0].toFloat(), radii[0].toFloat(),
                radii[1].toFloat(), radii[1].toFloat(),
                radii[2].toFloat(), radii[2].toFloat(),
                radii[3].toFloat(), radii[3].toFloat(),
            )
        }

    /** 主按钮底。雾蓝方案里主按钮是实色，不做渐变——渐变会把它变成「促销按钮」。 */
    fun brand(ctx: Context, radius: Int): GradientDrawable = solid(ctx, BRAND, radius)

    fun circle(ctx: Context, color: Int, size: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dp(ctx, size), dp(ctx, size))
    }

    fun text(
        ctx: Context,
        value: CharSequence,
        size: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
    }

    /** 主按钮：实色底白字。 */
    fun primaryButton(ctx: Context, label: String, height: Int = 52, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 15.5f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            background = brand(ctx, 15)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, height)
            )
        }

    /** 次按钮：卡片底 + 发丝线。 */
    fun ghostButton(ctx: Context, label: String, height: Int = 52, color: Int = INK, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            background = solid(ctx, CARD, 15, LINE, 1)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, height)
            )
        }

    /** 纯文字按钮：无底无边。 */
    fun textButton(ctx: Context, label: String, color: Int = INK2, size: Float = 13.5f, onClick: () -> Unit): TextView =
        text(ctx, label, size, color).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    fun spacer(ctx: Context, height: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, height))
    }

    fun row(ctx: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
        }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun margin(ctx: Context, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(ctx, top)
            bottomMargin = dp(ctx, bottom)
            leftMargin = dp(ctx, left)
            rightMargin = dp(ctx, right)
        }

    /** App 标志：圆角方块 + 白字，省掉一套图标资源。 */
    fun logo(ctx: Context, size: Int, corner: Int, textSize: Float): TextView =
        text(ctx, "月溯", textSize * 0.82f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = brand(ctx, corner)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size))
        }

}
