// JVM 测试驱动：把协议层与 agent loop 直接跑在 JVM 上，不需要真机、不需要模拟器。
//
// 编译的文件只有 CloudClient / AgentLoop / PhoneTools / ScreenOutline / DeviceApi / Config
// ——它们都不引 Android API。手机特有的东西（无障碍、Keystore、SQLite）一概没进来，
// 所以这里验证的就是真机上跑的那份代码。

import cn.wbphone.agent.AgentLoop
import cn.wbphone.agent.ApprovalDecision
import cn.wbphone.agent.ApprovalGate
import cn.wbphone.agent.ApprovalPolicy
import cn.wbphone.agent.ApprovalPrompter
import cn.wbphone.agent.ApprovalRequest
import cn.wbphone.agent.CloudClient
import cn.wbphone.agent.DeviceApi
import cn.wbphone.agent.GatewayPlan
import cn.wbphone.agent.GatewayWire
import cn.wbphone.agent.Iat
import cn.wbphone.agent.KeeperPlan
import cn.wbphone.agent.Memory
import cn.wbphone.agent.ModelRegistry
import cn.wbphone.agent.OfficeText
import cn.wbphone.agent.ReconnectPlan
import cn.wbphone.agent.PhoneToolbox
import cn.wbphone.agent.Risk
import cn.wbphone.agent.RiskRules
import cn.wbphone.agent.ScreenFacts
import cn.wbphone.agent.ScreenFactsReader
import cn.wbphone.agent.ScreenOutline
import cn.wbphone.agent.Marked
import cn.wbphone.agent.VoiceText
import cn.wbphone.agent.FileText
import cn.wbphone.agent.DocxWriter
import cn.wbphone.agent.Skill
import cn.wbphone.agent.TokenInfo
import cn.wbphone.agent.TokenSource
import cn.wbphone.agent.SessionStore
import cn.wbphone.agent.Workbench
import cn.wbphone.agent.fail
import cn.wbphone.agent.ok
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------- 假设备

private data class Item(
    val text: String,
    val l: Int,
    val t: Int,
    val r: Int,
    val b: Int,
    val clickable: Boolean = false,
    val next: String? = null,
)

private val SCREENS = mapOf(
    "home" to listOf(
        Item("电话", 0, 200, 1080, 380, true),
        Item("设置", 0, 400, 1080, 580, true, "settings"),
        Item("相机", 0, 600, 1080, 780, true),
    ),
    "settings" to listOf(
        Item("网络和互联网", 0, 200, 1080, 380, true),
        Item("关于手机", 0, 900, 1080, 1080, true, "about"),
    ),
    "about" to listOf(
        Item("设备名称 Pixel 8", 0, 200, 1080, 300),
        Item("Android 版本 14", 0, 320, 1080, 420),
        Item("内核版本 5.15.94", 0, 440, 1080, 540),
    ),
    // 微信支付页：元素带文本（便于序号点按），但整页带金额，用来验证闸门
    "pay" to listOf(
        Item("订单金额 ¥128.00", 0, 200, 1080, 300),
        Item("零钱余额 ¥9,999.00", 0, 320, 1080, 420),
        Item("确认支付", 0, 2100, 1080, 2300, true),
        Item("取消", 0, 2320, 1080, 2400, true),
    ),
)

class FakeDevice : DeviceApi {
    var screen = "home"
        private set

    /** 截图尺寸可以改，用来验证「图片像素 → 真实屏幕像素」的换算。 */
    var shotWidth = 1080
    var shotHeight = 2400

    val taps = ArrayList<Pair<Int, Int>>()
    var lastError = ""

    fun goTo(name: String) {
        screen = name
    }

    private fun node(role: String, text: String, item: Item? = null): JSONObject {
        val o = JSONObject()
            .put("role", role)
            .put("text", text)
            .put("desc", "")
            .put("clickable", item?.clickable ?: false)
            .put("editable", false)
            .put("scrollable", false)
            .put("password", false)
            .put("focused", false)
            .put("children", JSONArray())
        if (item != null) {
            o.put("bounds", JSONArray().put(item.l).put(item.t).put(item.r).put(item.b))
        } else {
            o.put("bounds", JSONArray().put(0).put(0).put(1080).put(2400))
        }
        return o
    }

    override fun readScreen(): JSONObject {
        val root = node("FrameLayout", "")
        val children = root.getJSONArray("children")
        for (item in SCREENS.getValue(screen)) children.put(node("TextView", item.text, item))
        return ok(
            "screen" to JSONObject().put("width", 1080).put("height", 2400).put("package", pkg()),
            "tree" to root
        )
    }

    override fun tap(x: Int, y: Int): JSONObject {
        taps.add(x to y)
        val hit = SCREENS.getValue(screen).firstOrNull { x in it.l..it.r && y in it.t..it.b }
            ?: return fail("坐标没有命中任何元素", "MISS")
        if (!hit.clickable) return fail("「${hit.text}」不可点击", "NOT_CLICKABLE")
        hit.next?.let { screen = it }
        return ok("activated" to hit.text, "navigatedTo" to pkg())
    }

    override fun inputText(text: String, submit: Boolean): JSONObject = ok("value" to text, "submitted" to submit)

    override fun longPress(x: Int, y: Int, durationMs: Int): JSONObject =
        ok("x" to x, "y" to y, "duration" to durationMs)

    override fun pressKey(key: String): JSONObject = when (key) {
        "back" -> {
            if (screen == "about") screen = "settings" else if (screen == "settings") screen = "home"
            ok("navigatedTo" to pkg())
        }
        "home" -> {
            screen = "home"
            ok("navigatedTo" to pkg())
        }
        else -> ok("key" to key)
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): JSONObject = ok("scrolled" to true)

    override fun launchApp(pkg: String): JSONObject {
        if (pkg.contains("settings")) {
            screen = "settings"
            return ok("package" to pkg)
        }
        lastError = "没有应用 $pkg"
        return fail("找不到包 $pkg", "NO_SUCH_PACKAGE")
    }

    override fun screenshot(x: Int, y: Int, w: Int, h: Int): JSONObject {
        val crop = x >= 0 && y >= 0 && w > 0 && h > 0
        return ok(
            "imageBase64" to "AAAA",
            "mime" to "image/jpeg",
            "bytes" to 3,
            "width" to shotWidth,
            "height" to shotHeight,
            "originX" to if (crop) x else 0,
            "originY" to if (crop) y else 0,
            "regionWidth" to if (crop) w else 1080,
            "regionHeight" to if (crop) h else 2400,
        )
    }

    override fun displaySize(): IntArray = intArrayOf(1080, 2400)

    override fun deviceLabel(): String = "Fake Pixel 8 · Android 14"

    private fun pkg(): String = when (screen) {
        "home" -> "com.android.launcher"
        "pay" -> "com.tencent.mm"
        else -> "com.android.settings"
    }
}

// ---------------------------------------------------------------- 假的审批人

/** 按剧本依次回答，同时把收到的请求记下来。 */
class ScriptedPrompter(vararg script: ApprovalDecision) : ApprovalPrompter {
    private val queue = ArrayDeque(script.toList())
    val requests = ArrayList<ApprovalRequest>()

    override fun ask(request: ApprovalRequest): ApprovalDecision {
        requests.add(request)
        return if (queue.isEmpty()) ApprovalDecision.DENY else queue.removeFirst()
    }
}


// ---------------------------------------------------------------- 假令牌

class FakeTokens(private var token: String, private val next: String?) : TokenSource {
    var refreshes = 0
        private set

    override fun accessToken(): String? = token

    override fun refresh(): Boolean {
        refreshes += 1
        val n = next ?: return false
        token = n
        return true
    }
}

// ---------------------------------------------------------------- 记录器

class Recorder : AgentLoop.Listener {
    val tools = ArrayList<String>()
    val results = ArrayList<String>()
    val text = StringBuilder()
    var finalText = ""
    var error = ""
    var turns = 0
    var toolCalls = 0

    override fun onText(delta: String) {
        text.append(delta)
    }

    override fun onToolStart(name: String, args: JSONObject) {
        tools.add(name)
    }

    override fun onToolEnd(name: String, result: String, ok: Boolean) {
        results.add(result)
    }

    var compacted = 0
    val artifacts = ArrayList<String>()

    override fun onCompact(removed: Int, kept: Int) {
        compacted++
    }

    override fun onArtifact(path: String) {
        artifacts.add(path)
    }

    override fun onTurnEnd(turn: Int) = Unit

    override fun onFinished(finalText: String, turns: Int, toolCalls: Int) {
        this.finalText = finalText
        this.turns = turns
        this.toolCalls = toolCalls
    }

    override fun onError(message: String) {
        error = message
    }
}

private fun result(name: String, ok: Boolean, detail: String = "") {
    println("RESULT|$name|${if (ok) "OK" else "FAIL"}|$detail")
}

// ---------------------------------------------------------------- 场景

/** 多轮工具调用：模型看图选序号、手机把序号换成坐标。 */
private fun scenarioLoop(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { if (it == "设置") "com.android.settings" else null })
    val tokens = FakeTokens("t0", null)
    val client = CloudClient("$base/A", "hy3", tokens)
    val loop = AgentLoop(client, toolbox, 10)
    val rec = Recorder()

    loop.run("打开设置，进关于手机，告诉我 Android 版本", rec)

    result("loop_no_error", rec.error.isEmpty(), rec.error)
    result("loop_tool_sequence", rec.tools == listOf("phone_status", "phone_read_screen", "phone_tap", "phone_read_screen", "phone_tap"),
        rec.tools.joinToString(","))
    result("loop_tap_center_1", device.taps.getOrNull(0) == (540 to 490), device.taps.joinToString(","))
    result("loop_tap_center_2", device.taps.getOrNull(1) == (540 to 990), device.taps.joinToString(","))
    result("loop_screen_is_about", device.screen == "about", device.screen)
    result("loop_final_text", rec.finalText.contains("Android 14"), rec.finalText)
    result("loop_tool_count", rec.toolCalls == 5, rec.toolCalls.toString())
    result("loop_turns", rec.turns == 6, rec.turns.toString())
    result("loop_streamed_text", rec.text.toString().contains("Android 14"), rec.text.toString())

    // 历史里应该有 user + 5 组 assistant/tool + 最终 assistant
    result("loop_history_grew", loop.historySize() == 12, loop.historySize().toString())
}

/** 序号复用：点完之后旧序号必须作废，否则会点错东西。 */
private fun scenarioStaleIndex() {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val first = toolbox.execute("phone_read_screen", JSONObject()).text
    val tap1 = toolbox.execute("phone_tap", JSONObject().put("index", 2)).text
    val tapAgain = toolbox.execute("phone_tap", JSONObject().put("index", 2)).text
    val badIndex = run {
        toolbox.execute("phone_read_screen", JSONObject())
        toolbox.execute("phone_tap", JSONObject().put("index", 99)).text
    }

    result("stale_outline_has_index", first.contains("[2]"), first.take(60))
    result("stale_first_tap_ok", tap1.contains("\"ok\":true"), tap1)
    // 新语义：动作结果自带 screen_after、序号随之刷新，第二次点同一个序号
    // 点的是**新屏幕**上的那个元素——合法，不再拒绝。
    result("stale_second_tap_now_valid", tapAgain.contains("\"ok\":true"), tapAgain)
    // screen_after 必须真的附上了：这是「动作后核对零轮次」的根基
    result("stale_tap_has_screen_after", tap1.contains("screen_after"), tap1.take(160))
    result("stale_bad_index_rejected", badIndex.contains("NO_SUCH_INDEX"), badIndex)
    result("stale_no_accessibility", PhoneToolbox({ null }).execute("phone_read_screen", JSONObject()).text.contains("NO_ACCESSIBILITY"))
}

/** 多窗口读屏：应用窗口 + 键盘窗口合到大纲，键盘候选词（如「小罗」）必须可定位可点。 */
private fun scenarioOutlineWindows() {
    fun n(
        role: String, text: String, l: Int, t: Int, r: Int, b: Int,
        clickable: Boolean = false, editable: Boolean = false, children: JSONArray = JSONArray(),
    ): JSONObject = JSONObject()
        .put("role", role).put("text", text).put("desc", "")
        .put("bounds", JSONArray().put(l).put(t).put(r).put(b))
        .put("clickable", clickable).put("editable", editable).put("scrollable", false)
        .put("password", false).put("focused", false)
        .put("children", children)

    // 应用窗口：微信搜索页；键盘窗口：候选条挂着「小罗」
    val wechat = n("FrameLayout", "", 0, 0, 1080, 1600,
        children = JSONArray().put(n("EditText", "搜索本地或网络结果", 0, 100, 1080, 220, editable = true)))
    val keyboard = n("FrameLayout", "", 0, 1600, 1080, 2400,
        children = JSONArray()
            .put(n("TextView", "小罗", 40, 1620, 300, 1700, clickable = true))
            .put(n("TextView", "问财", 340, 1620, 600, 1700, clickable = true)))
    val payload = JSONObject()
        .put("ok", true)
        .put("screen", JSONObject().put("width", 1080).put("height", 2400).put("package", "com.tencent.mm"))
        .put("tree", JSONObject().put("role", "MultiWindow")
            .put("children", JSONArray()
                .put(JSONObject().put("role", "window").put("package", "com.tencent.mm")
                    .put("children", JSONArray().put(wechat)))
                .put(JSONObject().put("role", "window").put("package", "com.vivo.ime(键盘)")
                    .put("children", JSONArray().put(keyboard)))))

    val rendered = ScreenOutline.render(payload)
    result("outline_window_separators",
        rendered.text.contains("── com.tencent.mm ──") && rendered.text.contains("── com.vivo.ime(键盘) ──"),
        rendered.text.take(120))
    result("outline_candidate_indexed", rendered.labels.values.any { it == "小罗" }, rendered.text.take(200))
    val idx = rendered.labels.entries.firstOrNull { it.value == "小罗" }?.key ?: -1
    val c = if (idx > 0) rendered.center(idx) else null
    result("outline_candidate_center", c != null && c[0] == 170 && c[1] == 1660,
        c?.joinToString(",") ?: "null")

    // 审批闸门的事实提取也能看到键盘候选词
    val facts = ScreenFactsReader.fromPayload(payload)
    result("outline_facts_include_ime", facts.texts.contains("小罗"), facts.texts.joinToString("/"))

    // 旧格式单窗口 payload 不受影响：没有分隔线，序号照常
    val single = JSONObject()
        .put("ok", true)
        .put("screen", JSONObject().put("width", 1080).put("height", 2400).put("package", "com.android.settings"))
        .put("tree", n("FrameLayout", "", 0, 0, 1080, 2400,
            children = JSONArray().put(n("TextView", "设置", 0, 100, 1080, 200, clickable = true))))
    val r2 = ScreenOutline.render(single)
    result("outline_single_unwrapped", !r2.text.contains("──") && r2.labels.values.contains("设置"),
        r2.text.take(120))
}

/** 令牌过期：401 → 刷新 → 重试一次，不打扰用户。 */
private fun scenarioRefresh(base: String) {
    val tokens = FakeTokens("bad", "good")
    val client = CloudClient("$base/B", "hy3", tokens)
    val rec = Recorder()
    val loop = AgentLoop(client, PhoneToolbox({ FakeDevice() }, { null }), 3)

    loop.run("只回复两个字：可以", rec)

    result("refresh_called", tokens.refreshes == 1, tokens.refreshes.toString())
    result("refresh_text", rec.finalText.contains("可以"), rec.finalText)
    result("refresh_no_error", rec.error.isEmpty(), rec.error)
}

