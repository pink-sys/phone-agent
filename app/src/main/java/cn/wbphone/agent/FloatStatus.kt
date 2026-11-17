package cn.wbphone.agent

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 悬浮状态窗：任务跑到别的应用界面上时，一个小胶囊浮在屏幕上沿，
 * 告诉你它此刻在干什么——正在读屏、正在截图、正在思考、正在点按…
 * 点一下胶囊回到对话页看完整进度；按住可以拖走；任务结束自动收起。
 *
 * 为什么做成 object 挂 WindowManager：悬浮窗的生命周期跟「有没有会话在跑」绑定，
 * 不跟任何 Activity 或 Service 绑定——Activity 切走、销毁都不该影响它。
 * 权限（显示在应用上层）没给时一切空转，界面上不会有任何动静。
 */
object FloatStatus {

    private val main = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var view: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var label: TextView? = null

    /** 在跑任务的会话 id。多个会话并行时，最后一个结束才收窗。 */
    private val busy = LinkedHashSet<String>()

    private var lastText = ""

    /** 系统设置里这个开关的名字很绕，包一层好读。 */
    fun permitted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** 设置页的授权入口：跳到「显示在应用上层」的月溯详情页。 */
    fun openPermissionPage(ctx: Context) {
        val uri = android.net.Uri.parse("package:" + ctx.packageName)
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // 个别 ROM 没有这页，退回总列表
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** 任务开始：挂上胶囊，先说「正在思考…」（第一轮总是模型在想）。 */
    fun taskStart(ctx: Context, sid: String) = main.post {
        busy.add(sid)
        show(ctx.applicationContext, "正在思考…")
    }

    /** 任务结束（跑完、出错、手动停都算）：收窗。 */
    fun taskEnd(ctx: Context, sid: String) = main.post {
        busy.remove(sid)
        if (busy.isEmpty()) remove()
    }

    /** 状态变化：只在有任务在跑时更新，同一句话不重复刷。 */
    fun status(ctx: Context, text: String) = main.post {
        if (busy.isEmpty() || text == lastText) return@post
        show(ctx.applicationContext, text)
    }

    /** 用户关了开关（或清了 busy）：立刻收窗，下次任务开始前不再出现。 */
    fun refresh(ctx: Context) = main.post {
        if (!AgentSettings(ctx).floatOn || busy.isEmpty()) remove()
    }

    /**
     * Agent 派手势（点按/长按/滑动）期间，把胶囊对触摸临时关闭。
     *
     * 为什么必须有这开关：手势是屏幕级注入的，谁在最顶层就落在谁身上。胶囊默认钉在
     * 屏幕底部左侧，而应用底部的输入框、发送键也常在那里——碰撞面仍存在。
     * 加 FLAG_NOT_TOUCHABLE 后手势穿透到下面的应用；胶囊保持可见（不闪烁），
     * 手势一结束立刻恢复可点，用户点胶囊回对话页的行为不受影响。
     *
     * 【双保险一】gestureActive：手势派发的整个窗口期置 true（在派发线程上**同步**
     * 置位，没有异步竞态）。胶囊的触摸监听看到它就直接吞掉事件、绝不 startActivity。
     * 这是确定性的兜底——updateViewLayout 的标志位要等下一帧 traversal 才真正送到
     * WindowManager，注入的触摸有可能赶在标志生效前到达（真机日志实锤过：
     * input_interaction 落在自家 type=2038 悬浮窗上，80ms 后 wm_new_intent 拉起
     * MainActivity），所以不能只靠 FLAG_NOT_TOUCHABLE。
     */
    @Volatile
    var gestureActive = false

    fun setTouchBlocked(blocked: Boolean) {
        val apply = Runnable {
            val v = view ?: return@Runnable
            val l = lp ?: return@Runnable
            val flags = if (blocked) {
                l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            if (flags == l.flags) return@Runnable
            l.flags = flags
            try {
                wm?.updateViewLayout(v, l)
            } catch (_: Exception) {
            }
        }
        // 已经在主线程就同步执行，保证标志在派手势之前发出；否则才走 post。
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    /** 工具名 → 人话。跟 MainActivity.toolRunningText 一个口径，但悬浮窗不依赖 Activity。 */
    fun toolStatus(name: String): String = when (name) {
        "phone_read_screen" -> "正在读屏…"
        "phone_screenshot" -> "正在截图…"
        "phone_tap", "phone_tap_at" -> "正在点按…"
        "phone_long_press" -> "正在长按…"
        "phone_launch_app" -> "正在打开应用…"
        "phone_input" -> "正在输入…"
        "phone_press_key" -> "正在按键…"
        "phone_swipe" -> "正在滑动…"
        "fs_list" -> "正在列目录…"
        "fs_read" -> "正在读文件…"
        "fs_write" -> "正在写文件…"
        "fs_edit" -> "正在改文件…"
        "fs_search" -> "正在搜索…"
        "web_fetch" -> "正在抓网页…"
        "web_search" -> "正在联网查…"
        "memory_save" -> "正在记下…"
        "skill_load" -> "正在翻技能…"
        "pdf_write" -> "正在生成 PDF…"
        "pptx_write" -> "正在生成 PPT…"
        "docx_write" -> "正在生成 Word…"
        "task" -> "正在派子代理查…"
        else -> "正在执行…"
    }

    // ------------------------------------------------------------ 窗体

    private fun wanted(ctx: Context): Boolean =
        AgentSettings(ctx).floatOn && permitted(ctx)

    private fun show(ctx: Context, text: String) {
        lastText = text
        if (!wanted(ctx)) return
        val manager = wm ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        wm = manager
        val v = view
        if (v == null) {
            val fresh = build(ctx)
            view = fresh
            lp = params(ctx)
            try {
                manager.addView(fresh, lp)
            } catch (e: Exception) {
                // 偶发的 BadTokenException（权限刚被收走）不值得崩：静默退回
                view = null
                lp = null
                return
            }
        }
        label?.text = text
    }

    private fun remove() {
        lastText = ""
        val v = view ?: return
        view = null
        lp = null
        label = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun params(ctx: Context): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 默认钉在屏幕底部左侧：顶部是重灾区（搜索框/返回键/小窗标题栏都在那里），
            // 底部通常只有导航条，碰撞面小得多。用户拖过之后 lp.x/y 就是新位置，随拖随存。
            gravity = Gravity.BOTTOM or Gravity.START
            val d = ctx.resources.displayMetrics.density
            x = (20 * d).toInt()
            y = (16 * d).toInt()   // 离屏幕底边一点距离，不压导航手势条
        }

    /** 玻璃小胶囊：转圈 + 一句话。拖动跟点击共用一根手指——挪过就不算点击。 */
    private fun build(ctx: Context): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.solid(ctx, 0xF2FFFFFF.toInt(), 22, Ui.GLASS_LINE, 1)
        row.setPadding(Ui.dp(ctx, 13), Ui.dp(ctx, 8), Ui.dp(ctx, 13), Ui.dp(ctx, 8))

        val spinner = ProgressBar(ctx)
        spinner.indeterminateTintList = ColorStateList.valueOf(Ui.BRAND)
        row.addView(spinner, LinearLayout.LayoutParams(Ui.dp(ctx, 15), Ui.dp(ctx, 15)).apply {
            rightMargin = Ui.dp(ctx, 8)
        })

        val t = TextView(ctx)
        t.textSize = 12f
        t.setTextColor(Ui.INK)
        t.isSingleLine = true
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        label = t
        row.addView(t)

        // 点击回对话页；按住拖走。MOVE 超过 10px 判定为拖动，UP 时没拖过才算点击。
        row.setOnTouchListener { v, ev ->
            // 双保险二：Agent 手势期间吞掉一切触摸——这一下可能是注入的手势误落在
            // 胶囊上，绝不能触发跳回对话页（startActivity），也不能开始拖动。
            if (gestureActive) return@setOnTouchListener true
            val p = lp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = p.x; startY = p.y; dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) dragged = true
                    if (dragged) {
                        p.x = startX + dx
                        // gravity 是 BOTTOM：lp.y 从底边向上量，手指下移(dy>0)要减 y
                        p.y = startY - dy
                        try {
                            wm?.updateViewLayout(v, p)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        v.context.startActivity(
                            Intent(v.context, MainActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                )
                        )
                    }
                    true
                }
                else -> false
            }
        }
        return row
    }

    // 拖动手势的临时量（都在主线程摸，不用加锁）
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
}
