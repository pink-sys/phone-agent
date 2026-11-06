package cn.wbphone.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * 一条常驻通知，换一个不容易被回收的进程。
 *
 * 为什么需要它：无障碍服务被关掉，绝大多数时候不是"权限自己失效"，而是应用进程被系统回收后，
 * 又在某个环节被强停（比如从最近任务划掉，很多国产 ROM 等同于 force stop）。
 * 有前台服务的进程在内存回收队列里排在最后，被划掉时也不容易连服务一起停。
 *
 * 这条通知必须常驻，不能做成"可以划掉"——能让用户划走的通知没有保活意义。
 * 所以它做得尽量安静：低优先级通道、不响铃、不弹窗、不可展开。
 */
class KeepAliveService : Service() {

    private val ticker = Handler(Looper.getMainLooper())

    /** 每 2 秒看一眼无障碍还在不在。真正读写设置的动作在 A11yKeeper 的线程里，这里只负责叫醒它。 */
    private val tick = object : Runnable {
        override fun run() {
            try {
                A11yKeeper.check(this@KeepAliveService)
                // 自愈失败（没授权）才轮到提醒。有权限时上面那行两秒内就修好了，
                // 下面这个判断一次都不会成立。
                if (nag.shouldNotify(
                        wanted = appSettings.a11yWanted,
                        granted = A11yKeeper.granted(this@KeepAliveService),
                        enabled = ServiceHealth.enabled(this@KeepAliveService),
                        now = SystemClock.elapsedRealtime(),
                    )
                ) {
                    noticeReconnect(this@KeepAliveService)
                }
            } catch (e: Exception) {
                // 守护线程不该把服务带崩——服务崩几次，系统会把无障碍一起禁用
            }
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private val restored = { refresh(this) }

    /** 没授权时要不要叫人。授权过的手机上它永远不会触发。 */
    private val nag = ReconnectPlan()

    private val appSettings by lazy { AgentSettings(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        start()
        A11yKeeper.addListener(restored)
        ticker.post(tick)
        startGatewayIfWanted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        start()
        // 被杀后让系统再拉一次。force stop 除外——那种情况谁都拦不住。
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ticker.removeCallbacks(tick)
        A11yKeeper.removeListener(restored)
        LocalGateway.stop()
        super.onDestroy()
    }

    /** 本地网关跟保活服务同生共死：它要给 dsh-Go 用，就得跟无障碍一样常驻。 */
    private fun startGatewayIfWanted() {
        if (!appSettings.gatewayOn) return
        try {
            LocalGateway.start(this)
        } catch (e: Exception) {
            // 起不来就让设置页显示原因，别把服务带崩
        }
    }

    private fun start() {
        // 连通知构建都要包住：这里抛异常，服务会崩，而服务崩几次系统就会把它连同无障碍一起禁用——
        // 恰是我们要防的那件事。
        val notification = try {
            build(this, ServiceHealth.enabled(this))
        } catch (e: Exception) {
            null
        }
        if (notification == null) return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFY_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+ 在后台启动前台服务会抛异常。这里吞掉：没有常驻通知也能用，
            // 只是被回收的概率高一些，不该因此让整个应用挂掉。
            AgentHost.log("常驻通知没起来：${e.message}")
        }
    }

    companion object {
        @Volatile
        private var running = false

        private const val CHANNEL_ID = "wb-phone-keepalive"
        private const val NOTIFY_ID = 4700
        private const val EVENT_CHANNEL_ID = "wb-phone-events"
        private const val EVENT_NOTIFY_ID = 4801
        private const val ALERT_CHANNEL_ID = "wb-phone-alert"
        private const val ALERT_NOTIFY_ID = 4901

        /** 检查间隔。读的是内存里的设置字符串，代价可以忽略。 */
        private const val TICK_MS = 2000L

        fun isRunning(): Boolean = running

        fun ensureRunning(context: Context) {
            if (running) {
                refresh(context)
                return
            }
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                AgentHost.log("启动常驻服务失败：${e.message}")
            }
        }

        /** 无障碍状态变了：把通知文字改掉，别让用户看着"已连接"其实早断了。 */
        fun refresh(context: Context) {
            if (!running) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val on = ServiceHealth.enabled(context)
            runCatching { nm.notify(NOTIFY_ID, build(context, on)) }
            // 恢复了就把掉线提醒收掉，别留一条"被关掉了"在那儿误导人。
            if (on) runCatching { nm.cancel(ALERT_NOTIFY_ID) }
        }

        /**
         * 开关被关掉、而且我们没权限自己写回去——这时候只能叫人。
         *
         * 点通知直接落到本应用的无障碍详情页：那一页只有一个开关，点一下就恢复，
         * 不用在几十个应用里找。恢复后 [refresh] 会把这条提醒收掉。
         */
        fun noticeReconnect(context: Context) {
            try {
                ensureAlertChannel(context)
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 0
                ) return
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val open = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_A11Y, true)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                val pi = PendingIntent.getActivity(context, 3, open, flags)

                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, ALERT_CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }
                nm.notify(ALERT_NOTIFY_ID, builder
                    .setSmallIcon(R.drawable.ic_agent)
                    .setContentTitle("无障碍被关掉了")
                    .setContentText("点这里打开开关，助手才能继续操作手机")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build())
                AgentHost.log("无障碍掉了且无法自动恢复，已提醒用户")
            } catch (e: Exception) {
                AgentHost.log("掉线提醒没发出去：${e.message}")
            }
        }

        private fun ensureAlertChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "无障碍掉线提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "无障碍开关被系统关掉时提醒你打开"
                    setShowBadge(true)
                }
            )
        }

        /**
         * 任务在后台跑完了，而自动返回没把界面拉起来（通常是没给「后台弹出界面」权限）。
         * 这条是兜底：点一下回对话页看结果。
         */
        fun taskDone(context: Context, summary: String) {
            try {
                ensureEventChannel(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != 0
                ) return

                val open = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                val pi = PendingIntent.getActivity(context, 1, open, flags)

                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, EVENT_CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }
                val text = summary.replace("\n", " ").take(80)
                nm.notify(EVENT_NOTIFY_ID, builder
                    .setSmallIcon(R.drawable.ic_agent)
                    .setContentTitle("任务完成，回来看看结果")
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .build())
            } catch (e: Exception) {
                AgentHost.log("完成通知没发出去：${e.message}")
            }
        }

        private fun ensureEventChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(EVENT_CHANNEL_ID) != null) return
            val channel = NotificationChannel(EVENT_CHANNEL_ID, "任务结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "任务在你离开时跑完，用通知叫你回来看结果"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        private fun build(context: Context, accessibilityOn: Boolean): Notification {
            ensureChannel(context)

            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(context, 0, open, flags)

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            return builder
                .setSmallIcon(R.drawable.ic_agent)
                .setContentTitle("月溯")
                .setContentText(if (accessibilityOn) "无障碍已连接，随时可以交代任务" else "无障碍已关闭，点这里重新开启")
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, "常驻运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持助手在线，避免系统把无障碍服务一起关掉"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
