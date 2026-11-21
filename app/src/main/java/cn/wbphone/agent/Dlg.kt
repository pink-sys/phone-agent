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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 居中确认弹窗：替代系统 AlertDialog 的白色方框。
 *
 * 系统 AlertDialog 跟随平台主题——白底、方角、按钮是淡青色，和雾蓝主题放在一起
 * 像贴上去的另一张皮。这里用和 ApprovalSheet 同一套语言自绘：
 *
 * - 面是卡片色圆角 + 发丝描边，不是纯白方角；
 * - 标题深墨加粗、正文次级灰带行距，和设置页/审批单一个声音；
 * - 按钮是文字按钮（无底无边），确认键雾蓝、危险动作警示红——
 *   「删除」「清空」这类键不该和「知道了」一个颜色；
 * - 自定义视图（代码井、输入框）超高时约束到卡片高度内滚动，不顶出屏幕。
 */
internal object Dlg {

    class Builder(private val act: Activity) {
        var title: String = ""
        var message: String? = null
        /** 自定义内容视图（输入框、代码井……），排在标题/正文之后、按钮之前。 */
        var view: View? = null
        /** 自定义视图的最大高度（dp）。内容超出就限高滚动。 */
        var viewMaxHeightDp: Int = 420
        var positive: String? = null
        var onPositive: (() -> Unit)? = null
        /** 危险动作（删除、清空、退出）：确认键红字。 */
        var danger: Boolean = false
        var negative: String? = null
        var onNegative: (() -> Unit)? = null
        var neutral: String? = null
        var onNeutral: (() -> Unit)? = null
        /** 列表菜单（替代 setItems）：标题下一列可点的行。 */
        var items: List<Pair<String, () -> Unit>> = emptyList()
        /** 点遮罩或返回键取消时的兜底（对应 setOnCancelListener）。 */
        var onCancel: (() -> Unit)? = null

        private lateinit var dialog: Dialog

        fun show() {
            val d = Dialog(act, android.R.style.Theme_Translucent_NoTitleBar)
            dialog = d
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            val scrim = FrameLayout(act)
            scrim.isClickable = true
            scrim.setOnClickListener { cancel() }

            val card = Ui.column(act)
            card.background = Ui.solid(act, Ui.CARD, 22, Ui.LINE, 1)
            card.setPadding(dp(22), dp(20), dp(22), dp(12))
            card.isClickable = true

            if (title.isNotEmpty()) {
                card.addView(Ui.text(act, title, 17f, Ui.INK, true).apply {
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
            }

            message?.takeIf { it.isNotEmpty() }?.let { msg ->
                card.addView(Ui.text(act, msg, 13.5f, Ui.INK2).apply {
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(0, dp(10), 0, 0)
                })
            }

            view?.let {
                // 高度要在 LayoutParams 里一起给：wrap-content 会被内容（几十万字的
                // 代码井）撑爆，把按钮顶出屏幕外。
                val (v, h) = wrapView(it)
                card.addView(v, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h
                ).apply { topMargin = dp(14) })
            }

            if (items.isNotEmpty()) {
                val list = Ui.column(act)
                for ((label, action) in items) {
                    val row = Ui.text(act, label, 14f, Ui.INK).apply {
                        setLineSpacing(dp(3).toFloat(), 1f)
                        setPadding(dp(14), dp(14), dp(14), dp(14))
                        background = Ui.solid(act, Ui.SOFT, 14, Ui.LINE, 1)
                        setOnClickListener { dismiss(); action() }
                    }
                    list.addView(row, Ui.margin(act, top = 8))
                }
                card.addView(list, Ui.margin(act, top = 14))
            }

            card.addView(buttons(), Ui.margin(act, top = 10))

            scrim.addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { leftMargin = dp(26); rightMargin = dp(26) })

            d.setContentView(scrim)
            d.setOnCancelListener { onCancel?.invoke() }
            d.window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0.38f)
                runCatching { w.setWindowAnimations(android.R.style.Animation_InputMethod) }
            }
            d.show()
        }

        /** 超高的自定义视图限高滚动：代码井几十万字不能把卡片顶出屏幕。
         *  返回 (视图, 实际应给的高度)：没超限就是 WRAP_CONTENT。 */
        private fun wrapView(v: View): Pair<View, Int> {
            val maxH = dp(viewMaxHeightDp)
            val width = act.resources.displayMetrics.widthPixels - dp(26 * 2 + 22 * 2)
            runCatching {
                v.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            if (v.measuredHeight <= maxH) return v to ViewGroup.LayoutParams.WRAP_CONTENT
            return if (v is ScrollView) {
                v to maxH
            } else {
                ScrollView(act).apply {
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(v, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT))
                } to maxH
            }
        }

        /** 中性键靠左，取消/确认靠右；确认键颜色随危险档位变。 */
        private fun buttons(): View {
            val row = Ui.row(act)
            fun action(label: String, color: Int, action: () -> Unit): TextView =
                Ui.text(act, label, 14f, color, true).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(6))
                    setOnClickListener { dismiss(); action() }
                }
            if (neutral != null) {
                row.addView(action(neutral!!, Ui.INK2) { onNeutral?.invoke() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                row.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            }
            negative?.let {
                row.addView(action(it, Ui.INK2) { onNegative?.invoke() },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(6) })
            }
            positive?.let {
                row.addView(action(it, if (danger) Ui.DANGER else Ui.BRAND) { onPositive?.invoke() },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(6) })
            }
            return row
        }

        fun dismiss() {
            runCatching { if (dialog.isShowing) dialog.dismiss() }
        }

        private fun dp(value: Int): Int = Ui.dp(act, value)

        private fun cancel() {
            val cb = onCancel
            dismiss()
            cb?.invoke()
        }
    }

    fun alert(act: Activity, init: Builder.() -> Unit) {
        Builder(act).apply(init).show()
    }
}
