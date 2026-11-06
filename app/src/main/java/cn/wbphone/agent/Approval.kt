package cn.wbphone.agent

import org.json.JSONObject

/**
 * 审批闸门。
 *
 * 提示词里写「付款前要停下来问用户」是靠不住的：模型会忘、会被屏幕上的文字带偏、
 * 也会为了"完成任务"给自己找理由。所以真正的防线必须在执行层——
 * 动作发出之前，由手机自己判断这一次要不要人点头。
 *
 * 三条原则：
 *  1. **判定只看事实**：动作类型 + 目标元素文本 + 真实屏幕内容。模型说了什么不算数。
 *  2. **拦截发生在执行前**：不是事后告警，是动作根本没发出去。
 *  3. **拒绝后不可绕过**：模型拿到的是一句话，换坐标点按、拆成几步都还会再被拦。
 *
 * 本文件刻意不引任何 Android API，这样规则和闸门本身能在 JVM 上跑测试（见 tools/test_agent.py）。
 * 真正弹窗/发通知的部分在 ApprovalUi.kt。
 */

// ---------------------------------------------------------------- 屏幕事实

/**
 * 判定风险时需要的屏幕信息。由无障碍树提取，不看截图——
 * 截图是给模型看的，这里是给规则看的，两者互不依赖。
 */
data class ScreenFacts(
    val pkg: String = "",
    val texts: List<String> = emptyList(),
    /** 当前焦点是否落在密码输入框上（isPassword）。 */
    val passwordFocused: Boolean = false,
) {
    fun blob(): String = texts.joinToString(" / ")

    val isEmpty: Boolean get() = pkg.isEmpty() && texts.isEmpty()
}

object ScreenFactsReader {

    /** 从 readScreen() 的返回里提取。结构不认识时返回空事实（宁可漏判也不崩）。 */
    fun fromPayload(payload: JSONObject?): ScreenFacts {
        if (payload == null) return ScreenFacts()
        val pkg = payload.optJSONObject("screen")?.optString("package").orEmpty()
        val texts = ArrayList<String>()
        var passwordFocused = false

        fun walk(node: JSONObject?, depth: Int) {
            if (node == null || depth > 40 || texts.size >= 240) return
            if (node.optBoolean("password") && node.optBoolean("focused")) passwordFocused = true
            val text = node.optString("text").trim()
            if (text.isNotEmpty()) {
                texts.add(text)
            } else {
                val desc = node.optString("desc").trim()
                if (desc.isNotEmpty()) texts.add(desc)
            }
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) walk(children.optJSONObject(i), depth + 1)
        }

        walk(payload.optJSONObject("tree"), 0)
        return ScreenFacts(pkg, texts, passwordFocused)
    }
}

// ---------------------------------------------------------------- 风险判定

enum class Risk {
    /** 放行，不问。 */
    NONE,

    /** 值得让用户看一眼，可以在设置里降低打扰。 */
    SENSITIVE,

    /** 花钱、给别人钱、动密码、毁数据。永远要问。 */
    CRITICAL,
}

/**
 * 一次命中的结果。
 * @param rule 规则标识，用于「本任务内允许同一位置」的去重与日志
 * @param reason 给用户看的一句话，说明为什么拦
 * @param target 给用户看的目标描述；涉及密码/验证码时已脱敏
 */
data class RiskHit(
    val risk: Risk,
    val rule: String,
    val reason: String,
    val target: String,
) {
    val blocked: Boolean get() = risk != Risk.NONE
}

/**
 * 规则表。
 *
 * 全部是「动作 + 目标文本 + 屏幕文本」的字符串匹配，不用模型、不联网、不随机。
 * 好处是可解释、可测试、误判时能一眼看出命中了哪条词。
 * 代价是词表要养——所以集中放在这里，配一句话说明为什么加。
 */
object RiskRules {

    /**
     * 会动到钱的动作。
     * 刻意不收裸的「支付」「支付宝」：那会命中"支付宝"图标和"付款方式"这类无害入口，
     * 让用户学会无脑点允许——那闸门就白做了。
     */
    private val PAYMENT_WORDS = listOf(
        "付款", "确认支付", "立即支付", "去支付", "立即付款", "确认付款",
        "转账", "确认转账", "收款码", "扣款", "免密", "提现", "充值",
        "打赏", "结算", "提交订单", "确认订单", "立即购买", "立即抢购",
        "开通会员", "自动续费", "立即续费",
    )