/** 文本增量：SSE 分片必须拼成完整句子。 */
private fun scenarioStream(base: String) {
    val client = CloudClient("$base/C", "hy3", FakeTokens("t0", null))
    val rec = Recorder()
    val loop = AgentLoop(client, PhoneToolbox({ FakeDevice() }, { null }), 3)

    loop.run("说点话", rec)
    result("stream_joined", rec.finalText == "你好，我是手机上的助手。", rec.finalText)
    result("stream_deltas", rec.text.toString() == "你好，我是手机上的助手。", rec.text.toString())
}

/** 无工具调用时应当一轮就结束，并把 assistant 回复写进历史。 */
private fun scenarioNoTools(base: String) {
    val client = CloudClient("$base/D", "hy3", FakeTokens("t0", null))
    val loop = AgentLoop(client, PhoneToolbox({ FakeDevice() }, { null }), 5)
    val rec = Recorder()
    loop.run("你好", rec)
    result("notools_turns", rec.turns == 1, rec.turns.toString())
    result("notools_history", loop.historySize() == 2, loop.historySize().toString())
    result("notools_tools", rec.toolCalls == 0, rec.toolCalls.toString())
}

/** 多模态：截图必须以独立的图片消息进入上下文，旧图要被降级成文本。 */
private fun scenarioVision(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val client = CloudClient("$base/E", "hy3", FakeTokens("t0", null))
    val loop = AgentLoop(client, toolbox, 8)
    val rec = Recorder()

    loop.run("看看屏幕上有什么", rec)

    result("vision_no_error", rec.error.isEmpty(), rec.error)
    result("vision_tools", rec.tools == listOf("phone_screenshot", "phone_screenshot"),
        rec.tools.joinToString(","))
    result("vision_final", rec.finalText.contains("两个按钮"), rec.finalText)
    // 结构：user(1) + [assistant/tool/user-image]×2 + assistant(1) = 8
    result("vision_history", loop.historySize() == 8, loop.historySize().toString())

    val r = toolbox.execute("phone_screenshot", JSONObject())
    result("vision_image_attached",
        r.imageDataUrl?.startsWith("data:image/jpeg;base64,") == true,
        r.imageDataUrl?.take(30) ?: "null")
    result("vision_text_clean",
        !r.text.contains("base64") && r.text.contains("\"ok\":true"), r.text.take(70))
}

// ---------------------------------------------------------------- 闸门与坐标

/** 规则本身：不看模型、不看网络，纯字符串判定。误判时要能一眼看出命中了哪条词。 */
private fun scenarioRiskRules() {
    val wx = ScreenFacts("com.tencent.mm", listOf("订单金额 ¥128.00", "确认支付"), false)
    val plain = ScreenFacts("com.android.settings", listOf("关于手机", "Android 版本 14"), false)

    fun hit(action: String, target: String = "", input: String = "", facts: ScreenFacts = plain, coord: Boolean = false) =
        RiskRules.assess(action, target, input, facts, coord)

    result("risk_tap_payment", hit("phone_tap", "确认支付", facts = wx).risk == Risk.CRITICAL,
        hit("phone_tap", "确认支付", facts = wx).toString())
    result("risk_tap_plain", hit("phone_tap", "关于手机").risk == Risk.NONE,
        hit("phone_tap", "关于手机").toString())
    result("risk_tap_delete", hit("phone_tap", "删除全部记录").risk == Risk.CRITICAL,
        hit("phone_tap", "删除全部记录").toString())
    result("risk_tap_plain_cancel", hit("phone_tap", "取消", facts = wx).risk == Risk.NONE,
        hit("phone_tap", "取消", facts = wx).toString())

    val pwd = hit("phone_input", "", "hunter2", ScreenFacts("com.android.settings", listOf("登录"), true))
    result("risk_input_password", pwd.risk == Risk.CRITICAL, pwd.toString())
    result("risk_password_masked", !pwd.target.contains("hunter2") && pwd.target.contains("已隐藏"), pwd.target)

    val otp = hit("phone_input", "", "482913", ScreenFacts("com.tencent.mm", listOf("请输入短信验证码"), false))
    result("risk_input_otp", otp.risk == Risk.CRITICAL, otp.toString())
    result("risk_otp_masked", !otp.target.contains("482913"), otp.target)

    result("risk_input_plain", hit("phone_input", "", "明天天气怎么样").risk == Risk.NONE,
        hit("phone_input", "", "明天天气怎么样").toString())

    result("risk_read_clear", hit("phone_read_screen", facts = wx).risk == Risk.NONE, "")
    result("risk_swipe_clear", hit("phone_swipe", "up", facts = wx).risk == Risk.NONE, "")
    result("risk_back_clear", hit("phone_press_key", facts = wx).risk == Risk.NONE, "")

    result("risk_coord_pay_screen", hit("phone_tap_at", facts = wx, coord = true).risk == Risk.CRITICAL,
        hit("phone_tap_at", facts = wx, coord = true).toString())
    val quietWx = ScreenFacts("com.tencent.mm", listOf("聊天", "文件传输助手"), false)
    result("risk_coord_pay_app_quiet", hit("phone_tap_at", facts = quietWx, coord = true).risk == Risk.SENSITIVE,
        hit("phone_tap_at", facts = quietWx, coord = true).toString())
    result("risk_coord_plain", hit("phone_tap_at", facts = plain, coord = true).risk == Risk.NONE,
        hit("phone_tap_at", facts = plain, coord = true).toString())

    // 密码框聚焦时点软键盘，等价于输入密码——这是 input 被拦之后最容易想到的绕过路径
    val onPassword = hit("phone_tap", "1", facts = ScreenFacts("com.android.settings", listOf("输入密码"), true))
    result("risk_tap_on_password", onPassword.risk != Risk.NONE, onPassword.toString())

    result("risk_pay_app_detected",
        RiskRules.isPaymentApp("com.tencent.mm") && RiskRules.isPaymentApp("com.eg.android.AlipayGphone") &&
            !RiskRules.isPaymentApp("com.android.settings"), "")
}

/** 闸门语义：拒绝、只放一次、本任务放行、换目标仍要问、超时、新任务清空。 */
private fun scenarioGate() {
    val payTap = ScreenFacts("com.tencent.mm", listOf("订单金额 ¥128.00", "确认支付"), false)

    run {
        val p = ScriptedPrompter(ApprovalDecision.DENY)
        val gate = ApprovalGate(p)
        gate.beginTask()
        val r = gate.guard("phone_tap", "确认支付", "", payTap)
        result("gate_deny_blocks", r != null && r.contains("USER_DENIED"), r ?: "null")
        result("gate_deny_stops_retry", r?.contains("不要重试") == true, r ?: "null")
        result("gate_deny_counted", gate.deniedCount == 1 && gate.allowedCount == 0,
            "${gate.deniedCount}/${gate.allowedCount}")
    }

    run {
        val p = ScriptedPrompter(ApprovalDecision.ALLOW_ONCE, ApprovalDecision.ALLOW_ONCE)
        val gate = ApprovalGate(p)
        gate.beginTask()
        val a = gate.guard("phone_tap", "确认支付", "", payTap)
        val b = gate.guard("phone_tap", "确认支付", "", payTap)
        result("gate_once_allows", a == null && b == null, "$a / $b")
        result("gate_once_asks_again", p.requests.size == 2, p.requests.size.toString())
    }

    run {
        val p = ScriptedPrompter(ApprovalDecision.ALLOW_TASK, ApprovalDecision.DENY)
        val gate = ApprovalGate(p)
        gate.beginTask()
        gate.guard("phone_tap", "确认支付", "", payTap)
        val same = gate.guard("phone_tap", "确认支付", "", payTap)
        val other = gate.guard("phone_tap", "立即转账", "", payTap)
        result("gate_task_allows_same", same == null, same ?: "null")
        result("gate_task_still_asks_other", p.requests.size == 2 && other != null,
            "${p.requests.size} / $other")
    }

    run {
        val p = ScriptedPrompter(ApprovalDecision.TIMEOUT)
        val gate = ApprovalGate(p)
        gate.beginTask()
        val r = gate.guard("phone_input", "", "482913", ScreenFacts("com.tencent.mm", listOf("请输入验证码"), false))
        result("gate_timeout_code", r?.contains("APPROVAL_TIMEOUT") == true, r ?: "null")
    }

    run {
        val p = ScriptedPrompter()
        val gate = ApprovalGate(p)
        gate.beginTask()
        val r = gate.guard("phone_swipe", "up", "", ScreenFacts("com.tencent.mm", listOf("聊天"), false))
        result("gate_quiet_actions", r == null && p.requests.isEmpty(), "$r / ${p.requests.size}")
    }

    run {
        val p = ScriptedPrompter(ApprovalDecision.ALLOW_TASK, ApprovalDecision.ALLOW_TASK)
        val gate = ApprovalGate(p)
        gate.beginTask()
        gate.guard("phone_tap", "确认支付", "", payTap)
        gate.beginTask()
        gate.guard("phone_tap", "确认支付", "", payTap)
        result("gate_reset_between_tasks", p.requests.size == 2, p.requests.size.toString())
    }

    run {
        val p = ScriptedPrompter(ApprovalDecision.DENY, ApprovalDecision.DENY, ApprovalDecision.DENY)
        val gate = ApprovalGate(p)
        gate.beginTask()
        repeat(2) { gate.guard("phone_tap", "确认支付", "", payTap) }
        val before = gate.shouldAbort
        gate.guard("phone_tap", "确认支付", "", payTap)
        result("gate_abort_after_denials", !before && gate.shouldAbort, "before=$before after=${gate.shouldAbort}")
    }
}

/** 坐标点按：截图坐标必须换算到真实屏幕，且点完不能再用旧图。 */
private fun scenarioCoordTap() {
    val device = FakeDevice()
    device.goTo("pay")
    device.shotWidth = 720
    device.shotHeight = 1600
    val toolbox = PhoneToolbox({ device }, { null })

    val before = toolbox.execute("phone_tap_at", JSONObject().put("x", 360).put("y", 1466)).text
    result("coord_needs_screenshot", before.contains("STALE_SCREEN"), before)

    toolbox.execute("phone_screenshot", JSONObject())
    val tapped = toolbox.execute("phone_tap_at", JSONObject().put("x", 360).put("y", 1466)).text
    result("coord_scaled_to_screen", device.taps.lastOrNull() == (540 to 2199),
        device.taps.joinToString(","))
    result("coord_reports_both_frames",
        tapped.contains("\"tapped_at\":[540,2199]") && tapped.contains("\"from_image\":[360,1466]"), tapped)

    val again = toolbox.execute("phone_tap_at", JSONObject().put("x", 360).put("y", 1466)).text
    result("coord_frame_dropped_after_tap", again.contains("STALE_SCREEN"), again)

    toolbox.execute("phone_screenshot", JSONObject())
    val oob = toolbox.execute("phone_tap_at", JSONObject().put("x", 900).put("y", 100)).text
    result("coord_out_of_bounds", oob.contains("OUT_OF_BOUNDS"), oob)

    // 局部放大截图：坐标要叠加截取区域的偏移，再按那块区域的尺寸换算
    toolbox.execute(
        "phone_screenshot",
        JSONObject().put("x", 0).put("y", 1800).put("w", 1080).put("h", 600),
    )
    val zoom = toolbox.execute("phone_tap_at", JSONObject().put("x", 360).put("y", 1493)).text
    result("coord_zoom_maps_with_origin", device.taps.lastOrNull() == (540 to 2359),
        device.taps.joinToString(","))
    result("coord_zoom_reports_both_frames", zoom.contains("\"tapped_at\":[540,2359]"), zoom)
}

/** 端到端：模型要付款 → 用户拒绝 → 动作根本没发到设备上。 */
private fun scenarioApprovalDenied(base: String) {
    val device = FakeDevice()
    device.goTo("pay")
    val prompter = ScriptedPrompter(ApprovalDecision.DENY)
    val toolbox = PhoneToolbox({ device }, { null }, ApprovalGate(prompter))
    val client = CloudClient("$base/F", "hy3", FakeTokens("t0", null))
    val loop = AgentLoop(client, toolbox, 6)
    val rec = Recorder()

    loop.run("帮我把这个 128 块付了", rec)

    result("approval_no_error", rec.error.isEmpty(), rec.error)
    result("approval_blocked_in_loop", rec.results.any { it.contains("USER_DENIED") },
        rec.results.joinToString(" | "))
    result("approval_action_never_executed", device.taps.isEmpty(), device.taps.joinToString(","))
    result("approval_final_explains", rec.finalText.contains("自己确认"), rec.finalText)
}

/** 端到端：被拒三次后必须收手，不能靠模型的耐心把任务磨下去。 */
private fun scenarioAbortAfterDenials(base: String) {
    val device = FakeDevice()
    device.goTo("pay")
    val prompter = ScriptedPrompter(ApprovalDecision.DENY, ApprovalDecision.DENY, ApprovalDecision.DENY)
    val toolbox = PhoneToolbox({ device }, { null }, ApprovalGate(prompter))
    val client = CloudClient("$base/G", "hy3", FakeTokens("t0", null))
    val loop = AgentLoop(client, toolbox, 10)
    val rec = Recorder()

    loop.run("付款", rec)

    result("abort_no_error", rec.error.isEmpty(), rec.error)
    result("abort_asked_three_times", prompter.requests.size == 3, prompter.requests.size.toString())
    result("abort_nothing_executed", device.taps.isEmpty(), device.taps.joinToString(","))
    result("abort_final_text", rec.finalText.contains("停在这里"), rec.finalText)
    // user + 4 轮(assistant+tool)。历史上必须成对，否则下一句话就发不出去了
    result("abort_history_paired", loop.historySize() == 9, loop.historySize().toString())
}

/**
 * 持久化：任务结束导出历史 → 剥图 → 新 loop 恢复 → 继续对话。
 * 服务端（剧本 H）会核验第二个任务的请求里带着上次的上下文，且不含任何图片。
 */
private fun scenarioPersist(base: String) {
    val toolbox = PhoneToolbox({ FakeDevice() }, { null })
    val loop = AgentLoop(CloudClient("$base/H", "hy3", FakeTokens("t0", null)), toolbox, 10)
    val rec = Recorder()

    loop.run("打开设置", rec)
    result("persist_no_error", rec.error.isEmpty(), rec.error)

    val exported = AgentLoop.stripImages(loop.exportHistory())

    // 剥图函数的直接验证：带图的消息换成一条纯文本，其余消息原样通过
    val withImage = JSONArray().put(
        JSONObject().put("role", "user").put(
            "content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "这是截图"))
                .put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,AAAA"))
                )
        )
    )
    val stripped = AgentLoop.stripImages(withImage)
    result("persist_image_stripped", stripped.optJSONObject(0)?.optString("content") == "这是截图", stripped.toString())

    val untouched = AgentLoop.stripImages(
        JSONArray().put(JSONObject().put("role", "user").put("content", "普通文本消息"))
    )
    result("persist_text_untouched", untouched.optJSONObject(0)?.optString("content") == "普通文本消息", untouched.toString())

    // 模拟进程重启：新 loop 装回历史
    val loop2 = AgentLoop(CloudClient("$base/H", "hy3", FakeTokens("t0", null)), toolbox, 10)
    loop2.restoreHistory(exported)
    result("persist_restored_size", loop2.historySize() == exported.length(),
        "${loop2.historySize()} vs ${exported.length()}")

    val rec2 = Recorder()
    loop2.run("接着说", rec2)
    result("persist_second_no_error", rec2.error.isEmpty(), rec2.error)
    result("persist_second_reply", rec2.finalText.contains("还记得"), rec2.finalText)
}

/**
 * 自动恢复无障碍的决策逻辑：两种失效形态、只动自己的条目、退避与节流。
 * 这部分错了会写坏别人的无障碍开关，所以逐条钉死。
 */
