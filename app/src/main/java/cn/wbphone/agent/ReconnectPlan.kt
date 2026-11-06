package cn.wbphone.agent

/**
 * 没有写设置权限时，掉了之后要不要打扰用户。
 *
 * 有 `WRITE_SECURE_SETTINGS` 时这整块用不到——[A11yKeeper] 两秒内就自己修好了，
 * 用户根本不知道断过。只有在没授权的手机上，断了没人修，才需要叫一声。
 *
 * 但"叫一声"很容易变成噪音：无障碍在服务刚启动、系统正在绑定的一瞬间本来就是未就绪的。
 * 所以要满足三个条件才提醒：用户想要这个功能、确实没权限修、且已经持续掉线超过宽限期。
 * 修好之后立刻清零，下次再掉还能正常提醒一次。
 *
 * 纯逻辑，不引 Android API，能在 JVM 上逐条断言。
 */
class ReconnectPlan(
    /** 掉线多久才算"真掉了"。系统重新绑定的空窗期常有一两秒，别在这时候弹通知。 */
    private val graceMs: Long = 8_000L,
    /** 同一个故障最多多久提醒一次，避免反复打扰。 */
    private val nagIntervalMs: Long = 30 * 60 * 1000L,
) {

    /** -1 表示"还没开始计时"。不能用 0：开机附近 SystemClock 就是从 0 开始的。 */
    private var badSince = -1L
    private var lastNagAt = -1L

    /**
     * @param wanted 用户是否要这个能力（关掉就不再提醒，别跟用户的意愿对着干）
     * @param granted 是否拿到了写设置权限（拿到了就不需要人管）
     * @param enabled 无障碍开关当前是否真的开着
     * @param now 当前时刻，测试里注入，生产用 SystemClock.elapsedRealtime()
     */
    fun shouldNotify(wanted: Boolean, granted: Boolean, enabled: Boolean, now: Long): Boolean {
        if (!wanted || granted) {
            badSince = -1L
            return false
        }
        if (enabled) {
            // 恢复了：计时和节流一起清，下次再掉还应该提醒。
            badSince = -1L
            return false
        }
        if (badSince < 0L) badSince = now
        if (now - badSince < graceMs) return false
        if (lastNagAt >= 0L && now - lastNagAt < nagIntervalMs) return false
        lastNagAt = now
        return true
    }

    fun reset() {
        badSince = -1L
        lastNagAt = -1L
    }
}
