package cn.wbphone.agent

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * 首次使用的两步引导：登录 → 给权限。
 *
 * 只在第一次出现（[AgentSettings.onboardingDone]），之后每次打开直接进对话页。
 * 每一步只放一个主按钮，避免上一版那种"一屏四张卡、每张都有三四个按钮"的场面。
 */
class OnboardingUi(
    private val act: Activity,
    private val onLogin: () -> Unit,
    private val onOpenAccessibility: () -> Unit,
    private val onOpenAppInfo: () -> Unit,
    private val onAllowBackground: () -> Unit,
    private val onAutoRestore: () -> Unit,
    private val onFinish: () -> Unit,
) {

    val root: View

    private lateinit var stepLabel: TextView
    private lateinit var bar1: View
    private lateinit var bar2: View
    private lateinit var body: LinearLayout
    private lateinit var footer: LinearLayout

    private var step = 1
    private var loginBusy = false
    private var loginDone = false
    private var permOn = false

    private var loginStateText: TextView? = null
    private var loginSpinner: ProgressBar? = null
    private var loginButton: android.widget.Button? = null
    private var permButton: android.widget.Button? = null
    private var permTip: TextView? = null
    private val permRows = ArrayList<LinearLayout>()

    init {
        root = build()
        showStep(1)
    }

    private fun build(): View {
        val col = Ui.column(act)
        col.setBackgroundColor(Ui.BG)

        val head = Ui.row(act)
        head.setPadding(dp(26), dp(12), dp(26), dp(18))
        head.addView(Ui.logo(act, 34, 11, 11f))
        head.addView(Ui.text(act, "月溯", 14f, Ui.INK, true).apply {
            setPadding(dp(10), 0, 0, 0)
        })
        stepLabel = Ui.text(act, "1 / 2", 12f, Ui.INK3).apply { gravity = Gravity.END }
        head.addView(stepLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head, Ui.margin(act))

        val bars = Ui.row(act)
        bars.setPadding(dp(26), 0, dp(26), 0)
        bar1 = View(act).apply { background = Ui.solid(act, Ui.BRAND, 2) }
        bar2 = View(act).apply { background = Ui.solid(act, 0xFFDFE2E8.toInt(), 2) }
        bars.addView(bar1, LinearLayout.LayoutParams(0, dp(3), 1f).apply { rightMargin = dp(3) })
        bars.addView(bar2, LinearLayout.LayoutParams(0, dp(3), 1f).apply { leftMargin = dp(3) })
        col.addView(bars, Ui.margin(act, top = 0))

        body = Ui.column(act)
        body.setPadding(dp(26), dp(26), dp(26), 0)
        val scroll = ScrollView(act).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        footer = Ui.column(act)
        footer.setPadding(dp(26), dp(18), dp(26), dp(26))
        col.addView(footer, Ui.margin(act))

        return col
    }

    fun showStep(value: Int) {
        step = value
        stepLabel.text = "$value / 2"
        bar2.background = Ui.solid(act, if (value >= 2) Ui.BRAND else 0xFFDFE2E8.toInt(), 2)
        body.removeAllViews()
        footer.removeAllViews()
        if (value == 1) buildLogin() else buildPermission()
    }

    fun currentStep(): Int = step
    fun isLoginBusy(): Boolean = loginBusy
    fun isLoginDone(): Boolean = loginDone

    // ------------------------------------------------------------ 第一步

    private fun buildLogin() {
        body.addView(title("先登录你的\nWorkBuddy 账号"))
        body.addView(sub("之后你说的话、它做的每一步，都走你自己的额度。\n不经过任何第三方服务器。"))

        val card = Ui.column(act).apply {
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = Ui.solid(act, Color.WHITE, 20, Ui.LINE, 1)
        }

        val badge = FrameLayout(act).apply {
            background = Ui.solid(act, Ui.SOFT, 16, Ui.LINE, 1)
        }
        val inner = Ui.column(act).apply { gravity = Gravity.CENTER }
        inner.addView(Ui.logo(act, 54, 16, 17f), LinearLayout.LayoutParams(dp(54), dp(54)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        inner.addView(Ui.text(act, "浏览器登录", 12f, Ui.INK3).apply {
            setPadding(0, dp(10), 0, 0)
            gravity = Gravity.CENTER
        })
        badge.addView(inner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        card.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        val stateRow = Ui.row(act).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        loginSpinner = ProgressBar(act, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= 21) indeterminateTintList = ColorStateList.valueOf(Ui.BRAND)
        }
        stateRow.addView(loginSpinner, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) })
        loginStateText = Ui.text(act, "点下面的按钮，在浏览器里确认登录", 13f, Ui.INK2).apply {
            gravity = Gravity.CENTER
        }
        stateRow.addView(loginStateText)
        card.addView(stateRow, Ui.margin(act))

        body.addView(card, Ui.margin(act, top = 24, bottom = 0))

        val note = Ui.row(act, Gravity.TOP)
        note.setPadding(0, dp(16), 0, 0)
        note.addView(Ui.text(act, "锁", 10f, Ui.INK3, true).apply {
            gravity = Gravity.CENTER
            background = Ui.solid(act, 0xFFF0F1F5.toInt(), 6)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(9) })
        note.addView(Ui.text(
            act,
            "登录凭证用本机芯片里的密钥加密保存，拷走文件也解不开。",
            12f, Ui.INK3
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(note, Ui.margin(act))

        loginButton = Ui.primaryButton(act, "打开浏览器登录") { onLogin() }
        footer.addView(loginButton)
        footer.addView(Ui.text(act, "浏览器里确认完回到这里就行", 12f, Ui.INK3).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }, Ui.margin(act))
    }

    fun setLoginWaiting(waiting: Boolean) {
        loginBusy = waiting
        loginSpinner?.visibility = if (waiting) View.VISIBLE else View.GONE
        loginStateText?.setTextColor(if (waiting) Ui.INK2 else Ui.INK2)
        loginStateText?.text = if (waiting) "正在等你确认，别关掉浏览器" else "点下面的按钮，在浏览器里确认登录"
        loginButton?.text = if (waiting) "等待浏览器确认…" else "打开浏览器登录"
        loginButton?.isEnabled = !waiting
        loginButton?.alpha = if (waiting) 0.65f else 1f
    }

    fun setLoginDone(name: String) {
        loginDone = true
        loginBusy = false
        loginSpinner?.visibility = View.GONE
        loginStateText?.text = if (name.isEmpty()) "已登录" else "已登录 · $name"
        loginStateText?.setTextColor(Ui.OK)
        loginButton?.text = "继续"
        loginButton?.isEnabled = true
        loginButton?.alpha = 1f
        bar2.background = Ui.solid(act, Ui.BRAND, 2)
    }

    // ------------------------------------------------------------ 第二步

    private fun buildPermission() {
        permRows.clear()
        body.addView(title("给它手和眼"))
        body.addView(sub("系统里叫「无障碍」，开启后才能替你点屏幕。\n不开的话，它看不到也点不动你的手机。"))

        permRows.add(permRow("读", "读屏", "看到当前界面上有哪些按钮、写了什么字"))
        permRows.add(permRow("点", "点按与输入", "替你点按钮、打字、滑动、返回"))
        permRows.add(permRow("图", "截图", "微信这类自绘界面读不到字，只能看图。只在它主动截的时候才发生"))

        for (row in permRows) body.addView(row, Ui.margin(act, top = 10))

        val hint = Ui.row(act, Gravity.TOP)
        hint.setPadding(dp(14), dp(12), dp(14), dp(12))
        hint.background = Ui.solid(act, Ui.WARN_BG, 13, Ui.WARN_LINE, 1)
        hint.addView(Ui.text(act, "!", 11f, Ui.WARN, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(9); topMargin = dp(1) })
        hint.addView(Ui.text(
            act,
            "开完记得回来。有些手机会在清理后台时把无障碍一起关掉。" +
                "常驻通知能降低概率；再花一分钟授权，它就能自己把开关写回来。",
            12f, 0xFF8A5A05.toInt()
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(hint, Ui.margin(act, top = 16))

        val autoRow = Ui.row(act, Gravity.CENTER)
        autoRow.addView(Ui.textButton(act, "让它自己把开关写回来（推荐）", size = 12.5f) { onAutoRestore() })
        body.addView(autoRow, Ui.margin(act, top = 2))

        permButton = Ui.primaryButton(act, if (permOn) "开始使用" else "去系统设置开启") {
            if (permOn) onFinish() else onOpenAccessibility()
        }
        footer.addView(permButton)

        permTip = Ui.text(
            act,
            if (permOn) "权限已开启，回去之后它会一直保持" else "在列表里找到「月溯」打开开关",
            12f, Ui.INK3
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        footer.addView(permTip)

        val extra = Ui.row(act, Gravity.CENTER)
        extra.setPadding(0, dp(6), 0, 0)
        extra.addView(Ui.textButton(act, "后台运行设置") { onAllowBackground() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        extra.addView(Ui.text(act, "·", 12f, Ui.INK3).apply { setPadding(dp(6), 0, dp(6), 0) })
        extra.addView(Ui.textButton(act, "开关打不开？", size = 12.5f) { onOpenAppInfo() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        footer.addView(extra)
    }

    private fun permRow(badge: String, name: String, desc: String): LinearLayout {
        val row = Ui.row(act, Gravity.TOP)
        row.setPadding(dp(16), dp(15), dp(16), dp(15))
        row.background = Ui.solid(act, Color.WHITE, 16, Ui.LINE, 1)

        val icon = Ui.text(act, badge, 13f, Ui.INK2, true).apply {
            gravity = Gravity.CENTER
            background = Ui.solid(act, 0xFFF2F3F7.toInt(), 10)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(13) })

        val texts = Ui.column(act)
        texts.addView(Ui.text(act, name, 14f, Ui.INK, true))
        texts.addView(Ui.text(act, desc, 12.5f, Ui.INK3).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    fun setPermission(on: Boolean) {
        permOn = on
        for (row in permRows) {
            val icon = row.getChildAt(0) as? TextView ?: continue
            if (on) {
                icon.text = "✓"
                icon.setTextColor(Ui.OK)
                icon.background = Ui.solid(act, 0xFFE5F6EC.toInt(), 10)
                row.background = Ui.solid(act, Ui.OK_BG, 16, 0xFFCFE9D8.toInt(), 1)
            } else {
                icon.setTextColor(Ui.INK2)
                icon.background = Ui.solid(act, 0xFFF2F3F7.toInt(), 10)
                row.background = Ui.solid(act, Color.WHITE, 16, Ui.LINE, 1)
            }
        }
        permButton?.text = if (on) "开始使用" else "去系统设置开启"
        permTip?.text = if (on) "权限已开启，回去之后它会一直保持" else "在列表里找到「月溯」打开开关"
    }

    // ------------------------------------------------------------ 文本件

    private fun title(value: String): TextView = Ui.text(act, value, 27f, Ui.INK, true).apply {
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun sub(value: String): TextView = Ui.text(act, value, 14.5f, Ui.INK2).apply {
        setLineSpacing(dp(5).toFloat(), 1f)
        setPadding(0, dp(12), 0, 0)
    }

    private fun dp(value: Int): Int = Ui.dp(act, value)
}