private fun scenarioKeeper() {
    val pkg = "cn.wbphone.agent"
    val cls = "cn.wbphone.agent.PhoneAccessibilityService"
    val me = "$pkg/$cls"
    val other = "com.a/com.a.S"

    val off = KeeperPlan(pkg, cls)
    result("keeper_off_none", off.next(false, true, null, false, 0L) == KeeperPlan.Step.NONE, "关掉功能后不该动系统设置")

    val noGrant = KeeperPlan(pkg, cls)
    result("keeper_no_permission_none", noGrant.next(true, false, null, false, 0L) == KeeperPlan.Step.NONE, "没授权时不该尝试写")

    val missing = KeeperPlan(pkg, cls)
    result("keeper_add_when_missing", missing.next(true, true, other, false, 0L) == KeeperPlan.Step.ADD, "不在名单里应该追加")

    val throttled = KeeperPlan(pkg, cls)
    throttled.next(true, true, other, false, 0L)
    result("keeper_add_rate_limited", throttled.next(true, true, other, false, 1000L) == KeeperPlan.Step.NONE, "刚写过一秒内不该重复写")

    val listed = KeeperPlan(pkg, cls)
    result("keeper_short_class_listed", listed.listed("com.a/x:$pkg/.PhoneAccessibilityService"), "短类名写法")
    result("keeper_other_app_not_listed", !listed.listed(other), other)

    result(
        "keeper_with_me_keeps_others",
        listed.withMe(other) == "$other:$me",
        listed.withMe(other),
    )
    result(
        "keeper_without_me_keeps_others",
        listed.withoutMe("$other:$me:com.b/com.b.S") == "$other:com.b/com.b.S",
        listed.withoutMe("$other:$me:com.b/com.b.S"),
    )

    // 「无法运行」：名单里有、但没绑定。要先等，不能一有风吹草动就摘了重加。
    val unbound = KeeperPlan(pkg, cls)
    result("keeper_waits_before_rebind", unbound.next(true, true, me, false, 0L) == KeeperPlan.Step.NONE, "刚失去绑定应先等")
    result("keeper_rebind_after_unbound", unbound.next(true, true, me, false, 11_000L) == KeeperPlan.Step.REBIND, "十秒仍没绑上应摘了重加")

    result(
        "keeper_backoff_ladder",
        KeeperPlan.backoff(1) == 5_000L && KeeperPlan.backoff(2) == 10_000L && KeeperPlan.backoff(3) == 20_000L,
        "${KeeperPlan.backoff(1)},${KeeperPlan.backoff(2)},${KeeperPlan.backoff(3)}",
    )
    result("keeper_backoff_cap", KeeperPlan.backoff(9) == 600_000L, KeeperPlan.backoff(9).toString())

    // 连上并稳定一分钟后，之前的失败次数清零：下次再掉可以立刻修，而不是等十分钟。
    val stable = KeeperPlan(pkg, cls)
    stable.next(true, true, null, false, 0L)
    stable.next(true, true, null, true, 3_000L)
    val afterStable = stable.next(true, true, null, false, 64_000L)
    result("keeper_backoff_reset_after_stable", afterStable == KeeperPlan.Step.ADD, afterStable.toString())

    val connected = KeeperPlan(pkg, cls)
    result("keeper_connected_none", connected.next(true, true, null, true, 0L) == KeeperPlan.Step.NONE, "连着就什么都别做")
}

/**
 * 没授权时的掉线提醒：只在"真掉了且修不了"时叫，且别叫个不停。
 * 这段错了就是半夜被通知吵醒，或者真断了没人管。
 */
private fun scenarioReconnect() {
    fun plan() = ReconnectPlan(graceMs = 8_000L, nagIntervalMs = 30 * 60 * 1000L)

    result("reconnect_granted_quiet", !plan().shouldNotify(true, true, false, 0L), "有权限就别吵，自愈会处理")
    result("reconnect_unwanted_quiet", !plan().shouldNotify(false, false, false, 100_000L), "用户关掉这个功能就别提醒")
    result("reconnect_healthy_quiet", !plan().shouldNotify(true, false, true, 100_000L), "开关好好的，不提醒")

    val p = plan()
    // 刚掉的一两秒是系统重绑窗口，不能算真掉了
    val graceQuiet = !p.shouldNotify(true, false, false, 0L) && !p.shouldNotify(true, false, false, 7_999L)
    result("reconnect_grace_quiet", graceQuiet, "")
    result("reconnect_nag_after_grace", p.shouldNotify(true, false, false, 8_000L), "")
    result("reconnect_throttled", !p.shouldNotify(true, false, false, 9_000L), "刚叫过就别再叫")
    result("reconnect_after_interval", p.shouldNotify(true, false, false, 8_000L + 30 * 60 * 1000L), "半小时后再叫一次")

    // 恢复过之后：重新计时（宽限期要重新走），但节流不重置——
    // 开关来回抖动时每 10 秒弹一次通知，比不提醒还糟。
    val q = plan()
    q.shouldNotify(true, false, false, 0L)
    q.shouldNotify(true, false, false, 8_000L)          // 叫过一次
    q.shouldNotify(true, false, true, 9_000L)           // 恢复了
    val notImmediately = !q.shouldNotify(true, false, false, 10_000L)          // 又在宽限期内
    val notAgainWithinWindow = !q.shouldNotify(true, false, false, 18_000L)    // 过了宽限，但没过节流
    val afterWindow = q.shouldNotify(true, false, false, 8_000L + 30 * 60 * 1000L)
    result("reconnect_nag_again_after_recover", notImmediately && notAgainWithinWindow && afterWindow, "")
}

/**
 * 模型清单与切换：清单本身要能扛住脏值，切换要真的落到请求体里。
 * 只看内存变量不算数——必须核对服务端收到的是哪个 model。
 */
private fun scenarioModelRegistry(base: String) {
    val def = ModelRegistry.default()
    result("model_default_free", def.id == ModelRegistry.DEFAULT_ID && def.credit == "x0.00", def.toString())
    result("model_ids_unique", ModelRegistry.OPTIONS.map { it.id }.toSet().size == ModelRegistry.OPTIONS.size,
        ModelRegistry.OPTIONS.size.toString())
    result("model_labels_present", ModelRegistry.OPTIONS.all { it.label.isNotBlank() && it.id.isNotBlank() }, "")
    // 脏值（旧版本残留、手改、服务端下线）必须落回默认，不能把野字符串发出去
    result("model_unknown_falls_back",
        ModelRegistry.resolve("gpt-9-ultra").id == ModelRegistry.DEFAULT_ID &&
            ModelRegistry.resolve(null).id == ModelRegistry.DEFAULT_ID &&
            ModelRegistry.resolve("").id == ModelRegistry.DEFAULT_ID, "")
    result("model_index_falls_back",
        ModelRegistry.indexOf("nope") == ModelRegistry.indexOf(ModelRegistry.DEFAULT_ID), "")
    result("model_summary_free", ModelRegistry.default().summary().endsWith("免费"), ModelRegistry.default().summary())

    // 顶栏胶囊和设置项都用 Option.summary()。它一旦拼错（比如 "Kimi-K3 · x1.62" 变成
    // "Kimi-K3 · " 或漏掉倍率），界面上就只剩个残缺的模型名——用户会以为是「没模型」。
    result("model_credit_format",
        ModelRegistry.OPTIONS.all { it.credit.matches(Regex("^x\\d+\\.\\d{2}$")) },
        ModelRegistry.OPTIONS.map { it.credit }.joinToString(","))
    result("model_summary_shape",
        ModelRegistry.OPTIONS.all { o ->
            val want = if (o.credit == "x0.00") "${o.label} · 免费" else "${o.label} · ${o.credit}"
            o.summary() == want
        },
        ModelRegistry.OPTIONS.map { it.summary() }.joinToString(" | "))
    // 选完要能按 id 找回来，并且 indexOf 指的就是它自己在列表里的位置
    result("model_roundtrip_by_id",
        ModelRegistry.OPTIONS.withIndex().all { (i, o) ->
            ModelRegistry.find(o.id)?.id == o.id && ModelRegistry.indexOf(o.id) == i
        }, "")
    // 顺序照抄桌面端下拉：前三项是三个通用档位。顺序错了用户找同一个模型要翻半天。
    result("model_top3_generic",
        ModelRegistry.OPTIONS.take(3).map { it.id } == listOf("fast-model", "balanced-model", "deep-model"),
        ModelRegistry.OPTIONS.take(3).map { it.id }.joinToString(","))

    // 切换模型：第二次请求的 model 字段必须是新值，历史照旧带着
    val client = CloudClient("$base/I", "hy3", FakeTokens("t0", null))
    val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "第一句"))
    val t1 = client.stream(msgs, null)
    client.model = "glm-5.3"
    val t2 = client.stream(msgs, null)
    result("model_turn1_reply", t1.content.contains("第一答复"), t1.content)
    result("model_turn2_reply", t2.content.contains("第二答复"), t2.content)
    // 假服务器把收到的 model 原样回显——所以这两个值是「服务端真的收到了什么」，
    // 不是我们内存里那个变量。切换没生效的话，t2.model 会还是 hy3。
    result("model_echo_follows_switch", t1.model == "hy3" && t2.model == "glm-5.3",
        "t1=${t1.model} t2=${t2.model}")

    // 网关可能不按请求走。回显说谁答的，就是谁答的——界面也就该显示谁。
    val gated = CloudClient("$base/J", "hy3", FakeTokens("t0", null))
    val tg = gated.stream(msgs, null)
    result("model_server_echo_wins", tg.model == "glm-5.3", tg.model ?: "null")
}


/**
 * 令牌有效期：dsh-Go 那边只能填一串静态 Key，用户唯一需要知道的就是「这串还能用多久」。
 * 读错这个数，界面会在令牌早已失效时还告诉人「还有 27 天」。
 */
private fun scenarioTokenInfo() {
    fun jwt(payloadJson: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val head = enc.encodeToString("""{"alg":"RS256"}""".toByteArray())
        val body = enc.encodeToString(payloadJson.toByteArray())
        return "$head.$body.sig"
    }

    val real = jwt("""{"sub":"u1","exp":1793000000}""")
    result("token_exp_parsed", TokenInfo.expirySeconds(real) == 1793000000L,
        TokenInfo.expirySeconds(real).toString())
    result("token_not_jwt", TokenInfo.expirySeconds("not-a-jwt") == 0L, "")
    result("token_no_exp", TokenInfo.expirySeconds(jwt("""{"sub":"u1"}""")) == 0L, "")
    result("token_bad_payload", TokenInfo.expirySeconds("a.!!!.c") == 0L, "")

    val now = 1_000_000_000L
    result("token_remaining_days", TokenInfo.remainingText(now + 27 * 86400, now) == "还有 27 天",
        TokenInfo.remainingText(now + 27 * 86400, now))
    result("token_remaining_hours", TokenInfo.remainingText(now + 5 * 3600, now) == "还有 5 小时",
        TokenInfo.remainingText(now + 5 * 3600, now))
    result("token_remaining_minutes", TokenInfo.remainingText(now + 120, now) == "还有 2 分钟",
        TokenInfo.remainingText(now + 120, now))
    result("token_expired", TokenInfo.remainingText(now - 10, now) == "已过期",
        TokenInfo.remainingText(now - 10, now))
    result("token_unknown_expiry", TokenInfo.remainingText(0L, now) == "读不出有效期", "")
    result("token_expiry_text_format",
        Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(TokenInfo.expiryText(now)),
        TokenInfo.expiryText(now))
}

/**
 * 本地网关的协议层：路由、模型清单、请求体规整、出站身份头。
 *
 * 这一层错了，用户看到的是 dsh-Go 里一句「出错了」——既不知道是路径没对上、
 * 还是模型名被拒、还是又被打回「非官方渠道」。所以每条判断都钉死。
 */
