package cn.wbphone.agent

/**
 * 端点与运行时常量。刻意不引任何 Android API——这样这组常量能跟着协议层一起在 JVM 上编译、跑测试。
 */
object Config {
    const val BASE_URL = "https://www.workbuddy.cn"
    const val REQUEST_PATH = "/v2/chat/completions"
    const val MODEL = "hy3"
    // 48 而不是 24：用户反馈「动不动就停」。动作一轮、读屏一轮，复杂任务 24 轮仍会截断；
    // 真正防烧钱的是下面的卡死检测，不是压轮数。
    const val MAX_TURNS = 48

    /**
     * 到轮数上限还没收敛时，自动替用户说一句「继续」接着跑，最多续这个次数。
     * 之前是硬停让用户自己点「继续」——多数时候用户根本不在看，任务就晾在那了。
     */
    const val MAX_CONTINUES = 2

    /** 卡死检测：连续几轮执行完全相同的操作算一次打转（先提醒、再止损）。 */
    const val STALL_REPEAT_ROUNDS = 3

    /** 卡死检测：连续几轮所有工具调用都失败就止损。 */
    const val STALL_FAIL_ROUNDS = 5

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
