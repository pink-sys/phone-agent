package cn.wbphone.agent

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 审批弹窗的界面形态：从底部升起的确认单。
 *
 * 用底部而不是居中对话框，是因为这一步的性质是"停下来，交还给你"——
 * 从下往上推出来的面板比居中弹框更像"系统在等你",也更适合单手点。
 */
class ApprovalSheet(
    private val act: Activity,
    private val request: ApprovalRequest,
    private val onDecision: (ApprovalDecision) -> Unit,
) {

    private val dialog = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
    private var answered = false

    fun show() {
        val content = build()
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.setCancelable(false)

        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.38f)
            runCatching { w.setWindowAnimations(android.R.style.Animation_InputMethod) }
        }
        dialog.show()
    }

    fun dismiss() {
        runCatching { if (dialog.isShowing) dialog.dismiss() }
    }

    fun isShowing(): Boolean = runCatching { dialog.isShowing }.getOrDefault(false)

    private fun decide(value: ApprovalDecision) {
        if (answered) return
        answered = true
        dismiss()
        onDecision(value)
    }

    private fun build(): View {
        val sheet = Ui.column(act)
        sheet.background = Ui.solidRadii(act, Ui.CARD, intArrayOf(dp(26), dp(26), 0, 0))
        sheet.setPadding(dp(20), dp(10), dp(20), dp(30))

        sheet.addView(View(act).apply { background = Ui.solid(act, 0xFFDFE4EE.toInt(), 2) },
            LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            })

        val titleRow = Ui.row(act)
        titleRow.addView(View(act).apply { background = Ui.circle(act, Ui.WARN, 9) },
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(9) })
        titleRow.addView(Ui.text(
            act,
            if (request.critical) "这一步要你确认" else "它想执行一步操作",
            16.5f, Ui.INK, true
        ))
        sheet.addView(titleRow)

        sheet.addView(Ui.text(act, request.reason, 13.5f, Ui.INK2).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        })

        val target = Ui.column(act)
        target.setPadding(dp(14), dp(12), dp(14), dp(12))
        target.background = Ui.solid(act, Ui.SOFT, 12, Ui.LINE, 1)
        target.addView(factRow("动作", request.action, false))
        if (request.target.isNotEmpty()) target.addView(factRow("目标", request.target, request.critical))
        target.addView(factRow("时限", "${request.timeoutSeconds} 秒内不回应按拒绝处理", false))
        sheet.addView(target, Ui.margin(act, top = 14))

        val buttons = Ui.row(act)
        buttons.setPadding(0, dp(18), 0, 0)
        val deny = Ui.ghostButton(act, "拒绝", 50, Ui.DANGER) { decide(ApprovalDecision.DENY) }.apply {
            background = Ui.solid(act, Ui.CARD, 15, 0xFFEBD3D2.toInt(), 1)
        }
        val allow = Ui.primaryButton(act, "允许这次", 50) { decide(ApprovalDecision.ALLOW_ONCE) }
        buttons.addView(deny, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(5) })
        buttons.addView(allow, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(5) })
        sheet.addView(buttons)

        val once = Ui.textButton(act, "本任务内允许同类操作", Ui.INK2, 13f) {
            decide(ApprovalDecision.ALLOW_TASK)
        }
        sheet.addView(once, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // 悬浮窗权限没开时，这个弹层只能回月溯来点——每一步审批都要在两个应用之间来回切。
        // 把授权入口放在眼前，点一次就一劳永逸。
        if (!ApprovalOverlay.permitted(act)) {
            sheet.addView(Ui.textButton(
                act, "开悬浮窗 · 确认框直接浮在目标应用上，不用来回切", Ui.BRAND, 12.5f
            ) {
                FloatStatus.openPermissionPage(act)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
        }

        return sheet
    }

    private fun factRow(key: String, value: String, danger: Boolean): LinearLayout {
        val row = Ui.row(act, Gravity.TOP)
        row.setPadding(0, dp(3), 0, dp(3))
        row.addView(Ui.text(act, key, 12.5f, Ui.INK3).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(Ui.text(act, value, 13.5f, if (danger) Ui.DANGER else Ui.INK, danger).apply {
            typeface = Typeface.create(Typeface.DEFAULT, if (danger) Typeface.BOLD else Typeface.NORMAL)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun dp(value: Int): Int = Ui.dp(act, value)
}