private fun scenarioGatewayPlan() {
    // 路由：dsh-Go 拼出来的是 /v1/chat/completions，桌面端用的是 /v2，两条都要认
    result("gw_route_v1_chat", GatewayPlan.route("POST", "/v1/chat/completions") == GatewayPlan.Route.CHAT, "")
    result("gw_route_v2_chat", GatewayPlan.route("POST", "/v2/chat/completions") == GatewayPlan.Route.CHAT, "")
    result("gw_route_query", GatewayPlan.route("POST", "/v1/chat/completions?x=1") == GatewayPlan.Route.CHAT, "")
    result("gw_route_models", GatewayPlan.route("GET", "/v1/models") == GatewayPlan.Route.MODELS, "")
    result("gw_route_root", GatewayPlan.route("GET", "/") == GatewayPlan.Route.OTHER, "")

    // 模型清单：网关自己没有 /models（404），这份必须由本地补齐，否则「获取模型」永远失败
    val models = JSONObject(GatewayPlan.modelsJson())
    val data = models.optJSONArray("data")
    result("gw_models_shape", models.optString("object") == "list" && data != null, "")
    result("gw_models_count", data?.length() == ModelRegistry.OPTIONS.size, data?.length().toString())
    val ids = (0 until (data?.length() ?: 0)).map { data!!.optJSONObject(it).optString("id") }
    result("gw_models_has_default", ids.contains(ModelRegistry.DEFAULT_ID), "")
    result("gw_models_order_kept", ids == ModelRegistry.OPTIONS.map { it.id }, ids.take(3).toString())

    // 请求体规整：非流式必被网关拒（11101），野模型名必被拒（11102），这两个坑先堵掉
    val raw = JSONObject()
        .put("model", "gpt-4o")
        .put("stream", false)
        .put("max_tokens", 100_000)
        .put("temperature", 0.3)
        .put("n", 2)
        .put("user", "dsh")
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "你好")))
    val fixed = JSONObject(GatewayPlan.prepare(raw.toString()))
    result("gw_force_stream", fixed.optBoolean("stream"), "")
    result("gw_model_fallback", fixed.optString("model") == ModelRegistry.DEFAULT_ID, fixed.optString("model"))
    result("gw_clamp_output", fixed.optInt("max_tokens") == GatewayPlan.MAX_OUTPUT_TOKENS, fixed.optInt("max_tokens").toString())
    result("gw_keeps_temperature", fixed.optDouble("temperature", -1.0) == 0.3, "")
    result("gw_drops_n", !fixed.has("n"), "")
    result("gw_drops_user", !fixed.has("user"), "")
    result("gw_keeps_messages", fixed.optJSONArray("messages")?.length() == 1, "")

    // 工具定义与强制调用必须原样过：丢了它们，dsh-Go 的智能体就只剩聊天
    val withTools = JSONObject(GatewayPlan.prepare(
        """{"model":"hy3","stream":true,"messages":[{"role":"user","content":"hi"}],
           "tool_choice":{"type":"function","function":{"name":"ping"}},
           "tools":[{"type":"function","function":{"name":"ping","description":"test",
                     "parameters":{"type":"object","properties":{}}}}]}"""
    ))
    result("gw_keeps_tools", withTools.optJSONArray("tools")?.length() == 1, "")
    result("gw_keeps_tool_choice_object",
        withTools.optJSONObject("tool_choice")?.optJSONObject("function")?.optString("name") == "ping",
        withTools.opt("tool_choice")?.toString() ?: "")

    // 不带 max_tokens 时给个保守默认，别让上游按 32K 预扣
    val noCap = JSONObject(GatewayPlan.prepare(
        """{"model":"hy3","messages":[{"role":"user","content":"hi"}],"stream":true}"""
    ))
    result("gw_default_output", noCap.optInt("max_tokens") in 1..GatewayPlan.MAX_OUTPUT_TOKENS,
        noCap.optInt("max_tokens").toString())

    // 空 messages 必须早点报错，而不是把空请求转给上游
    val threw = try {
        GatewayPlan.prepare("""{"model":"hy3","messages":[]}""")
        false
    } catch (e: IllegalArgumentException) {
        true
    }
    result("gw_reject_empty_messages", threw, "")

    // 降档实验：11128 只在手机上出现，触发条件藏在真实请求体里，靠这几档自动定位
    val ladderRaw = """{"model":"hy3","stream":true,"temperature":0.5,
        "tools":[{"type":"function","function":{"name":"ping","parameters":{"type":"object"}}}],
        "messages":[
            {"role":"system","content":"你是助手"},
            {"role":"user","content":"第一问"},
            {"role":"assistant","content":"答"},
            {"role":"user","content":"第二问"}]}"""
    val l0 = JSONObject(GatewayPlan.prepareLevel(ladderRaw, 0))
    val l1 = JSONObject(GatewayPlan.prepareLevel(ladderRaw, 1))
    val l2 = JSONObject(GatewayPlan.prepareLevel(ladderRaw, 2))
    val l3 = JSONObject(GatewayPlan.prepareLevel(ladderRaw, 3))
    val l4 = JSONObject(GatewayPlan.prepareLevel(ladderRaw, 4))
    val l8 = JSONObject(GatewayPlan.prepareLevel(ladderRaw, 8))
    result("gw_lvl0_full", l0.has("tools") && l0.has("temperature"), "")
    // 1 档必须「只改角色」：tools / 采样参数 / 历史都得还在，否则 dsh-Go 的智能体是残的
    val l1m = l1.optJSONArray("messages")
    result("gw_lvl1_role_only",
        l1.has("tools") && l1.has("temperature")
            && l1m?.length() == 4
            && l1m.optJSONObject(0)?.optString("role") == "user"
            && l1m.optJSONObject(0)?.optString("content") == "你是助手", l1m?.toString() ?: "")
    result("gw_lvl1_no_system_left",
        (0 until (l1m?.length() ?: 0)).none { l1m?.optJSONObject(it)?.optString("role") == "system" }, "")
    result("gw_lvl2_bare", !l2.has("tools") && !l2.has("temperature") && l2.optJSONArray("messages")?.length() == 4, "")
    val l3m = l3.optJSONArray("messages")
    result("gw_lvl3_system_last_user",
        l3m?.length() == 2 && l3m.optJSONObject(0)?.optString("role") == "system"
            && l3m.optJSONObject(1)?.optString("content") == "第二问", l3m?.toString() ?: "")
    val l4m = l4.optJSONArray("messages")
    result("gw_lvl4_system_as_user",
        l4m?.length() == 2 && l4m.optJSONObject(0)?.optString("role") == "user"
            && l4m.optJSONObject(0)?.optString("content") == "你是助手", l4m?.toString() ?: "")
    val l8m = l8.optJSONArray("messages")
    result("gw_lvl8_last_user_only",
        l8m?.length() == 1 && l8m.optJSONObject(0)?.optString("content") == "第二问", "")
    result("gw_lvl_all_same_model", l1.optString("model") == "hy3" && l8.optString("model") == "hy3", "")
    result("gw_lvl_all_stream", l1.optBoolean("stream") && l8.optBoolean("stream"), "")
    result("gw_lvl_bounds", GatewayPlan.prepareLevel(ladderRaw, 99) == GatewayPlan.prepareLevel(ladderRaw, 8), "")
    // 截断档要真长提示词验：短内容截不动是正常行为
    val sys8k = """{"model":"hy3","stream":true,"messages":[
        {"role":"system","content":"${"长提示词片段。".repeat(4000)}"},
        {"role":"user","content":"hi"}]}"""
    val t5 = JSONObject(GatewayPlan.prepareLevel(sys8k, 5)).optJSONArray("messages")
    result("gw_lvl5_truncates_8k", t5?.optJSONObject(0)?.optString("content")?.length == 8192,
        t5?.optJSONObject(0)?.optString("content")?.length?.toString() ?: "")
    val t7 = JSONObject(GatewayPlan.prepareLevel(sys8k, 7)).optJSONArray("messages")
    result("gw_lvl7_truncates_512", t7?.optJSONObject(0)?.optString("content")?.length == 512,
        t7?.optJSONObject(0)?.optString("content")?.length?.toString() ?: "")
    // 档位解释必须区分「能干活」和「只能聊天」：1 档要写明工具完整
    result("gw_level_note_1", GatewayPlan.levelNote(1).contains("工具定义完整"), GatewayPlan.levelNote(1))
    result("gw_level_note_2_loses_tools", GatewayPlan.levelNote(2).contains("不能调用工具"), GatewayPlan.levelNote(2))
    result("gw_level_note_all", (0 until GatewayPlan.BODY_LEVELS).all { GatewayPlan.levelNote(it).isNotEmpty() }, "")
    result("gw_blocked_detector", GatewayPlan.isBlockedError(400, """{"code":11128,"msg":"x"}""")
        && !GatewayPlan.isBlockedError(400, """{"code":11102}""")
        && !GatewayPlan.isBlockedError(500, """{"code":11128}"""), "")

    // 身份头：这组头就是「像官方客户端」的全部依据，缺一个都可能被打回 11128
    val h = GatewayPlan.officialHeaders("conv", "creq", "msg", "req", "conn",
        "0123456789abcdef0123456789abcdef", "0123456789abcdef", "fedcba9876543210")
    result("gw_header_product", h["X-Product"] == "SaaS", h["X-Product"] ?: "")
    result("gw_header_ide", h["X-IDE-Type"] == "WorkBuddy", h["X-IDE-Type"] ?: "")
    result("gw_header_codebuddy", h["x-codebuddy-request"] == "1", "")
    result("gw_header_trace", h["traceparent"] == "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
        h["traceparent"] ?: "")
    result("gw_header_count", h.size >= 18, h.size.toString())
    result("gw_header_no_auth", h.keys.none { it.equals("Authorization", true) }, "")

    result("gw_local_base", GatewayPlan.localBaseUrl(8787) == "http://127.0.0.1:8787/v1",
        GatewayPlan.localBaseUrl(8787))
    result("gw_ports_start", GatewayPlan.ports().first() == 8787 && GatewayPlan.ports().size >= 5, "")
}

/**
 * 网关的字节处理。这些用例都在内存流上跑，不需要 socket——
 * 真机上这里错一步，dsh-Go 只会回一句「出错了」，什么线索都没有。
 */
private fun scenarioGatewayWire() {
    fun streamOf(text: String) = java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    // 正常请求头：头名大小写混着来，也要能取到
    val head = GatewayWire.readHead(streamOf(
        "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Host: 127.0.0.1:8787\r\n" +
            "Content-Type: application/json\r\n" +
            "content-length: 5\r\n\r\n"
    ))
    result("wire_method_upper", head?.method == "POST", head?.method ?: "null")
    result("wire_path", head?.path == "/v1/chat/completions", head?.path ?: "")
    result("wire_header_lower", head?.headers?.get("content-length") == "5", "")
    result("wire_header_value_trim", head?.headers?.get("host") == "127.0.0.1:8787", "")

    // 空连接：不该抛，也不该编一个假请求出来
    result("wire_empty_null", GatewayWire.readHead(streamOf("")) == null, "")

    // 头和体在同一个流上：读头不能把体吃掉
    val both = streamOf("POST /v1/chat/completions HTTP/1.1\r\nContent-Length: 7\r\n\r\n{\"a\":1}")
    val h2 = GatewayWire.readHead(both)
    val b2 = GatewayWire.readBody(both, h2!!.headers)
    result("wire_body_length", b2 == """{"a":1}""", b2)

    // chunked：dsh-Go 的运行时可能不带 Content-Length
    val chunked = GatewayWire.readBody(
        streamOf("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"),
        mapOf("transfer-encoding" to "chunked"),
    )
    result("wire_body_chunked", chunked == "hello world", chunked)

    // 没有长度也没有 chunked：当空体，别去读到超时
    result("wire_body_empty", GatewayWire.readBody(streamOf(""), emptyMap()) == "", "")

    // 响应头：SSE 不能带 Content-Length，否则客户端会等满那么多字节
    val sse = GatewayWire.head(200, "text/event-stream; charset=utf-8", null)
    result("wire_sse_no_length", !sse.contains("Content-Length"), sse.replace("\r\n", "|"))
    result("wire_sse_close", sse.contains("Connection: close"), "")
    result("wire_sse_status_line", sse.startsWith("HTTP/1.1 200 OK\r\n"), "")
    result("wire_json_length", GatewayWire.head(400, "application/json", 12).contains("Content-Length: 12"), "")
    result("wire_reason_401", GatewayWire.reason(401) == "Unauthorized", GatewayWire.reason(401))

    // 报错正文要能安全塞进 JSON：dsh-Go 会把它原样显示给用户
    val err = GatewayWire.jsonError("""解析失败："坏" 的 JSON""")
    result("wire_error_escaped", JSONObject(err).optJSONObject("error") != null, err)
    result("wire_error_has_type", JSONObject(err).optJSONObject("error").optString("type") == "invalid_request_error", "")
}

/**
 * 真 socket 往返：用生产代码里同一份 GatewayWire 起服务端，
 * 客户端用 HttpURLConnection（网关自检走的就是它）。
 *
 * 只测字节层对不对：头能不能读完、JSON 能不能取到、SSE 能不能边发边收。
 * 不测转发——那部分要 Android 的令牌与日志。
 */
private fun scenarioGatewaySocket() {
    fun serveOnce(handler: (GatewayWire.Request, java.io.OutputStream) -> Unit): Int {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                server.accept().use { client ->
                    val input = java.io.BufferedInputStream(client.getInputStream())
                    val req = GatewayWire.readHead(input)
                    if (req != null) {
                        GatewayWire.readBody(input, req.headers)
                        handler(req, client.getOutputStream())
                        client.getOutputStream().flush()
                    }
                }
            } catch (e: Exception) {
                // 断言会从客户端侧反映失败
            } finally {
                try { server.close() } catch (e: Exception) {}
            }
        }.start()
        return server.localPort
    }

    // 1) 模型清单：带 Content-Length 的普通 JSON
    val p1 = serveOnce { _, out ->
        val body = GatewayPlan.modelsJson().toByteArray()
        out.write(GatewayWire.head(200, "application/json; charset=utf-8", body.size).toByteArray())
        out.write(body)
    }
    val listResult = try {
        val url = java.net.URL("http://127.0.0.1:$p1/v1/models")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        val arr = JSONObject(text).optJSONArray("data")
        "ok:" + (arr?.length() ?: -1)
    } catch (e: Exception) {
        "err:" + (e.message ?: e.javaClass.simpleName)
    }
    result("wire_socket_models", listResult == "ok:${ModelRegistry.OPTIONS.size}", listResult)

    // 2) SSE：不带 Content-Length，写两帧后关连接，客户端要能收到两帧
    val p2 = serveOnce { _, out ->
        out.write(GatewayWire.head(200, "text/event-stream; charset=utf-8", null).toByteArray())
        out.write("data: {\"model\":\"hy3\",\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n".toByteArray())
        out.flush()
        Thread.sleep(30)
        out.write("data: [DONE]\n\n".toByteArray())
    }
    val sseResult = try {
        val conn = java.net.URL("http://127.0.0.1:$p2/v1/chat/completions")
            .openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        val lines = conn.inputStream.bufferedReader().readLines()
        conn.disconnect()
        val dataLines = lines.count { it.startsWith("data:") }
        "ok:" + dataLines
    } catch (e: Exception) {
        "err:" + (e.message ?: e.javaClass.simpleName)
    }
    result("wire_socket_sse", sseResult == "ok:2", sseResult)
}

/**
 * 上游不认采样参数时，客户端要自己摘掉重试，而不是把 400 直接甩给用户。
 * 这是「新增一个可选参数」最危险的副作用：一个参数不被接受，整个功能就不能用了。
 */
private fun scenarioSamplingFallback(base: String) {
    val tokens = FakeTokens("t0", null)
    val client = CloudClient("$base/K", "hy3", tokens)
    val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
    var err = ""
    var text = ""
    try {
        text = client.stream(msgs, null).content
    } catch (e: Exception) {
        err = e.message ?: e.javaClass.simpleName
    }
    result("sampling_retry_no_error", err.isEmpty(), err)
    result("sampling_retry_text", text.contains("降级后可以"), text)
}

/**
 * 工作台：文件读写、编辑的唯一性校验、路径沙箱、网页抓取。
 *
 * 这里要紧的不是「功能能不能跑」，而是「跑错时怎么办」：改文件找不到内容必须拒绝、
 * 出现多次必须拒绝、`../` 必须拒绝。这些地方错了不会报错，只会安安静静改坏文件。
 */
