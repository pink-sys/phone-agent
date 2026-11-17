package cn.wbphone.agent

/**
 * 「谁在用这块屏幕」的独占租约。
 *
 * 为什么必须有：会话是可以并行跑的（[AgentHost.ask] 只挡「同一个会话重复提交」），
 * 但手机只有一块屏幕。两个任务交错操作时坏得很难看：
 *
 *  - 序号表是 `PhoneToolbox` 上的字段，B 一开始跑就 `beginTask()` 清表，A 手里那串
 *    「第 3 项」当场失效，接着 A 按旧序号点下去就点到了别的东西；
 *  - 截图坐标系同理：A 的点是按 A 那张图换算的，B 一截图 `lastShot` 就换了，坐标全错；
 *  - 手势是往同一块屏幕上 dispatch 的，两边各点各的，最后落在哪个界面说不清。
 *
 * 这类错不是「慢一点」而是「做错事」——用户看到的是 agent 莫名其妙点了不该点的按钮，
 * 而且事后再怎么翻历史都看不出问题（每一步单独看都是合法的）。
 *
 * 粒度刻意定在「整个任务」而不是「一次工具调用」：一次动作依赖上一屏的观测
 * （读屏 → 算序号 → 点），中间只要被别人插一脚，那一步就已经错了。
 *
 * 只挡碰屏幕的工具。纯聊天、写工作台文件、搜索这些不需要租约，
 * 所以「开个新会话并行」依然成立——只是不能同时操作手机。
 */
object PhoneLease {

    private val lock = Any()

    /** 当前持有者（会话 id）。null = 手机空闲。 */
    private var owner: String? = null

    /** 谁拿着手机；空闲返回 null。 */
    fun holder(): String? = synchronized(lock) { owner }

    /**
     * 试着把手机判给 [id]。
     *
     * 空 id 直接放行：那是纯 JVM 测试或没有会话身份的调用方，
     * 强行收租只会让测试之间互相干扰。
     *
     * 不等：一等等几秒的话，界面上就是一段没解释的空白，用户只会以为卡死。
     * 拿不到就立刻说清楚，让人自己决定是停掉那边还是等会儿再来。
     */
    fun tryAcquire(id: String): Boolean {
        if (id.isEmpty()) return true
        synchronized(lock) {
            if (owner == null || owner == id) {
                owner = id
                return true
            }
            return false
        }
    }

    /** 交还租约。只有持有者本人能交还，别人的 release 不会误放掉正在跑的会话。 */
    fun release(id: String) {
        if (id.isEmpty()) return
        synchronized(lock) {
            if (owner == id) owner = null
        }
    }

    /** 测试用：把租约恢复成空闲。 */
    fun reset() {
        synchronized(lock) { owner = null }
    }
}
