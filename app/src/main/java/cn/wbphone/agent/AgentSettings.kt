package cn.wbphone.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户配置。令牌不在这里——它在 Auth 的 Keystore 加密存储里。
 */
class AgentSettings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("wb_phone_agent", Context.MODE_PRIVATE)

    var model: String
        get() = sp.getString(KEY_MODEL, Config.MODEL) ?: Config.MODEL
        set(value) = sp.edit().putString(KEY_MODEL, value.trim()).apply()

    /** 上次登录的账号昵称，只用于界面显示，不是凭证。 */
    var lastAccountName: String
        get() = sp.getString(KEY_ACCOUNT, "") ?: ""
        set(value) = sp.edit().putString(KEY_ACCOUNT, value).apply()

    /** 保留的对话轮数，0 表示不限制（内存里，别设太大）。 */
    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /**
     * 任务结束后自动把对话页拉回前台。你在别的应用里交代的事，做完理应叫你回来看结果。
     * Android 10+ 对后台启动界面有限制，vivo 这类系统需要给「后台弹出界面」权限，
     * 没给时会退回一条通知，点一下也能回来。
     */
    var autoReturn: Boolean
        get() = sp.getBoolean(KEY_AUTO_RETURN, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_RETURN, value).apply()

    /**
     * 用户是否要这个能力。自动写回只看这个，不看系统里的开关——
     * 开关关了恰恰是要修的状态；用户主动关掉这个开关，我们才真的停手。
     */
    var a11yWanted: Boolean
        get() = sp.getBoolean(KEY_A11Y_WANTED, true)
        set(value) = sp.edit().putBoolean(KEY_A11Y_WANTED, value).apply()

    /**
     * 本地网关（给 dsh-Go 当 OpenAI 渠道用）是否随保活服务一起开着。
     * 默认开：只监听回环，留着不额外耗电，但 dsh-Go 随时能连上。
     */
    var gatewayOn: Boolean
        get() = sp.getBoolean(KEY_GATEWAY_ON, true)
        set(value) = sp.edit().putBoolean(KEY_GATEWAY_ON, value).apply()

    /**
     * 长期记忆：把工作台 memory.md 里记下的偏好/事实注入每次请求。
     * 默认开。关掉后记忆文件还在，只是不再往系统提示里放。
     */
    var memoryOn: Boolean
        get() = sp.getBoolean(KEY_MEMORY_ON, true)
        set(value) = sp.edit().putBoolean(KEY_MEMORY_ON, value).apply()

    /**
     * 技能包：把工作台 skills/ 里的技能清单注入系统提示，让模型按需 skill_load。
     * 默认开。关掉后索引不再注入，模型也就不知道该加载什么。
     */
    var skillsOn: Boolean
        get() = sp.getBoolean(KEY_SKILLS_ON, true)
        set(value) = sp.edit().putBoolean(KEY_SKILLS_ON, value).apply()

    /**
     * 被单独停用的技能名。
     *
     * 为什么是「禁用名单」而不是「启用名单」：技能是可以随时往 skills/ 里加的，
     * 用启用名单的话，新加的文件默认是关的，用户得先去设置里勾一下才能用。
     */
    var disabledSkills: Set<String>
        get() = (sp.getString(KEY_SKILLS_OFF, "") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        set(value) = sp.edit().putString(KEY_SKILLS_OFF, value.joinToString(",")).apply()

    /**
     * 悬浮状态窗：任务切到别的应用上时，小胶囊浮着显示它在干什么。
     * 默认开。系统「显示在应用上层」没授权时它自己空转，不会烦人。
     */
    var floatOn: Boolean
        get() = sp.getBoolean(KEY_FLOAT_ON, true)
        set(value) = sp.edit().putBoolean(KEY_FLOAT_ON, value).apply()

    /**
     * 语音朗读：每轮回答说完自动念出来。
     * **默认关**——办公室、地铁里自动出声是打扰；想听再点轮末的「朗读」。
     * 开了之后长回答会被截断（先说重点，细节留着自己看）。
     */
    var voiceOn: Boolean
        get() = sp.getBoolean(KEY_VOICE_ON, false)
        set(value) = sp.edit().putBoolean(KEY_VOICE_ON, value).apply()

    /**
     * 上次实测能通过上游渠道校验的请求体档位。
     * 存下来是为了冷启动时直接从这档开始——否则每次开应用都要先吃一发 11128 才知道该用哪档。
     */
    var gatewayLevel: Int
        get() = sp.getInt(KEY_GATEWAY_LEVEL, 0)
        set(value) = sp.edit().putInt(KEY_GATEWAY_LEVEL, value).apply()

    /**
     * 是否允许模型执行 shell 命令。
     *
     * **默认关**。这是唯一一个「模型能做的事超出沙箱」的开关——文件工具被锁在工作台目录里，
     * 而 shell 能跑任何 app 权限范围内的命令。默认关不是不信任模型，是不给一个还没被
     * 真实使用检验过的能力开放默认入口。
     */
    var shellOn: Boolean
        get() = sp.getBoolean(KEY_SHELL_ON, false)
        set(value) = sp.edit().putBoolean(KEY_SHELL_ON, value).apply()

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_ACCOUNT = "last_account"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_AUTO_RETURN = "auto_return"
        private const val KEY_A11Y_WANTED = "a11y_wanted"
        private const val KEY_GATEWAY_ON = "gateway_on"
        private const val KEY_MEMORY_ON = "memory_on"
        private const val KEY_GATEWAY_LEVEL = "gateway_level"
        private const val KEY_FLOAT_ON = "float_on"
        private const val KEY_SHELL_ON = "shell_on"
        private const val KEY_SKILLS_ON = "skills_on"
        private const val KEY_SKILLS_OFF = "skills_off"
        private const val KEY_VOICE_ON = "voice_on"
    }
}