private fun scenarioWorkbench(base: String) {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-bench-" + System.nanoTime())
    dir.mkdirs()
    val bench = Workbench(dir)

    fun call(name: String, vararg kv: Pair<String, Any>): JSONObject {
        val args = JSONObject()
        for ((k, v) in kv) args.put(k, v)
        return JSONObject(bench.execute(name, args))
    }

    val w1 = call("fs_write", "path" to "notes/a.md", "content" to "# 标题\n第一行\n第二行\n")
    result("bench_write_created", w1.optBoolean("ok") && w1.optBoolean("created"), w1.toString())

    val r1 = call("fs_read", "path" to "notes/a.md")
    result("bench_read_numbered", r1.optString("content").startsWith("1|# 标题"), r1.optString("content").take(40))
    result("bench_read_total", r1.optInt("total") == 3, r1.optInt("total").toString())

    val e1 = call("fs_edit", "path" to "notes/a.md", "old" to "第一行", "new" to "改过的第一行")
    result("bench_edit_ok", e1.optBoolean("ok") && e1.optInt("line") == 2, e1.toString())
    result("bench_edit_context", e1.optString("context").contains(">2|改过的第一行"), e1.optString("context").take(80))

    val e2 = call("fs_edit", "path" to "notes/a.md", "old" to "不存在的行", "new" to "x")
    result("bench_edit_no_match", !e2.optBoolean("ok") && e2.optString("code") == "NO_MATCH", e2.toString())

    call("fs_write", "path" to "dup.txt", "content" to "a\na\n")
    val e3 = call("fs_edit", "path" to "dup.txt", "old" to "a", "new" to "b")
    result("bench_edit_ambiguous", !e3.optBoolean("ok") && e3.optString("code") == "AMBIGUOUS", e3.toString())

    val esc = call("fs_read", "path" to "../../etc/passwd")
    result("bench_escape_blocked", !esc.optBoolean("ok"), esc.optString("code"))
    val escAbs = call("fs_read", "path" to "/etc/hosts")
    result("bench_abs_escape_blocked", !escAbs.optBoolean("ok"), escAbs.optString("code"))
    val dot = call("fs_read", "path" to "./notes/a.md")
    result("bench_dot_path_ok", dot.optBoolean("ok"), dot.optString("code"))

    val l = call("fs_list", "path" to ".", "depth" to 2)
    result("bench_list_nested", l.optString("entries").contains("notes/a.md"), l.optString("entries").take(100))

    val s = call("fs_search", "pattern" to "改过", "glob" to ".md")
    result("bench_search_hit", s.optInt("hits") >= 1 && s.optString("results").contains("notes/a.md"),
        s.toString().take(140))

    val nf = call("fs_read", "path" to "nope.txt")
    result("bench_read_missing", nf.optString("code") == "NOT_FOUND", nf.toString())

    val f = call("web_fetch", "url" to "$base/page")
    result("bench_fetch_ok", f.optBoolean("ok") && f.optString("content").contains("工作台测试页"),
        f.toString().take(160))
    result("bench_fetch_strip_tags", !f.optString("content").contains("<"), f.optString("content").take(80))

    val noBench = PhoneToolbox({ FakeDevice() }, { null })
    result("bench_schema_without", noBench.schemas().length() == 10, noBench.schemas().length().toString())
    val withBench = PhoneToolbox({ FakeDevice() }, { null }, null, bench)
    result("bench_schema_with", withBench.schemas().length() == 20, withBench.schemas().length().toString())

    val viaToolbox = withBench.execute("fs_write", JSONObject().put("path", "via.txt").put("content", "ok"))
    result("bench_via_toolbox", viaToolbox.text.contains("\"ok\":true"), viaToolbox.text.take(60))

    // ---- 文档生成：pptx_write（纯 JVM 真跑）+ pdf_write（引擎注入才可用）----
    fun benchNames(b: Workbench): List<String> =
        (0 until b.schemas().length()).map { b.schemas().getJSONObject(it).getJSONObject("function").getString("name") }

    val ppt = call(
        "pptx_write", "path" to "报告.pptx",
        "slides" to JSONArray()
            .put(JSONObject().put("title", "封面").put("bullets", JSONArray().put("第一条").put("第二条")))
            .put(JSONObject().put("title", "第二页").put("bullets", JSONArray())),
    )
    result("doc_pptx_ok", ppt.optBoolean("ok") && ppt.optInt("slides") == 2, ppt.toString())

    val pptxFile = File(dir, "报告.pptx")
    result("doc_pptx_exists", pptxFile.exists() && pptxFile.length() > 100, pptxFile.path)

    fun zipText(f: File, entry: String): String =
        java.util.zip.ZipFile(f).use { z ->
            z.getInputStream(z.getEntry(entry)).readBytes().toString(Charsets.UTF_8)
        }

    fun zipNames(f: File): List<String> =
        java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toList() }

    val names = zipNames(pptxFile)
    result("doc_pptx_zip_complete",
        names.containsAll(listOf(
            "[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml",
            "ppt/theme/theme1.xml", "ppt/slides/slide1.xml", "ppt/slides/slide2.xml",
        )), names.joinToString(","))
    val s1 = zipText(pptxFile, "ppt/slides/slide1.xml")
    result("doc_pptx_title_text", s1.contains("封面") && s1.contains("第一条"), s1.take(140))
    result("doc_pptx_escapes_xml", !s1.contains("<第一条"), "")
    result("doc_pptx_16_9", zipText(pptxFile, "ppt/presentation.xml").contains("cx=\"12192000\""), "")

    // 封面版式：第 1 页 = 满版深蓝底矩形 + 居中白字大标题
    result("doc_pptx_cover_bg", s1.contains("srgbClr val=\"14395B\"") && s1.contains("algn=\"ctr\""), "")
    result("doc_pptx_cover_white_title", s1.contains("sz=\"4400\"") && s1.contains("srgbClr val=\"FFFFFF\""), "")
    // 内容页：品牌色竖条 + 页码（页码在正文提取时会被过滤，但 XML 里必须在）
    val s2 = zipText(pptxFile, "ppt/slides/slide2.xml")
    result("doc_pptx_content_page_no", s2.contains(">2 / 2<") && s2.contains("srgbClr val=\"4B67B4\""), "")
    // 预览提取：页码行不进正文
    val mdPaged = OfficeText.pptxToMarkdown(pptxFile) ?: ""
    result("doc_pptx_md_no_pageno", !mdPaged.contains("2 / 2") && mdPaged.contains("第 2 页"), "")

    val pptEmpty = call("pptx_write", "path" to "x.pptx", "slides" to JSONArray())
    result("doc_pptx_empty_rejected", !pptEmpty.optBoolean("ok") && pptEmpty.optString("code") == "BAD_ARGS", pptEmpty.toString())
    val pptBadExt = call("pptx_write", "path" to "x.pdf", "slides" to JSONArray().put(JSONObject().put("title", "t")))
    result("doc_pptx_bad_ext", !pptBadExt.optBoolean("ok") && pptBadExt.optString("code") == "BAD_EXT", pptBadExt.toString())

    // ---- docx_write：真生成一份 Word，再解包逐项核对版式 ----
    val docx = call(
        "docx_write", "path" to "月报.docx", "title" to "月度经营报告",
        "blocks" to JSONArray()
            .put(JSONObject().put("type", "h1").put("text", "一、总体情况"))
            .put(JSONObject().put("type", "p").put("text", "这段有 **粗体** 和 `代码`，还有符号 & < >。"))
            .put(JSONObject().put("type", "bullet").put("text", "要点甲"))
            .put(JSONObject().put("type", "bullet").put("text", "要点乙"))
            .put(JSONObject().put("type", "quote").put("text", "引用的一句话"))
            .put(JSONObject().put("type", "pagebreak"))
            .put(JSONObject().put("type", "h2").put("text", "二、下一步"))
            .put(JSONObject().put("type", "p").put("text", "正文第二段")),
    )
    result("doc_docx_ok", docx.optBoolean("ok") && docx.optString("path") == "月报.docx", docx.toString())
    val docxFile = File(dir, "月报.docx")
    result("doc_docx_exists", docxFile.exists() && docxFile.length() > 800, docxFile.length().toString())
    result("doc_docx_zip_complete",
        zipNames(docxFile).containsAll(listOf(
            "[Content_Types].xml", "_rels/.rels", "docProps/core.xml",
            "word/document.xml", "word/styles.xml", "word/numbering.xml",
            "word/_rels/document.xml.rels",
        )), zipNames(docxFile).joinToString(","))

    val body = zipText(docxFile, "word/document.xml")
    result("doc_docx_title_centered", body.contains("月度经营报告") && body.contains("w:jc w:val=\"center\""), body.take(160))
    result("doc_docx_headings", body.contains("w:pStyle w:val=\"Heading1\"") &&
        body.contains("w:pStyle w:val=\"Heading2\""), "")
    result("doc_docx_heading_outline",
        zipText(docxFile, "word/styles.xml").contains("w:outlineLvl w:val=\"0\""), "大纲级别")
    // 粗体是 run 属性，不是留在正文里的星号；`代码` 走等宽字体
    val boldAt = body.indexOf("粗体")
    val boldRun = body.substring(body.lastIndexOf("<w:r>", boldAt), boldAt)
    result("doc_docx_bold_run", "<w:b/>" in boldRun && "**" !in body, boldRun.takeLast(80))
    result("doc_docx_code_run", body.contains("Consolas") && "`" !in body, "")
    result("doc_docx_bullets_real",
        body.contains("<w:numId w:val=\"1\"/>") &&
            zipText(docxFile, "word/numbering.xml").contains("w:lvlText w:val=\"•\""), "真项目符号")
    result("doc_docx_pagebreak", body.contains("<w:br w:type=\"page\"/>"), "")
    result("doc_docx_indent", body.contains("w:firstLineChars=\"200\""), "首行缩进两字")
    result("doc_docx_escape", body.contains("&amp;") && body.contains("&lt;") && !body.contains("& <"), "")
    result("doc_docx_a4", body.contains("w:w=\"11906\"") && body.contains("w:h=\"16838\""), "A4 页幅")
    result("doc_docx_core_title", zipText(docxFile, "docProps/core.xml").contains("月度经营报告"), "")

    // 预览回读：OfficeText 的 docx 提取器读得懂自己写的文件（产物卡能直接展示正文）
    val docxMd = OfficeText.docxToMarkdown(docxFile) ?: ""
    result("doc_docx_preview_roundtrip", docxMd.contains("总体情况") && docxMd.contains("要点甲"), docxMd.take(120))

    val docxEmpty = call("docx_write", "path" to "x.docx", "blocks" to JSONArray())
    result("doc_docx_empty_rejected", !docxEmpty.optBoolean("ok") && docxEmpty.optString("code") == "BAD_ARGS", docxEmpty.toString())
    val docxBadExt = call("docx_write", "path" to "x.pdf",
        "blocks" to JSONArray().put(JSONObject().put("type", "p").put("text", "hi")))
    result("doc_docx_bad_ext", !docxBadExt.optBoolean("ok") && docxBadExt.optString("code") == "BAD_EXT", docxBadExt.toString())

    // pdf_write：没引擎时工具不可见、调用报错；注入引擎后能真写出文件
    result("doc_pdf_hidden_without_engine", "pdf_write" !in benchNames(bench), benchNames(bench).joinToString(","))
    val pdfNoEngine = call("pdf_write", "path" to "a.pdf",
        "blocks" to JSONArray().put(JSONObject().put("type", "p").put("text", "hi")))
    result("doc_pdf_no_engine_rejected", !pdfNoEngine.optBoolean("ok") && pdfNoEngine.optString("code") == "NO_ENGINE",
        pdfNoEngine.toString())

    val withPdf = Workbench(dir, docEngine = object : Workbench.DocEngine {
        override fun writePdf(out: File, title: String, blocks: JSONArray): String {
            out.writeText("pdf-bytes")
            return JSONObject().put("ok", true).put("pages", 1).toString()
        }
    })
    result("doc_pdf_shown_with_engine", "pdf_write" in benchNames(withPdf), benchNames(withPdf).joinToString(","))
    val pdfRaw = withPdf.execute(
        "pdf_write",
        JSONObject()
            .put("path", "a.pdf")
            .put("title", "测试")
            .put("blocks", JSONArray().put(JSONObject().put("type", "p").put("text", "你好 PDF")))
    )
    val pdfObj = JSONObject(pdfRaw)
    result("doc_pdf_ok", pdfObj.optBoolean("ok") && pdfObj.optString("path") == "a.pdf", pdfRaw.take(120))
    result("doc_pdf_file_written", File(dir, "a.pdf").exists() && File(dir, "a.pdf").readText() == "pdf-bytes", "")
    val pdfBadArgs = JSONObject(withPdf.execute("pdf_write", JSONObject().put("path", "b.pdf")))
    result("doc_pdf_empty_blocks_rejected",
        !pdfBadArgs.optBoolean("ok") && pdfBadArgs.optString("code") == "BAD_ARGS", pdfBadArgs.toString())

    // 分页：模型读长文件时会用 from/lines，这是它唯一的"往后翻"手段
    val big = (1..50).joinToString("\n") { "line$it" }
    call("fs_write", "path" to "big.txt", "content" to big)
    val page = call("fs_read", "path" to "big.txt", "from" to 11, "lines" to 5)
    result("bench_read_paging",
        page.optInt("from") == 11 && page.optString("content").startsWith("11|line11") && page.optBoolean("truncated"),
        page.toString().take(120))

    // 超限要在写之前拒绝，不能先写坏再报错
    val over = call("fs_write", "path" to "huge.bin", "content" to "x".repeat(600 * 1024))
    result("bench_write_too_big", !over.optBoolean("ok") && over.optString("code") == "TOO_BIG", over.optString("code"))

    // schema 里声明了、execute 里没分发——这类漏配模型只会收到"没有这个工具"，
    // 然后反复重试同一件事。必须是每个声明过的工具都能路由到。
    val declared = (0 until withBench.schemas().length()).map {
        withBench.schemas().getJSONObject(it).getJSONObject("function").getString("name")
    }
    val unroutable = declared.filter { n ->
        val t = withBench.execute(n, JSONObject()).text
        val o = runCatching { JSONObject(t) }.getOrNull()
        // 只有「不认识这个工具」才算没路由上。非 JSON 的返回（如 phone_read_screen 的大纲）
        // 和结构化的参数校验拒绝（BAD_ARGS/BAD_URL）都是真实工具在干活。
        (o != null && o.optString("code") == "UNKNOWN_TOOL") ||
            t.contains("UNSUPPORTED") || t.contains("手机端没有")
    }
    result("bench_schema_all_routable", declared.size == 20 && unroutable.isEmpty(),
        "declared=${declared.size} bad=${unroutable.joinToString(",")}")

    dir.deleteRecursively()
}

/**
 * shell 工具。
 *
 * 桌面（Windows）没有 `/system/bin/sh`，所以解释器路径是注入的；找不到 sh 就只测
 * 「该拒的有没有拒」，不测真实执行。这比假装测过要好。
 */
private fun scenarioShell(shPath: String?) {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-sh-" + System.nanoTime())
    dir.mkdirs()
    val runner = shPath ?: "/bin/sh"

    fun names(b: Workbench): List<String> =
        (0 until b.schemas().length()).map { b.schemas().getJSONObject(it).getJSONObject("function").getString("name") }

    val off = Workbench(dir, shellEnabled = { false }, shellPath = runner)
    val denied = JSONObject(off.execute("shell", JSONObject().put("command", "echo hi")))
    result("shell_off_denied", !denied.optBoolean("ok") && denied.optString("code") == "SHELL_OFF", denied.toString())
    result("shell_hidden_when_off", "shell" !in names(off), names(off).joinToString(","))

    val on = Workbench(dir, shellEnabled = { true }, shellPath = runner)
    result("shell_shown_when_on", "shell" in names(on), names(on).joinToString(","))

    // 注定跑不通的命令，别让模型把轮次耗在上面
    val blocked = JSONObject(on.execute("shell", JSONObject().put("command", "reboot")))
    result("shell_blocked_hopeless", !blocked.optBoolean("ok") && blocked.optString("code") == "BLOCKED",
        blocked.toString())

    val tooLong = JSONObject(on.execute("shell", JSONObject().put("command", "x".repeat(5000))))
    result("shell_command_too_long", tooLong.optString("code") == "TOO_LONG", tooLong.optString("code"))

    val escapedCwd = JSONObject(on.execute("shell", JSONObject().put("command", "echo hi").put("cwd", "../../")))
    result("shell_cwd_escape_blocked", !escapedCwd.optBoolean("ok"), escapedCwd.optString("code"))

    if (shPath != null) {
        val echo = JSONObject(on.execute("shell", JSONObject().put("command", "echo bench-ok")))
        result("shell_echo", echo.optBoolean("ok") && echo.optString("output").contains("bench-ok"), echo.toString())

        val fail = JSONObject(on.execute("shell", JSONObject().put("command", "exit 3")))
        result("shell_exit_code", !fail.optBoolean("ok") && fail.optInt("exit") == 3, fail.toString())

        val pwd = JSONObject(on.execute("shell", JSONObject().put("command", "pwd")))
        result("shell_cwd_applied", pwd.optString("output").contains("wb-sh-"), pwd.optString("output"))

        // 超时必须真的 kill，而不是等到命令自己结束
        val slow = JSONObject(on.execute("shell", JSONObject().put("command", "sleep 5").put("timeout_sec", 1)))
        result("shell_timeout_killed", slow.optBoolean("timed_out") && !slow.optBoolean("ok"), slow.toString())

        // 大输出：截断，且不能把进程卡死（读完丢，不读会死锁）
        JSONObject(on.execute("fs_write", JSONObject().put("path", "big.txt").put("content", "x".repeat(100_000))))
        val flood = JSONObject(on.execute("shell", JSONObject().put("command", "cat big.txt")))
        result("shell_output_capped",
            flood.optBoolean("truncated") && flood.optString("output").length <= Workbench.MAX_OUTPUT_CHARS,
            "len=" + flood.optString("output").length + " trunc=" + flood.optBoolean("truncated"))
    } else {
        println("  (跳过 shell 真实执行：本机没有 sh)")
    }

    dir.deleteRecursively()
}

/**
 * check: 每条 tool 消息都能对上前面某个 assistant 的 tool_calls。
 * 压缩最容易犯的错就是把这一对劈开，而服务端只会回一个 400，看不出原因。
 */
