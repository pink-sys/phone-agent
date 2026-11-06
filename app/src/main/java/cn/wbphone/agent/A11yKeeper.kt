package cn.wbphone.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 无障碍的自愈：开关被系统关掉之后，自己把它写回去。
 *
 * 为什么原来的保活做了还是掉：前台服务只能降低被杀概率，拦不住 **force stop**。
 * 被强停时系统会把无障碍开关一起摘掉（设置里变成「已关闭」），任何应用都没有办法阻止。
 * 但开关本身只是 `Settings.Secure` 里的一个字符串——拿到 `WRITE_SECURE_SETTINGS`
 * 就能像系统设置页那样把它写回来。这是 dsh-Go 真正在做的事。
 *
 * 这个权限是 development 级，普通安装拿不到，需要用户用一条 adb 命令授予一次：
 * `adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS`
 * 升级不丢，卸载重装才需要重来。
 *
 * 没有授权时这个类什么都不做，界面照旧提示用户去设置里打开。
 */
object A11yKeeper {

    /** 摘掉和写回之间留的间隔：系统必须看得到这两次变化才会重新绑定。 */
    private const val REBIND_GAP_MS = 1000L

    /** 两次检测的最小间隔。调用方可以每 2 秒喊一次，实际读设置不会那么频繁。 */
    private const val MIN_INTERVAL_MS = 1500L

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "a11y-keeper").apply { isDaemon = true }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private var plan: KeeperPlan? = null
    private var settings: AgentSettings? = null

    @Volatile private var lastCheckAt = 0L

    /** 有没有一次性授权。没有的话所有自动恢复都是空转。 */
    fun granted(context: Context): Boolean = try {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** 恢复成功时回调，用来刷新界面上的黄条和通知。 */
    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 看一眼要不要修，需要就在后台线程修。非阻塞，可以随便调。
     * @param force 无视节流（刚授权完、刚打开应用时用）
     */
    fun check(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCheckAt < MIN_INTERVAL_MS) return
        lastCheckAt = now
        val app = context.applicationContext
        worker.execute { runCatching { doCheck(app) } }
    }

    private fun doCheck(app: Context) {
        val p = plan ?: KeeperPlan(app.packageName, PhoneAccessibilityService::class.java.name)
            .also { plan = it }
        val s = settings ?: AgentSettings(app).also { settings = it }

        val wanted = s.a11yWanted
        val connected = ServiceHealth.connected()
        // 已经连着就不用管；没授权也不用管。两个判断都便宜，先做掉。
        val writable = wanted && !connected && granted(app)
        val list = if (writable) read(app) else null

        val step = p.next(wanted, writable, list, connected, SystemClock.elapsedRealtime())
        if (step == KeeperPlan.Step.NONE) return

        try {
            when (step) {
                KeeperPlan.Step.ADD -> write(app, p.withMe(list), enable = true)
                KeeperPlan.Step.REBIND -> {
                    write(app, p.withoutMe(list), enable = false)
                    SystemClock.sleep(REBIND_GAP_MS)
                    // 这一秒里用户可能关掉了开关、撤销了授权，或者系统自己绑上了
                    if (!s.a11yWanted || !granted(app) || ServiceHealth.connected()) return
                    write(app, p.withMe(read(app)), enable = true)
                }
                KeeperPlan.Step.NONE -> return
            }
            AgentHost.log("无障碍已自动写回（$step）")
            for (l in listeners) runCatching { l() }
        } catch (e: Exception) {
            AgentHost.log("自动写回失败：${e.message}")
        }
    }

    private fun read(app: Context): String? = try {
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    } catch (e: Exception) {
        null
    }

    private fun write(app: Context, list: String, enable: Boolean) {
        val ok = Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            list
        )
        if (!ok) throw IllegalStateException("系统拒绝了写入")
        if (enable) {
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /** 给界面看的授权方式，复制到剪贴板用。 */
    fun grantCommand(pkg: String): String =
        "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
}
