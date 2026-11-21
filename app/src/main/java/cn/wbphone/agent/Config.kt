package cn.wbphone.agent

/**
 * 端点与运行时常量。刻意不引任何 Android API——这样这组常量能跟着协议层一起在 JVM 上编译、跑测试。
 */
object Config {
    const val BASE_URL = "https://www.workbuddy.cn"
    const val REQUEST_PATH = "/v2/chat/completions"
    const val MODEL = "hy4flash-free"
    // 48 而不是 24：用户反馈「动不动就停」。动作一轮、读屏一轮，复杂任务 24 轮仍会截断；
    // 真正防烧钱的是下面的卡死检测，不是压轮数。
    const val MAX_TURNS = 48

    /**
     * 到轮数上限还没收敛时，自动替用户说一句「继续」接着跑，最多续这个次数。
     * 之前是硬停让用户自己点「继续」——多数时候用户根本不在看，任务就晾在那了。
     */
    const val MAX_CONTINUES = 2

    /**
     * 空回复兜底的重试上限。模型偶尔会交白卷（无文字、无工具调用），直接收尾的表象
     * 是「已完成」却一句话都没有、也没有运行指示——用户只能手动发「继续」。
     * 交给循环自动推一把，最多这个次数；连续正常轮次后计数清零。
     */
    const val MAX_EMPTY_RETRIES = 2

    /** 卡死检测：连续几轮执行完全相同的操作算一次打转（先提醒、再止损）。 */
    const val STALL_REPEAT_ROUNDS = 3

    /** 卡死检测：连续几个屏幕动作执行失败就止损（按调用累计，只被成功的动作清零）。 */
    const val STALL_FAIL_CALLS = 6

    /** 卡死检测：连续几轮「有动作但界面零变化」（点了没生效）就先提醒再止损。 */
    const val STALL_INEFFECTIVE_ROUNDS = 3

    /**
     * 采样参数。之前什么都不传、走服务端默认，结果是同一个模型答得比在 dsh-Go 里啰嗦发散。
     *
     * - TEMPERATURE 0.2：操作类任务的每一步都是**有唯一正确答案**的判断（点哪个元素、要不要等），
     *   高温只带来随机跑偏。dsh-Go 那边也是显式低温。
     * - TOP_P 0.9：配合低温收窄候选，避免偶发的离谱选择。
     * - MAX_TOKENS 8192：给足输出空间。工具调用参数偶尔会很长，掐太紧会把 JSON 截断成非法。
     *
     * 这些参数会被网关降档逻辑处理：档位 1 原样保留，2 档起会被摘掉（见 GatewayPlan.prepare），
     * 所以就算上游不认，也不会因此报错。
     */
    const val TEMPERATURE = 0.2
    const val TOP_P = 0.9
    const val MAX_TOKENS = 8192

    /** 默认思考深度：标准（不额外指定，走服务端默认）。见 [Think]。 */
    const val THINK_LEVEL = Think.STD

    /**
     * 讯飞「语音听写（流式版）」凭据（https://console.xfyun.cn → 语音听写）。
     * 语音输入不再依赖设备的 RecognitionService——这台 vivo 什么识别服务都没有，
     * 系统那条路彻底断的；自己录音走听写 API，识别能力在云端，装什么应用都无关。
     *
     * 凭据**不进仓库**：写在仓库根目录的 secrets.properties（已 gitignore），
     * 由 tools/build_apk.py 构建时生成 Secrets.kt。三个值任一为空 = 语音听写关闭，
     * 界面上会提示「未配置听写 API · 暂时用键盘话筒」，其余功能不受影响。
     */
    const val IAT_APP_ID = Secrets.IAT_APP_ID
    const val IAT_API_KEY = Secrets.IAT_API_KEY
    const val IAT_API_SECRET = Secrets.IAT_API_SECRET
}

/**
 * 思考深度档位。三档对应用户在设置里能选的三件事：想得少 / 不想指定 / 想得细。
 *
 * 为什么值得做成开关：实测首字延迟 ≈ 1.35s + 0.0191 × 思考 token（R²=0.990），
 * 也就是每 100 个思考 token 约 1.9 秒。输入 9k token 的预填充只值 0.27 秒——
 * 真正让回答慢的是模型在「想」，不是提示词太长。「回答慢」这件事唯一有效的旋钮就在这里。
 *
 * 实现走 OpenAI 形态的 `reasoning_effort`（low / high）。不保证每家上游都认，
 * 所以客户端带着它发、被明确拒了就自动摘掉重试（见 CloudClient.stream），
 * 并把服务端原话记下来——下次换模型要不要再试，看那句话就知道。
 */
object Think {
    /** 想得少：最快。适合「打开设置」这类步骤明确的任务。 */
    const val LOW = 0

    /** 标准：不传参数，走服务端默认。不改变任何既有行为。 */
    const val STD = 1

    /** 想得细：更慢但更稳。留给需要规划的长任务。 */
    const val HIGH = 2

    val LEVELS = intArrayOf(LOW, STD, HIGH)

    fun label(level: Int): String = when (level) {
        LOW -> "低"
        HIGH -> "高"
        else -> "标准"
    }

    fun note(level: Int): String = when (level) {
        LOW -> "省：实测同一道难题，思考量约为标准的 1/4，整轮快一半以上"
        HIGH -> "实测和标准几乎没差别（5 次对照），想更细的效果没出现"
        else -> "不额外指定，用服务端默认——不确定就选这个"
    }

    /**
     * 请求体里 `reasoning_effort` 该填什么。null = 这一档不传参数。
     *
     * 只给低/高两档传值，是因为「标准」必须保持和旧版本逐字节一致的请求体——
     * 否则升级后所有老用户的请求都会变，缓存前缀跟着作废，反而更慢。
     */
    fun effort(level: Int): String? = when (level) {
        LOW -> "low"
        HIGH -> "high"
        else -> null
    }

    /** 把回传的 effort 值翻回中文，日志和流水账里用。 */
    fun labelOfEffort(effort: String?): String = when (effort) {
        "low" -> "低"
        "high" -> "高"
        else -> "标准"
    }

    fun clamp(level: Int): Int = if (level in LEVELS) level else STD
}