private fun pairingOk(hist: JSONArray): Boolean {
    val pending = HashSet<String>()
    for (i in 0 until hist.length()) {
        val m = hist.optJSONObject(i) ?: continue
        when (m.optString("role")) {
            "assistant" -> m.optJSONArray("tool_calls")?.let { calls ->
                for (j in 0 until calls.length()) pending.add(calls.optJSONObject(j).optString("id"))
            }
            "tool" -> if (!pending.remove(m.optString("tool_call_id"))) return false
        }
    }
    return true
}

/**
 * 上下文压缩。
 *
 * 历史形状必须按真机来构造：**一条 user 指令 + 几十轮 tool 往返**。
 * 第一版把切点限定在 user 上，这种历史永远压不动——用「多条 user」的假数据测，会假绿。
 */
private fun scenarioCompact(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(CloudClient("$base/L", "hy3", FakeTokens("t0", null)), toolbox, 4)

    val hist = JSONArray().put(JSONObject().put("role", "user").put("content", "原始任务：读出系统版本号"))
    for (i in 1..40) {
        hist.put(
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "c$i").put("type", "function")
                        .put("function", JSONObject().put("name", "phone_read_screen").put("arguments", "{}"))
                )
            )
        )
        hist.put(
            JSONObject().put("role", "tool").put("tool_call_id", "c$i")
                .put("content", "{\"ok\":true,\"content\":\"" + "x".repeat(1500) + "\"}")
        )
    }
    loop.restoreHistory(hist)
    val before = loop.historySize()
    result("compact_precondition", before == 81, before.toString())

    val rec = Recorder()
    loop.run("继续", rec)

    result("compact_happened", loop.compactions >= 1, loop.compactions.toString())
    result("compact_shrank", loop.historySize() < before, "$before -> ${loop.historySize()}")
    result("compact_callback", rec.compacted >= 1, rec.compacted.toString())
    result("compact_no_error", rec.error.isEmpty(), rec.error)
    result("compact_reply", rec.finalText.contains("好的"), rec.finalText)

    val after = loop.exportHistory()
    result("compact_summary_first", after.getJSONObject(0).optString("content").contains("早期步骤摘要"),
        after.getJSONObject(0).optString("content").take(60))
    result("compact_pairing_intact", pairingOk(after))
    result("compact_kept_recent", after.length() <= 12, after.length().toString())
}

/**
 * 多级压缩不套娃。
 *
 * 真机上吃过的亏：第一次压缩的摘要是条 user 消息，第二次压缩时被当成用户的新目标
 * 原样收进「目标：」里——「目标：[早期步骤摘要…目标：[早期步骤摘要…目标：打开微信…]]」
 * 一层层包下去，模型最后连自己最初要干啥都看不见。
 * 正确行为：认出摘要（compact 标记 / 前缀兜底），解包继承最初的目标，过程行并入。
 */
private fun scenarioCompactNesting(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(CloudClient("$base/P", "hy3", FakeTokens("t0", null)), toolbox, 4)

    // 第一级摘要：故意用旧格式（没有 compact 标记，只有前缀）——验证兜底识别
    val hist = JSONArray().put(
        JSONObject().put("role", "user").put(
            "content",
            "[早期步骤摘要｜已压缩 20 条消息、18 次工具调用]\n" +
                "目标：打开微信给小罗发个你好\n过程：\n· 成功：读屏\n（细节已省略。）"
        )
    )
    for (i in 1..40) {
        hist.put(
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "n$i").put("type", "function")
                        .put("function", JSONObject().put("name", "phone_read_screen").put("arguments", "{}"))
                )
            )
        )
        hist.put(
            JSONObject().put("role", "tool").put("tool_call_id", "n$i")
                .put("content", "{\"ok\":true,\"content\":\"" + "y".repeat(1500) + "\"}")
        )
    }
    loop.restoreHistory(hist)
    val rec = Recorder()
    loop.run("继续", rec)

    result("compact2_no_error", rec.error.isEmpty(), rec.error)
    result("compact2_happened", loop.compactions >= 1, loop.compactions.toString())

    val after = loop.exportHistory()
    val s0 = after.getJSONObject(0).optString("content")
    // 最初的任务原样出现在目标行，没有被摘要头再包一层
    result("compact2_goal_unwrapped",
        s0.contains("目标：打开微信给小罗发个你好") && !s0.contains("目标：[早期步骤摘要"),
        s0.take(120))
    // 新摘要必须带上 compact 标记，下一轮压缩走标记识别（前缀匹配只是兜底）
    result("compact2_flagged", after.getJSONObject(0).optBoolean("compact"),
        after.getJSONObject(0).toString().take(80))
    // 三级压缩也不套：直接把带标记的摘要再喂进去压一轮
    val again = JSONArray().put(after.getJSONObject(0))
    for (i in 1..40) {
        again.put(
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "m$i").put("type", "function")
                        .put("function", JSONObject().put("name", "phone_read_screen").put("arguments", "{}"))
                )
            )
        )
        again.put(
            JSONObject().put("role", "tool").put("tool_call_id", "m$i")
                .put("content", "{\"ok\":true,\"content\":\"" + "z".repeat(1500) + "\"}")
        )
    }
    val loop2 = AgentLoop(CloudClient("$base/P", "hy3", FakeTokens("t0", null)), toolbox, 4)
    loop2.restoreHistory(again)
    val rec2 = Recorder()
    loop2.run("继续", rec2)
    result("compact3_no_error", rec2.error.isEmpty(), rec2.error)
    val s3 = loop2.exportHistory().getJSONObject(0).optString("content")
    result("compact3_goal_still_original",
        s3.contains("目标：打开微信给小罗发个你好") && !s3.contains("目标：[早期步骤摘要"),
        s3.take(120))
}

/**
 * 摘要目标解包（AgentLoop.summaryGoalOf）：压缩和界面恢复共用的纯函数。
 *
 * 旧版 bug 的存量摘要是套娃的——嵌套摘要里原始目标在「目标：[摘要头]」行的**下一行**，
 * 必须扫描全部「目标：」行取最后一条干净的。
 */
private fun scenarioSummaryGoal() {
    val flat = "[早期步骤摘要｜已压缩 3 条消息、1 次工具调用]\n" +
        "目标：读出系统版本号\n过程：\n· 成功\n（细节已省略。）"
    result("goal_flat", AgentLoop.summaryGoalOf(flat) == "读出系统版本号", AgentLoop.summaryGoalOf(flat))

    // 三级嵌套：目标行全是摘要头，原始目标在它们后面的行里（真机截图里的形态）
    val nested = "[早期步骤摘要｜已压缩 9 条消息、8 次工具调用]\n" +
        "目标：[早期步骤摘要｜已压缩 6 条消息、2 次工具调用]\n" +
        "目标：[早期步骤摘要｜已压缩 3 条消息、1 次工具调用]\n" +
        "目标：打开微信给小罗发个你好\n过程：\n· 成功\n（细节已省略。）"
    result("goal_nested_unwrap",
        AgentLoop.summaryGoalOf(nested) == "打开微信给小罗发个你好", AgentLoop.summaryGoalOf(nested))

    // 被截断的极端：干净的原始目标不在文本里，返回最深处能剥到的（调用方兜底显示）
    val truncated = "[早期步骤摘要｜已压缩 6 条消息、2 次工具调用]\n" +
        "目标：[早期步骤摘要｜已压缩 6 条消息、2 次"
    val g = AgentLoop.summaryGoalOf(truncated)
    result("goal_truncated_best_effort", g.isNotEmpty(), g)

    // 识别：旧格式靠前缀兜底，新格式靠 compact 标记，普通消息不能误伤
    val legacy = JSONObject().put("role", "user").put("content", flat)
    result("summary_detect_legacy_prefix", AgentLoop.isSummaryMessage(legacy))
    val tagged = JSONObject().put("role", "user").put("content", "你好").put("compact", true)
    result("summary_detect_tag", AgentLoop.isSummaryMessage(tagged))
    val plain = JSONObject().put("role", "user").put("content", "打开微信给小罗发个你好")
    result("summary_reject_plain", !AgentLoop.isSummaryMessage(plain))
}

/**
 * 产物链路：fs_write / fs_edit 成功后必须触发 onArtifact，且路径与磁盘事实一致。
 *
 * 卡片是 UI 的事，但「有没有报、报的路径对不对、文件是不是真的被写了」是协议层的事，
 * 在这里守。
 */
private fun scenarioArtifact(base: String) {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-art-" + System.nanoTime())
    dir.mkdirs()
    val toolbox = PhoneToolbox({ FakeDevice() }, { null }, null, Workbench(dir))
    val loop = AgentLoop(CloudClient("$base/M", "hy3", FakeTokens("t0", null)), toolbox, 4)
    val rec = Recorder()
    loop.run("写个文件再改一行", rec)

    result("artifact_reported", rec.artifacts.size == 2 && rec.artifacts.distinct().size == 1,
        rec.artifacts.joinToString(","))
    result("artifact_path_right", rec.artifacts.firstOrNull() == "notes/todo.md",
        rec.artifacts.joinToString(","))

    val f = File(dir, "notes/todo.md")
    result("artifact_file_exists", f.exists(), f.path)
    result("artifact_edit_applied", f.exists() && f.readText().contains("改好的第二行"),
        if (f.exists()) f.readText().take(80) else "missing")

    result("artifact_loop_no_error", rec.error.isEmpty(), rec.error)
    result("artifact_final_text", rec.finalText.contains("完成"), rec.finalText)

    dir.deleteRecursively()
}

/**
 * 多会话存储：落盘→列表→读回→删除，标题派生，截断保配对。
 *
 * 会话功能最容易坏的不是「存上了」，而是「存的是最后状态」：
 * 保存必须反映 fs_edit 之后的历史，切回来看到的得是最新内容。
 */
private fun scenarioSessionStore() {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-sess-" + System.nanoTime())
    val store = SessionStore(dir)

    // 空会话不值得留：save 空 history 等于删除，不会留一个空文件
    store.save("empty", JSONArray())
    result("sess_empty_not_saved", !File(dir, "empty.json").exists(), dir.path)

    // 写→改→存：读回来的历史要包含 edit 之后的内容
    val h1 = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "帮我写个待办清单应用"))
    val toolCall = JSONObject().put("id", "t1").put("type", "function").put(
        "function", JSONObject().put("name", "fs_write").put("arguments", "{}"))
    val h2 = JSONArray(h1.toString())
        .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(toolCall)))
        .put(JSONObject().put("role", "tool").put("tool_call_id", "t1").put("content", "{\"ok\":true}"))
        .put(JSONObject().put("role", "assistant").put("content", "写好了"))
    store.save("a", h2)
    // 再「改一轮」：带图的消息落盘时 base64 被剥掉
    val h3 = JSONArray(h2.toString())
        .put(JSONObject().put("role", "user").put("content", JSONArray().put(
            JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,AAAA")))))
    store.save("a", h3)
    val back = store.load("a")
    result("sess_latest_saved", back != null && back.length() == h3.length(), back?.length().toString())
    result("sess_image_stripped",
        back?.toString()?.contains("base64") == false, "base64 残留")

    val metas = store.list()
    result("sess_listed", metas.size == 1 && metas[0].id == "a", metas.toString())
    result("sess_title_from_user", metas[0].title.contains("待办清单"), metas[0].title)
    result("sess_count_right", metas[0].count == back?.length(), metas[0].count.toString())

    // 标题稳定：第二次保存不换标题
    store.save("a", JSONArray(h3.toString()).put(
        JSONObject().put("role", "assistant").put("content", "继续")))
    result("sess_title_stable", store.list().first().title == metas[0].title, store.list().first().title)

    // 排序：最近更新的在前
    Thread.sleep(15)
    store.save("b", JSONArray().put(JSONObject().put("role", "user").put("content", "第二件事")))
    result("sess_sorted_recent_first", store.list().first().id == "b", store.list().map { it.id }.joinToString(","))

    // 删除：文件和索引一起走
    store.delete("a")
    result("sess_deleted", store.load("a") == null && store.list().none { it.id == "a" },
        store.list().map { it.id }.joinToString(","))

    // 截断：切点不落在 tool 上（user 只是优先项，不是必要条件——
    // 手机任务常常只有一条 user，等它就会截出空数组），tool 与 tool_calls 不被劈开
    val big = JSONArray().put(JSONObject().put("role", "user").put("content", "老任务"))
    for (i in 1..120) {
        big.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
            "tool_calls", JSONArray().put(JSONObject().put("id", "c$i").put("type", "function")
                .put("function", JSONObject().put("name", "phone_read_screen").put("arguments", "{}")))))
        big.put(JSONObject().put("role", "tool").put("tool_call_id", "c$i").put("content", "{\"ok\":true}"))
    }
    val trimmed = SessionStore.trim(big, 100)
    result("sess_trim_size", trimmed.length() in 1..100, trimmed.length().toString())
    result("sess_trim_starts_user", trimmed.getJSONObject(0).optString("role") != "tool",
        trimmed.getJSONObject(0).optString("role"))
    var okPair = true
    val pending = HashSet<String>()
    for (i in 0 until trimmed.length()) {
        val m = trimmed.optJSONObject(i) ?: continue
        when (m.optString("role")) {
            "assistant" -> m.optJSONArray("tool_calls")?.let { calls ->
                for (j in 0 until calls.length()) pending.add(calls.optJSONObject(j).optString("id"))
            }
            "tool" -> if (!pending.remove(m.optString("tool_call_id"))) okPair = false
        }
    }
    result("sess_trim_pairing", okPair)

    // 分叉前缀：切点必须落在 assistant 上——tool 永远紧跟它的 assistant，
    // 切在 assistant 上配对必然完整；切在 tool 上会留下没人认领的结果，必须拒绝
    val forkHist = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "查一下天气"))
        .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
            "tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", "phone_read_screen").put("arguments", "{}")))))
        .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "{\"ok\":true}"))
        .put(JSONObject().put("role", "assistant").put("content", "今天是晴天"))
    val forkPrefix = SessionStore.prefixUpTo(forkHist, 3)
    result("sess_fork_prefix",
        forkPrefix != null && forkPrefix.length() == 4 &&
            forkPrefix.getJSONObject(3).optString("content") == "今天是晴天",
        forkPrefix?.length().toString())
    result("sess_fork_reject_tool", SessionStore.prefixUpTo(forkHist, 2) == null,
        "切在 tool 上必须拒绝")
    result("sess_fork_reject_range",
        SessionStore.prefixUpTo(forkHist, 9) == null && SessionStore.prefixUpTo(forkHist, -1) == null,
        "越界必须拒绝")
    result("sess_fork_reject_user", SessionStore.prefixUpTo(forkHist, 0) == null,
        "切在 user 上不是一条完整回答，必须拒绝")

    dir.deleteRecursively()
}

/**
 * Office 产物预览的文本提取：pptx → 每页标题+要点、docx → 段落、zip → 清单。
 * pptx 用真生成器产出再回读（端到端）；docx 手造最小 zip；坏文件必须给 null 退说明卡。
 */
