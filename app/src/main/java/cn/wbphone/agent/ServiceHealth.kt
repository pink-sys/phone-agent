package cn.wbphone.agent

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * 无障碍到底还能不能用——两个判据，缺一不可。
 *
 * 1. 系统设置里的开关还开着吗（[enabled]）。关了就一定不能用，且应用无权自行打开。
 * 2. 服务实例还在吗（[connected]）。开关开着但实例为空，说明进程刚被回收、系统还没重新绑定，
 *    这时候点下去会失败，需要等几百毫秒或者提示用户。
 *
 * 只查第一个，会出现「显示已开启但点不动」；只查第二个，会在服务尚未绑定时报「权限没开」，
 * 把用户往设置页里赶——而那里明明是开的。
 */
object ServiceHealth {

    fun enabled(context: Context): Boolean {
        val expected = ComponentName(context, PhoneAccessibilityService::class.java)
        val raw = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        } ?: return false
        return raw.split(':').any {
            runCatching { ComponentName.unflattenFromString(it) }.getOrNull() == expected
        }
    }

    fun connected(): Boolean = PhoneAccessibilityService.instance != null

    fun healthy(context: Context): Boolean = enabled(context) && connected()
}
