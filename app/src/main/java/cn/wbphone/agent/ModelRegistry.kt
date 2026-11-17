package cn.wbphone.agent

/**
 * 可选模型清单。
 *
 * 这里列的是 WorkBuddy 主对话（agent 场景）里那个下拉框的内容——顺序、名字、倍率都照抄，
 * 因为用户看到的是同一个东西，两边不能对不上。`id` 是真正发给
 * `POST /v2/chat/completions` 的 `model` 字段。
 *
 * 刻意不引 Android API：清单和取值逻辑能跟着其他协议层文件一起在 JVM 上编译、跑断言。
 *
 * 倍率是相对基准价的计费系数，`x0.00` 表示当前免费。倍率只作展示，
 * 真实扣费以服务端返回的 usage 为准——手机上不做任何价格推算。
 */
object ModelRegistry {

    data class Option(
        /** 请求里 model 字段的值 */
        val id: String,
        /** 界面上显示的名字 */
        val label: String,
        /** 计费倍率，空串表示服务端没给 */
        val credit: String = "",
        /** 一句话说明，帮助选 */
        val note: String = "",
    ) {
        /** 「Hy4 flash · 免费」这种一行摘要，给列表和设置项用 */
        fun summary(): String {
            val c = when {
                credit.isEmpty() -> ""
                credit == "x0.00" -> "免费"
                else -> credit
            }
            return if (c.isEmpty()) label else "$label · $c"
        }
    }

    /**
     * 默认模型：桌面端当前默认就是它，且 11/16–11/30 限时免费（`x0.00` 来自活动折扣，不是原价）。
     * 活动结束后它会按原价计费，届时再看要不要换——所以这里的注释带了日期。
     */
    const val DEFAULT_ID = "hy4flash-free"

    /**
     * 顺序照抄桌面端选择器：先是 Auto 与三档通用档位，再是具体模型。
     * 只放能在 agent 场景用（`supportsToolCall = true`）的模型；纯补全、文生图那类不放进来。
     *
     * 数据来源是客户端本地缓存的整份 models 目录（`~/.workbuddy/local_storage`），
     * 60 个模型里筛出 craft 场景可用的这批。2026-11-16 这版的变化：
     *  - `hy3`、`hy4-preview` 下线（hy3 的免费活动 11-16 00:00 到期，桌面端已看不到）；
     *  - 换成 `hy4std` / `hy4flash-free` / `hy4flash` 三个变体；
     *  - 新增 `auto`（倍率浮动，服务端不返回固定倍率，所以 credit 留空）。
     */
    val OPTIONS: List<Option> = listOf(
        Option("auto", "Auto", "", "自动匹配最优模型，倍率随之浮动"),
        Option("fast-model", "快速", "x0.15", "优先速度，适合简单任务"),
        Option("balanced-model", "均衡", "x0.50", "速度与质量兼顾"),
        Option("deep-model", "极致", "x1.00", "优先深度与准确性"),
        Option("hy4std", "Hy4", "x0.29", "最新旗舰，Agent 与多模态专长"),
        Option("hy4flash-free", "Hy4 flash", "x0.00", "限时免费至 11/30，轻量日常主力"),
        Option("hy4flash", "Hy4 flash", "x0.03", "轻量高效，适合高频日常任务"),
        Option("deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", "x0.11", "1M 上下文，原生多模态"),
        Option("glm-5.3", "GLM-5.3", "x0.69", "旗舰，复杂长程任务"),
        Option("glm-5.3-flash", "GLM-5.3-Flash", "x0.08", "原生多模态，便宜"),
        Option("glm-5.2", "GLM-5.2", "x0.79", "1M 上下文，夜间 5 折"),
        Option("space-bunny", "Space-Bunny", "x0.08", "极快，原生多模态"),
        Option("minimax-m3.1-flash", "MiniMax-M3.1-Flash", "x0.08", "原生多模态"),
        Option("mimo-v2.6-pro", "MiMo-V2.6-Pro", "x0.13", "长程复杂工作流"),
        Option("step-5-preview", "Step-5-Preview", "x0.33", "新一代旗舰基模"),
        Option("deepseek-v4-pro", "DeepSeek-V4-Pro", "x0.51", "旗舰，1M 上下文"),
        Option("kimi-k2.8-preview", "Kimi-K2.8-Preview", "x0.77", "长程自主任务"),
        Option("kimi-k3.1-pro", "Kimi-K3.1-Pro", "x0.97", "Kimi 旗舰"),
        Option("kimi-k3-1", "Kimi-K3", "x1.62", "最贵一档，前端与科研突出"),
    )

    fun default(): Option = OPTIONS.first { it.id == DEFAULT_ID }

    fun find(id: String): Option? = OPTIONS.firstOrNull { it.id == id }

    /**
     * 存储值可能来自旧版本、手改、或者服务端下线了某个模型，
     * 认不出来就落回默认，绝不让一个野字符串把请求打废。
     */
    fun resolve(id: String?): Option = find(id ?: "") ?: default()

    fun labelOf(id: String?): String = resolve(id).label

    fun indexOf(id: String?): Int {
        val i = OPTIONS.indexOfFirst { it.id == (id ?: "") }
        return if (i >= 0) i else OPTIONS.indexOfFirst { it.id == DEFAULT_ID }
    }
}