private fun scenarioMemory() {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-mem-" + System.nanoTime())
    dir.mkdirs()

    result("mem_digest_empty", Memory.digest(dir) == "", "")
    result("mem_append", Memory.append(dir, "用户叫罗唯珍，河南财经政法大学数据科学专业大二") != null, "")

    val line = Memory.entries(dir).firstOrNull() ?: ""
    result("mem_line_format", line.startsWith("- ") && line.contains(" · "), line)
    result("mem_dedupe", Memory.append(dir, "用户叫罗唯珍，河南财经政法大学数据科学专业大二") == null, "")
    result("mem_blank", Memory.append(dir, "   ") == null, "")
    val long = Memory.append(dir, "长".repeat(400))
    result("mem_truncate", long != null && long.length <= Memory.MAX_ENTRY_CHARS + 20, long?.length?.toString() ?: "null")

    val d = Memory.digest(dir)
    result("mem_digest_has_entry", d.contains("罗唯珍") && d.contains("长期记忆"), d.take(120))
    result("mem_digest_framing", d.contains("不要问用户第二遍") && d.contains("以用户这一轮为准"), "")
    result("mem_stamp", Memory.stamp(0L).matches(Regex("\\d{4}-\\d{2}-\\d{2}")), Memory.stamp(0L))

    // 注入只带最近 20 条：记 22 条，最早的 2 条不该出现
    Memory.clear(dir)
    for (i in 0 until 22) Memory.append(dir, "第%02d条记忆".format(i))
    val d2 = Memory.digest(dir)
    result("mem_inject_limit", d2.contains("第21条") && !d2.contains("第00条") && !d2.contains("第01条"),
        d2.lines().size.toString())
    result("mem_inject_keeps_recent", d2.contains("第02条"), "")

    // Workbench 工具口径：正常记下 / 重复拒绝 / schema 里有它
    val bench = Workbench(dir)
    val args = JSONObject().put("text", "用户偏好：回答要短，别用表情")
    val ok = JSONObject(bench.execute("memory_save", args))
    result("mem_tool_ok", ok.optBoolean("ok") && ok.optString("saved").contains("回答要短"), ok.toString().take(120))
    val dup = JSONObject(bench.execute("memory_save", args))
    result("mem_tool_dedupe", !dup.optBoolean("ok") && dup.optString("code") == "NOT_SAVED", dup.toString().take(80))

    val names = (0 until bench.schemas().length()).map {
        bench.schemas().getJSONObject(it).getJSONObject("function").getString("name")
    }
    result("mem_schema_present", "memory_save" in names, names.joinToString(","))

    Memory.clear(dir)
    result("mem_clear", Memory.entries(dir).isEmpty() && Memory.digest(dir) == "", "")
}

private fun scenarioOfficeText() {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-office-" + System.nanoTime())
    dir.mkdirs()
    val bench = Workbench(dir)
    fun call(name: String, vararg kv: Pair<String, Any>): JSONObject {
        val args = JSONObject()
        for ((k, v) in kv) args.put(k, v)
        return JSONObject(bench.execute(name, args))
    }

    // 1) pptx：真生成 → 回读，第一页有标题和要点，第二页空页有提示
    call("pptx_write", "path" to "预览.pptx",
        "slides" to JSONArray()
            .put(JSONObject().put("title", "封面标题").put("bullets", JSONArray().put("要点甲").put("要点乙")))
            .put(JSONObject().put("title", "只有标题的页").put("bullets", JSONArray())))
    val md = OfficeText.pptxToMarkdown(File(dir, "预览.pptx"))
    result("office_pptx_extract", md != null && md.contains("封面标题") && md.contains("- 要点甲"), md?.take(120) ?: "null")
    result("office_pptx_page2", md != null && md.contains("第 2 页") && md.contains("只有标题的页"), md ?: "")
    // 自己写的转义必须能解回来
    result("office_unescape", OfficeText.xmlUnescape("a&lt;b&gt;&amp;c") == "a<b>&c", "")

    // 2) docx：手造最小包——w:p 段落、w:t 文本、&amp; 转义、编号行当小标题
    val docx = File(dir, "手册.docx")
    java.util.zip.ZipOutputStream(docx.outputStream()).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
        val xml = """<?xml version="1.0"?><w:document xmlns:w="w">
<w:body>
<w:p><w:r><w:t>一、开始使用</w:t></w:r></w:p>
<w:p><w:r><w:t>正文第一段，带 &amp; 转义</w:t></w:r></w:p>
<w:p><w:r><w:t></w:t></w:r></w:p>
<w:p><w:r><w:t>第二段</w:t></w:r></w:p>
</w:body></w:document>"""
        z.write(xml.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }
    val docMd = OfficeText.docxToMarkdown(docx)
    result("office_docx_extract", docMd != null && docMd.contains("## 一、开始使用") && docMd.contains("第二段"),
        docMd?.take(120) ?: "null")
    result("office_docx_unescaped", docMd != null && docMd.contains("带 & 转义"), "")
    result("office_docx_skips_empty", docMd != null && !docMd.contains("   "), "")

    // 3) zip 清单 + 坏文件退 null
    val zipF = File(dir, "包.zip")
    java.util.zip.ZipOutputStream(zipF.outputStream()).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("a/b.txt")); z.write("hi".toByteArray()); z.closeEntry()
    }
    val zmd = OfficeText.zipToMarkdown(zipF)
    result("office_zip_list", zmd.contains("a/b.txt") && zmd.contains("个条目"), zmd.take(80))
    result("office_pptx_bad_null", OfficeText.pptxToMarkdown(zipF) == null, "zip 不是 pptx 应为 null")
    result("office_docx_bad_null", OfficeText.docxToMarkdown(zipF) == null, "zip 没有 document.xml 应为 null")
    val garbage = File(dir, "垃圾.docx").apply { writeText("not a zip") }
    result("office_garbage_null", OfficeText.docxToMarkdown(garbage) == null, "非 zip 应为 null")

    dir.deleteRecursively()
}

/**
 * 讯飞听写协议层：鉴权签名逐字节复算、请求帧结构、结果解析（增量拼接/结束/错误码）、
 * WebSocket 帧编解码回环。纯 JVM，与 VoiceIn 的录音/连接解耦。
 */
private fun scenarioIat() {
    // ---- 时间格式：RFC1123 GMT，服务端对 date 极敏感
    val date = Iat.rfc1123Now()
    result("iat_rfc1123", Regex("^\\w{3}, \\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT$").matches(date), date)

    // ---- 鉴权 URL：用同一套 javax.crypto 在测试侧独立复算签名，逐段对上
    val appId = "test_app"
    val apiKey = "ak_test"
    val apiSecret = "sk_test"
    val url = Iat.authUrl(appId, apiKey, apiSecret, date)
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
    val origin = "host: ${Iat.HOST}\ndate: $date\nGET ${Iat.PATH} HTTP/1.1"
    val sig = java.util.Base64.getEncoder().encodeToString(mac.doFinal(origin.toByteArray()))
    val authOrigin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$sig\""
    val auth = java.util.Base64.getEncoder().encodeToString(authOrigin.toByteArray())
    // 与 Iat.urlEncode 一致的独立实现：授权与 date 都要百分号编码（+ / = 空格逗号一个不漏）
    fun pct(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray()) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') sb.append(c)
            else sb.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }
    val urlOk = url.startsWith("wss://${Iat.HOST}${Iat.PATH}?authorization=${pct(auth)}") &&
        "&date=${pct(date)}" in url &&
        "&host=${Iat.HOST}" in url
    result("iat_auth_url", urlOk, url.take(120))

    // ---- 三种请求帧
    val start = Iat.startFrame(appId, "QUJD")
    result("iat_start_frame", "\"app_id\":\"$appId\"" in start && "\"status\":0" in start &&
        "\"vad_eos\":${Iat.VAD_EOS_MS}" in start && "\"audio\":\"QUJD\"" in start &&
        "audio/L16;rate=16000" in start, start.take(120))
    val mid = Iat.audioFrame("REJB")
    result("iat_audio_frame", "\"status\":1" in mid && "\"audio\":\"REJB\"" in mid, mid)
    val fin = Iat.endFrame()
    result("iat_end_frame", "\"status\":2" in fin && "\"audio\":\"\"" in fin, fin)

    // ---- 结果解析：增量词拼接、中间态、结束态
    val r1 = Iat.parseReply(
        """{"code":0,"message":"success","sid":"s1","data":{"status":0,"result":{"ws":[{"cw":[{"sc":0,"w":"今"}]},{"cw":[{"sc":0,"w":"天"}]}]}}}""")
    result("iat_parse_partial", r1.ok && !r1.end && r1.text == "今天", r1.toString())

    val r2 = Iat.parseReply(
        """{"code":0,"message":"success","data":{"status":2,"result":{"ws":[{"cw":[{"sc":0,"w":"天气"}]},{"cw":[{"sc":0,"w":"好"}]}]}}}""")
    result("iat_parse_final", r2.end && r2.text == "天气好", r2.toString())

    // 空结果（服务端在静音段会回 ws 为空的结果对象）
    val r3 = Iat.parseReply("""{"code":0,"message":"success","data":{"status":1,"result":{}}}""")
    result("iat_parse_empty", r3.ok && r3.text.isEmpty() && !r3.end, r3.toString())

    val r4 = Iat.parseReply("""{"code":10105,"message":"invalid appid"}""")
    result("iat_parse_error", !r4.ok && r4.code == 10105 && r4.text.isEmpty(), r4.toString())
    result("iat_error_text", Iat.errorText(10105).contains("APPID") && Iat.errorText(11200).contains("额度"),
        "${Iat.errorText(10105)} / ${Iat.errorText(11200)}")

    // ---- WebSocket 帧：客户端掩码 + 读回（ByteArrayInputStream 充当网络流）
    fun roundtrip(tag: String, opcode: Int, payload: ByteArray) {
        val masked = Iat.clientFrame(opcode, payload, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        val (op, got) = Iat.readFrame(java.io.ByteArrayInputStream(masked))
        result(tag, op == opcode && got.contentEquals(payload), "opcode=$op len=${got.size}")
    }
    // 手动解掩码对照：掩码只做 XOR，独立验证一遍
    val payload = "hello 帧".toByteArray()
    val maskKey = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    val frame = Iat.clientFrame(1, payload, maskKey)
    val unmasked = frame.sliceArray(6 until frame.size)
        .mapIndexed { i, b -> (b.toInt() xor maskKey[i and 3].toInt()).toByte() }.toByteArray()
    result("iat_frame_mask_xor", unmasked.contentEquals(payload), "len=${frame.size}")

    roundtrip("iat_frame_rt_text", 1, "小载荷".toByteArray())
    roundtrip("iat_frame_rt_16bit", 2, ByteArray(300) { it.toByte() })     // 走 16 位扩展长度
    roundtrip("iat_frame_rt_64bit", 2, ByteArray(70_000) { it.toByte() })  // 走 64 位扩展长度
    roundtrip("iat_frame_rt_ping", 9, byteArrayOf())                       // ping 空载荷
    result("iat_frame_small_len", Iat.clientFrame(1, byteArrayOf(1, 2), maskKey).size == 2 + 4 + 2, "最小帧头")
}

/** 附件消息结构：没图时必须是纯文本（兼容不支持多模态的模型），有图时才拼 parts。 */
private fun scenarioAttach() {    val plain: Any = AgentLoop.userContent("你好", null)
    result("attach_plain_text", plain is String && plain == "你好", plain.toString())

    val blank: Any = AgentLoop.userContent("你好", emptyList())
    result("attach_blank_image_is_text", blank is String && blank == "你好", blank.toString())

    val withImg = AgentLoop.userContent(
        "这张图里有几个字", listOf("data:image/jpeg;base64,AAA")) as? JSONArray
    val ok = withImg != null && withImg.length() == 2 &&
        withImg.getJSONObject(0).optString("type") == "text" &&
        withImg.getJSONObject(0).optString("text") == "这张图里有几个字" &&
        withImg.getJSONObject(1).optString("type") == "image_url" &&
        withImg.getJSONObject(1).getJSONObject("image_url").optString("url")
            .startsWith("data:image/jpeg;base64,")
    result("attach_parts", ok, withImg?.toString()?.take(100) ?: "null")

    // 多选：N 张图就是 text + N 个 image_url，顺序跟用户勾选的一致
    val multi = AgentLoop.userContent(
        "对比这三张图", listOf("data:image/jpeg;base64,A", "data:image/jpeg;base64,B", "data:image/jpeg;base64,C")
    ) as? JSONArray
    var multiOk = multi != null && multi.length() == 4 &&
        multi.getJSONObject(0).optString("type") == "text"
    if (multi != null) {
        for (j in 1..3) {
            multiOk = multiOk &&
                multi.getJSONObject(j).optString("type") == "image_url" &&
                multi.getJSONObject(j).getJSONObject("image_url").optString("url") ==
                "data:image/jpeg;base64,${"ABC"[j - 1]}"
        }
    }
    result("attach_parts_multi", multiOk, multi?.toString()?.take(120) ?: "null")

    // PDF 页码范围解析：区间、单页、混写、全角符号，以及各种写错的样子
    fun pp(input: String, total: Int = 30, max: Int = 6): String =
        FileText.parsePages(input, total, max)?.joinToString(",") ?: "null"
    result("pages_range", pp("7-12") == "6,7,8,9,10,11", pp("7-12"))
    result("pages_single", pp("5") == "4", pp("5"))
    result("pages_mixed", pp("1-3,20") == "0,1,2,19", pp("1-3,20"))
    result("pages_fullwidth", pp("７－８，９") == "6,7,8", pp("７－８，９"))
    result("pages_overflow_total", pp("25-40") == "null", pp("25-40"))
    result("pages_reverse", pp("9-3") == "null", pp("9-3"))
    result("pages_garbage", pp("abc") == "null", pp("abc"))
    result("pages_empty", pp("") == "null" && pp(",,") == "null", pp(",,"))
    result("pages_cap", pp("1-30", max = 6) == "0,1,2,3,4,5", pp("1-30", max = 6))
    result("pages_cap_mid", pp("10-30", max = 6) == "9,10,11,12,13,14", pp("10-30", max = 6))
}

/**
 * 技能包：种、解析、索引、加载、去重、路径穿越。
 *
 * 这一块最容易出错的地方不是解析 Markdown，而是两件「看不见」的事：
 *  - 索引是不是按开关裁剪了（关掉的技能不能还挂在提示里）；
 *  - 同一轮里重复加载会不会把三五千字再塞一遍。
 */
