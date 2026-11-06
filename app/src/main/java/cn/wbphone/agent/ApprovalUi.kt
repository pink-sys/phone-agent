package cn.wbphone.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 审批闸门的 Android 落地：一个对话框 + 一条通知。
 *
 * 为什么两样都要：
 *  - 对话跑在后台线程上，用户往往已经把 App 切走了。只弹对话框，他根本看不到，120 秒后静默超时，
 *    任务莫名其妙失败，体验比不做闸门还差。
 *  - 只发通知，回到 App 就得自己找地方确认，多一步。
 *
 * 两者共用同一个 [Pending]：先到的那个生效，另一个自动收掉。
 */
object ApprovalUi : ApprovalPrompter {

    private const val CHANNEL_ID = "wb-phone-approval"
    private const val NOTIFY_ID = 4711

    private var appContext: Context? = null

    private val activityRef = AtomicReference<MainActivity?>(null)

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile private var pending: Pending? = null

    private class Pending(val request: ApprovalRequest) {
        val latch = CountDownLatch(1)
        private val decision = AtomicReference<ApprovalDecision?>(null)

        fun resolve(value: ApprovalDecision) {
            if (decision.compareAndSet(null, value)) latch.countDown()
        }

        fun current(): ApprovalDecision? = decision.get()
    }

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "操作确认",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "AI 要执行付款、输入密码这类动作时，先在这里问你一句"
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    /** Activity 回到前台：重新建立联系，并把还没答的请求补弹出来（悬浮卡或弹层）。 */
    fun attach(activity: MainActivity) {
        activityRef.set(activity)
        pending?.let { showPrompt(it) }
    }

    fun detach(activity: MainActivity) {
        activityRef.compareAndSet(activity, null)
        activity.dismissApproval()
    }

    /** 用户点了「停止」：把等待中的审批一并按拒绝收掉，否则后台线程会一直卡到超时。 */
    fun cancelPending() {
        pending?.resolve(ApprovalDecision.DENY)
    }

    fun isAwaiting(): Boolean = pending != null

    override fun ask(request: ApprovalRequest): ApprovalDecision {
        val p = Pending(request)
        pending = p

        postNotification(request)
        showPrompt(p)

        val answered = p.latch.await(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        pending = null

        cancelNotification()
        dismissPrompt()

        return if (!answered) ApprovalDecision.TIMEOUT else (p.current() ?: ApprovalDecision.DENY)
    }

    // ------------------------------------------------------------ 提示界面

    /**
     * 有悬浮窗权限 → 审批卡直接浮在**当前应用**（可能是微信/地图/任何正在干活的界面）
     * 上，用户点一下就回去了，不用切 App。
     * 没权限 → 退回月溯内的底部弹层（用户得切回来点，但至少通知还在）。
     */
    private fun showPrompt(p: Pending) {
        val activity = activityRef.get()
        val run = Runnable {
            if (pending !== p) return@Runnable
            val ctx = activity ?: appContext ?: return@Runnable
            val shown = runCatching {
                ApprovalOverlay.show(ctx, p.request) { decision -> p.resolve(decision) }
            }.getOrDefault(false)
            if (!shown && activity != null && !activity.isFinishing) {
                activity.showApproval(p.request) { decision -> p.resolve(decision) }
            }
        }
        if (activity != null) activity.runOnUiThread(run) else mainHandler.post(run)

        // 保险丝：卡片本该在主线程一挂就有。1.5 秒后它还是不在（权限被收、ROM 拦了
        // TYPE_APPLICATION_OVERLAY、挂载抛异常……），说明用户眼前什么都没有——
        // 这种时候宁可退回 App 内弹层，也不能让他对着一个不存在的确认框干等 120 秒。
        mainHandler.postDelayed({
            if (pending !== p) return@postDelayed
            if (ApprovalOverlay.isShowing()) return@postDelayed
            val act = activityRef.get() ?: return@postDelayed
            if (act.isFinishing) return@postDelayed
            act.runOnUiThread {
                if (pending === p && !ApprovalOverlay.isShowing()) {
                    android.util.Log.w("YueshuApproval", "overlay missing after 1.5s, fall back to in-app sheet")
                    act.showApproval(p.request) { decision -> p.resolve(decision) }
                }
            }
        }, 1500)
    }

    private fun dismissPrompt() {
        ApprovalOverlay.dismiss()
        val activity = activityRef.get() ?: return
        activity.runOnUiThread { activity.dismissApproval() }
    }

    // ------------------------------------------------------------ 通知

    private fun postNotification(request: ApprovalRequest) {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(ctx, 0, intent, flags)

        val line = notificationText(request)
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(if (request.critical) "AI 停下来了，这一步要你确认" else "AI 想执行一步操作")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)

        runCatching { nm.notify(NOTIFY_ID, builder.build()) }
    }

    private fun cancelNotification() {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(NOTIFY_ID) }
    }

    private fun notificationText(request: ApprovalRequest): String =
        buildString {
            append(request.reason)
            if (request.target.isNotEmpty()) append("：").append(request.target)
            append("（${request.timeoutSeconds} 秒内不回应按拒绝处理）")
        }
}
