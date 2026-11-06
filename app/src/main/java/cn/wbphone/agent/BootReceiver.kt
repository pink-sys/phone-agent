package cn.wbphone.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机和升级之后，把无障碍和常驻服务重新拉起来。
 *
 * 这两件事恰好是「应用被停过」之后最需要补的：
 * - 升级安装后系统常常把无障碍服务解绑，用户不会知道，直到下一次用才发现点不动；
 * - 重启手机后，如果开关被系统关过，没人会想起来去设置里翻。
 *
 * 广播里不能做长活，这里只做三件轻事：初始化宿主、拉起常驻服务、看一眼要不要写回开关。
 * 写回失败不重试——常驻服务的 2 秒 tick 会接上。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> Unit
            else -> return
        }
        val app = context.applicationContext
        try {
            AgentHost.init(app)
        } catch (e: Exception) {
            // 初始化失败也要往下走，服务本身不需要宿主
        }
        KeepAliveService.ensureRunning(app)
        A11yKeeper.check(app, force = true)
    }
}