    /** 操作不可逆的动作。 */
    private val DESTRUCTIVE_WORDS = listOf(
        "删除", "清空", "注销", "解绑", "卸载", "恢复出厂", "格式化",
        "冻结", "关闭账户", "移除设备", "永久删除", "退出登录", "停用",
    )

    /** 涉及凭证的动作。 */
    private val SECRET_WORDS = listOf(
        "密码", "验证码", "校验码", "动态码", "支付密码", "短信验证", "PIN",
    )

    /** 金额形态：¥128 / 128.00 元 / $9.9 */
    private val AMOUNT = Regex("[¥￥$]\\s*\\d|\\d+(\\.\\d{1,2})?\\s*元")

    /**
     * 支付类应用。前缀匹配，覆盖各家的分包（com.tencent.mm.plugin 之类）。
     * 这些应用里「按坐标点按」等于闭着眼点，风险比别处高。
     */
    private val PAY_APP_HINTS = listOf(
        "com.tencent.mm",              // 微信（微信支付）
        "com.eg.android.alipay",       // 支付宝
        "com.unionpay",                // 云闪付
        "com.icbc", "com.chinamworld", // 工行 / 中行
        "com.cmbchina", "cmb.pb",      // 招行
        "com.bankcomm", "com.spdb",    // 交行 / 浦发
        "com.android.bankabc", "com.boc.bocsoft", "com.ccb",
        "com.tencent.mobileqq",        // QQ（QQ 钱包）
        "com.jd.jrapp",                // 京东金融
        "com.antfortune.wealth",       // 蚂蚁财富
    )

    fun isPaymentApp(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val lower = pkg.lowercase()
        return PAY_APP_HINTS.any { lower.startsWith(it) }
    }

    /**
     * 判定一次动作。
     *
     * @param action 工具名
     * @param target 目标元素的文本（点按）或空
     * @param input 要输入的内容（仅 phone_input）
     * @param facts 当前屏幕事实
     * @param coordinate 是否按坐标点按（无法预知目标）
     */
    fun assess(
        action: String,
        target: String,
        input: String,
        facts: ScreenFacts,
        coordinate: Boolean = false,
    ): RiskHit = when (action) {
        // 只读、移动、导航：不改变任何状态，永远放行
        "phone_read_screen", "phone_screenshot", "phone_status",
        "phone_swipe", "phone_press_key", "phone_launch_app" -> clear()

        "phone_input" -> assessInput(input, facts)

        "phone_tap_at" -> assessCoordinateTap(facts)

        "phone_tap" -> assessTap(target, facts)

        else -> clear()
    }

    private fun assessInput(input: String, facts: ScreenFacts): RiskHit {
        if (facts.passwordFocused) {
            return RiskHit(
                Risk.CRITICAL,
                "input.password",
                "焦点在密码输入框上，这次输入可能是密码",
                mask(input),
            )
        }
        if (input.isEmpty()) return clear()

        val trimmed = input.trim()
        val shortDigits = trimmed.length in 4..8 && trimmed.all { it.isDigit() }
        val blob = facts.blob()

        if (shortDigits && containsAny(blob, SECRET_WORDS)) {
            return RiskHit(
                Risk.CRITICAL,
                "input.otp",
                "屏幕正在要验证码，而这次输入是一串短数字",
                mask(input),
            )
        }
        if (containsAny(trimmed, SECRET_WORDS)) {
            return RiskHit(
                Risk.SENSITIVE,
                "input.secret",
                "输入内容里提到了密码或验证码",
                mask(input),
            )
        }
        if (containsAny(blob, PAYMENT_WORDS) && (AMOUNT.containsMatchIn(trimmed) || shortDigits)) {
            return RiskHit(
                Risk.SENSITIVE,
                "input.payment",
                "当前在付款流程里，而这次输入像是金额或口令",
                mask(input),
            )
        }
        return clear()
    }