private fun scenarioSkill() {
    val root = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-skill-" + System.nanoTime())
    root.mkdirs()
    val d = File(root, Skill.DIR)

    // 1) 首装：内置技能落到 skills/，写标记；再装一次什么都不做
    val first = Skill.install(root)
    result("skill_install_count", first == Skill.BUILTIN_COUNT, first.toString())
    val names0 = Skill.scan(root).map { it.name }
    result("skill_install_files", names0.size == Skill.BUILTIN_COUNT, names0.joinToString(","))
    result("skill_install_marker", File(d, ".seeded").isFile, "")
    result("skill_install_once", Skill.install(root) == 0 && Skill.scan(root).size == Skill.BUILTIN_COUNT, "")

    // 2) frontmatter 解析
    val wr = Skill.scan(root).firstOrNull { it.name == "weekly-report" }
    result("skill_meta_title", wr?.title == "周报", wr?.title ?: "null")
    result("skill_meta_desc", (wr?.desc?.length ?: 0) in 1..Skill.MAX_INDEX_DESC, wr?.desc ?: "null")
    result("skill_meta_when", wr?.whenToUse?.contains("周报") == true, wr?.whenToUse ?: "")
    result("skill_meta_bytes", (wr?.bytes ?: 0) > 500, (wr?.bytes ?: 0).toString())

    // 3) 正文：去掉 frontmatter，内容完整
    val body = Skill.body(wr!!)
    result("skill_body_no_front", !body.contains("name: weekly-report") && !body.startsWith("---"), body.take(40))
    result("skill_body_content", body.startsWith("# 周报") && body.contains("本周完成"), body.take(40))

    // 4) 索引：有名字、有触发条件、有「怎么取」的说明；停用的不出现；空目录返回空串
    val idx = Skill.index(root)
    result("skill_index_has", idx.contains("weekly-report") && idx.contains("用于：") && idx.contains("skill_load"),
        idx.replace('\n', ' ').take(100))
    result("skill_index_all", Skill.BUILTIN_COUNT == idx.lines().count { it.startsWith("- ") },
        idx.lines().count { it.startsWith("- ") }.toString())
    val off = Skill.index(root, setOf("weekly-report"))
    result("skill_index_disabled", !off.contains("weekly-report") && off.contains("resume"),
        off.replace('\n', ' ').take(80))
    result("skill_index_empty", Skill.index(File(root, "nowhere")) == "", "")

    // 5) 没有 frontmatter 的朴素文件也能用（用户丢进来一个手写 md 是常态）
    File(d, "handmade.md").writeText("# 我的技能\n\n这是第一行描述。\n\n正文。\n", Charsets.UTF_8)
    val hm = Skill.scan(root).firstOrNull { it.name == "handmade" }
    result("skill_bare_title", hm?.title == "我的技能", hm?.title ?: "null")
    result("skill_bare_desc", hm?.desc?.startsWith("这是第一行描述") == true, hm?.desc ?: "null")

    // 6) 删掉的技能不会自己长回来（尊重删除）
    File(d, "resume.md").delete()
    Skill.install(root)
    result("skill_respect_delete", Skill.scan(root).none { it.name == "resume" }, "")

    // 7) 超长正文截断，并说明截了
    File(d, "long.md").writeText(
        "---\nname: long\ntitle: 长技能\n---\n" + "长".repeat(Skill.MAX_BODY_CHARS + 400), Charsets.UTF_8)
    val lb = Skill.body(Skill.scan(root).first { it.name == "long" })
    result("skill_body_truncate", lb.length <= Skill.MAX_BODY_CHARS + 40 && lb.contains("已截断"), lb.length.toString())

    // 8) Workbench 口径：加载、重复加载、任务开始清标记、未知名字、空白、穿越
    val bench = Workbench(root)
    fun load(name: String): JSONObject =
        JSONObject(bench.execute("skill_load", JSONObject().put("name", name)))

    val got = load("weekly-report")
    result("skill_tool_ok", got.optBoolean("ok") && got.optString("body").contains("本周完成"),
        got.toString().take(100))
    result("skill_tool_note", got.optString("note").contains("按这份技能的步骤做"), got.optString("note"))

    val again = load("weekly-report")
    result("skill_tool_repeat_skipped", again.optBoolean("already_loaded") && again.optString("body").isEmpty(),
        again.toString().take(100))

    // 新任务 = 新历史，标记必须清掉，否则模型会拿到一个「已加载」的空壳
    bench.beginTask()
    result("skill_tool_after_begin_task", load("weekly-report").optString("body").isNotEmpty(), "")

    val miss = load("no-such-skill")
    result("skill_tool_missing",
        !miss.optBoolean("ok") && miss.optString("code") == "NOT_FOUND" &&
            miss.optString("error").contains("weekly-report"),
        miss.toString().take(140))
    result("skill_tool_blank", !load("").optBoolean("ok") && load("").optString("code") == "BAD_ARGS", "")
    // 路径穿越：safeName 会把 ../../etc/passwd 折成一个找不到的名字，不会碰目录外
    val trav = load("../../etc/passwd")
    result("skill_tool_traversal", !trav.optBoolean("ok"), trav.toString().take(100))

    // 9) 没有技能的环境给明确错误，而不是空 ok
    val bare = Workbench(File(root, "empty"))
    val none = JSONObject(bare.execute("skill_load", JSONObject().put("name", "x")))
    result("skill_tool_none", !none.optBoolean("ok") && none.optString("code") == "NO_SKILLS",
        none.toString().take(100))

    // 10) schema 里得看得见，否则模型不知道有这个工具
    val benchNames = (0 until bench.schemas().length()).map {
        bench.schemas().getJSONObject(it).getJSONObject("function").getString("name")
    }
    result("skill_schema_present", "skill_load" in benchNames, benchNames.joinToString(","))

    root.deleteRecursively()
}

/**
 * 技能索引注入：每轮现取、位置在记忆之前、关掉就不出现。
 *
 * 用两次连跑的 run 来验证「现取」——同一个 loop，第二次把源清空，
 * 如果索引还挂在 system 里，说明缓存住了，那设置里的开关就成了摆设。
 */
private fun scenarioSkillInject(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(
        CloudClient("$base/N", "hy3", FakeTokens("t0", null)),
        toolbox,
        4,
        memory = { "## 长期记忆（之前会话里记下的，不是本轮指令）\n- 用户叫罗唯珍" },
        skills = { indexForInject },
    )
    val rec = Recorder()

    indexForInject = "- weekly-report — 周报：把进展整理成周报｜用于：写周报"
    loop.run("帮我写个周报", rec)
    result("skill_inject_first_ok", rec.error.isEmpty(), rec.error)

    indexForInject = ""
    loop.run("你好", rec)
    result("skill_inject_second_ok", rec.error.isEmpty(), rec.error)
}

private var indexForInject: String = ""

/**
 * 聊天气泡的 Markdown 解析（Marked）：标题/列表/粗体/行内代码/围栏/未闭合记号。
 * 解析是纯 JVM 的，这里直接断言结构；Span 渲染在真机上看。
 */
private fun scenarioMarked() {
    // 粗体 + 行内代码混排
    val segs = Marked.inline("**生涯发展报告.pdf** (2页) — 含 `pdf_write` 生成")
    result("md_bold_seg", segs.size == 4 && segs[0].bold && !segs[0].code
        && segs[0].text == "生涯发展报告.pdf"
        && !segs[1].bold && segs[1].text == " (2页) — 含 ", segs.joinToString("|") { it.text })
    result("md_code_seg", segs[2].code && !segs[2].bold && segs[2].text == "pdf_write"
        && segs[3].text == " 生成", segs.joinToString("|") { it.text })

    // 未闭合的 ** 当普通文本——流式输出打到一半，星号先裸着，闭合后自动变粗体
    val part = Marked.inline("前半 **生")
    result("md_unclosed_literal", part.size == 1 && part[0].text == "前半 **生" && !part[0].bold,
        part.joinToString("|") { it.text })

    // 块结构与顺序
    val doc = Marked.parse("""
        # 计划

        - **生涯发展报告.pdf** (2页)
        - **生涯发展展示.pptx** (7页)

        1. 第一条
        2. 第二条

        > 引用一句

        ```kotlin
        val a = 1
        ```
    """.trimIndent())
    result("md_blocks", doc.map { it.kind } == listOf("h", "ul", "ol", "quote", "code"),
        doc.map { it.kind }.toString())
    val h = doc[0]
    result("md_h_level", h.level == 1 && h.lines[0][0].text == "计划", h.toString())
    val ul = doc[1]
    fun dump(b: Marked.Block) = b.lines.joinToString("||") { line ->
        line.joinToString("~") { it.text + (if (it.bold) "[B]" else "") } }
    result("md_ul_items", ul.lines.size == 2 && ul.lines[0][0].bold
        && ul.lines[0][0].text == "生涯发展报告.pdf" && ul.lines[1][0].bold
        && ul.lines[1][0].text == "生涯发展展示.pptx", dump(ul))
    result("md_ul_gap", ul.gap == 1, ul.gap.toString())
    val ol = doc[2]
    result("md_ol_number_bold", ol.lines.size == 2 && ol.lines[0][0].bold
        && ol.lines[0][0].text == "1. " && ol.lines[0][1].text == "第一条", ol.lines.toString())
    result("md_code_raw", doc[4].lines[0][0].text == "val a = 1", doc[4].toString())

    // 代码块：每行独立占一行，空行和缩进都要活着——之前整块压成一行，代码挤成一坨
    val code = Marked.parse("```python\nimport pandas as pd\n\ndf.isnull()\n```")
    result("md_code_lines", code.size == 1 && code[0].kind == "code"
        && code[0].lines.size == 3
        && code[0].lines[0][0].text == "import pandas as pd"
        && code[0].lines[1][0].text == ""
        && code[0].lines[2][0].text == "df.isnull()",
        code[0].lines.joinToString("||") { l -> l.joinToString("~") { it.text } })

    // 紧挨的列表项并成一块，空行隔开的分开
    val tight = Marked.parse("- a\n- b\n- c")
    result("md_tight_merge", tight.size == 1 && tight[0].lines.size == 3, tight.toString())
    val apart = Marked.parse("- a\n\n- b")
    result("md_apart_split", apart.size == 2 && apart[1].gap == 1, apart.toString())

    // --- 是分隔线，不是三个列表
    val hr = Marked.parse("上面\n\n---\n\n下面")
    result("md_hr", hr.map { it.kind } == listOf("p", "hr", "p"), hr.map { it.kind }.toString())

    // 纯文本原样成段，一个字都不动
    val plain = Marked.parse("就一句话")
    result("md_plain", plain.size == 1 && plain[0].kind == "p"
        && plain[0].lines[0][0].text == "就一句话", plain.toString())
}

/**
 * 朗读文本加工：去 Markdown、按块断句、超长截断、挑中文语音。
 * 这层是纯 JVM 的，念出来难不难听只能真机听，但「会不会念出星号」这里能钉死。
 */
private fun scenarioVoice() {
    val spoken = VoiceText.toSpeech(
        """
        # 关于我

        **罗唯珍** 是一名学生。

        - 就读于河南财经政法大学
        - 大二在读

        ```kotlin
        val a = 1
        ```
        """.trimIndent()
    )
    result("voice_no_mark", !spoken.contains("**") && !spoken.contains("#") && !spoken.contains("`"),
        spoken)
    result("voice_keep_text", spoken.contains("罗唯珍") && spoken.contains("河南财经政法大学"), spoken)
    result("voice_code_note", spoken.contains("代码"), spoken)
    result("voice_ends_sentence", spoken.trim().endsWith("。") || spoken.trim().endsWith("）"), spoken.takeLast(24))

    // 一句话的回答也要带句号，TTS 才知道该收尾
    result("voice_plain", VoiceText.toSpeech("就一句话") == "就一句话。", VoiceText.toSpeech("就一句话"))

    // 超长截断：切在句号上、明说还有、不出现「。。」
    val long = "第一句。".repeat(400)
    val clipped = VoiceText.clip(long)
    result("voice_clip_shorter", clipped.length < long.length, "${clipped.length} / ${long.length}")
    result("voice_clip_says_more", clipped.endsWith("我先念到这儿。"), clipped.takeLast(24))
    result("voice_clip_no_double_stop", !clipped.contains("。。"), clipped.take(30))
    result("voice_clip_short_untouched", VoiceText.clip("很短。") == "很短。", VoiceText.clip("很短。"))

    // 挑语音：中文优先，没中文退英文，没得挑就别动
    result("voice_pick_zh", VoiceText.pickLanguage(listOf("en-US", "zh-CN")) == "zh-CN",
        VoiceText.pickLanguage(listOf("en-US", "zh-CN")).toString())
    result("voice_pick_zh_any", VoiceText.pickLanguage(listOf("en-GB", "zh-TW")) == "zh-TW",
        VoiceText.pickLanguage(listOf("en-GB", "zh-TW")).toString())
    result("voice_pick_en_fallback", VoiceText.pickLanguage(listOf("de-DE", "en-GB")) == "en-GB",
        VoiceText.pickLanguage(listOf("de-DE", "en-GB")).toString())
    result("voice_pick_none", VoiceText.pickLanguage(emptyList()) == null, "null")
}

private fun scenarioFileText() {
    // 路由：文档、代码、数据、大小写、无扩展名
    result("ft_route_text", FileText.route("笔记.MD") == "text" && FileText.route("main.kt") == "text"
        && FileText.route("data.csv") == "text", FileText.route("笔记.MD"))
    result("ft_route_pdf", FileText.route("报告.pdf") == "pdf", FileText.route("报告.pdf"))
    result("ft_route_docx", FileText.route("合同.docx") == "docx", FileText.route("合同.docx"))
    result("ft_route_pptx", FileText.route("答辩.pptx") == "pptx", FileText.route("答辩.pptx"))
    result("ft_route_unsupported", FileText.route("报表.xlsx") == "unsupported"
        && FileText.route("README") == "unsupported", FileText.route("报表.xlsx"))

    val dir = createTempDir("ftx")
    // 文本直读 + 截断注明
    val txt = java.io.File(dir, "notes.md").apply {
        writeText("第一条：把手头的事做完。\n第二条：把做完的事写下来。")
    }
    val r1 = FileText.extract(txt, "notes.md")
    result("ft_text_read", r1.kind == "text" && r1.text.contains("第二条") && r1.text.startsWith("【文件"),
        r1.text.take(40))
    val big = java.io.File(dir, "big.log").apply { writeText("行。".repeat(FileText.MAX_CHARS + 500)) }
    val r2 = FileText.extract(big, "big.log")
    result("ft_text_cap", r2.text.length < FileText.MAX_CHARS + 100
        && r2.text.contains("只取了前"), "len=${r2.text.length}")

    // docx：用 DocxWriter 造一份真的，抽出来应含标题与正文
    val blocks = org.json.JSONArray()
        .put(org.json.JSONObject().put("type", "h1").put("text", "采购说明"))
        .put(org.json.JSONObject().put("type", "p").put("text", "本月采购打印机两台。"))
    val docx = java.io.File(dir, "采购.docx")
    org.json.JSONObject(DocxWriter.write(docx, "采购说明", blocks))
    val r3 = FileText.extract(docx, "采购.docx")
    result("ft_docx_extract", r3.kind == "docx" && r3.text.contains("采购说明")
        && r3.text.contains("打印机"), r3.text.take(60))

    // 抽不出字（空 docx）：给的是人话提示而不是空串
    val emptyDocx = java.io.File(dir, "empty.docx")
    org.json.JSONObject(DocxWriter.write(emptyDocx, "空", org.json.JSONArray()))
    val r4 = FileText.extract(emptyDocx, "empty.docx")
    result("ft_docx_empty_hint", r4.text.contains("抽不出文字"), r4.text.take(50))

    // 不支持的格式：明说支持范围
    val xls = java.io.File(dir, "报表.xlsx").apply { writeText("fake") }
    val r5 = FileText.extract(xls, "报表.xlsx")
    result("ft_unsupported_msg", r5.kind == "unsupported" && r5.text.contains("暂时读不了")
        && r5.text.contains("pdf"), r5.text.take(50))

    dir.deleteRecursively()
}

fun main(args: Array<String>) {
    val base = args.firstOrNull() ?: "http://127.0.0.1:8799"
    // 第二个参数是可用的 shell 路径，由 test_agent.py 探测后传入
    val shPath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    try {
        scenarioLoop(base)
        scenarioStaleIndex()
        scenarioOutlineWindows()
        scenarioRefresh(base)
        scenarioStream(base)
        scenarioNoTools(base)
        scenarioVision(base)
        scenarioRiskRules()
        scenarioGate()
        scenarioCoordTap()
        scenarioApprovalDenied(base)
        scenarioAbortAfterDenials(base)
        scenarioPersist(base)
        scenarioKeeper()
        scenarioReconnect()
        scenarioModelRegistry(base)
        scenarioTokenInfo()
        scenarioSamplingFallback(base)
        scenarioWorkbench(base)
        scenarioOfficeText()
        scenarioMemory()
        scenarioSkill()
        scenarioSkillInject(base)
        scenarioMarked()
        scenarioVoice()
        scenarioIat()
        scenarioFileText()
        scenarioAttach()
        scenarioShell(shPath)
        scenarioCompact(base)
        scenarioCompactNesting(base)
        scenarioSummaryGoal()
        scenarioArtifact(base)
        scenarioSessionStore()
        scenarioGatewayPlan()
        scenarioGatewayWire()
        scenarioGatewaySocket()
    } catch (e: Exception) {
        result("harness_crash", false, e.message ?: e.javaClass.simpleName)
    }
    println("DONE")
}
