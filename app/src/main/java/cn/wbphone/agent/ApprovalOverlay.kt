package cn.wbphone.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 审批悬浮卡：Agent 在别的应用里干活时，把「它想执行一步操作」直接浮在
 * **那个应用的界面**上，点「允许」根本不用切走。
 *
 * 为什么必须有这个：以前审批是月溯自己的底部弹层，用户人在微信看着 Agent 干活，
 * 弹窗却只能回月溯才能点——切回来点「允许」，Agent 读屏发现当前应用不对，
 * 又重新打开微信，下一个动作再弹一次……每一步审批都白耗两个来回。
 *
 * 挂 WindowManager 而不是 Activity 弹窗，理由同 FloatStatus：审批的生命周期
 * 跟「闸门在等答案」绑定，不跟任何界面绑定。权限没给时这里一切空转，
 * ApprovalUi 自动退回 App 内的底部弹层 + 通知。
 *
 * 注意与 FloatStatus 不同：这里**不看** floatOn 开关——悬浮审批是安全流程的一部分，
 * 只要用户给了「显示在应用上层」权限，就该用它。
 */
object ApprovalOverlay {

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    @Volatile private var view: View? = null

    /** 最近一次挂载失败的原因（排查用，logcat 标签 YueshuApproval）。 */
    @Volatile private var lastError: String = ""

    fun permitted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * 主线程内联执行。这里必须内联的原因是一次真实的 bug：
     * show() 本身跑在主线程，如果 dismiss() 无条件 post 到主线程，这个「删卡片」的任务
     * 会排在 addView **之后**执行——卡片刚挂上就被自己删掉，界面上什么都没有，
     * 闸门一直等到超时（症状：审批卡闪一下没了，然后一直卡在等待审批）。
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * 挂上悬浮审批卡。成功返回 true（此时 ApprovalUi 不再弹 App 内弹层）。
     */
    fun show(ctx: Context, request: ApprovalRequest, onDecision: (ApprovalDecision) -> Unit): Boolean {
        if (!permitted(ctx)) {
            lastError = "no overlay permission"
            return false
        }
        val manager = wm ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) {
            lastError = "no WindowManager"
            return false
        }
        wm = manager
        var ok = false
        onMain { ok = attach(ctx, manager, request, onDecision) }
        return ok
    }

    private fun attach(
        ctx: Context,
        manager: WindowManager,
        request: ApprovalRequest,
        onDecision: (ApprovalDecision) -> Unit,
    ): Boolean {
        // 上一张先同步摘掉（若它还在），不能靠异步投递——见 onMain 的注释
        detachNow()

        val card = buildCard(ctx, request) { decision ->
            detachNow()
            onDecision(decision)
        }
        val d = ctx.resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            // 宽度给死：WRAP_CONTENT 里再套 MATCH_PARENT 子控件，某些 ROM 会量成 0 宽，
            // 卡片「在但不显示」比没挂上还难查。
            (ctx.resources.displayMetrics.widthPixels - (28 * d).toInt()).coerceAtLeast((240 * d).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL：卡片外的触摸全部透传给下面的应用，点微信还是点微信；
            // NOT_FOCUSABLE：不抢输入法不抢返回键，按钮靠触摸照样能点
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (28 * d).toInt()
        }

        return try {
            manager.addView(card, lp)
            view = card
            lastError = ""
            true
        } catch (e: Exception) {
            // BadTokenException（权限刚被收走等）：静默退回 App 内弹层
            view = null
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            android.util.Log.w("YueshuApproval", "overlay addView failed: $lastError")
            false
        }
    }

    fun dismiss() = onMain { detachNow() }

    /** 必须主线程调用。 */
    private fun detachNow() {
        val v = view ?: return
        view = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
    }

    fun isShowing(): Boolean = view != null

    // ------------------------------------------------------------ 卡片

    /** 与 ApprovalSheet 同一套信息结构，压缩成悬浮尺寸。 */
    private fun buildCard(
        ctx: Context,
        request: ApprovalRequest,
        decide: (ApprovalDecision) -> Unit,
    ): View {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        // 对无障碍隐藏：Agent 靠读屏看界面，审批卡是它自己引起的浮层，
        // 一旦被读进节点树，模型会以为「当前界面变成了一张确认卡」而反复重开目标应用。
        card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        card.background = Ui.solidRadii(ctx, Ui.CARD, intArrayOf(dp(20), dp(20), dp(20), dp(20)))
        card.setPadding(dp(16), dp(14), dp(16), dp(12))

        val titleRow = LinearLayout(ctx)
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(View(ctx).apply { background = Ui.circle(ctx, Ui.WARN, 8) },
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        titleRow.addView(text(ctx,
            if (request.critical) "这一步要你确认" else "它想执行一步操作",
            15f, Ui.INK, true))
        card.addView(titleRow)

        card.addView(text(ctx, request.reason, 12.5f, Ui.INK2).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(7), 0, 0)
        })

        if (request.target.isNotEmpty()) {
            val targetRow = LinearLayout(ctx)
            targetRow.gravity = Gravity.CENTER_VERTICAL
            targetRow.setPadding(dp(10), dp(7), dp(10), dp(7))
            targetRow.background = Ui.solid(ctx, Ui.SOFT, 10, Ui.LINE, 1)
            targetRow.addView(text(ctx, request.target, 12.5f,
                if (request.critical) Ui.DANGER else Ui.INK, request.critical).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            })
            card.addView(targetRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }

        val buttons = LinearLayout(ctx)
        buttons.setPadding(0, dp(12), 0, 0)
        val deny = Ui.ghostButton(ctx, "拒绝", 40, Ui.DANGER) { decide(ApprovalDecision.DENY) }.apply {
            background = Ui.solid(ctx, Ui.CARD, 13, 0xFFEBD3D2.toInt(), 1)
        }
        val allow = Ui.primaryButton(ctx, "允许这次", 40) { decide(ApprovalDecision.ALLOW_ONCE) }
        buttons.addView(deny, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) })
        buttons.addView(allow, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) })
        card.addView(buttons)

        card.addView(Ui.textButton(ctx, "本任务内允许同类操作", Ui.INK2, 12f) {
            decide(ApprovalDecision.ALLOW_TASK)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })

        return card
    }

    private fun text(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }
}