    private fun assessTap(target: String, facts: ScreenFacts): RiskHit {
        // 焦点在密码框上时，点数字键盘和打字是一回事。
        // 不加这条，模型被 input 拦住后会改用 phone_tap 一个个点键盘——拦了个寂寞。
        if (facts.passwordFocused) {
            return RiskHit(
                Risk.SENSITIVE,
                "tap.on_password",
                "焦点在密码输入框上，这次点按可能是在输入密码",
                target.ifEmpty { "无文本元素" },
            )
        }

        // 无障碍给的标签有时只是类名（<Button>），那等于没有标签。
        val blind = target.isEmpty() || target.startsWith("<")

        if (!blind) {
            if (containsAny(target, PAYMENT_WORDS)) {
                return RiskHit(Risk.CRITICAL, "tap.payment", "点的是付款/转账类按钮", target)
            }
            if (containsAny(target, DESTRUCTIVE_WORDS)) {
                return RiskHit(Risk.CRITICAL, "tap.delete", "点的是删除/注销类按钮，做了不好撤回", target)
            }
            if (containsAny(target, SECRET_WORDS)) {
                return RiskHit(Risk.SENSITIVE, "tap.secret", "点上像是密码或验证码入口", target)
            }
            if (AMOUNT.containsMatchIn(target)) {
                return RiskHit(Risk.SENSITIVE, "tap.amount", "点的这个位置上带着金额", target)
            }
            // 有明确文本且不命中词表 —— 就是普通按钮，放行。
            // 不要在这里补一条"屏幕在付款页所以也拦"：那会把「取消」「返回」一起拦下来，
            // 恰恰挡住了用户最想做的那个动作。
            return clear()
        }

        // 没有文本可依，只能看整屏。自绘界面里"看不见的按钮"可能正好是付款键。
        if (isPaymentApp(facts.pkg) && containsAny(facts.blob(), PAYMENT_WORDS)) {
            return RiskHit(
                Risk.SENSITIVE,
                "tap.payment_screen",
                "在支付类应用里，屏幕上出现付款字样，而这个元素没有可读文本",
                "无文本元素",
            )
        }
        return clear()
    }

    /**
     * 坐标点按：不知道点的是什么，只能看屏幕。
     * 支付类应用里尤其危险——微信/支付宝的界面全是自绘，一个坐标可能落在"转账"上。
     */
    private fun assessCoordinateTap(facts: ScreenFacts): RiskHit {
        val blob = facts.blob()
        val moneyOnScreen = containsAny(blob, PAYMENT_WORDS) || AMOUNT.containsMatchIn(blob)

        if (isPaymentApp(facts.pkg)) {
            return if (moneyOnScreen) {
                RiskHit(
                    Risk.CRITICAL,
                    "tap_at.payment_screen",
                    "支付类应用 + 屏幕上出现金额或付款字样，按坐标点下去可能直接付钱",
                    "坐标点按",
                )
            } else {
                RiskHit(
                    Risk.SENSITIVE,
                    "tap_at.payment_app",
                    "在支付类应用里按坐标点按，无法预知点到什么东西",
                    "坐标点按",
                )
            }
        }
        if (moneyOnScreen) {
            return RiskHit(
                Risk.SENSITIVE,
                "tap_at.money_screen",
                "屏幕上出现金额或付款字样，坐标点按无法预知目标",
                "坐标点按",
            )
        }
        return clear()
    }

    // ------------------------------------------------------------ 小工具

    private fun clear() = RiskHit(Risk.NONE, "", "", "")

    private fun containsAny(haystack: String, needles: List<String>): Boolean {
        if (haystack.isEmpty()) return false
        return needles.any { haystack.contains(it, ignoreCase = true) }
    }

    /** 密码和验证码绝不进日志、绝不进对话记录，只留长度。 */
    private fun mask(secret: String): String =
        if (secret.isEmpty()) "" else "••••（${secret.length} 字符，已隐藏）"
}

// ---------------------------------------------------------------- 闸门

data class ApprovalRequest(
    val id: Long,
    val action: String,
    val rule: String,
    val reason: String,
    val target: String,
    val critical: Boolean,
    val timeoutSeconds: Int,
)

enum class ApprovalDecision {
    /** 只放行这一次。 */
    ALLOW_ONCE,

    /** 本任务内，同一规则 + 同一目标不再问。 */
    ALLOW_TASK,

    DENY,

    /** 等太久没人理，按拒绝处理。 */
    TIMEOUT,
}

/** 让用户拿主意的地方。Android 侧是对话框 + 通知，测试里是自动应答。 */
interface ApprovalPrompter {
    fun ask(request: ApprovalRequest): ApprovalDecision
}

data class ApprovalPolicy(
    /** SENSITIVE 级是否也拦。关掉只拦 CRITICAL——CRITICAL 永远拦，没有开关。 */
    val askOnSensitive: Boolean = true,
    val timeoutSeconds: Int = 120,
)

/**
 * 闸门本体。
 *
 * `guard()` 返回 null 表示放行，返回一段 JSON 表示已拒绝——那段 JSON 会原样回到模型的
 * tool 结果里，所以措辞要能止住它重试的念头。
 */
