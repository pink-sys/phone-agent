package cn.wbphone.agent

/**
 * 「无障碍被系统关掉之后怎么自己写回来」的决策逻辑，与 Android 完全隔离，可在 JVM 上测。
 *
 * 背景：无障碍开关的状态存在 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里。
 * 应用默认无权写它，但 `WRITE_SECURE_SETTINGS` 是一条 development 权限——
 * 用 adb `pm grant` 授权一次，应用就能像系统设置页那样把开关写回去。
 *
 * 两种失效形态，两种处理：
 * - **不在列表里**（系统设置显示「已关闭」）→ 把自己的条目追加回去，并打开总开关。
 * - **在列表里但没绑定**（系统设置显示「无法运行」）→ 先把自己摘出去、隔一秒再加回来，
 *   让系统重新走一遍绑定流程。单纯重写一遍列表，系统不会重新绑定。
 *
 * 思路参照 dsh-Go 的 `A11yKeeper`，实现独立编写：它们的取舍是「稳」——
 * 连续写失败就指数退避，最长十分钟一次，避免和系统或用户的意志反复拉扯。
 */
class KeeperPlan(private val pkg: String, private val cls: String) {

    enum class Step { NONE, ADD, REBIND }

    private val me = "$pkg/$cls"

    private var unboundSince = NEVER
    private var heldSince = NEVER
    private var writes = 0
    private var nextAt = 0L

    /**
     * 此刻该做什么。
     *
     * @param wanted 用户是否希望这个能力可用（应用内开关）。关掉之后不再碰系统设置——
     *   否则用户手动关掉无障碍，会被我们一直写回去，那叫流氓。
     * @param writable 是否已拿到 `WRITE_SECURE_SETTINGS`
     * @param list `ENABLED_ACCESSIBILITY_SERVICES` 的原文
     * @param connected 无障碍服务是否已绑定
     * @param now `SystemClock.elapsedRealtime()`
     */
    fun next(wanted: Boolean, writable: Boolean, list: String?, connected: Boolean, now: Long): Step {
        if (!wanted) {
            reset()
            return Step.NONE
        }
        if (connected) {
            unboundSince = NEVER
            if (heldSince == NEVER) heldSince = now
            // 稳定连接一段时间：之前那次丢失不是有人跟我们较劲，退避重来。
            if (now - heldSince >= STABLE_MS) {
                writes = 0
                nextAt = 0L
            }
            return Step.NONE
        }
        heldSince = NEVER
        if (!writable) {
            unboundSince = NEVER
            return Step.NONE
        }

        val step = if (!listed(list)) {
            Step.ADD
        } else {
            if (unboundSince == NEVER) unboundSince = now
            // 刚失去绑定：系统可能正在重新绑定，等十秒再说。
            if (now - unboundSince < UNBOUND_MS) return Step.NONE
            Step.REBIND
        }

        // 退避期内的重复检测直接放过，否则每 2 秒写一次系统设置，日志会炸。
        if (now < nextAt) return Step.NONE
        writes = (writes + 1).coerceAtMost(8)
        nextAt = now + backoff(writes)
        unboundSince = NEVER
        return step
    }

    fun listed(list: String?): Boolean = list.orEmpty().split(':').any(::ours)

    /** 把我们的条目追加到末尾；已经在里面就原样返回。别的应用一个都不能动。 */
    fun withMe(list: String?): String = when {
        listed(list) -> list.orEmpty()
        list.isNullOrEmpty() -> me
        else -> "$list:$me"
    }

    /** 摘掉我们的条目，其余原样保留。 */
    fun withoutMe(list: String?): String =
        list.orEmpty().split(':').filterNot(::ours).joinToString(":")

    private fun ours(entry: String): Boolean {
        val e = entry.trim()
        val slash = e.indexOf('/')
        if (slash <= 0 || e.substring(0, slash) != pkg) return false
        val c = e.substring(slash + 1)
        // 系统里可能写成全类名，也可能写成 ".PhoneAccessibilityService" 这种短形式
        return c == cls || (c.startsWith('.') && pkg + c == cls)
    }

    private fun reset() {
        unboundSince = NEVER
        heldSince = NEVER
        writes = 0
        nextAt = 0L
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** 列在名单里但十秒还没绑定：系统不会自己绑了，得我们来踢一脚。 */
        const val UNBOUND_MS = 10_000L

        /** 连续稳定绑定一分钟，就可以把之前的失败次数清零。 */
        const val STABLE_MS = 60_000L

        private const val FIRST_WAIT_MS = 5_000L
        private const val MAX_WAIT_MS = 10 * 60_000L

        /** 连续第 n 次写入后等多久：5s、10s、20s… 最多 10 分钟。 */
        fun backoff(n: Int): Long =
            minOf(FIRST_WAIT_MS shl (n - 1).coerceIn(0, 7), MAX_WAIT_MS)
    }
}