class ApprovalGate(
    private val prompter: ApprovalPrompter,
    private val policy: ApprovalPolicy = ApprovalPolicy(),
    private val log: (String) -> Unit = {},
) {

    /**
     * 「本任务内允许」的键是 `规则|目标文本`，不是规则本身。
     * 只按规则去重会出事：用户允许了无毒的"付款方式"，同一规则下"确认支付"就跟着免检了。
     */
    private val allowedKeys = HashSet<String>()

    private var seq = 0L

    @Volatile var awaiting: ApprovalRequest? = null
        private set

    var allowedCount = 0
        private set
    var deniedCount = 0
        private set

    /**
     * 连续被拒到第 3 次就该收手。
     *
     * 只靠拒绝提示里的文字劝模型「别重试」是不够的——它有的是耐心，
     * 换个说法、换个工具、拆成几步，总有一遍能试到你烦。这里给它一个硬边界。
     */
    val shouldAbort: Boolean get() = deniedCount >= MAX_DENIALS

    /** 每个任务开始时清空，避免上一轮的授权漏到下一轮。 */
    fun beginTask() {
        synchronized(allowedKeys) { allowedKeys.clear() }
        allowedCount = 0
        deniedCount = 0
    }

    /**
     * 判定 + 必要时问人。
     * @return null = 放行；否则是给模型看的拒绝说明
     */
    fun guard(
        action: String,
        target: String,
        input: String,
        facts: ScreenFacts,
        coordinate: Boolean = false,
    ): String? {
        val hit = RiskRules.assess(action, target, input, facts, coordinate)
        if (!hit.blocked) return null
        if (hit.risk == Risk.SENSITIVE && !policy.askOnSensitive) return null

        val key = "${hit.rule}|${hit.target.take(32)}"
        synchronized(allowedKeys) { if (allowedKeys.contains(key)) return null }

        seq += 1
        val request = ApprovalRequest(
            id = seq,
            action = action,
            rule = hit.rule,
            reason = hit.reason,
            target = hit.target,
            critical = hit.risk == Risk.CRITICAL,
            timeoutSeconds = policy.timeoutSeconds,
        )
        awaiting = request
        log("⏸ 拦截 $action（${hit.rule}）：${hit.reason}")

        val decision = try {
            prompter.ask(request)
        } catch (e: Exception) {
            log("审批过程出错，按拒绝处理：${e.message}")
            ApprovalDecision.DENY
        } finally {
            awaiting = null
        }

        return when (decision) {
            ApprovalDecision.ALLOW_ONCE -> {
                allowedCount += 1
                log("✓ 用户放行一次：${hit.rule}")
                null
            }

            ApprovalDecision.ALLOW_TASK -> {
                allowedCount += 1
                synchronized(allowedKeys) { allowedKeys.add(key) }
                log("✓ 用户放行本任务：${hit.rule}")
                null
            }

            else -> {
                deniedCount += 1
                val what = if (decision == ApprovalDecision.TIMEOUT) "超时未确认" else "用户拒绝"
                log("✕ $what：${hit.rule}")
                denyResponse(request, decision)
            }
        }
    }

    /**
     * 拒绝说明。故意把「换个法子绕过」这条路堵死——
     * 模型很擅长把被拦的 tap 改写成坐标点按，或在输入框里分段粘贴。
     */
    private fun denyResponse(request: ApprovalRequest, decision: ApprovalDecision): String {
        val code = if (decision == ApprovalDecision.TIMEOUT) "APPROVAL_TIMEOUT" else "USER_DENIED"
        val why = if (decision == ApprovalDecision.TIMEOUT) {
            "这一步需要用户在手机上确认，但 ${request.timeoutSeconds} 秒内没有回应，已按拒绝处理"
        } else {
            "用户拒绝了这个操作（${request.reason}）"
        }
        return buildString {
            append("{\"ok\":false,\"code\":\"$code\",\"error\":\"")
            append(Json.escape(why))
            append("\",\"hint\":\"")
            append(
                Json.escape(
                    "不要重试这个动作，也不要换成别的工具绕过去（比如改用坐标点按、拆成几次输入、先启动 App 再点）。" +
                        "把当前进度和需要用户自己做的事说清楚，然后结束这次任务。"
                )
            )
            append("\"}")
        }
    }

    companion object {
        /** 累计拒绝这么多次就中止任务。 */
        const val MAX_DENIALS = 3
    }
}

internal object Json {
    fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", "")
}
