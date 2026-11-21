// JVM 测试驱动：把协议层与 agent loop 直接跑在 JVM 上，不需要真机、不需要模拟器。
//
// 编译的文件只有 CloudClient / AgentLoop / PhoneTools / ScreenOutline / DeviceApi / Config
// ——它们都不引 Android API。手机特有的东西（无障碍、Keystore、SQLite）一概没进来，
// 所以这里验证的就是真机上跑的那份代码。

import cn.wbphone.agent.AgentLoop
import cn.wbphone.agent.ApprovalDecision
import cn.wbphone.agent.ArtifactServer
import cn.wbphone.agent.ApprovalGate
import cn.wbphone.agent.ApprovalPolicy
import cn.wbphone.agent.ApprovalPrompter
import cn.wbphone.agent.ApprovalRequest
import cn.wbphone.agent.CloudClient
import cn.wbphone.agent.DeviceApi
import cn.wbphone.agent.Errors
import cn.wbphone.agent.GatewayPlan
import cn.wbphone.agent.GatewayWire
import cn.wbphone.agent.Iat
import cn.wbphone.agent.KeeperPlan
import cn.wbphone.agent.Memory
import cn.wbphone.agent.ModelRegistry
import cn.wbphone.agent.OfficeText
import cn.wbphone.agent.ReconnectPlan
import cn.wbphone.agent.PhoneLease
import cn.wbphone.agent.PhoneToolbox
import cn.wbphone.agent.ReadOnlyToolbox
import cn.wbphone.agent.SubAgent
import cn.wbphone.agent.Risk
import cn.wbphone.agent.RiskRules
import cn.wbphone.agent.ScreenFacts
import cn.wbphone.agent.ScreenFactsReader
import cn.wbphone.agent.ScreenOutline
import cn.wbphone.agent.Marked
import cn.wbphone.agent.VoiceText
import cn.wbphone.agent.FileText
import cn.wbphone.agent.DocxWriter
import cn.wbphone.agent.ShotText
import cn.wbphone.agent.ChartText
import cn.wbphone.agent.XlsxWriter
import cn.wbphone.agent.TarGz
import cn.wbphone.agent.Toolbox
import cn.wbphone.agent.ToolResult
import kotlin.math.abs
import cn.wbphone.agent.Skill
import cn.wbphone.agent.TokenInfo
import cn.wbphone.agent.TokenSource
import cn.wbphone.agent.Think
import cn.wbphone.agent.SessionStore
import cn.wbphone.agent.TranscriptText
import cn.wbphone.agent.Workbench
import cn.wbphone.agent.FetchResult
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
    // 系统应用（时钟/拨号/短信）被 Intent 拉起后的样子：能读到屏幕，但没什么可点的。
    "intent" to listOf(
        Item("系统应用界面", 0, 200, 1080, 300),
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

    /** phone_intent 的记录：action 和参数都留底，断言靠它核对「真的按参数起了」 */
    val intents = ArrayList<Pair<String, JSONObject>>()

    override fun startIntent(action: String, args: JSONObject): JSONObject {
        intents.add(action to args)
        screen = "intent"
        return when (action) {
            "alarm" -> {
                val h = args.optInt("hour", -1)
                val m = args.optInt("minute", -1)
                if (h !in 0..23 || m !in 0..59) fail("alarm 需要 hour（0-23）和 minute（0-59）", "BAD_ARGS")
                else ok("action" to action, "opened" to "闹钟 $h:${m.toString().padStart(2, '0')}")
            }
            "dial" -> if (args.optString("number").isBlank()) fail("dial 需要 number", "BAD_ARGS")
            else ok("action" to action, "opened" to action)
            "share" -> if (args.optString("text").isBlank()) fail("share 需要 text", "BAD_ARGS")
            else ok("action" to action, "opened" to "分享面板")
            else -> ok("action" to action, "opened" to action)
        }
    }

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

    override fun onToolEnd(name: String, args: org.json.JSONObject, result: String, ok: Boolean) {
        results.add(result)
    }

    var compacted = 0
    val artifacts = ArrayList<String>()

    /** 网络层退避重发的理由，按发生顺序。 */
    val retries = ArrayList<String>()

    /** 「这一轮作废重来」发生了几次。 */
    var restarts = 0

    /**
     * 上一轮结束时的文本长度。作废重来要把这一轮的半截截掉——
     * 真实界面（MainActivity + ChatUi.restartTurn）就是这么干的，这里照做，
     * 否则测试里会看到「半截 + 完整」拼在一起，而真机上是干净的。
     */
    private var textMark = 0

    /** 每次「背景资料变化」的回报，格式 chars|preview。 */
    val extras = ArrayList<String>()

    /** 流式过来的思考，按收到顺序拼起来（验证思考没有在客户端被丢掉）。 */
    val think = StringBuilder()

    override fun onThinking(delta: String) {
        think.append(delta)
    }

    override fun onExtras(chars: Int, preview: String) {
        extras.add(chars.toString() + "|" + preview)
    }

    override fun onCompact(removed: Int, kept: Int) {
        compacted++
    }

    override fun onArtifact(path: String) {
        artifacts.add(path)
    }

    override fun onRetry(why: String) {
        retries.add(why)
    }

    override fun onRestart() {
        restarts++
        text.setLength(textMark)
    }

    override fun onTurnEnd(turn: Int) {
        textMark = text.length
    }

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

/**
 * 空回复兜底：模型偶发交白卷（无文字、无工具调用、finish=stop）。旧逻辑直接
 * onFinished("")——界面显示「已完成」却一句话没有、状态条回到静止，
 * 用户以为断了，只能手动发「继续」（真机实录：matplotlib 任务在写文件后中断）。
 * 现在第一次白卷要被 EMPTY_NUDGE 推回来；正常恢复后计数清零。
 */
private fun scenarioEmptyReply(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(CloudClient("$base/Z", "hy3", FakeTokens("t0", null)), toolbox, 6)
    val rec = Recorder()
    loop.run("随便答点什么", rec)

    result("empty_no_error", rec.error.isEmpty(), rec.error)
    result("empty_recovered", rec.finalText.contains("第二次"), "final=「${rec.finalText.take(60)}」")
    // 白卷不入历史：历史 = user + nudge(user) + 最终 assistant = 3
    result("empty_history", loop.historySize() == 3, loop.historySize().toString())
}

/**
 * 思考落盘：思考全文跟着 assistant 消息进历史（`_think`），重启后仍能展开回看；
 * 但它**不能**被发回上游——那是模型自己想过的内容，再喂一遍白烧 token，
 * 严格的网关还会因为不认识的字段直接 400（请求体里没有它由 Python 侧断言）。
 */
private fun scenarioThinkPersist(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(CloudClient("$base/X", "hy3", FakeTokens("t0", null)), toolbox, 6)
    val rec = Recorder()
    loop.run("看看屏幕上有什么", rec)

    result("think_no_error", rec.error.isEmpty(), rec.error)
    result("think_streamed", rec.think.toString().contains("先读屏"), rec.think.toString().take(80))

    val hist = loop.exportHistory()
    var withThink = 0
    var lastThink = ""
    for (i in 0 until hist.length()) {
        val m = hist.optJSONObject(i) ?: continue
        if (m.optString("role") != "assistant") continue
        val t = m.optString(AgentLoop.THINK_KEY, "")
        if (t.isNotEmpty()) {
            withThink++
            lastThink = t
        }
    }
    // 两轮各一段：调工具那轮想了、给结论那轮也想了
    result("think_saved_per_turn", withThink == 2, "assistant with _think = $withThink")
    result("think_text_complete", lastThink.contains("看清楚了"), lastThink)
    // 不带 `_think` 的消息也要照常存在（别为了加字段把原内容写坏）
    result("think_content_intact", rec.finalText.contains("设置页"), rec.finalText)

    // 模拟进程重启：新 loop 装回历史再跑一轮，历史里带着 _think 也必须照常发得出去
    val loop2 = AgentLoop(CloudClient("$base/X", "hy3", FakeTokens("t0", null)), toolbox, 6)
    loop2.restoreHistory(hist)
    val rec2 = Recorder()
    loop2.run("继续", rec2)
    result("think_restore_runs", rec2.error.isEmpty() && rec2.finalText.contains("还是那个设置页"),
        rec2.error.ifEmpty { rec2.finalText })
    result("think_restore_keeps_history", loop2.historySize() > hist.length(),
        "${hist.length()} -> ${loop2.historySize()}")

    // 真落盘再读回来（会话是存文件、重启是读文件，光测内存等于没测这一步）
    val sdir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-think-" + System.nanoTime())
    val store = SessionStore(sdir)
    store.save("s1", hist)
    val reloaded = store.load("s1")
    var diskThink = 0
    if (reloaded != null) {
        for (i in 0 until reloaded.length()) {
            val m = reloaded.optJSONObject(i) ?: continue
            if (m.optString(AgentLoop.THINK_KEY).isNotEmpty()) diskThink++
        }
    }
    result("think_survives_disk", diskThink == 2, "reloaded _think = $diskThink")
    sdir.deleteRecursively()
}

/** 序号复用：点完之后旧序号必须作废，否则会点错东西。 */
private fun scenarioStaleIndex() {    val device = FakeDevice()
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

/**
 * 手机独占租约：两个会话不能同时操作同一块屏幕。
 *
 * 要防的坏法不是「慢」，是「点错」——B 一开始跑就清掉 A 的序号表，
 * A 手里那串序号当场失效，接着按旧序号点下去就点到了别的地方。
 * 每一步单看都合法，事后翻历史也看不出问题，所以必须在源头拦住。
 */
private fun scenarioPhoneLease() {
    // 1) 租约本身
    PhoneLease.reset()
    result("lease_idle", PhoneLease.holder() == null, PhoneLease.holder() ?: "")
    result("lease_empty_owner_skips", PhoneLease.tryAcquire("") && PhoneLease.holder() == null, "")
    result("lease_first_wins", PhoneLease.tryAcquire("A") && PhoneLease.holder() == "A", "")
    result("lease_second_refused", !PhoneLease.tryAcquire("B"), PhoneLease.holder() ?: "")
    result("lease_owner_reentry", PhoneLease.tryAcquire("A"), "")
    // 非持有者交还：不能把正在跑的会话的租约撬掉，否则等于没锁
    PhoneLease.release("B")
    result("lease_foreign_release_ignored", PhoneLease.holder() == "A", PhoneLease.holder() ?: "")
    PhoneLease.release("A")
    result("lease_released", PhoneLease.holder() == null, "")

    // 2) 工具层：拿不到就拒绝，而且必须写明「不要重试」
    PhoneLease.reset()
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val aFirst = toolbox.execute("phone_read_screen", JSONObject(), "A").text
    val bTry = toolbox.execute("phone_read_screen", JSONObject(), "B").text
    result("lease_tool_a_ok", aFirst.contains("个可定位元素"), aFirst.take(80))
    result("lease_tool_b_busy", bTry.contains("PHONE_BUSY"), bTry.take(120))
    result("lease_busy_says_no_retry", bTry.contains("不要重试"), bTry.take(160))

    // 3) B 开始任务时不能把 A 的序号表清掉——清了，A 下一步就会点到别处
    toolbox.beginTask("B")
    val tapStillWorks = toolbox.execute("phone_tap", JSONObject().put("index", 2), "A").text
    result("lease_begin_task_keeps_outline", tapStillWorks.contains("\"ok\":true"), tapStillWorks.take(120))

    // 4) 交还之后别的会话能接手（否则一次卡死就永久锁住手机）
    PhoneLease.release("A")
    val bAfter = toolbox.execute("phone_read_screen", JSONObject(), "B").text
    result("lease_handover", bAfter.contains("个可定位元素") && PhoneLease.holder() == "B", bAfter.take(80))
    PhoneLease.reset()
}

/**
 * 手机被别人占着：模型一上来就要读屏，客户端必须立刻收手并说清原因。
 *
 * 不收手的话模型会换一个手机工具接着试，每轮都是同一条拒绝——纯烧 token，
 * 界面上则是一串一模一样的话，用户只会以为「它坏了」。
 */
private fun scenarioPhoneBusy(base: String) {
    PhoneLease.reset()
    PhoneLease.tryAcquire("someone-else")

    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(
        CloudClient("$base/Y", "hy3", FakeTokens("t0", null)), toolbox, 6, owner = "me",
    )
    val rec = Recorder()
    loop.run("帮我把深色模式打开", rec)

    result("busy_stops_after_first_turn", rec.turns == 1, "turns=${rec.turns}")
    result("busy_explains_why", rec.finalText.contains("另一个会话"), rec.finalText.take(120))
    result("busy_no_action_taken", device.taps.isEmpty(), device.taps.toString())
    result(
        "busy_reason_kept_in_history",
        loop.exportHistory().toString().contains("PHONE_BUSY"), "",
    )
    // 拒绝别人的调用不能顺手把租约抢过来
    result("busy_lease_not_stolen", PhoneLease.holder() == "someone-else", PhoneLease.holder() ?: "")
    PhoneLease.reset()
}

/**
 * 流水账渲染：这份东西的用途是「查它当时到底做了什么」。
 *
 * 原始格式是一行一个 JSON，塞着转义引号和毫秒时间戳，看三行就放弃——等于没做。
 * 所以渲染成人话这件事本身要能测：耗时拆解、工具参数、失败标记、压缩与重试都得在。
 */
private fun scenarioTranscriptRender() {
    val entries = listOf(
        TranscriptText.user("帮我把深色模式打开"),
        TranscriptText.turn(
            1200, 3400, 9000, 12000, 12000, 210, 1, "hy3",
            reasoningChars = 1822, cacheHit = 9000,
        ),
        TranscriptText.tool("phone_read_screen", "{}", true, "屏幕 1080×2400 · com.android.settings · 3 个可定位元素"),
        TranscriptText.tool("phone_tap", "{\"index\":2}", false, "NO_SUCH_INDEX 屏上没有这个序号"),
        TranscriptText.assistant("已经打开了。"),
        TranscriptText.retry("429 限流，2.0 秒后重试"),
        TranscriptText.compact(34, 8, "打开深色模式"),
        TranscriptText.error("HTTP 500：upstream error"),
    )
    val text = TranscriptText.render(entries)

    result("tr_empty_ok", TranscriptText.render(emptyList()) == "", "")
    result("tr_keeps_user_words", text.contains("帮我把深色模式打开"), text.take(80))
    result(
        "tr_turn_timing_split",
        text.contains("预填") && text.contains("想 ") && text.contains("出字"), "",
    )
    result("tr_turn_tokens", text.contains("缓存 9000") && text.contains("思考 1,822"), "")
    result("tr_tool_shows_args", text.contains("参数 {}") && text.contains("参数 {\"index\":2}"), "")
    result("tr_tool_marks_failure", text.contains("失败 · NO_SUCH_INDEX"), "")
    result("tr_retry_and_compact", text.contains("网络自愈") && text.contains("上下文压缩"), "")
    // 渲染过的文本里不该再出现原始 JSON 的痕迹（否则等于没渲染）
    result("tr_no_raw_json", !text.contains("\"ts\":"), text.take(120))

    // 跨天要分节：一个长任务可能横跨零点，全混在一起会看不出先后
    val day1 = TranscriptText.user("昨天说的").put("ts", 1762000000000L)
    val day2 = TranscriptText.user("今天说的").put("ts", 1762172800000L)
    val multi = TranscriptText.render(listOf(day1, day2))
    result("tr_groups_by_day", multi.split("## ").size - 1 == 2, multi.take(100))

    // 全量历史的显示源就是流水账：用户长句（>2000）不能被腰斩，照片文件名要跟着走
    val longText = "很长的需求".repeat(600)  // 2400 字，旧版 take(2000) 会截
    val withPhotos = TranscriptText.user(longText, true, listOf("s1-123-0.jpg", "s1-123-1.jpg"))
    result("tr_user_full_text", withPhotos.optString("text").length == longText.length,
        "len=" + withPhotos.optString("text").length)
    val ph = withPhotos.optJSONArray("photos")
    result("tr_user_photos_field", ph != null && ph.length() == 2 && ph.optString(0) == "s1-123-0.jpg",
        ph?.toString() ?: "null")
    // 没照片时不写 photos 字段：老渲染器（流水账导出）不用知道它存在
    result("tr_user_no_photos_key", !TranscriptText.user("hi").has("photos"), "")
    // 回答的上限按界面完整显示来定：长 Markdown（旧版 4000 截断）要完整
    val longAns = "答案".repeat(5000)  // 10000 字
    result("tr_assistant_full_text", TranscriptText.assistant(longAns).optString("text").length == longAns.length,
        "len=" + TranscriptText.assistant(longAns).optString("text").length)
}

/**
 * 异常翻译：用户看到的应该是「该等 / 该换网 / 该重登」，而不是带 JSON 的英文原文。
 *
 * 翻译错方向比不翻译更糟（让他去重登一个其实只是限流的错误，等于把人支到错路上），
 * 所以每一类都要钉住。
 */
private fun scenarioErrors() {
    result(
        "err_429",
        Errors.humanize("HTTP 429：{\"error\":\"rate_limit\"}").contains("限流"), "",
    )
    result("err_500", Errors.humanize("HTTP 500：upstream error").contains("服务端出错"), "")
    result("err_401", Errors.humanize("HTTP 401 unauthorized").contains("重新登录"), "")
    result(
        "err_timeout",
        Errors.humanize("java.net.SocketTimeoutException: Read timed out").contains("等太久"), "",
    )
    result("err_dns", Errors.humanize("Unable to resolve host \"api.x.com\"").contains("断网"), "")
    result(
        "err_keeps_chinese",
        Errors.humanize("这个会话还有任务在跑").startsWith("这个会话"), "",
    )
    result("err_empty", Errors.humanize("").contains("再说一次"), "")
    result("err_unknown_en", Errors.humanize("weird failure").contains("这一步没成功"), "")
}

/** 多窗口读屏：应用窗口 + 键盘窗口合到大纲，键盘候选词（如「小罗」）必须可定位可点。 */private fun scenarioOutlineWindows() {
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

    // 学习通：大纲要带答题页工序卡（固定循环、大纲恒定、不必反复权衡——不提禁忌词）
    val cx = ScreenOutline.render(JSONObject()
        .put("ok", true)
        .put("screen", JSONObject().put("width", 1080).put("height", 2400).put("package", "com.chaoxing.mobile"))
        .put("tree", n("FrameLayout", "", 0, 0, 1080, 2400,
            children = JSONArray().put(n("TextView", "21.2课堂练习", 0, 100, 1080, 200, clickable = true)))))
    result("outline_chaoxing_hint",
        cx.text.contains("〔学习通答题页〕") && cx.text.contains("phone_tap_at")
            && cx.text.contains("不必反复权衡"),
        cx.text.take(220))
    // 其他应用不带这句，免得每屏都烧 token
    result("outline_chaoxing_hint_scoped", !r2.text.contains("〔学习通"), r2.text.take(80))

    // 微信：控件树对无障碍隐藏，大纲要带剪贴板三步工序卡（v1.4.5 起主提示词不再
    // 常驻微信三步，这张卡 + phone_input 描述是仅有的两份拷贝）
    val wx = ScreenOutline.render(JSONObject()
        .put("ok", true)
        .put("screen", JSONObject().put("width", 1080).put("height", 2400).put("package", "com.tencent.mm"))
        .put("tree", n("FrameLayout", "", 0, 0, 1080, 2400)))
    result("outline_wechat_hint",
        wx.text.contains("〔微信〕") && wx.text.contains("phone_input")
            && wx.text.contains("phone_long_press") && wx.text.contains("phone_tap_at")
            && wx.text.contains("盲操作"),
        wx.text.take(200))
    // 普通应用不带微信卡
    result("outline_wechat_hint_scoped", !r2.text.contains("〔微信〕"), r2.text.take(80))
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

/**
 * 网络自愈：这一组全是「手机网络本来就会遇到」的事故，没人在旁边盯着。
 *
 * 之前的表现：限流一次 → 整轮报错；吐了半截断线 → 半截答案留着让人以为答完了；
 * 撞输出上限 → 停在半句话上；失败一次 → 历史里留一条没人回答的 user 消息。
 * 这四条都发生在**模型之外**，跟提示词一点关系没有，但用户感受到的全是「这 agent 真难用」。
 */
private fun scenarioResilience(base: String) {
    // 1) 429 一次：客户端自己等一等再发，用户该看到的是「成了」，不是「服务器忙」
    run {
        val rec = Recorder()
        val loop = AgentLoop(
            CloudClient("$base/T", "hy3", FakeTokens("t0", null)),
            PhoneToolbox({ FakeDevice() }, { null }), 3,
        )
        loop.run("说句话", rec)
        result("retry_429_ok", rec.error.isEmpty() && rec.finalText.contains("限流过去了"),
            rec.error.ifEmpty { rec.finalText })
        result("retry_429_reported", rec.retries.any { it.contains("限流") }, rec.retries.joinToString("|"))
    }

    // 2) 吐了半截、连接被掐：先把半截撤掉再重发，界面上不能留「半截 + 完整」
    run {
        val rec = Recorder()
        val loop = AgentLoop(
            CloudClient("$base/U", "hy3", FakeTokens("t0", null)),
            PhoneToolbox({ FakeDevice() }, { null }), 3,
        )
        loop.run("说句话", rec)
        result("cut_retry_ok", rec.error.isEmpty() && rec.finalText.contains("完整回答"),
            rec.error.ifEmpty { rec.finalText })
        result("cut_retry_restart", rec.restarts == 1, rec.restarts.toString())
        result("cut_retry_no_partial", !rec.text.contains("半截话"), rec.text.toString())
    }

    // 3) finish_reason=length：必须自动接着写，而不是停在半句话上
    run {
        val rec = Recorder()
        val loop = AgentLoop(
            CloudClient("$base/V", "hy3", FakeTokens("t0", null)),
            PhoneToolbox({ FakeDevice() }, { null }), 4,
        )
        loop.run("写长一点", rec)
        result("length_continue_ok", rec.error.isEmpty(), rec.error)
        result("length_continue_turns", rec.turns == 2, rec.turns.toString())
        result("length_continue_text", rec.finalText.contains("接着写完了"), rec.finalText)
        // 两段都要在历史里，模型下一轮才接得上
        val h = loop.exportHistory().toString()
        result("length_continue_history", h.contains("写到一半") && h.contains("接着写完了"), h.take(140))
    }

    // 4) 一直 503：重试有上限，最后要给一句人话
    run {
        val rec = Recorder()
        val loop = AgentLoop(
            CloudClient("$base/W", "hy3", FakeTokens("t0", null)),
            PhoneToolbox({ FakeDevice() }, { null }), 3,
        )
        loop.run("说句话", rec)
        // 重试用尽后给用户的是人话（不是「HTTP 503」这种原文）
        result("giveup_after_retries", rec.error.contains("服务端出错"), rec.error)
        result("giveup_retry_count", rec.retries.count { it.contains("503") } == 2,
            rec.retries.joinToString("|"))
        // 失败后不能留一条没人接话的 user 消息：重发一次就变成同一句话说了两遍
        result("giveup_no_dangling_user", loop.historySize() == 0, loop.historySize().toString())
    }
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
    // 2026-11-16 桌面端换血：hy3 下线，默认落到限时免费的 hy4flash-free
    result("model_default_ok",
        def.id == "hy4flash-free" && def.credit == "x0.00" && ModelRegistry.DEFAULT_ID == "hy4flash-free",
        def.toString())
    result("model_ids_unique", ModelRegistry.OPTIONS.map { it.id }.toSet().size == ModelRegistry.OPTIONS.size,
        ModelRegistry.OPTIONS.size.toString())
    result("model_labels_present", ModelRegistry.OPTIONS.all { it.label.isNotBlank() && it.id.isNotBlank() }, "")
    // 已经下线/失效的 id 不能再挂回清单——挂回去就是每轮 11102
    result("model_no_dead_ids",
        ModelRegistry.find("hy3") == null && ModelRegistry.find("hy4-preview") == null,
        ModelRegistry.OPTIONS.map { it.id }.joinToString(","))
    // 脏值（旧版本残留、手改、服务端下线）必须落回默认，不能把野字符串发出去
    result("model_unknown_falls_back",
        ModelRegistry.resolve("gpt-9-ultra").id == ModelRegistry.DEFAULT_ID &&
            ModelRegistry.resolve("hy3").id == ModelRegistry.DEFAULT_ID &&
            ModelRegistry.resolve(null).id == ModelRegistry.DEFAULT_ID &&
            ModelRegistry.resolve("").id == ModelRegistry.DEFAULT_ID, "")
    result("model_index_falls_back",
        ModelRegistry.indexOf("nope") == ModelRegistry.indexOf(ModelRegistry.DEFAULT_ID), "")
    result("model_summary_default", ModelRegistry.default().summary() == "Hy4 flash · 免费",
        ModelRegistry.default().summary())

    // 顶栏胶囊和设置项都用 Option.summary()。它一旦拼错（比如 "Kimi-K3 · x1.62" 变成
    // "Kimi-K3 · " 或漏掉倍率），界面上就只剩个残缺的模型名——用户会以为是「没模型」。
    // auto 是唯一没有固定倍率的（服务端按任务浮动），允许空串。
    result("model_credit_format",
        ModelRegistry.OPTIONS.all { it.credit.isEmpty() || it.credit.matches(Regex("^x\\d+\\.\\d{2}$")) } &&
            ModelRegistry.OPTIONS.count { it.credit.isEmpty() } == 1 &&
            ModelRegistry.find("auto")?.credit == "",
        ModelRegistry.OPTIONS.map { it.credit }.joinToString(","))
    result("model_summary_shape",
        ModelRegistry.OPTIONS.all { o ->
            val want = when {
                o.credit.isEmpty() -> o.label
                o.credit == "x0.00" -> "${o.label} · 免费"
                else -> "${o.label} · ${o.credit}"
            }
            o.summary() == want
        },
        ModelRegistry.OPTIONS.map { it.summary() }.joinToString(" | "))
    // 选完要能按 id 找回来，并且 indexOf 指的就是它自己在列表里的位置
    result("model_roundtrip_by_id",
        ModelRegistry.OPTIONS.withIndex().all { (i, o) ->
            ModelRegistry.find(o.id)?.id == o.id && ModelRegistry.indexOf(o.id) == i
        }, "")
    // 顺序照抄桌面端下拉：Auto + 三个通用档位在最前面。顺序错了用户找同一个模型要翻半天。
    result("model_top4_generic",
        ModelRegistry.OPTIONS.take(4).map { it.id } ==
            listOf("auto", "fast-model", "balanced-model", "deep-model"),
        ModelRegistry.OPTIONS.take(4).map { it.id }.joinToString(","))

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
    val ladderRaw = """{"model":"glm-5.3","stream":true,"temperature":0.5,
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
    // 降档只该动消息结构，不能让 model 跟着飘——用现役 id，hy3 已下线会被回退成默认
    result("gw_lvl_all_same_model", l1.optString("model") == "glm-5.3" && l8.optString("model") == "glm-5.3", "")
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
 * 思考深度：`reasoning_effort` 能带上去，被上游拒了就自动回落标准。
 *
 * 这两条都在测「可选参数不能变成单点故障」：带错了不该报错给用户看，
 * 也不该把别的参数（采样）一起丢掉——两个开关是独立的。
 */
private fun scenarioThinkLevel(base: String) {
    // 档位映射本身：标准档必须返回 null（否则老用户请求体全变，缓存前缀全部作废）
    result("think_map_low", Think.effort(Think.LOW) == "low", Think.effort(Think.LOW) ?: "null")
    result("think_map_std", Think.effort(Think.STD) == null, Think.effort(Think.STD) ?: "null")
    result("think_map_high", Think.effort(Think.HIGH) == "high", Think.effort(Think.HIGH) ?: "null")
    result("think_clamp_unknown", Think.clamp(99) == Think.STD, Think.clamp(99).toString())

    // 1) 上游认这个参数：正常返回，Turn 里记下这一轮用的是哪档
    run {
        val client = CloudClient(
            baseUrl = "$base/R",
            model = "hy3",
            tokens = FakeTokens("t0", null),
            effort = { Think.effort(Think.HIGH) },
        )
        var err = ""
        var text = ""
        var effortSeen: String? = "没取到"
        try {
            val turn = client.stream(
                JSONArray().put(JSONObject().put("role", "user").put("content", "hi")), null
            )
            text = turn.content
            effortSeen = turn.reasoningEffort
        } catch (e: Exception) {
            err = e.message ?: e.javaClass.simpleName
        }
        result("think_high_no_error", err.isEmpty(), err)
        result("think_high_text", text.contains("高档位"), text)
        result("think_high_recorded", effortSeen == "high", effortSeen ?: "null")
    }

    // 2) 上游拒绝：要自动回落、把原话交出来，且这一轮的 effort 记录为「没带」
    run {
        var rejected = ""
        val client = CloudClient(
            baseUrl = "$base/Q",
            model = "hy3",
            tokens = FakeTokens("t0", null),
            effort = { "low" },
            onEffortRejected = { rejected = it },
        )
        var err = ""
        var text = ""
        var effortSeen: String? = "没取到"
        try {
            val turn = client.stream(
                JSONArray().put(JSONObject().put("role", "user").put("content", "嗨")), null
            )
            text = turn.content
            effortSeen = turn.reasoningEffort
        } catch (e: Exception) {
            err = e.message ?: e.javaClass.simpleName
        }
        result("think_fallback_no_error", err.isEmpty(), err)
        result("think_fallback_text", text.contains("回落"), text)
        result("think_fallback_reported", rejected.contains("reasoning_effort"), rejected)
        result("think_fallback_cleared", effortSeen == null, effortSeen ?: "null")
    }
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

    // ---- fs_write 转义兜底：content 里带字面 \/ 的（模型 JSON 双重转义落盘，真案：
    //      shizuku-companion-apps.md 27 处斜线）写盘前归一，并附 note 让模型改掉习惯 ----
    val w2 = call("fs_write", "path" to "escw.md", "content" to "官网 https:\\/\\/gkd.li\\/guide\\/\n")
    result("bench_write_escape_note",
        w2.optBoolean("ok") && w2.optString("note").contains("归一"), w2.toString())
    result("bench_write_escape_clean",
        !call("fs_read", "path" to "escw.md").optString("content").contains("\\"), "")
    result("bench_write_escape_url",
        call("fs_read", "path" to "escw.md").optString("content").contains("https://gkd.li/guide/"), "")

    val r1 = call("fs_read", "path" to "notes/a.md")
    result("bench_read_numbered", r1.optString("content").startsWith("1|# 标题"), r1.optString("content").take(40))
    result("bench_read_total", r1.optInt("total") == 3, r1.optInt("total").toString())

    val e1 = call("fs_edit", "path" to "notes/a.md", "old" to "第一行", "new" to "改过的第一行")
    result("bench_edit_ok", e1.optBoolean("ok") && e1.optInt("line") == 2, e1.toString())
    result("bench_edit_context", e1.optString("context").contains(">2|改过的第一行"), e1.optString("context").take(80))

    val e2 = call("fs_edit", "path" to "notes/a.md", "old" to "不存在的行", "new" to "x")
    result("bench_edit_no_match", !e2.optBoolean("ok") && e2.optString("code") == "NO_MATCH", e2.toString())

    // ---- fs_edit 转义兜底：模型把 JSON 里的 / 过度转义成 \/，解析后 old 带字面反斜杠，
    //      原样匹配必败（真案：连续 6 次 NO_MATCH）。两档归一都要能改上并注明。----
    call("fs_write", "path" to "esc1.md", "content" to "黑阈 | 官网 / 酷安\n")
    val e4 = call("fs_edit", "path" to "esc1.md", "old" to "黑阈 | 官网 \\/ 酷安", "new" to "黑阈 | 官网、酷安")
    result("bench_edit_escape_old_normalized",
        e4.optBoolean("ok") && e4.optString("note").contains("归一"), e4.toString())
    result("bench_edit_escape_old_applied",
        call("fs_read", "path" to "esc1.md").optString("content").contains("官网、酷安"), "")

    // fs_write 现在会把 content 里的 \/ 归一（上一条已验），要造「文件已被污染」的
    // 历史样本（模型双重转义落盘的老文件）得绕过它直接写盘
    File(dir, "esc2.md").writeText("黑阈 | 官网 \\/ 酷安\n")
    val e5 = call("fs_edit", "path" to "esc2.md", "old" to "黑阈 | 官网 / 酷安", "new" to "黑阈 | 官网、酷安")
    result("bench_edit_escape_file_normalized",
        e5.optBoolean("ok") && e5.optString("note").contains("归一"), e5.toString())
    result("bench_edit_escape_file_cleaned",
        !call("fs_read", "path" to "esc2.md").optString("content").contains("\\"), "")

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
    result("bench_schema_without", noBench.schemas().length() == 11, noBench.schemas().length().toString())
    val withBench = PhoneToolbox({ FakeDevice() }, { null }, null, bench)
    result("bench_schema_with", withBench.schemas().length() == 24, withBench.schemas().length().toString())

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

    // ---- docx_write table 块：三线表 + 数字右对齐 + 表头跨页重复 + 回读管道表 ----
    // 动机：真机上模型拿「项目|内容」两列表当封面，全黑网格盖在标题下被用户骂丑。
    // 表格样式现在在写入端定死（三线表），模型只给数据。
    val docxTbl = call("docx_write", "path" to "季报.docx", "title" to "季度营收",
        "blocks" to JSONArray().put(JSONObject()
            .put("type", "table")
            .put("caption", "表 1 各季度营收")
            .put("head", JSONArray().put("季度").put("营收").put("同比"))
            .put("rows", JSONArray()
                .put(JSONArray().put("第一季度").put("1,234").put("+8%"))
                .put(JSONArray().put("第二季度").put("1,580").put("+12%"))
                .put(JSONArray().put("第三季度").put("").put("-3%")))))
    result("doc_docx_table_ok", docxTbl.optBoolean("ok"), docxTbl.toString())
    val tblFile = File(dir, "季报.docx")
    val tblXml = zipText(tblFile, "word/document.xml")
    result("doc_docx_table_grid", tblXml.contains("<w:tbl>") && tblXml.contains("<w:gridCol"), "表结构")
    result("doc_docx_table_threeline", tblXml.contains("w:insideV w:val=\"nil\"") &&
        tblXml.contains("w:insideH w:val=\"single\""), "无竖线、有横线")
    result("doc_docx_table_header", tblXml.contains("<w:tblHeader/>") &&
        tblXml.contains("w:fill=\"F2F2F2\""), "表头跨页重复 + 浅灰底")
    result("doc_docx_table_numeric_right", tblXml.contains("<w:jc w:val=\"right\"/>"), "数字列右对齐")
    result("doc_docx_table_caption", tblXml.contains("表 1 各季度营收"), "表题")
    val tblReadBack = OfficeText.docxToMarkdown(tblFile) ?: ""
    result("doc_docx_table_empty_cell", tblReadBack.contains("| 第三季度 |  | -3% |"), "空单元格占位")
    result("doc_docx_table_roundtrip",
        tblReadBack.contains("| 季度 | 营收 | 同比 |") && tblReadBack.contains("| 第一季度 | 1,234 | +8% |"),
        tblReadBack.take(200))
    // 配色收口：正文/标题/表线不再带品牌蓝（真机反馈：正式文档彩色标题突兀）
    result("doc_docx_no_brand_blue", !tblXml.contains("1F4E79") && !tblXml.contains("14395B") &&
        !zipText(tblFile, "word/styles.xml").contains("1F4E79"), "全文黑色")

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
    result("bench_schema_all_routable", declared.size == 24 && unroutable.isEmpty(),
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
 * 带图消息进压缩：目标行只留文字，图片换成占位。
 * 修前的 bug——optString 对 JSON 数组会掏出整段 base64，截断后就是界面上的「乱码」。
 */
private fun scenarioCompactImageGoal(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(CloudClient("$base/IG", "hy3", FakeTokens("t0", null)), toolbox, 4)

    val parts = JSONArray()
        .put(JSONObject().put("type", "text").put("text", "帮我搜集这个手机root的相关资料"))
        .put(JSONObject().put("type", "image_url").put("image_url",
            JSONObject().put("url", "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ" + "A".repeat(2000))))
        // 真实历史里带图消息还挂着一条图说（AgentLoop.attachImageNote 写的）：
        // 压进摘要后图只剩占位，但题目原文必须靠这段活下来。
        .put(JSONObject().put("type", "text").put("text",
            "\n\n" + AgentLoop.IMG_NOTE_HEAD + "\nRedmi 8A 已解锁 BL，刷了 Magisk，问还能装哪些模块"))
    val hist = JSONArray().put(JSONObject().put("role", "user").put("content", parts))
    for (i in 1..40) {
        hist.put(
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "ig$i").put("type", "function")
                        .put("function", JSONObject().put("name", "phone_read_screen").put("arguments", "{}"))
                )
            )
        )
        hist.put(
            JSONObject().put("role", "tool").put("tool_call_id", "ig$i")
                .put("content", "{\"ok\":true,\"content\":\"" + "z".repeat(1500) + "\"}")
        )
    }
    loop.restoreHistory(hist)
    val rec = Recorder()
    loop.run("继续", rec)

    result("compact_image_no_error", rec.error.isEmpty(), rec.error)
    result("compact_image_happened", loop.compactions >= 1, loop.compactions.toString())
    val s0 = loop.exportHistory().getJSONObject(0).optString("content")
    result("compact_image_goal_text", s0.contains("目标：帮我搜集这个手机root的相关资料"), s0.take(80))
    result("compact_image_no_base64", !s0.contains("base64") && !s0.contains("image_url"),
        s0.take(80))
    result("compact_image_placeholder", s0.contains("[附一张图片]"), s0.take(80))
    // 图里的内容不能跟着占位一起没：摘要必须留一行「- 图：…」
    result("compact_image_note_kept", s0.contains("- 图：") && s0.contains("Magisk"), s0.take(200))
}

/**
 * 压缩计账不能把 base64 截图算进去（2026-11-20 真机事故）。
 *
 * historyChars 之前用 history.toString().length：一张 1080×2400 截图的 base64 有
 * 15~30 万字符，阈值才 4.8 万——截图一进历史就永远超阈值，「上下文压缩」每轮一条，
 * 模型的计划每轮被压掉，真机表现就是反复点同一个搜索图标（微信 34 轮没发出去一条消息）。
 * 计账必须和 buildMessages 的口径一致：只有最后一张图真发出，旧图降级成一行占位。
 */
private fun scenarioCompactNoBase64Inflation(base: String) {
    // 纯函数：一张 20 万字符的 base64 在计账里只折 3000
    fun shotMsg(b64: String) = JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", "（这是当前屏幕的截图）"))
        .put(JSONObject().put("type", "image_url").put("image_url",
            JSONObject().put("url", "data:image/jpeg;base64,$b64"))))
    val imgHist = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "打开微信给小罗发个你好"))
        .put(shotMsg("A".repeat(200_000)))
    val cost = AgentLoop.historyChars(imgHist)
    result("histchars_ignores_base64",
        cost < 10_000 && cost >= 3_000, "cost=$cost")

    // 端到端：14 轮截图往返（旧口径下 toString 长度 >300 万字符，必压），
    // 真实文本量很小——不该触发任何一次压缩
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(CloudClient("$base/NB", "hy3", FakeTokens("t0", null)), toolbox, 2)
    val hist = JSONArray().put(JSONObject().put("role", "user").put("content", "打开微信给小罗发个你好"))
    for (i in 1..14) {
        hist.put(
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "nb$i").put("type", "function")
                        .put("function", JSONObject().put("name", "phone_screenshot").put("arguments", "{}"))
                )
            )
        )
        hist.put(
            JSONObject().put("role", "tool").put("tool_call_id", "nb$i")
                .put("content", "{\"ok\":true,\"width\":1080,\"height\":2400,\"imageBytes\":230000,\"note\":\"截图已附在随后一条消息里\"}")
        )
        hist.put(shotMsg("B".repeat(200_000)))
    }
    loop.restoreHistory(hist)
    result("histchars_screenshot_history_small",
        AgentLoop.historyChars(loop.exportHistory()) < AgentLoop.COMPACT_AT_CHARS,
        AgentLoop.historyChars(loop.exportHistory()).toString())
    val recN = Recorder()
    loop.run("继续", recN)
    result("compact_not_thrashed_by_images",
        recN.error.isEmpty() && loop.compactions == 0,
        "compactions=${loop.compactions} err=${recN.error}")
}

/**
 * 收到图先让模型把图说一遍。
 *
 * 为什么要有这一步：图片只活在内存里（落盘前 base64 被剥），压缩只留文字——
 * 真机上用户发一张作业截图 +「学号后三位是002」，压缩完模型连作业是什么都不知道了。
 */
private fun scenarioImageNote(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    // 落盘出口用假的：真实宿主写到工作台 images/ 下，这里只记下「写了什么」。
    var savedTo: String? = null
    val loop = AgentLoop(CloudClient("$base/IM", "hy3", FakeTokens("t0", null)), toolbox, 4,
        saveNote = { name, text ->
            savedTo = "$name|$text"
            "images/$name"
        })
    val rec = Recorder()
    loop.run("我的学号后三位是002，按图里的要求做", rec,
        listOf("data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ" + "B".repeat(800)))

    result("img_note_no_error", rec.error.isEmpty(), rec.error)
    val hist = loop.exportHistory()
    val user = hist.optJSONObject(0)
    val parts = user?.optJSONArray("content")
    var texts = ""
    if (parts != null) {
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optString("type") == "text") texts += p.optString("text")
        }
    }
    result("img_note_attached", texts.contains(AgentLoop.IMG_NOTE_HEAD), texts.take(80))
    result("img_note_has_content", texts.contains("学号后三位 002"), texts.take(160))
    // 路径必须在最前面：摘要截断时先保住「去哪儿拿全文」
    result("img_note_saved_path",
        texts.contains(AgentLoop.IMG_NOTE_HEAD + "\n全文 images/"), texts.take(200))
    val saved = savedTo
    result("img_note_written", saved != null && saved.contains("题目：用学号后三位选一只股票"),
        saved?.take(120) ?: "没落盘")
    // 气泡里不能出现图说：用户自己没打过这些字
    val shown = AgentLoop.visibleUserText(texts)
    result("img_note_hidden_in_ui",
        shown.contains("我的学号后三位是002") && !shown.contains(AgentLoop.IMG_NOTE_HEAD),
        shown.take(80))
    result("img_note_reply", rec.finalText.contains("好的"), rec.finalText)
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

    // 旧版 bug 存量：带图消息的目标行混进了 content 数组原始 JSON，截断在 base64 中间。
    // 要抢救出纯文字，而不是把乱码显示到界面上/继承进下一份摘要。
    val junkGoal = "[早期步骤摘要｜已压缩 5 条消息、1 次工具调用]\n" +
        "目标：[{\"type\":\"text\",\"text\":\"帮我搜集这个手机root的相关资料\"}," +
        "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image\\/jpeg;base64,/9j/4AAQSkZJRg\"}},{\"type"
    val gj = AgentLoop.summaryGoalOf(junkGoal)
    result("goal_image_junk_stripped",
        gj == "帮我搜集这个手机root的相关资料", gj)
    result("goal_no_base64_leftover", !gj.contains("base64") && !gj.contains("{\"type\""), gj)
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

    // 相关性检索：老记忆只要跟本轮沾边，就该压过一堆无关的新记忆。
    // 这正是「记忆一多就串台」的根子——旧版只取最近 20 条，别的主题的旧事被硬塞进来。
    Memory.clear(dir)
    Memory.append(dir, "用户在做「家庭记账」小工具，想让月溯帮忙算账")
    Memory.append(dir, "家庭记账里记着：每月房租 1800，吃饭大约 1500")
    for (i in 2 until 22) Memory.append(dir, "第%02d条无关记录".format(i))
    val rel = Memory.digest(dir, "帮我看看家庭记账那块")
    result("mem_relevance_pulls_old", rel.contains("家庭记账"),
        rel.lines().firstOrNull { it.contains("家庭") } ?: "none")
    // 22 条里只留 20 条：被挤掉的必须是最不新鲜又最不相关的两条
    result("mem_relevance_drops_noise", !rel.contains("第02条") && !rel.contains("第03条"),
        rel.lines().size.toString())
    result("mem_relevance_note", rel.contains("只列出与本轮相关"), "")
    // 挑完仍按时间排：最近记的还得在最后，模型读到的末尾才是最新
    result("mem_relevance_order", rel.indexOf("房租") < rel.indexOf("第10条"), "")
    result("mem_relevance_score", Memory.relevance(Memory.entries(dir).first(), "家庭记账 房租") >= 3, "")
    result("mem_relevance_zero", Memory.relevance(Memory.entries(dir).last(), "家庭记账") == 0, "")
    // 没有话题词（老调用点/纯 JVM 场景）退回老行为：只按新鲜度取
    val recencyOnly = Memory.digest(dir)
    result("mem_no_query_recency",
        !recencyOnly.contains("家庭记账") && recencyOnly.contains("第21条"), "")

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

    // 管理页：单条删除。只认整行——「家庭记账」不能顺带把「家庭记账，房租 1800」也删掉
    Memory.append(dir, "用户在做家庭记账")
    Memory.append(dir, "家庭记账里：房租 1800")
    Memory.append(dir, "用户不吃香菜")
    val before = Memory.entries(dir)
    val shortLine = before.first { it.contains("在做家庭记账") }
    result("mem_remove_ok", Memory.remove(dir, shortLine), "")
    val after = Memory.entries(dir)
    result("mem_remove_exact_only",
        after.none { it == shortLine } && after.any { it.contains("房租 1800") } && after.any { it.contains("香菜") },
        after.joinToString(" | "))
    result("mem_remove_keeps_header",
        File(dir, Memory.FILE).readText(Charsets.UTF_8).startsWith("# 月溯的长期记忆"), "")
    result("mem_remove_missing", !Memory.remove(dir, "- 2099-01-01 · 从来没记过这条"), "")
    result("mem_remove_gone_from_digest", !Memory.digest(dir).contains("在做家庭记账"), "")
    // 删完还能继续记：文件格式没写坏
    result("mem_remove_then_append", Memory.append(dir, "删完再记一条") != null
        && Memory.entries(dir).any { it.contains("删完再记一条") }, "")
    Memory.clear(dir)
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

    // 2b) docx 表格：整块转管道表，不再把单元格拍成一竖条（真机实录：股票报告封面表）
    val docxTbl = File(dir, "报告.docx")
    java.util.zip.ZipOutputStream(docxTbl.outputStream()).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
        val xml = """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>
<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>股票数据分析报告</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>项目</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>内容</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>姓名</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>罗|唯珍</w:t></w:r></w:p></w:tc></w:tr>
<w:tr><w:tc><w:p><w:r><w:t>学号</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t></w:t></w:r></w:p></w:tc></w:tr>
</w:tbl>
<w:p><w:r><w:t>表后的正文。</w:t></w:r></w:p>
</w:body></w:document>"""
        z.write(xml.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }
    val tblMd = OfficeText.docxToMarkdown(docxTbl)
    // 居中标题成为 H2；管道表保留行列结构；单元格里的 | 要转义
    result("office_docx_table", tblMd != null && tblMd.contains("| 项目 | 内容 |") &&
        tblMd.contains("| 姓名 | 罗\\|唯珍 |"), tblMd?.take(200) ?: "null")
    result("office_docx_table_empty_cell", tblMd != null && tblMd.contains("| 学号 |  |"),
        tblMd?.take(300) ?: "null")
    result("office_docx_table_after", tblMd != null && tblMd.contains("表后的正文。"), "")
    // 正文已有居中标题，不再把文件名重复当 H1
    result("office_docx_no_dup_title", tblMd != null && !tblMd.contains("# 报告.docx"), tblMd?.take(60) ?: "null")
    // Heading 样式映射成对应层级
    val docxH = File(dir, "样式.docx")
    java.util.zip.ZipOutputStream(docxH.outputStream()).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
        val xml = """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>研究方法</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr/></w:pPr><w:r><w:t>列表项甲</w:t></w:r></w:p>
</w:body></w:document>"""
        z.write(xml.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }
    val hMd = OfficeText.docxToMarkdown(docxH)
    result("office_docx_heading_style", hMd != null && hMd.contains("## 研究方法"), hMd?.take(120) ?: "null")
    result("office_docx_numpr_list", hMd != null && hMd.contains("- 列表项甲"), hMd ?: "null")

    // 2c) code 块：逐行等宽写进去、预览转围栏读出来（真机实录：附录源码被压成一坨）
    val codeDocx = File(dir, "附录代码.docx")
    val codeBlocks = org.json.JSONArray()
        .put(org.json.JSONObject().put("type", "h2").put("text", "附录 A 完整源代码"))
        .put(org.json.JSONObject().put("type", "code")
            .put("text", "import os\nimport pandas as pd\n\ndef load(p):\n\treturn pd.read_csv(p)"))
    val codeRes = org.json.JSONObject(DocxWriter.write(codeDocx, "分析报告", codeBlocks))
    result("office_docx_code_ok", codeRes.optBoolean("ok"), codeRes.optString("error"))
    val codeMd = OfficeText.docxToMarkdown(codeDocx) ?: ""
    result("office_docx_code_fence", codeMd.contains("## 附录 A 完整源代码") &&
        codeMd.contains("import os\nimport pandas as pd\n\ndef load(p):"), codeMd.take(200))
    // tab 缩进折成 4 空格且原样保留；空行不吞（围栏里的代码还是那门语言的样子）
    result("office_docx_code_indent", codeMd.contains("\n    return pd.read_csv(p)"), codeMd.take(300))
    // 标题围栏之间只有一对 ```：连续代码段落并进同一围栏，不逐段各开各的
    result("office_docx_code_one_fence", codeMd.split("```").size - 1 == 2, codeMd.take(400))
    // 正文里夹一小段 `行内代码` 的段落是普通正文，不能误判成代码围栏
    val mixDocx = File(dir, "正文夹码.docx")
    val mixBlocks = org.json.JSONArray().put(org.json.JSONObject().put("type", "p")
        .put("text", "先运行 `pip install pandas` 装依赖，再跑脚本。"))
    org.json.JSONObject(DocxWriter.write(mixDocx, "说明", mixBlocks))
    val mixMd = OfficeText.docxToMarkdown(mixDocx) ?: ""
    result("office_docx_inline_code_not_fence",
        mixMd.contains("pip install pandas") && !mixMd.contains("```"), mixMd.take(120))

    // 2d) hr 分隔线 + indent:false 顶格段（对照交付稿：封面署名区与摘要之间一条线，关键词顶格左对齐）
    val hrDocx = File(dir, "封面.docx")
    val hrBlocks = org.json.JSONArray()
        .put(org.json.JSONObject().put("type", "subtitle").put("text", "——以万科 A 为例"))
        .put(org.json.JSONObject().put("type", "hr"))
        .put(org.json.JSONObject().put("type", "h2").put("text", "摘要"))
        .put(org.json.JSONObject().put("type", "p").put("indent", false)
            .put("text", "关键词：涨跌预测；技术指标"))
    val hrRes = org.json.JSONObject(DocxWriter.write(hrDocx, "数据报告", hrBlocks))
    result("office_docx_hr_ok", hrRes.optBoolean("ok"), hrRes.optString("error"))
    val hrXml = java.util.zip.ZipFile(hrDocx).use { zip ->
        zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
    }
    result("office_docx_hr_line", hrXml.contains("<w:pBdr><w:bottom"), "")
    val kwP = Regex("<w:p>.*?</w:p>").findAll(hrXml)
        .firstOrNull { it.value.contains("关键词") }?.value ?: ""
    // 顶格段：不再有 firstLineChars（缩进的「关键词」和「摘要」左边缘对不上）
    result("office_docx_kw_no_indent", kwP.isNotEmpty() && !kwP.contains("firstLineChars"), kwP.take(160))
    // 副标题默认宋体：等线的破折号字形中间断开，宋体才是连续一条线
    result("office_docx_sub_song", hrXml.contains("w:eastAsia=\"宋体\""), "")

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

    // 7b) 每个内置技能都必须是「能装上、能解析、字数不超限」的完整件
    for ((name, text) in Skill.BUILTIN) {
        val f = File(d, "$name.md")
        f.writeText(text, Charsets.UTF_8)
        val it = Skill.scan(root).firstOrNull { it.name == name }
        val body = if (it != null) Skill.body(it) else ""
        result("skill_builtin_$name",
            it != null && it.title.isNotEmpty() && it.desc.isNotEmpty() && it.whenToUse.isNotEmpty()
                && body.length in 200..Skill.MAX_BODY_CHARS && !body.contains("已截断"),
            "title=${it?.title} desc=${it?.desc} bodyLen=${body.length}")
    }

    // 7c) 升级播种：老标记（"1"）+ 老技能删了一个 → 只补这一版新增的，删掉的不复活
    // 受管技能（NEW_SEEDS）的旧内容必须被覆盖：真机实录——doc-format 补了「用工具别手搓/
    // 封面克制」却不升种子版本，老用户永远读到旧规则，照样拿 python-docx 手搓丑表格。
    val up = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-skill-up-" + System.nanoTime())
    File(up, Skill.DIR).mkdirs()
    File(up, Skill.DIR + "/weekly-report.md").writeText("---\nname: weekly-report\ntitle: 周报\n---\n旧版内容\n", Charsets.UTF_8)
    File(up, Skill.DIR + "/doc-format.md").writeText("---\nname: doc-format\ntitle: 文档排版\n---\n旧版规则：没有生成约束\n", Charsets.UTF_8)
    File(up, Skill.DIR + "/.seeded").writeText("1", Charsets.UTF_8)
    val added = Skill.install(up)
    val upNames = Skill.scan(up).map { it.name }
    result("skill_upgrade_adds_new", added == 4 && upNames.containsAll(listOf("doc-format", "slides", "doc-coauthor", "skill-craft")),
        "$added / $upNames")
    result("skill_upgrade_keeps_old", Skill.body(Skill.scan(up).first { it.name == "weekly-report" }).contains("旧版内容"), "")
    result("skill_upgrade_overwrites_managed",
        Skill.body(Skill.scan(up).first { it.name == "doc-format" }).contains("别手搓") &&
            Skill.body(Skill.scan(up).first { it.name == "doc-format" }).contains("封面克制"),
        "受管技能内容已更新")
    result("skill_upgrade_no_deleted_revive", "resume" !in upNames, upNames.joinToString(","))
    result("skill_upgrade_marker", File(up, Skill.DIR + "/.seeded").readText().trim() == Skill.SEED_VERSION.toString(),
        File(up, Skill.DIR + "/.seeded").readText())

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
 * 人情味开关：语气段随请求附加、关掉就消失。
 *
 * 走的是 extras 通道（附在末条 user 消息），所以 fake server 侧能看到它——
 * 关掉后整段不存在，而不是留一句「语气：正常」占位。
 */
private fun scenarioTone(base: String) {
    val loop = AgentLoop(
        CloudClient("$base/TN", "hy3", FakeTokens("t0", null)),
        PhoneToolbox({ FakeDevice() }, { null }),
        3,
        memory = { "" },
        skills = { "" },
        tone = { toneForInject },
    )
    val rec = Recorder()

    toneForInject = "- 先接住情绪再干活：先回应感受，再进正题。"
    loop.run("今天好累啊", rec)
    result("tone_run_ok", rec.error.isEmpty(), rec.error)

    toneForInject = ""
    loop.run("你好", rec)
    result("tone_off_ok", rec.error.isEmpty(), rec.error)
}

private var toneForInject: String = ""

private var extrasMemory: String = ""

/** 记忆注入源收到的「本轮话题」。验证 AgentLoop 真的把用户说的话传下去了。 */
private var memTopic: String = ""

/**
 * 背景资料（记忆/技能索引）注入要能被宿主看见。
 *
 * 这一条是被真事逼出来的：用户问「主题四」，模型整段思考和正文都在讲主题三。
 * 只翻用户原文查不出原因——真正塞进去的东西必须留痕，否则「它为什么这么想」
 * 永远只能猜。同时验证：内容没变不重复报（否则日志会被每轮刷满）。
 */
private fun scenarioExtras(base: String) {
    val loop = AgentLoop(
        CloudClient("$base/S", "hy3", FakeTokens("t0", null)),
        PhoneToolbox({ FakeDevice() }, { null }),
        3,
        memory = { t -> memTopic = t; extrasMemory },
        skills = { "" },
    )
    val rec = Recorder()

    extrasMemory = "- 用户在准备主题三的比赛方案"
    loop.run("帮我查一下主题四", rec)
    result("extras_reported", rec.extras.any { it.contains("主题三") }, rec.extras.joinToString(" | "))
    // 话题词必须真的传到了记忆源——传不过去，相关性检索就是空转
    result("extras_topic_passed", memTopic.contains("主题四"), memTopic)

    // 内容变了（记忆被清掉）：必须再报一次，且字数归零
    val before = rec.extras.size
    extrasMemory = ""
    loop.run("你好", rec)
    // 追问也要带上文：像「那第二个呢」这种单看一句没有实词，靠前几句才找得到相关记忆
    result("extras_topic_carries_context",
        memTopic.contains("你好") && memTopic.contains("主题四"), memTopic)
    result("extras_reported_empty",
        rec.extras.size > before && rec.extras.last().startsWith("0|"),
        rec.extras.joinToString(" | "))

    // 内容没变：不该重复刷
    val after = rec.extras.size
    loop.run("你好", rec)
    result("extras_no_repeat", rec.extras.size == after, "${rec.extras.size} vs $after")
}

/**
 * 联网搜索。
 *
 * 为什么这个工具必须存在：没有它，模型遇到「某大赛主题四是什么」只能凭训练记忆
 * 猜编号——实测猜成主题三，还答得理直气壮。这里用假网页验证三件事：
 * 两个引擎的解析都认、第一个引擎挂了会自动退、全挂了要说「查不到」而不是编。
 */
private fun scenarioWebSearch() {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-search-" + System.nanoTime())
    dir.mkdirs()

    val bing = """
        <html><body><ol id="b_results">
        <li class="b_algo"><h2><a href="https://a.example.com/t4">河南省大赛<strong>主题四</strong>说明</a></h2>
          <div class="b_caption"><p>慧智银行数字化运营，分上下半场。</p></div></li>
        <li class="b_algo"><h2><a href="https://a.example.com/t3">往届主题三</a></h2><p>另一条</p></li>
        </ol></body></html>
    """.trimIndent()

    // 百度：h3 + /link?url= 跳转链接，摘要混在正文里，还夹着「翻译此页」这类样板噪声
    val baidu = """
        <html><body>
        <div><h3><a href="http://www.baidu.com/link?url=AAA">河南省大赛<b>主题四</b>说明</a></h3>
          <span>慧智银行数字化运营，分上下半场。百度快照</span></div>
        <div><h3><a href="http://www.baidu.com/link?url=BBB">另一条</a></h3>
          <span>查看此网页的中文翻译，请点击 翻译此页</span></div>
        </body></html>
    """.trimIndent()

    val bench = Workbench(dir)
    val parsed = bench.parseHits(bing, 5)
    result("search_parse_bing_count", parsed.size == 2, parsed.size.toString())
    result("search_parse_bing_title", parsed.firstOrNull()?.title?.contains("主题四") == true
        && parsed.first()?.title?.contains("<") == false, parsed.firstOrNull()?.title ?: "null")
    result("search_parse_bing_snippet", parsed.firstOrNull()?.snippet?.contains("慧智银行") == true,
        parsed.firstOrNull()?.snippet ?: "null")

    val pb = bench.parseHits(baidu, 5)
    result("search_parse_baidu_count", pb.size == 2, pb.size.toString())
    result("search_parse_baidu_title", pb.firstOrNull()?.title?.contains("主题四") == true
        && pb.first()?.title?.contains("<") == false, pb.firstOrNull()?.title ?: "null")
    result("search_parse_baidu_snippet", pb.firstOrNull()?.snippet?.contains("慧智银行") == true
        && pb.firstOrNull()?.snippet?.contains("百度快照") == false,
        pb.firstOrNull()?.snippet ?: "null")
    result("search_parse_baidu_noise_dropped", pb.getOrNull(1)?.snippet?.isEmpty() == true,
        pb.getOrNull(1)?.snippet ?: "null")
    result("search_parse_baidu_keeps_jump_link", pb.firstOrNull()?.url?.startsWith("http") == true,
        pb.firstOrNull()?.url ?: "null")

    // 百度移动版：data-log 里的 mu 是真实地址；摘要尾巴会残留 JSON 片段
    val baiduM = """
        <html><body>
        <div class="c-result result" data-log='{"order":1,"mu":"https://jyt.ah.gov.cn/a.html","x":1}'>
          <h3 class="cosc-title title_x"><span>关于举办第十六届三创赛的通知</span></h3>
          <div class="c-abstract">竞赛设特等奖、一等奖。"}],"not_struct":true}</div>
        </div>
        <div class="c-result result" data-log='{"order":2,"mu":"https://b.example.org/y"}'>
          <h3 class="cosc-title title_x">第二条</h3><div>摘要二</div>
        </div>
        </body></html>
    """.trimIndent()
    val pm = bench.parseHits(baiduM, 5)
    result("search_parse_baidu_m_count", pm.size == 2, pm.size.toString())
    result("search_parse_baidu_m_url", pm.firstOrNull()?.url == "https://jyt.ah.gov.cn/a.html",
        pm.firstOrNull()?.url ?: "null")
    result("search_parse_baidu_m_title", pm.firstOrNull()?.title?.contains("三创赛") == true,
        pm.firstOrNull()?.title ?: "null")
    result("search_parse_baidu_m_snippet_json_dropped",
        pm.firstOrNull()?.snippet?.contains("not_struct") == false
        && pm.firstOrNull()?.snippet?.contains("竞赛设") == true,
        pm.firstOrNull()?.snippet ?: "null")

    result("search_unwrap_ddg", bench.unwrapRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fb.example.org%2Fx")
        == "https://b.example.org/x", bench.unwrapRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fb.example.org%2Fx"))

    // 搜狗移动版：类名带构建哈希（title__titleText_287f），真实地址藏在 href 的 url= 参数里，
    // 页面上的 ./id=… 是统计跳转。（2026-11 实测：中文结果质量明显好于必应）
    val sogouM = """
        <html><body>
        <a href="./id=abc&amp;url=https%3A%2F%2Fzol.com.cn%2Fx.html&amp;vrid=1" class="link__saLink">
          <h3 class="sa-text-clamp2 title__titleContainer_287f"><span class="title__titleText_287f">2026年<em>免费AI API</em>清单</span></h3></a>
        <div>正文摘要：十大平台实测。</div>
        <a href="./id=def&amp;url=https%3A%2F%2Fb.example.org%2Fy&amp;vrid=2" class="link__saLink">
          <h3 class="sa-text-clamp2 title__titleContainer_287f"><span class="title__titleText_287f">第二条</span></h3></a>
        </body></html>
    """.trimIndent()
    val ps = bench.parseHits(sogouM, 5)
    result("search_parse_sogou_count", ps.size == 2, ps.size.toString())
    result("search_parse_sogou_url", ps.firstOrNull()?.url == "https://zol.com.cn/x.html",
        ps.firstOrNull()?.url ?: "null")
    result("search_parse_sogou_title", ps.firstOrNull()?.title == "2026年免费AI API清单",
        ps.firstOrNull()?.title ?: "null")
    result("search_parse_sogou_snippet", ps.firstOrNull()?.snippet?.contains("十大平台") == true,
        ps.firstOrNull()?.snippet ?: "null")

    // 微信搜索：标题后面要拼公众号名（同一篇转来转去时，谁写的决定可不可信），相对链接补域名
    val weixin = """
        <html><body><ul class="news-list">
        <li><div class="txt-box">
          <h3><a target="_blank" href="/link?url=dn9a&amp;type=2&amp;query=免费 AI 额度">10个开源仓库拼<b>AI</b>内容工厂</a></h3>
          <p class="txt-info">免费 API 额度总表，附领取步骤。</p>
          <div class="s-p"><a class="account">幺叁幺肆</a></div>
        </div></li>
        <li><div class="txt-box">
          <h3><a href="/link?url=xyz&amp;type=2">2026 全球 AI 免费 API 攻略</a></h3>
          <p class="txt-info">25 个平台能领的全在这了。</p>
          <div class="s-p"><a class="account">松枝裁云</a></div>
        </div></li>
        </ul></body></html>
    """.trimIndent()
    val pw = bench.parseHits(weixin, 5)
    result("search_parse_weixin_count", pw.size == 2, pw.size.toString())
    result("search_parse_weixin_title_has_account",
        pw.firstOrNull()?.title == "10个开源仓库拼AI内容工厂 · 幺叁幺肆", pw.firstOrNull()?.title ?: "null")
    result("search_parse_weixin_abs_url",
        pw.firstOrNull()?.url?.startsWith("https://weixin.sogou.com/link?url=") == true,
        pw.firstOrNull()?.url ?: "null")
    result("search_parse_weixin_snippet", pw.firstOrNull()?.snippet?.contains("总表") == true,
        pw.firstOrNull()?.snippet ?: "null")
    // 搜狗给的链接里 query 带裸空格（实测），不编码的话 HttpURLConnection 直接抛异常
    result("search_parse_weixin_url_encoded",
        pw.firstOrNull()?.url?.contains("%20") == true && pw.firstOrNull()?.url?.contains(" ") == false,
        pw.firstOrNull()?.url ?: "null")

    // 360 搜索：li.res-list + h3.res-title，真实落地页在 data-mdurl（href 是 so.com/link 跳转串）
    val so360 = """
        <html><body><ul class="result">
        <li class="res-list" data-result-type="normal" data-result-pos="1">
          <h3 class="res-title "><a href="https://www.so.com/link?m=abc" data-mdurl="https://c.example.com/t4">河南省大赛<em>主题四</em>说明</a></h3>
          <p class="res-desc"><span class="gray g-c-gray">2026年4月30日&nbsp;-&nbsp;</span>慧智银行数字化运营，分上下半场。</p>
        </li>
        <li class="res-list"><h3 class="res-title"><a href="https://www.so.com/link?m=def" data-mdurl="https://c.example.com/t3">往届主题三</a></h3>
          <p class="res-desc">另一条</p></li>
        </ul></body></html>
    """.trimIndent()
    val p360 = bench.parseHits(so360, 5)
    result("search_parse_360_count", p360.size == 2, p360.size.toString())
    result("search_parse_360_title", p360.firstOrNull()?.title == "河南省大赛主题四说明",
        p360.firstOrNull()?.title ?: "null")
    result("search_parse_360_real_url_not_jump",
        p360.firstOrNull()?.url == "https://c.example.com/t4", p360.firstOrNull()?.url ?: "null")
    result("search_parse_360_snippet", p360.firstOrNull()?.snippet?.contains("慧智银行") == true,
        p360.firstOrNull()?.snippet ?: "null")

    // source=weixin：只打微信搜索一个站，不浪费往返去试百度/必应
    val wxUrls = ArrayList<String>()
    val wxOnly = Workbench(dir, searchFetcher = { url, _ ->
        wxUrls.add(url)
        if (url.contains("weixin.sogou.com")) FetchResult(200, "text/html", weixin)
        else FetchResult(200, "text/html", bing)
    })
    val rw = JSONObject(wxOnly.execute("web_search", JSONObject().put("query", "免费 API").put("source", "weixin")))
    result("search_source_weixin_engine", rw.optBoolean("ok") && rw.optString("engine") == "微信搜索",
        rw.optString("engine"))
    result("search_source_weixin_only_weixin_host",
        wxUrls.isNotEmpty() && wxUrls.all { it.contains("weixin.sogou.com") }, wxUrls.joinToString(","))


    // 前面的引擎（百度/搜狗）500，360 给结果页：要自动退过去，而不是直接失败
    val urls = ArrayList<String>()
    val logs = ArrayList<String>()
    val fallback = Workbench(dir, searchFetcher = { url, _ ->
        urls.add(url)
        when {
            url.contains("baidu.com") || url.contains("m.sogou.com") -> FetchResult(500, "text/html", "")
            url.contains("so.com") -> FetchResult(200, "text/html", so360)
            else -> FetchResult(200, "text/html", bing)
        }
    }, logger = { logs.add(it) })
    val r1 = JSONObject(fallback.execute("web_search", JSONObject().put("query", "主题四 赛制")))
    result("search_fallback_ok", r1.optBoolean("ok"), r1.toString().take(120))
    result("search_fallback_engine", r1.optString("engine") == "360搜索", r1.optString("engine"))
    // 引擎数量增加后按「不同站点」判断：m.baidu、m.sogou、www.so 都试过了
    // （360 已出结果就不该再打必应——这里是「退让到成功即停」的断言）
    result("search_fallback_tried_all_engines",
        urls.map { it.substringAfter("://").substringBefore("/") }.distinct().size == 3
            && urls.none { it.contains("bing.com") },
        urls.map { it.substringAfter("://").substringBefore("/") }.distinct().joinToString(","))
    result("search_query_encoded", urls.firstOrNull()?.contains("%E4%B8%BB%E9%A2%98%E5%9B%9B") == true,
        urls.firstOrNull() ?: "")
    result("search_360_snippet", r1.optString("results").contains("慧智银行"), r1.optString("results").take(120))
    // 降级链路要进运行日志：流水账只留结果前 300 字，fallbacks 会被截掉，
    // 用户问「为什么这次全是必应」时只能靠这一行
    result("search_fallback_logged",
        logs.any { it.contains("360搜索") && it.contains("先试过") && it.contains("百度移动版") },
        logs.joinToString(" | ").take(160))

    // 全挂：必须是「查不到」的明确失败，不能返回空成功（空成功会让模型以为搜过了、没结果）
    val dead = Workbench(dir, searchFetcher = { _, _ -> FetchResult(503, "text/html", "") })
    val r2 = JSONObject(dead.execute("web_search", JSONObject().put("query", "x")))
    result("search_all_fail_code", !r2.optBoolean("ok") && r2.optString("code") == "SEARCH_FAILED",
        r2.toString().take(120))
    result("search_all_fail_tells_model_not_to_guess", r2.optString("error").contains("不要凭记忆"), r2.optString("error"))

    result("search_blank_query", JSONObject(Workbench(dir).execute("web_search", JSONObject()))
        .optString("code") == "BAD_ARGS", "")

    // 搜索页必须按「大页」的额度读。实测百度一页 895KB、第一个 <h3> 在 687KB 处：
    // 按正文抓取的 512KB 截断就一条都抽不到，会静默退到必应拿噪声（真机踩过）。
    val limits = ArrayList<Int>()
    val big = Workbench(dir, searchFetcher = { _, max ->
        limits.add(max)
        // 700KB 填充 + 结果在末尾，模拟真实百度页的结果位置
        FetchResult(200, "text/html", "<html><body>" + "x".repeat(700 * 1024) + baidu)
    })
    val r3 = JSONObject(big.execute("web_search", JSONObject().put("query", "三创赛")))
    result("search_reads_large_page", r3.optBoolean("ok") && r3.optInt("count") > 0, r3.toString().take(120))
    result("search_byte_budget_ge_2mb", limits.isNotEmpty() && limits.all { it >= 2 * 1024 * 1024 },
        limits.joinToString(","))
    result("search_parses_hits_after_700kb", r3.optString("results").contains("主题四"),
        r3.optString("results").take(120))

    // 前一个引擎失败的原因要带出来，否则只能看到「又是必应噪声」却不知道百度为什么没出结果
    val partial = Workbench(dir, searchFetcher = { url, _ ->
        if (url.contains("baidu.com")) FetchResult(403, "text/html", "")
        else FetchResult(200, "text/html", bing)
    })
    val r4 = JSONObject(partial.execute("web_search", JSONObject().put("query", "主题四")))
    result("search_fallback_reason", r4.optBoolean("ok") && r4.optString("fallbacks").contains("百度"),
        r4.optString("fallbacks"))

    // 反爬页必须认出来：百度桌面版被 302 到 855KB 的「安全验证」壳，按体积判断会当成
    // 「读到 855 KB、0 条」，看着像查询没结果（2026-11 实测，排查卡在这）。
    val captchaBench = Workbench(dir, searchFetcher = { url, _ ->
        if (url.contains("baidu.com")) FetchResult(
            200, "text/html",
            "<html><body>" + "x".repeat(120 * 1024) +
                "<script>window.location.replace('https://www.baidu.com/_____tmd_____/punish?x5secdata=abc')</script>" +
                "</body></html>"
        )
        else FetchResult(200, "text/html", bing)
    })
    val rc = JSONObject(captchaBench.execute("web_search", JSONObject().put("query", "主题四")))
    result("search_captcha_detected",
        rc.optBoolean("ok") && rc.optString("fallbacks").contains("反爬"), rc.optString("fallbacks"))

    // 手机流量下百度只回 1.5KB 的「百度安全验证」页：认出来之后不该再重试第二遍
    // （每次重试都是一次真实往返，用户看到的就是「搜索怎么这么慢」）
    var verifyCalls = 0
    val verifyBench = Workbench(dir, searchFetcher = { url, _ ->
        if (url.contains("baidu.com")) {
            verifyCalls += 1
            FetchResult(200, "text/html", "<html><head><title>百度安全验证</title></head><body></body></html>")
        } else FetchResult(200, "text/html", bing)
    })
    val rv = JSONObject(verifyBench.execute("web_search", JSONObject().put("query", "主题四")))
    result("search_verify_no_retry",
        rv.optBoolean("ok") && verifyCalls == 1 && rv.optString("fallbacks").contains("反爬"),
        "calls=$verifyCalls fallbacks=" + rv.optString("fallbacks"))
    // 冷却：同一次任务里连搜几次时，不该每次先去撞一遍同一个反爬引擎（每次都是一趟往返）
    val rv2 = JSONObject(verifyBench.execute("web_search", JSONObject().put("query", "主题五")))
    result("search_captcha_cooldown",
        rv2.optBoolean("ok") && verifyCalls == 1 && rv2.optString("fallbacks").contains("跳过"),
        "calls=$verifyCalls fallbacks=" + rv2.optString("fallbacks"))

    // 转义过的 HTML（真机百度摘要里的形态：&lt;div class="…"&gt;正文）不能漏进摘要
    val escaped = "&lt;div class=&quot;cos-color-text-tiny&quot;&gt;正文在这里&lt;/div&gt;"
    val cleaned = Workbench.htmlToText(escaped)
    result("html_escaped_tags_stripped",
        cleaned.contains("正文在这里") && !cleaned.contains("div") && !cleaned.contains("class="),
        cleaned)
    // 截断点落在标签中间：真机摘要开头就是半截 <div class="cos-color-text-tiny…
    val cut = Workbench.htmlToText("<div>正文<div class=\"cos-color-text-tiny summary-gap")
    result("html_truncated_tag_dropped", cut.contains("正文") && !cut.contains("class="), cut)
    val long = "x".repeat(100) + "<div class=\"a b\">摘要正文</div>"
    val aligned = Workbench.alignToTagEnd(long, 105)
    result("align_tag_end", long.substring(0, aligned).endsWith(">"), aligned.toString())
    result("align_tag_end_noop", Workbench.alignToTagEnd("abc", 2) == 2, "")

    // 百度偶发回一个 1KB 的空壳页（没有反爬标记）：这种可能是瞬时的，要重试一次，
    // 重试成了就用百度，而不是立刻退到搜狗/必应
    var baiduCalls = 0
    val retry = Workbench(dir, searchFetcher = { url, _ ->
        if (url.contains("baidu.com")) {
            baiduCalls++
            if (baiduCalls == 1) FetchResult(200, "text/html", "<html><body>" + "x".repeat(900) + "</body></html>")
            else FetchResult(200, "text/html", baidu)
        } else FetchResult(200, "text/html", bing)
    })
    val r5 = JSONObject(retry.execute("web_search", JSONObject().put("query", "主题四")))
    result("search_retry_after_verify", r5.optString("engine") == "百度移动版" && baiduCalls == 2,
        r5.optString("engine") + "/calls=" + baiduCalls)

    // 验证页一直存在：要写明「反爬」，不能让模型/用户以为「这个查询没结果」
    val blocked = Workbench(dir, searchFetcher = { url, _ ->
        if (url.contains("baidu.com")) FetchResult(200, "text/html", "<html>百度安全验证</html>")
        else FetchResult(200, "text/html", bing)
    })
    val r6 = JSONObject(blocked.execute("web_search", JSONObject().put("query", "主题四")))
    result("search_verify_labeled", r6.optString("fallbacks").contains("反爬"), r6.optString("fallbacks"))

    // 热门填充检测：不同 query 返回同一批 URL = 引擎没匹配上，拿热门页凑数。
    // 真机实录（GTA6 悬赏调研）：模型连发引号短语查询，必应三次不同查询回一模一样的
    // 四条（AI 工具集导航 / IBM / Google / Kimi）——URL 集重合就是铁证。
    val fillLogs = ArrayList<String>()
    val fill = Workbench(dir, searchFetcher = { url, _ ->
        if (url.contains("bing.com")) FetchResult(200, "text/html", bing)
        else FetchResult(500, "text/html", "")
    }, logger = { fillLogs.add(it) })
    val rf1 = JSONObject(fill.execute("web_search", JSONObject().put("query", "AI judges bounty platform")))
    result("search_fill_first_soft", rf1.optBoolean("ok") && rf1.optString("engine") == "必应"
        && rf1.optString("warning").contains("核实"), rf1.optString("warning"))
    val rf2 = JSONObject(fill.execute("web_search", JSONObject().put("query", "micro prize entry fee")))
    result("search_fill_detected", rf2.optString("warning").contains("热门填充")
        && rf2.optString("warning").contains("别堆关键词"), rf2.optString("warning"))
    result("search_fill_logged", fillLogs.any { it.contains("疑似热门填充") },
        fillLogs.joinToString(" | ").take(200))
    // 同一个查询词重搜返回同样结果：那是正常缓存，不算填充
    val rf3 = JSONObject(fill.execute("web_search", JSONObject().put("query", "micro prize entry fee")))
    result("search_fill_same_query_not_flagged",
        rf3.optString("warning").contains("核实") && !rf3.optString("warning").contains("热门填充"),
        rf3.optString("warning"))

    dir.deleteRecursively()
}

/**
 * 真机网络冒烟（只在 WB_LIVE_SEARCH=1 时跑）：验证「百度真的能出结果」。
 * 单测全用假页面，永远查不出「读取上限截掉了结果」这一类问题——这次就是它。
 */
private fun scenarioLiveSearch() {
    if (System.getenv("WB_LIVE_SEARCH") == null) return
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-live-" + System.nanoTime())
    dir.mkdirs()
    val r = JSONObject(Workbench(dir).execute("web_search",
        JSONObject().put("query", "第十六届全国大学生电子商务三创赛 官网")))
    println("[live] engine=" + r.optString("engine") + " count=" + r.optInt("count")
        + " fallbacks=" + r.optString("fallbacks"))
    println("[live] results=" + r.optString("results").take(300))
    dir.deleteRecursively()
}

/**
 * 重定向解析：http→https 这类跨协议跳转 JVM 不自动跟，必须自己算下一跳。
 * 3chuang.net 就是 http 302 到 https，修前一直报「HTTP 302」。
 */
private fun scenarioHttpRedirect() {
    result("redirect_cross_protocol",
        Workbench.nextUrl("http://www.3chuang.net/", "https://www.3chuang.net/") == "https://www.3chuang.net/",
        Workbench.nextUrl("http://www.3chuang.net/", "https://www.3chuang.net/"))
    result("redirect_relative_path",
        Workbench.nextUrl("https://www.3chuang.net/", "/news") == "https://www.3chuang.net/news",
        Workbench.nextUrl("https://www.3chuang.net/", "/news"))
    result("redirect_relative_segment",
        Workbench.nextUrl("https://a.example.com/x/y", "z") == "https://a.example.com/x/z",
        Workbench.nextUrl("https://a.example.com/x/y", "z"))
    result("redirect_other_host",
        Workbench.nextUrl("https://a.example.com/x", "https://b.example.com/y") == "https://b.example.com/y",
        Workbench.nextUrl("https://a.example.com/x", "https://b.example.com/y"))
}

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

    // 表格：真机翻车现场（GitHub 榜单四列）——管道符和 ---: 不许再裸奔，
    // 收成「表头加粗 · 数据行首列加粗」的紧凑行，分隔行直接扔掉
    val tbl = Marked.parse(
        "| 项目 | 简介 | 语言 | 今日 +Star |\n" +
        "|---:|---|---|\n" +
        "| [obra/superpowers](https://github.com/obra/superpowers) | 可落地的 Agent 技能框架 | Shell | +2,029 |\n" +
        "| [anthropics/Claude-BugHunter](https://github.com/anthropics/h) | 红队技能包 | Python | +1,353 |"
    )
    result("md_table_block", tbl.size == 1 && tbl[0].kind == "table" && tbl[0].lines.size == 3
        && !tbl[0].lines.joinToString("|") { l -> l.joinToString() { it.text } }.contains("---"),
        tbl.map { it.kind }.toString())
    result("md_table_header_bold", tbl[0].lines[0].all { it.bold || it.text == " · " }
        && tbl[0].lines[0][0].text == "项目", dump(tbl[0]))
    result("md_table_first_cell_bold", tbl[0].lines[1][0].bold && tbl[0].lines[1][0].link != null
        && tbl[0].lines[1][0].text == "obra/superpowers", dump(tbl[0]))
    result("md_table_link_url", tbl[0].lines[1][0].link == "https://github.com/obra/superpowers"
        && tbl[0].lines[1].any { it.text == " · " }
        && tbl[0].lines[1].last().text == "+2,029", dump(tbl[0]))

    // 不是表格的 | 行（没有分隔行）不误伤，照旧是段落
    val notTbl = Marked.parse("| 这只是个竖线 | 别当表格 |")
    result("md_table_no_sep", notTbl.size == 1 && notTbl[0].kind == "p", notTbl.map { it.kind }.toString())

    // 行内链接：文字剥出来挂 url，文字里的粗体也活着（链接文字按粗体边界拆成两段）
    val link = Marked.inline("去 [**GitHub** Trending](https://github.com/trending) 看看")
    result("md_link_seg", link.size == 4 && link[1].link == "https://github.com/trending"
        && link[1].bold && link[1].text == "GitHub"
        && link[2].link == "https://github.com/trending" && link[2].text == " Trending"
        && link[0].text == "去 " && link[3].text == " 看看",
        link.joinToString("|") { it.text })
    // 伪链接（方括号后面没跟着圆括号）不误伤
    val noLink = Marked.inline("数组[0]取值")
    result("md_link_no_false_hit", noLink.size == 1 && noLink[0].text == "数组[0]取值"
        && noLink[0].link == null, noLink.joinToString("|") { it.text })
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
    result("ft_route_unsupported", FileText.route("照片.heic") == "unsupported"
        && FileText.route("README") == "unsupported", FileText.route("照片.heic"))
    result("ft_route_xlsx", FileText.route("报表.XLSX") == "xlsx", FileText.route("报表.XLSX"))

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

    // 不支持的格式：明说支持范围（xlsx 现在能读了，换 heic 当反例）
    val bin = java.io.File(dir, "数据.bin").apply { writeText("fake") }
    val r5 = FileText.extract(bin, "数据.bin")
    result("ft_unsupported_msg", r5.kind == "unsupported" && r5.text.contains("暂时读不了")
        && r5.text.contains("pdf"), r5.text.take(50))

    dir.deleteRecursively()
}

/** docx 块级样式：真造一份论文格式的，解包 document.xml 逐项核对 OOXML。 */
private fun scenarioDocxStyles() {
    val dir = java.nio.file.Files.createTempDirectory("dxs").toFile()
    val ts = org.json.JSONObject()
        .put("font", "宋体").put("ascii", "Times New Roman")
        .put("size", "三号").put("bold", true)
    val blocks = org.json.JSONArray()
        // 题目：宋体三号加粗居中
        .put(org.json.JSONObject().put("type", "p").put("text", "基于边缘计算的智慧大棚监测系统")
            .put("font", "宋体").put("size", "三号").put("bold", true).put("align", "居中"))
        // 摘要：楷体小四
        .put(org.json.JSONObject().put("type", "p").put("text", "摘要：本文提出一种监测方案。")
            .put("font", "楷体").put("size", "小四").put("line", 1.5))
        // 正文：宋体小四两端对齐
        .put(org.json.JSONObject().put("type", "p").put("text", "实验结果表明该方案可行。")
            .put("font", "宋体").put("ascii", "Times New Roman").put("size", "12")
            .put("align", "both"))
    val f = java.io.File(dir, "论文.docx")
    val resp = org.json.JSONObject(DocxWriter.write(f, "基于边缘计算的智慧大棚监测系统", blocks, ts))
    result("dx_style_ok", resp.optBoolean("ok"), resp.optString("error"))

    // 解包 zip 拿 document.xml（DocxWriter 不依赖 android，JVM 侧直接解）
    val docXml = java.util.zip.ZipFile(f).use { zip ->
        zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
    }

    // 按段切出来逐个核对（每段一个 <w:p>…</w:p>）
    val paras = Regex("<w:p>.*?</w:p>").findAll(docXml).map { it.value }.toList()
    val titleP = paras.firstOrNull { it.contains("智慧大棚") } ?: ""
    val absP = paras.firstOrNull { it.contains("摘要") } ?: ""
    val bodyP = paras.firstOrNull { it.contains("实验结果") } ?: ""
    // 大标题：给了 title_style 就不能再有装饰细线（过不了格式审查），颜色也得回黑
    result("dx_title_no_rule", titleP.isNotEmpty() && !titleP.contains("w:pBdr")
        && !titleP.contains("w:color"), "")
    // 中文字号「三号」→ 32 半磅（16pt），解析错就整个论文格式塌掉
    result("dx_size_cn", docXml.contains("w:val=\"32\""), "")
    // 「12」磅 → 24 半磅；「小四」→ 24 半磅
    result("dx_size_pt", docXml.contains("w:sz w:val=\"24\""), "")
    // 字体真的写进去了：eastAsia=宋体、楷体，西文独立给 Times New Roman
    result("dx_font", docXml.contains("w:eastAsia=\"宋体\"") && docXml.contains("w:eastAsia=\"楷体\"")
        && docXml.contains("w:ascii=\"Times New Roman\""), "")
    // 居中/两端对齐的段必须真带 w:jc；居中的段不能再带 firstLineChars（缩进的居中段是歪的）
    result("dx_align", titleP.contains("w:jc w:val=\"center\"")
        && bodyP.contains("w:jc w:val=\"both\""), "")
    result("dx_center_no_indent", titleP.contains("w:jc") && !titleP.contains("firstLineChars"), "")
    // 行距 1.5 → w:line=360 且 lineRule=auto（漏了 lineRule 在 WPS 上表现不定）
    result("dx_line", absP.contains("w:line=\"360\"") && absP.contains("lineRule=\"auto\""), "")
    // 首行缩进还在：普通正文（没给 align 的）不能丢两字缩进——默认版式的底线
    result("dx_indent_kept", paras.any { it.contains("firstLineChars=\"200\"") }, "")

    // 坏样式不炸：align 写成乱码要落回默认而不是抛异常
    val bad = org.json.JSONArray().put(org.json.JSONObject()
        .put("type", "p").put("text", "普通段").put("align", "天知道").put("size", "看不懂"))
    val f2 = java.io.File(dir, "坏样式.docx")
    val r2 = org.json.JSONObject(DocxWriter.write(f2, "t", bad))
    result("dx_bad_style_safe", r2.optBoolean("ok"), r2.optString("error"))

    dir.deleteRecursively()
}

/**
 * 压缩必须留住两类东西：用户中途补的要求、任务成果本身（URL / 文件路径）。
 * 真机事故：40 条消息压成摘要后 URL 全没了，模型推荐了 10 个模块一条链接都没带，
 * 用户问「我不是要下载地址吗」它又重抓了三轮——用户被迫一步步提示。
 */
private fun scenarioCompactKeepsAsks(base: String) {
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })
    val loop = AgentLoop(CloudClient("$base/L", "hy3", FakeTokens("t0", null)), toolbox, 4)

    val hist = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "推荐一些 Redmi8A 能用的 root 模块"))
        .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls",
            JSONArray().put(JSONObject().put("id", "k1").put("type", "function")
                .put("function", JSONObject().put("name", "web_fetch")
                    .put("arguments", "{\"url\":\"https://github.com/LSPosed/LSPosed/releases\"}")))))
        .put(JSONObject().put("role", "tool").put("tool_call_id", "k1")
            .put("content", "{\"ok\":true,\"url\":\"https://github.com/LSPosed/LSPosed/releases\",\"content\":\"" + "x".repeat(300) + "\"}"))
        .put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls",
            JSONArray().put(JSONObject().put("id", "k2").put("type", "function")
                .put("function", JSONObject().put("name", "fs_write").put("arguments", "{}")))))
        .put(JSONObject().put("role", "tool").put("tool_call_id", "k2")
            .put("content", "{\"ok\":true,\"path\":\"清单.md\",\"bytes\":3944}"))
        // 用户中途补的要求——被压掉就等于没提过
        .put(JSONObject().put("role", "user").put("content", "下载地址也要一起给"))
    for (i in 1..12) {
        hist.put(JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls",
            JSONArray().put(JSONObject().put("id", "kx$i").put("type", "function")
                .put("function", JSONObject().put("name", "phone_read_screen").put("arguments", "{}")))))
        hist.put(JSONObject().put("role", "tool").put("tool_call_id", "kx$i")
            .put("content", "{\"ok\":true,\"content\":\"" + "y".repeat(4200) + "\"}"))
    }
    loop.restoreHistory(hist)
    val rec = Recorder()
    loop.run("继续", rec)
    result("ck_compacted", loop.compactions >= 1, loop.compactions.toString())

    val sum = loop.exportHistory().getJSONObject(0).optString("content")
    result("ck_goal_kept", sum.contains("目标：推荐一些"), sum.take(80))
    result("ck_asks_kept", sum.contains("- 要求：下载地址也要一起给"),
        sum.lines().filter { it.startsWith("- 要求") }.joinToString(" | "))
    // URL 是成果本身，不许被 90 字截断挤掉
    result("ck_url_kept", sum.contains("https://github.com/LSPosed/LSPosed/releases"), "")
    result("ck_path_kept", sum.contains("清单.md"), "")
}

/**
 * phone_intent：系统能直接办的事不走 UI。
 *
 * 两个要害：①参数缺失必须在本地挡掉——打开一个空白页模型会以为成功了；
 * ②它不碰屏幕，所以不占租约：另一个会话正拿着手机时也该能设闹钟。
 */
private fun scenarioIntent() {
    PhoneLease.reset()
    val device = FakeDevice()
    val toolbox = PhoneToolbox({ device }, { null })

    val alarm = toolbox.execute("phone_intent",
        JSONObject().put("action", "alarm").put("hour", 7).put("minute", 5).put("message", "吃药"), "X").text
    result("int_alarm_ok", alarm.contains("\"ok\":true") && alarm.contains("闹钟 7:05"), alarm.take(120))
    // 参数真的原样交给了系统（hour/minute 没被吞掉）
    val rec = device.intents.lastOrNull()
    result("int_alarm_args", rec?.first == "alarm" && rec.second.optInt("hour") == 7
        && rec.second.optInt("minute") == 5, rec?.second?.toString() ?: "none")

    // 缺 minute：本地就拒，不打开空白页
    val bad = toolbox.execute("phone_intent",
        JSONObject().put("action", "alarm").put("hour", 7), "X").text
    result("int_missing_arg", bad.contains("\"ok\":false"), bad.take(120))

    // 不在白名单的 action 一律不认（不给模型开放任意 Intent）
    val unknown = toolbox.execute("phone_intent",
        JSONObject().put("action", "start_activity").put("component", "com.x/.Y"), "X").text
    result("int_action_whitelist", unknown.contains("BAD_ACTION"), unknown.take(120))

    // 动作成功后带 screen_after，模型同一轮就能确认落在哪个界面
    result("int_screen_after", alarm.contains("screen_after"), alarm.take(160))

    // 租约在别人手里时，系统意图照样能用（它不碰屏幕）
    PhoneLease.reset()
    PhoneLease.tryAcquire("A")
    val during = toolbox.execute("phone_intent",
        JSONObject().put("action", "timer").put("seconds", 1500), "B").text
    result("int_no_lease_needed", during.contains("\"ok\":true"), during.take(120))
    val dialBlocked = toolbox.execute("phone_read_screen", JSONObject(), "B").text
    result("int_lease_still_guards_screen", dialBlocked.contains("PHONE_BUSY"), dialBlocked.take(100))
    PhoneLease.reset()
}

/**
 * 子代理隔离。
 *
 * 要证明的就一件事：子代理抓回来的东西**不进主线**，主线只拿到结论。
 * 这件事没法靠读代码保证（历史是运行时拼的），所以真跑一遍：假服务器扮演
 * 「主线模型」和「子代理模型」两种身份，子代理那边抓一次网页，看主线历史里有没有。
 */
private fun scenarioSubAgent(base: String) {
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "subagent-" + System.nanoTime())
    dir.mkdirs()
    val bench = Workbench(dir, fetcher = { _, _ ->
        FetchResult(200, "text/html", "<html><body><p>A 的下载地址 https://example.com/a</p></body></html>")
    })

    // ---- 只读工具箱：schema 里只能有读操作
    val box = ReadOnlyToolbox(bench)
    val names = HashSet<String>()
    val arr = box.schemas()
    for (i in 0 until arr.length()) {
        names.add(arr.getJSONObject(i).getJSONObject("function").getString("name"))
    }
    result(
        "sub_schema_readonly",
        names == setOf("fs_read", "fs_list", "fs_search", "web_fetch", "web_search"),
        names.joinToString(",")
    )

    // 写文件必须被拒——子代理看不到主对话，能写文件就没人能追溯它改了什么
    val w = box.execute("fs_write", JSONObject().put("path", "x.txt").put("content", "hi"))
    result("sub_write_blocked", w.text.contains("SUB_READONLY"), w.text.take(100))

    // ---- 端到端：主线派 task → 子代理自己抓网页 → 只有结论回来
    val toolbox = PhoneToolbox(
        deviceProvider = { null },
        workbench = bench,
        delegate = { goal, focus, onProgress, isCancelled ->
            SubAgent.run(
                client = CloudClient("$base/SUB", "glm-5.3", FakeTokens("t0", null)),
                bench = bench,
                goal = goal,
                focus = focus,
                onProgress = onProgress,
                parentCancelled = isCancelled,
            )
        },
    )
    val loop = AgentLoop(CloudClient("$base/SUB", "glm-5.3", FakeTokens("t0", null)), toolbox, 6)

    val toolNames = ArrayList<String>()
    val progress = ArrayList<String>()
    var answer = ""
    var error = ""
    loop.run("帮我查一下 A 的下载地址", object : AgentLoop.Listener {
        override fun onText(delta: String) {}
        override fun onToolStart(name: String, args: JSONObject) { toolNames.add(name) }
        override fun onToolEnd(name: String, args: org.json.JSONObject, result: String, ok: Boolean) {}
        override fun onTurnEnd(turn: Int) {}
        override fun onFinished(finalText: String, turns: Int, toolCalls: Int) { answer = finalText }
        override fun onError(message: String) { error = message }
        override fun onSubProgress(note: String) { progress.add(note) }
    })

    result("sub_parent_only_task", toolNames == listOf("task"), toolNames.joinToString(",") + error)
    result("sub_progress_reported", progress.any { it.contains("抓网页") }, progress.joinToString("|"))

    val hist = loop.exportHistory().toString()
    result("sub_isolated", !hist.contains("web_fetch") && hist.contains("example.com/a"), hist.take(300))
    result("sub_tokens_counted", loop.promptTokens > 0 && loop.completionTokens > 0,
        "${loop.promptTokens}/${loop.completionTokens}")

    // 主线的最终回答来自主线模型，不是子代理那一段
    result("sub_result_has_url", answer.contains("example.com/a"), answer.take(120))

    // 子代理一直读文件、始终不给结论：必须明说没完成，不能把「还没完成」当答案
    java.io.File(dir, "notes.md").writeText("版本 3.2.1\n")
    val stuck = SubAgent.run(
        client = CloudClient("$base/SUBI", "glm-5.3", FakeTokens("t0", null)),
        bench = bench,
        goal = "把 notes.md 里所有版本号都找出来（这是个跑不完的活）",
        maxTurns = 3,
    )
    result("sub_incomplete_reported", stuck.text.contains("SUB_INCOMPLETE"), stuck.text.take(160))

    // ---- 连续失败熔断：真机实录 GitHub 不可达时子代理连抓 39 步，用户干等 14 分钟。
    // 现在失败连击到 FAIL_STREAK_LIMIT 引擎直接收，主线收到 SUB_STUCK + 换路提示。
    val deadDir = java.io.File(System.getProperty("java.io.tmpdir"), "subagent-dead-" + System.nanoTime())
    deadDir.mkdirs()
    val deadBench = Workbench(deadDir, fetcher = { _, _ ->
        throw java.io.IOException("failed to connect to github.com after 15000ms")
    })
    var giveUpNote = ""
    val notes = ArrayList<String>()
    // 探针：先单发一次，确认死站点返回的确实是 ok:false 的失败 JSON
    // （断言用解析而不是 startsWith——org.json 键序是 HashMap 序，ok 不保证在开头）
    val probe = ReadOnlyToolbox(deadBench).execute(
        "web_fetch", JSONObject().put("url", "https://github.com/x0").put("max_chars", 1000))
    result("sub_dead_probe",
        !runCatching { JSONObject(probe.text).optBoolean("ok", true) }.getOrDefault(true),
        probe.text.take(150))
    val dead = SubAgent.run(
        client = CloudClient("$base/SUBF", "glm-5.3", FakeTokens("t0", null)),
        bench = deadBench,
        goal = "抓 github.com 上的应用清单（站点是死的，怎么抓都超时）",
        onProgress = { notes.add(it); giveUpNote = it },
    )
    result("sub_circuit_breaker",
        dead.text.contains("SUB_STUCK") && dead.text.contains("fail_streak"),
        dead.text.take(200) + " || notes=" + notes.joinToString("|"))
    result("sub_giveup_progress", giveUpNote.contains("提前收工"), giveUpNote)

    // ---- 子代理不自动续跑：真机实录里 3 个子代理各跑 10 轮，被自动续成 30 轮，
    //      并排跑了 19 分钟才一起失败。这里用「永远给工具调用、永远不给结论」的
    //      假服务端：必须正好停在 maxTurns，回到主线的是 SUB_INCOMPLETE，不是 3 倍时长。
    val loopDir = java.io.File(System.getProperty("java.io.tmpdir"), "subagent-loop-" + System.nanoTime())
    loopDir.mkdirs()
    val loopBench = Workbench(loopDir)
    val loopNotes = ArrayList<String>()
    val endless = SubAgent.run(
        client = CloudClient("$base/SUBL", "glm-5.3", FakeTokens("t0", null)),
        bench = loopBench,
        goal = "把所有文件列一遍（这是个永远做不完的活）",
        maxTurns = 4,
        onProgress = { loopNotes.add(it) },
    )
    val maxTurn = loopNotes.mapNotNull {
        Regex("第 (\\d+) 轮").find(it)?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull() ?: -1
    result("sub_no_autocontinue_turns", maxTurn == 4, "maxTurn=$maxTurn notes=${loopNotes.joinToString("|")}")
    result("sub_no_autocontinue_incomplete",
        endless.text.contains("SUB_INCOMPLETE") && endless.text.contains("4 轮"), endless.text.take(200))

    // ---- 墙钟到点收工：每轮慢 0.4 秒、永远不收敛，预算 1.2 秒必须触发 SUB_TIMEOUT，
    //      而不是让用户等到 4 分钟上限。
    val slowNotes = ArrayList<String>()
    val slow = SubAgent.run(
        client = CloudClient("$base/SUBSLOW", "glm-5.3", FakeTokens("t0", null)),
        bench = loopBench,
        goal = "把所有文件列一遍（每轮都很慢）",
        maxTurns = 20,
        maxWallMs = 1_200,
        onProgress = { slowNotes.add(it) },
    )
    result("sub_wall_clock_timeout", slow.text.contains("SUB_TIMEOUT"), slow.text.take(200))
    result("sub_wall_clock_progress", slowNotes.any { it.contains("已 ") }, slowNotes.joinToString("|").take(160))

    loopDir.deleteRecursively()
    dir.deleteRecursively()
    deadDir.deleteRecursively()
}

/**
 * html_preview：工具校验 + 本机回环 HTTP 服务真起真连 + onOpenHtml 链路。
 *
 * 之前的坑：产物分享用 content://，手机浏览器把它当搜索词（2026-11-17 实测）。
 * 现在网页统一走 ArtifactServer 的 http://127.0.0.1——这里用真的 GET 验证，
 * 包括中文路径 percent 编码往返、相对资源 MIME、404、目录逃逸拦截。
 */
private fun scenarioHtmlPreview(base: String) {
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), "htmlprev-" + System.nanoTime())
    dir.mkdirs()
    val bench = Workbench(dir)

    val page = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>阅读实验室</title>" +
        "<link rel=\"stylesheet\" href=\"style.css\"></head>" +
        "<body><h1>你好</h1><script>localStorage.setItem(\"k\",\"v\")</script></body></html>"
    bench.execute("fs_write", JSONObject().put("path", "lab/index.html").put("content", page))
    bench.execute("fs_write", JSONObject().put("path", "lab/style.css").put("content", "h1{color:red}"))

    // ---- 工具校验：ok、类型不对拒绝、文件不存在拒绝
    val r = bench.execute("html_preview", JSONObject().put("path", "lab/index.html"))
    result("html_preview_ok", r.contains("\"ok\":true") && r.contains("lab/index.html"), r.take(140))
    result("html_preview_reject_type",
        bench.execute("html_preview", JSONObject().put("path", "lab/style.css")).contains("BAD_TYPE"), "")
    result("html_preview_missing",
        bench.execute("html_preview", JSONObject().put("path", "lab/404.html")).contains("NOT_FOUND"), "")

    // ---- schema 必须在
    var inSchema = false
    val arr = bench.schemas()
    for (i in 0 until arr.length()) {
        if (arr.getJSONObject(i).getJSONObject("function").getString("name") == "html_preview") inSchema = true
    }
    result("html_preview_in_schema", inSchema, "")

    // ---- 回环服务：真 GET（中文路径、相对资源、404、目录逃逸）
    result("srv_start", ArtifactServer.start(dir), "")
    fun get(u: String): Triple<Int, String, String> {
        val c = (java.net.URL(u).openConnection() as java.net.HttpURLConnection)
        c.instanceFollowRedirects = false
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Triple(code, text, c.contentType ?: "")
    }
    val index = get(ArtifactServer.url("lab/index.html"))
    result("srv_index_ok", index.first == 200 && index.second.contains("阅读实验室"),
        "${index.first}:${index.second.take(80)}")
    result("srv_html_mime", index.first == 200 && index.third.startsWith("text/html"), index.third)
    val css = get(ArtifactServer.url("lab/style.css"))
    result("srv_css_mime", css.first == 200 && css.third.startsWith("text/css"), css.third)
    val missing = get(ArtifactServer.url("lab/nope.html"))
    result("srv_404", missing.first == 404, missing.first.toString())
    // 目录逃逸：编码后的 ../ 也必须挡在 canonical 前缀校验这一关
    val escape = get(ArtifactServer.url("lab/index.html").replace("lab/index.html", "..%2F..%2F..%2Fsecret.txt"))
    result("srv_escape_blocked", escape.first != 200, "${escape.first}:${escape.second.take(60)}")
    ArtifactServer.stop()

    // ---- 端到端：模型调 html_preview → 全屏渲染回调 + 产物卡都要触发
    val toolbox = PhoneToolbox(deviceProvider = { null }, workbench = bench)
    val loop = AgentLoop(CloudClient("$base/HP", "glm-5.3", FakeTokens("t0", null)), toolbox, 6)
    val opened = ArrayList<String>()
    val cards = ArrayList<String>()
    var answer = ""
    var error = ""
    loop.run("给一个网页", object : AgentLoop.Listener {
        override fun onText(delta: String) {}
        override fun onToolStart(name: String, args: JSONObject) {}
        override fun onToolEnd(name: String, args: org.json.JSONObject, result: String, ok: Boolean) {}
        override fun onTurnEnd(turn: Int) {}
        override fun onFinished(finalText: String, turns: Int, toolCalls: Int) { answer = finalText }
        override fun onError(message: String) { error = message }
        override fun onOpenHtml(path: String) { opened.add(path) }
        override fun onArtifact(path: String) { cards.add(path) }
    })
    result("html_open_fired", opened == listOf("lab/index.html"), opened.joinToString(",") + error)
    result("html_card_fired", cards.contains("lab/index.html"), cards.joinToString(",") + error)
    result("html_final_text", answer.contains("渲染好"), answer.take(100))

    dir.deleteRecursively()
}

/**
 * text_shot + docx image 块：把「命令输出 → 终端风 PNG → 插进 Word」整条链路跑一遍。
 *
 * 像素层在 Android（TextShot），这里注入假引擎（真 PNG 字节，ImageIO 造），
 * 走的是真 Workbench 分发、真 DocxWriter 装配——错在逻辑层的断言一个都躲不掉。
 */
private fun scenarioTextShot() {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-shot-" + System.nanoTime())
    dir.mkdirs()

    // ---- ShotText 纯逻辑：宽度、折行、清理
    result("shot_cjk_width", ShotText.strWidth("中a") == 3 && ShotText.charWidth('中') == 2, "")
    result("shot_ascii_width", ShotText.strWidth("ab") == 2, "")
    val wrapped = ShotText.wrap("abcdefghij", 4)
    result("shot_wrap_hard_break", wrapped.size == 3 && wrapped.all { ShotText.strWidth(it) <= 4 },
        wrapped.joinToString("|"))
    val cjk = ShotText.wrap("中文中文中文中文", 5)
    result("shot_wrap_cjk_not_split", cjk.first() == "中文" && cjk.all { ShotText.strWidth(it) <= 5 },
        cjk.joinToString("|"))
    result("shot_sanitize_tab", ShotText.sanitize("a\tb") == "a    b" && ShotText.sanitize("a\u0001b") == "a b",
        "")
    result("shot_pick_cols_cap", ShotText.pickCols("x".repeat(200)) == ShotText.MAX_COLS, "")

    // ---- 无引擎：报错 + schema 摘除（和 pdf_write 同一口径）
    val bare = Workbench(dir)
    val noEngine = JSONObject(bare.execute("text_shot", JSONObject().put("text", "hi")))
    result("shot_no_engine_rejected", !noEngine.optBoolean("ok") && noEngine.optString("code") == "NO_ENGINE",
        noEngine.toString())
    result("shot_hidden_without_engine",
        (0 until bare.schemas().length()).none { bare.schemas().getJSONObject(it).getJSONObject("function").optString("name") == "text_shot" },
        "")

    // ---- 有引擎（假引擎：真 PNG 字节）+ docx image 全链路
    val bench = Workbench(dir, shotEngine = object : Workbench.ShotEngine {
        override fun write(out: File, text: String, title: String, cols: Int): String {
            val img = java.awt.image.BufferedImage(64, 40, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val bos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", bos)
            out.parentFile?.mkdirs()
            out.writeBytes(bos.toByteArray())
            return JSONObject().put("ok", true).put("out", out.name)
                .put("lines", text.split('\n').size).toString()
        }
    })
    val benchNames = (0 until bench.schemas().length())
        .map { bench.schemas().getJSONObject(it).getJSONObject("function").optString("name") }
    result("shot_in_schema_with_engine", "text_shot" in benchNames, "")

    val shot = JSONObject(bench.execute("text_shot", JSONObject()
        .put("text", "epoch 10 acc=0.93\n中文输出也折得齐")
        .put("title", "python train.py 的输出")
        .put("out", "run.png")))
    result("shot_ok", shot.optBoolean("ok") && File(dir, "run.png").isFile, shot.toString())
    result("shot_path_relative", shot.optString("path") == "run.png", shot.toString())

    // 直接调 DocxWriter 的尺寸解析（纯函数）：PNG 头 / 认不出
    val pngBytes = File(dir, "run.png").readBytes()
    result("shot_png_size_read", pngBytes[0] == 0x89.toByte() && pngBytes.size > 24, "")

    // docx image 块：插刚才的截图，带图注
    val blocks = org.json.JSONArray()
        .put(JSONObject().put("type", "p").put("text", "模型训练过程如下"))
        .put(JSONObject().put("type", "image").put("path", "run.png")
            .put("width", "80").put("text", "图 1 训练输出"))
    val doc = JSONObject(bench.execute("docx_write", JSONObject()
        .put("path", "作业.docx").put("title", "股票涨跌预测").put("blocks", blocks)))
    result("shot_docx_image_ok", doc.optBoolean("ok"), doc.toString())

    val f = File(dir, "作业.docx")
    val zip = java.util.zip.ZipFile(f)
    val media = zip.entries().asSequence().map { it.name }.toList()
    result("shot_docx_has_media", media.contains("word/media/image1.png"), media.joinToString(","))
    val docXml = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes().decodeToString()
    val relsXml = zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).readBytes().decodeToString()
    val ctXml = zip.getInputStream(zip.getEntry("[Content_Types].xml")).readBytes().decodeToString()
    zip.close()
    result("shot_docx_drawing", docXml.contains("<wp:extent") && docXml.contains("r:embed=\"rIdImg1\""), "")
    result("shot_docx_caption", docXml.contains("图 1 训练输出") && docXml.contains("w:jc w:val=\"center\""), "")
    result("shot_docx_rels", relsXml.contains("Id=\"rIdImg1\"") && relsXml.contains("Target=\"media/image1.png\""), "")
    result("shot_docx_ct", ctXml.contains("Extension=\"png\""), "")

    // 错误分支：缺图 / 越界 / 认不出格式
    val missing = JSONObject(bench.execute("docx_write", JSONObject().put("path", "a.docx")
        .put("blocks", JSONArray().put(JSONObject().put("type", "image").put("path", "nope.png")))))
    result("shot_docx_img_missing", !missing.optBoolean("ok") && missing.optString("code") == "IMG_MISSING",
        missing.toString())
    val outside = JSONObject(bench.execute("docx_write", JSONObject().put("path", "b.docx")
        .put("blocks", JSONArray().put(JSONObject().put("type", "image").put("path", "../x.png")))))
    result("shot_docx_img_outside", !outside.optBoolean("ok") && outside.optString("code") == "IMG_OUTSIDE",
        outside.toString())
    bench.execute("fs_write", JSONObject().put("path", "fake.png").put("content", "这不是图片"))
    val badFmt = JSONObject(bench.execute("docx_write", JSONObject().put("path", "c.docx")
        .put("blocks", JSONArray().put(JSONObject().put("type", "image").put("path", "fake.png")))))
    result("shot_docx_img_format", !badFmt.optBoolean("ok") && badFmt.optString("code") == "IMG_FORMAT",
        badFmt.toString())

    // text_shot 的 path 来源与上限
    bench.execute("fs_write", JSONObject().put("path", "log.txt").put("content", "step 1 ok\nstep 2 ok"))
    val fromFile = JSONObject(bench.execute("text_shot", JSONObject()
        .put("path", "log.txt").put("out", "log.png")))
    result("shot_from_file", fromFile.optBoolean("ok") && File(dir, "log.png").isFile, fromFile.toString())
    val tooLong = JSONObject(bench.execute("text_shot", JSONObject()
        .put("text", "x".repeat(Workbench.MAX_SHOT_CHARS + 1))))
    result("shot_too_long", !tooLong.optBoolean("ok") && tooLong.optString("code") == "TOO_LONG", "")

    dir.deleteRecursively()
}

/**
 * xlsx_write + chart_write：表格与图表这两块热路径的闭环。
 *
 * xlsx 是纯 JVM 真产物：写出真 .xlsx 再解包断言 XML，并用 OfficeText 读回来——
 * 写和读走同一个人写的解析器容易「错得对称」，所以读回的断言特意换角度
 * （数字格、inlineStr、CJK 列宽）验证。
 * chart 的布局是纯 JVM（坐标可精确断言），像素层注入假引擎只记计划。
 */
/** 解包一个 zip 条目的文本（xlsx/docx/pptx 断言共用）。 */
private fun zipText(f: File, entry: String): String =
    java.util.zip.ZipFile(f).use { z ->
        z.getInputStream(z.getEntry(entry)).readBytes().toString(Charsets.UTF_8)
    }

/** 列出 zip 条目名。 */
private fun zipNames(f: File): List<String> =
    java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toList() }

private fun scenarioXlsxChart() {
    val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "wb-xlsx-" + System.nanoTime())
    dir.mkdirs()
    val bench = Workbench(dir)

    // ═══════════ xlsx：真写出、真解包、真读回 ═══════════

    val r = JSONObject(bench.execute("xlsx_write", JSONObject()
        .put("path", "成绩单.xlsx")
        .put("sheets", JSONArray()
            .put(JSONObject().put("name", "一班").put("rows", JSONArray()
                .put(JSONArray().put("姓名").put("分数").put("及格"))
                .put(JSONArray().put("罗唯珍").put(92.5).put(true))
                .put(JSONArray().put("李四").put("88").put(false))
            ))
            .put(JSONObject().put("name", "统计").put("rows", JSONArray()
                .put(JSONArray().put("平均分").put(90.25))
            ))
        )))
    result("xlsx_ok", r.optBoolean("ok") && r.optString("path") == "成绩单.xlsx", r.toString().take(160))
    result("xlsx_counts", r.optInt("sheets") == 2 && r.optInt("rows") == 4 && r.optInt("cells") == 11,
        r.toString().take(120))

    val xf = File(dir, "成绩单.xlsx")
    result("xlsx_exists", xf.isFile && xf.length() > 600, if (xf.isFile) xf.length().toString() else "missing")
    val parts = zipNames(xf)
    result("xlsx_zip_complete", parts.containsAll(listOf(
        "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
        "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml",
        "xl/worksheets/sheet2.xml", "xl/styles.xml")), parts.joinToString(","))

    val sheet1 = zipText(xf, "xl/worksheets/sheet1.xml")
    result("xlsx_inline_str", sheet1.contains("t=\"inlineStr\"") && sheet1.contains("罗唯珍"), sheet1.take(120))
    // 模型把数字写成字符串 "88" 也要落成数值格（用户才能求和）
    result("xlsx_number_cell", sheet1.contains("<v>88.0</v>") || sheet1.contains("<v>88</v>"), sheet1.take(160))
    result("xlsx_number_cell2", sheet1.contains("<v>92.5</v>"), "")
    result("xlsx_bool_cell", sheet1.contains("t=\"b\"") && sheet1.contains("<v>1</v>") && sheet1.contains("<v>0</v>"), "")
    result("xlsx_header_bold", sheet1.contains("s=\"1\"") && zipText(xf, "xl/styles.xml").contains("<b/>"), "")
    result("xlsx_freeze_pane", sheet1.contains("state=\"frozen\"") && sheet1.contains("ySplit=\"1\""), "")
    result("xlsx_col_width", sheet1.contains("customWidth=\"1\"") && sheet1.contains("width=\""), "")
    result("xlsx_sheet_ref", zipText(xf, "xl/workbook.xml").contains("name=\"一班\"") &&
        zipText(xf, "xl/workbook.xml.rels".let { "xl/_rels/workbook.xml.rels" }).contains("sheet1.xml"), "")
    result("xlsx_ct_types", zipText(xf, "[Content_Types].xml").contains("spreadsheetml.sheet.main+xml"), "")

    // 错误分支：空 sheets、重名、坏扩展名
    result("xlsx_empty_rejected", bench.execute("xlsx_write", JSONObject()
        .put("path", "a.xlsx").put("sheets", JSONArray())).contains("BAD_ARGS"), "")
    result("xlsx_dup_sheet", bench.execute("xlsx_write", JSONObject()
        .put("path", "b.xlsx")
        .put("sheets", JSONArray()
            .put(JSONObject().put("name", "表").put("rows", JSONArray().put(JSONArray().put(1))))
            .put(JSONObject().put("name", "表").put("rows", JSONArray().put(JSONArray().put(1))))
        )).contains("BAD_NAME"), "")
    result("xlsx_bad_ext", bench.execute("xlsx_write", JSONObject()
        .put("path", "c.xls")
        .put("sheets", JSONArray().put(JSONObject().put("rows", JSONArray().put(JSONArray().put(1))))
        )).contains("BAD_EXT"), "")

    // 读回：写的人也是读的人，断言换角度——Markdown 表、行数、截断说明
    val md = OfficeText.xlsxToMarkdown(xf)
    result("xlsx_read_back", md != null && md!!.contains("姓名") && md.contains("罗唯珍") &&
        md.contains("92.5") && md.contains("## 一班") && md.contains("## 统计"),
        md?.take(120) ?: "null")
    result("xlsx_read_header_sep", md != null && md!!.contains("| --- |"), "")
    // TRUE/FALSE 是布尔格的读回；数字 88 被存成数值后读回也是 "88.0" 或 "88"——都可接受
    result("xlsx_read_bool", md != null && md!!.contains("TRUE") && md.contains("FALSE"), "")

    // 附件通道：FileText 路由到 xlsx 并带出表格
    val ex = FileText.extract(xf, "成绩单.xlsx")
    result("xlsx_filetext_route", ex.kind == "xlsx" && ex.text.contains("平均分"), ex.text.take(100))

    // ═══════════ chart：布局纯 JVM，坐标精确断言 ═══════════

    // nice 刻度：整除、2.5 档、以及 max 落在步长中间的关键回归
    result("chart_ticks_even", ChartText.niceTicks(0.0, 100.0, 5) == listOf(0.0, 25.0, 50.0, 75.0, 100.0),
        ChartText.niceTicks(0.0, 100.0, 5).toString())
    val ticks36 = ChartText.niceTicks(0.0, 36.0, 5)
    result("chart_ticks_cover_max", ticks36.last() >= 36.0 && ticks36.last() % 10.0 == 0.0, ticks36.toString())
    result("chart_fmt_int", ChartText.fmt(92.0) == "92" && ChartText.fmt(92.5) == "92.5", "")

    val bar = ChartText.plan("bar", "期中成绩", listOf("语文", "数学", "英语"),
        listOf(ChartText.Series("一班", listOf(92.0, 88.0, 95.0)),
            ChartText.Series("二班", listOf(85.0, 90.0, 93.0))), null)
    val barItems = bar.getJSONArray("items")
    val rects = (0 until barItems.length()).count { barItems.getJSONObject(it).optString("op") == "rect" }
    result("chart_bar_rects", rects == 6 + 2, "rects=$rects")   // 3 类目×2 系列 + 2 图例色块
    result("chart_bar_two_series_colors", barItems.toString().contains("#378ADD") &&
        barItems.toString().contains("#E24B4A"), "")
    // 刻度必须罩住最大值（92×1.08=99.36 → 顶刻度 ≥100）
    val texts = (0 until barItems.length()).map { barItems.getJSONObject(it) }
        .filter { it.optString("op") == "text" && it.optString("color") == "#888780" }
        .mapNotNull { it.optString("s").toDoubleOrNull() }
    result("chart_bar_tick_covers_max", texts.isNotEmpty() && texts.max() >= 99.36, texts.toString())
    result("chart_bar_y_labels", texts.contains(0.0), "")

    val line = ChartText.plan("line", "体温", listOf("周一", "周二", "周三"),
        listOf(ChartText.Series("体温", listOf(36.5, 37.8, 36.9))), null)
    val lineItems = line.getJSONArray("items")
    result("chart_line_poly", (0 until lineItems.length()).any { it.let { i -> lineItems.getJSONObject(i).optString("op") == "poly" } }, "")
    result("chart_line_dots", (0 until lineItems.length()).count { lineItems.getJSONObject(it).optString("op") == "circle" } == 3, "")

    val pie = ChartText.plan("pie", "支出", listOf("房租", "吃饭", "其他"), emptyList(),
        listOf(1800.0, 1200.0, 600.0))
    val pieItems = pie.getJSONArray("items")
    val sweeps = (0 until pieItems.length()).map { pieItems.getJSONObject(it) }
        .filter { it.optString("op") == "arc" }.map { it.optDouble("sweep") }
    result("chart_pie_arcs", sweeps.size == 3 && abs(sweeps.sum() - 360.0) < 0.01, sweeps.toString())
    result("chart_pie_pct", pieItems.toString().contains("50.0%") && pieItems.toString().contains("33.3%"),
        pieItems.toString().take(200))

    // 数据错误要有可读的人话，别让模型对堆栈猜
    val bad1 = runCatching { ChartText.plan("bar", "", listOf("甲", "乙"),
        listOf(ChartText.Series("s", listOf(1.0))), null) }
    result("chart_len_mismatch", bad1.exceptionOrNull()?.message?.contains("数量要一致") == true,
        bad1.exceptionOrNull()?.message ?: "none")
    val bad2 = runCatching { ChartText.plan("pie", "", listOf("甲", "乙"), emptyList(),
        listOf(0.0, 0.0)) }
    result("chart_pie_zero", bad2.exceptionOrNull()?.message?.contains("0") == true,
        bad2.exceptionOrNull()?.message ?: "none")

    // Workbench 分发：假引擎记计划、真写 PNG；无引擎摘除；BAD_DATA 人话透传
    val bare = Workbench(dir)
    result("chart_no_engine", bare.execute("chart_write", JSONObject()
        .put("type", "bar").put("labels", JSONArray().put("a"))).contains("NO_ENGINE"), "")
    result("chart_hidden_without_engine",
        (0 until bare.schemas().length()).none {
            bare.schemas().getJSONObject(it).getJSONObject("function").optString("name") == "chart_write" }, "")

    val plans = ArrayList<String>()
    val cbench = Workbench(dir, chartEngine = object : Workbench.ChartEngine {
        override fun write(out: File, plan: String, width: Int, height: Int): String {
            plans.add(plan)
            out.parentFile?.mkdirs()
            val img = java.awt.image.BufferedImage(width / 8, height / 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val bos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", bos)
            out.writeBytes(bos.toByteArray())
            return JSONObject().put("ok", true).put("out", out.name).put("width", width).put("height", height).toString()
        }
    })
    val c1 = JSONObject(cbench.execute("chart_write", JSONObject()
        .put("type", "bar").put("title", "销量").put("out", "销量.png")
        .put("labels", JSONArray().put("一月").put("二月"))
        .put("series", JSONArray().put(JSONObject().put("name", "华东")
            .put("values", JSONArray().put(12).put(34))))))
    result("chart_dispatch_ok", c1.optBoolean("ok") && c1.optString("path") == "销量.png" &&
        File(dir, "销量.png").isFile, c1.toString().take(140))
    result("chart_plan_passed", plans.size == 1 && plans[0].contains("\"op\":\"rect\""), "")
    result("chart_bad_data_human", cbench.execute("chart_write", JSONObject()
        .put("type", "bar")
        .put("labels", JSONArray().put("甲").put("乙"))
        .put("series", JSONArray().put(JSONObject().put("values", JSONArray().put(1))))
    ).contains("数量要一致"), "")
    result("chart_bad_type", cbench.execute("chart_write", JSONObject()
        .put("type", "scatter").put("labels", JSONArray().put("a"))).contains("BAD_TYPE"), "")
    val cb = JSONObject(cbench.execute("chart_write", JSONObject()
        .put("type", "pie").put("labels", JSONArray().put("甲").put("乙"))
        .put("values", JSONArray().put(1).put(3))))
    result("chart_pie_via_tool", cb.optBoolean("ok"), cb.toString().take(100))

    // 两个新工具必须在 schema 里（有引擎的 bench）；xlsx 永远在
    val names = (0 until cbench.schemas().length())
        .map { cbench.schemas().getJSONObject(it).getJSONObject("function").optString("name") }
    result("xlsx_in_schema", "xlsx_write" in names, "")
    result("chart_in_schema_with_engine", "chart_write" in names, "")

    dir.deleteRecursively()
}

/**
 * python_run：参数校验 + 引擎分发 + 开关摘除。引擎是假的（记录调用、返回固定 JSON），
 * 真正的 proot 执行只有真机能测——但模型会踩的每条错误路径都在这层。
 */
private fun scenarioPython() {
    val dir = java.nio.file.Files.createTempDirectory("harness-py").toFile()
    dir.resolve("job.py").writeText("print('hi')")

    val calls = ArrayList<String>()
    val progress = ArrayList<String>()
    val fake = object : Workbench.PyEngine {
        override fun status(): String = "ready"
        override fun prepare(): String = "ready"
        override fun run(
            script: java.io.File,
            args: List<String>,
            timeoutSec: Int,
            onProgress: (String) -> Unit,
        ): String {
            calls.add(script.name + "|" + args.joinToString(",") + "|" + timeoutSec)
            onProgress("python 运行中 · 已 5s（上限 ${timeoutSec}s）")
            return """{"ok":true,"exit":0,"output":"done"}"""
        }
    }

    // 1) 开关关着（默认）：schema 里没有它。Workbench 层 17 个工具定义，
    //    没引擎时摘掉 pdf_write/text_shot/chart_write，py 关着再摘 python_run = 13
    val off = Workbench(dir)
    val offNames = (0 until off.schemas().length())
        .map { off.schemas().getJSONObject(it).getJSONObject("function").optString("name") }
    result("py_schema_without", "python_run" !in offNames && offNames.size == 13,
        "size=${offNames.size}")
    result("py_off_message", off.execute("python_run", JSONObject().put("script", "job.py"))
        .contains("PYTHON_OFF"), "")

    // 2) 开关开着 + 引擎注入：14 个且含 python_run；正常调用透传 args/timeout
    val on = Workbench(dir, pyEngine = fake, pyEnabled = { true })
    val onNames = (0 until on.schemas().length())
        .map { on.schemas().getJSONObject(it).getJSONObject("function").optString("name") }
    result("py_schema_with", "python_run" in onNames && onNames.size == 14, "size=${onNames.size}")

    val okRun = JSONObject(on.execute("python_run", JSONObject()
        .put("script", "job.py")
        .put("args", JSONArray().put("-u").put("--fast"))
        .put("timeout_sec", 300), progress::add))
    result("py_run_ok", okRun.optBoolean("ok") && okRun.optString("status") == "ready",
        okRun.toString().take(120))
    result("py_run_args_passed", calls.singleOrNull()?.split("|") == listOf("job.py", "-u,--fast", "300"),
        calls.joinToString(";"))
    // 进度回调一路透传到引擎：长任务（apk 装包、import scipy）期间界面不再静止
    result("py_run_progress_passed", progress.singleOrNull() == "python 运行中 · 已 5s（上限 300s）",
        progress.joinToString(";"))

    // 3) 错误路径
    result("py_no_script", on.execute("python_run", JSONObject()).contains("BAD_ARGS"), "")
    result("py_bad_ext", on.execute("python_run", JSONObject().put("script", "job.txt"))
        .contains("BAD_EXT"), "")
    result("py_missing", on.execute("python_run", JSONObject().put("script", "nope.py"))
        .contains("NOT_FOUND"), "")

    // 4) 声明与分发一致：14 个 schema 每一个都有对应实现
    val declared = (0 until on.schemas().length())
        .map { on.schemas().getJSONObject(it).getJSONObject("function").optString("name") }
    val unroutable = declared.filter { n ->
        on.execute(n, JSONObject()).contains("UNKNOWN_TOOL")
    }
    result("py_schema_all_routable", declared.size == 14 && unroutable.isEmpty(),
        unroutable.joinToString(","))

    dir.deleteRecursively()
}

/**
 * TarGz：零依赖解包器。rootfs 就靠它落盘，解析错了 Python 环境就永远装不上，
 * 所以这里用手工构造的 tar 逐条测：普通文件、目录、符号链接、PAX 长名、硬链接、
 * 越界路径拒绝、断流报错。
 */
private fun scenarioTarGz() {
    // ---- 手工造 tar：512 头 + 数据 + 结尾零块，gzip 一层
    fun header(name: String, size: Long, type: Char, link: String = ""): ByteArray {
        val h = ByteArray(512)
        name.toByteArray(Charsets.ISO_8859_1).copyInto(h)
        "0000644\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(h, 100)
        "0000000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(h, 108)
        "0000000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(h, 116)
        "%011o\u0000".format(size).toByteArray(Charsets.ISO_8859_1).copyInto(h, 124)
        "%011o\u0000".format(1731900000L).toByteArray(Charsets.ISO_8859_1).copyInto(h, 136)
        for (i in 148..155) h[i] = ' '.code.toByte()   // 校验和字段先按空格算
        h[156] = type.code.toByte()
        link.toByteArray(Charsets.ISO_8859_1).copyInto(h, 157)
        "ustar\u000000".toByteArray(Charsets.ISO_8859_1).copyInto(h, 257)
        var sum = 0L
        for (b in h) sum += b.toInt() and 0xFF
        "%06o\u0000 ".format(sum).toByteArray(Charsets.ISO_8859_1).copyInto(h, 148)
        return h
    }
    fun entry(name: String, type: Char, data: ByteArray, link: String = ""): ByteArray {
        val pad = ByteArray(((512 - data.size % 512) % 512).toInt())
        return header(name, data.size.toLong(), type, link) + data + pad
    }
    fun tarOf(vararg entries: ByteArray): ByteArray {
        val raw = ByteArray(entries.sumOf { it.size } + 1024)
        var off = 0
        for (e in entries) { e.copyInto(raw, off); off += e.size }
        return raw   // 尾部 1024 字节零块 = 结束符
    }

    // ---- 1) 基本：文件 + 目录 + 符号链接 + "./" 前缀
    val basic = tarOf(
        entry("./lib", '5', ByteArray(0)),
        entry("./lib/so.conf", '0', "server 1.2".toByteArray()),
        entry("./bin", '5', ByteArray(0)),
        entry("./bin/py", '2', ByteArray(0), link = "/lib/python"),
    )
    val d1 = java.nio.file.Files.createTempDirectory("harness-tar1").toFile()
    val n1 = TarGz.extract(java.io.ByteArrayInputStream(gzip(basic)), d1)
    result("targz_count", n1 == 4, "n=$n1")
    result("targz_file_content", d1.resolve("lib/so.conf").readText() == "server 1.2", "")
    result("targz_dir_made", d1.resolve("lib").isDirectory && d1.resolve("bin").isDirectory, "")
    // symlink：支持链接的环境断言目标；不支持的（桌面无特权）接受空文件兜底
    val py = d1.resolve("bin/py")
    val linkOk = try {
        java.nio.file.Files.readSymbolicLink(py.toPath()).toString() == "/lib/python"
    } catch (e: Exception) {
        py.exists() && py.length() == 0L
    }
    result("targz_symlink", linkOk, "")

    // ---- 2) PAX 长名（>100 字符的路径走 'x' 扩展头）
    val longName = "deep/" + "p".repeat(120) + "/final.txt"
    // PAX 记录格式："{len} path=值\n"，len 是整条记录的字节数（含 len 自身的位数），
    // 所以位数参与计算，得迭代到收敛
    var digits = 1
    var total = 0
    while (true) {
        val t = digits + 1 + "path=".length + longName.length + 1
        if ("$t".length == digits) { total = t; break }
        digits++
    }
    val record = "$total path=$longName\n".toByteArray()
    val pax = tarOf(
        entry("", 'x', record),
        entry("placeholder", '0', "overwritten".toByteArray()),
    )
    val d2 = java.nio.file.Files.createTempDirectory("harness-tar2").toFile()
    val n2 = TarGz.extract(java.io.ByteArrayInputStream(gzip(pax)), d2)
    result("targz_pax_longname",
        d2.resolve(longName).isFile && d2.resolve(longName).readText() == "overwritten" &&
            !d2.resolve("placeholder").exists(), "n=$n2")

    // ---- 3) 硬链接：真身在前，后者复制内容
    val hard = tarOf(
        entry("real.txt", '0', "twin".toByteArray()),
        entry("copy.txt", '1', ByteArray(0), link = "real.txt"),
    )
    val d3 = java.nio.file.Files.createTempDirectory("harness-tar3").toFile()
    TarGz.extract(java.io.ByteArrayInputStream(gzip(hard)), d3)
    result("targz_hardlink", d3.resolve("copy.txt").readText() == "twin", "")

    // ---- 4) 越界路径拒绝、断流报错
    val evil = tarOf(entry("../evil.txt", '0', "x".toByteArray()))
    val escaped = try {
        val d4 = java.nio.file.Files.createTempDirectory("harness-tar4").toFile()
        TarGz.extract(java.io.ByteArrayInputStream(gzip(evil)), d4); false
    } catch (e: TarGz.Bad) { true }
    result("targz_reject_dotdot", escaped, "")

    val truncated = basic.copyOfRange(0, basic.size - 700)  // 掐掉结尾零块之外还掐进数据
    val broke = try {
        val d5 = java.nio.file.Files.createTempDirectory("harness-tar5").toFile()
        TarGz.extract(java.io.ByteArrayInputStream(gzip(truncated)), d5); false
    } catch (e: TarGz.Bad) { true }
    result("targz_truncated_error", broke, "")

    // ---- 5) sanitize 直接单测
    result("targz_sanitize_strip", TarGz.sanitize("./a//b/") == "a/b", "")
    result("targz_sanitize_reject", runCatching { TarGz.sanitize("a/../b") }.isFailure, "")
}

/** gzip 一段字节（测试助手）。 */
private fun gzip(raw: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
    return out.toByteArray()
}

/**
 * 同一回合多工具并行：模型经常「派子代理 + 自己搜」一起发（嘴上说同时，引擎以前是
 * 串行的——task 独占九十秒，搜索干等）。两个各睡 400ms 的假工具并行跑，
 * 总耗时必须明显小于串行的 800ms；结果仍按原调用顺序入账。
 */
private fun scenarioParallel(base: String) {
    val toolbox = object : Toolbox {
        override fun schemas() = JSONArray()
        override fun beginTask(owner: String) {}
        override fun execute(
            name: String, args: JSONObject, owner: String,
            onProgress: (String) -> Unit, isCancelled: () -> Boolean,
        ): ToolResult {
            Thread.sleep(400)
            return ToolResult(text = "{\"ok\":true,\"tool\":\"$name\"}")
        }
    }
    val loop = AgentLoop(CloudClient("$base/PA", "hy3", FakeTokens("t0", null)), toolbox, 4)
    val rec = Recorder()
    val t0 = System.currentTimeMillis()
    loop.run("两个活一起干", rec)
    val elapsed = System.currentTimeMillis() - t0

    result("par_no_error", rec.error.isEmpty(), rec.error)
    result("par_both_ran", rec.tools == listOf("web_search", "web_fetch") && rec.results.size == 2,
        rec.tools.joinToString(","))
    result("par_in_order", rec.tools == listOf("web_search", "web_fetch"), rec.tools.joinToString(","))
    result("par_concurrent", elapsed < 800, "${elapsed}ms（串行要 800ms+）")
}

fun main(args: Array<String>) {
    val base = args.firstOrNull() ?: "http://127.0.0.1:8799"
    // 第二个参数是可用的 shell 路径，由 test_agent.py 探测后传入
    val shPath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    try {
        scenarioLoop(base)
        scenarioEmptyReply(base)
        scenarioStaleIndex()
        scenarioPhoneLease()
        scenarioIntent()
        scenarioSubAgent(base)
        scenarioHtmlPreview(base)
        scenarioTextShot()
        scenarioXlsxChart()
        scenarioPython()
        scenarioTarGz()
        scenarioPhoneBusy(base)
        scenarioTranscriptRender()
        scenarioErrors()
        scenarioOutlineWindows()
        scenarioRefresh(base)
        scenarioStream(base)
        scenarioNoTools(base)
        scenarioResilience(base)
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
        scenarioThinkLevel(base)
        scenarioThinkPersist(base)
        scenarioWorkbench(base)
        scenarioWebSearch()
        scenarioHttpRedirect()
        scenarioLiveSearch()
        scenarioOfficeText()
        scenarioMemory()
        scenarioSkill()
        scenarioSkillInject(base)
        scenarioExtras(base)
        scenarioTone(base)
        scenarioMarked()
        scenarioVoice()
        scenarioIat()
        scenarioFileText()
        scenarioDocxStyles()
        scenarioAttach()
        scenarioShell(shPath)
        scenarioCompact(base)
        scenarioCompactKeepsAsks(base)
        scenarioCompactImageGoal(base)
        scenarioCompactNoBase64Inflation(base)
        scenarioImageNote(base)
        scenarioCompactNesting(base)
        scenarioSummaryGoal()
        scenarioArtifact(base)
        scenarioSessionStore()
        scenarioGatewayPlan()
        scenarioGatewayWire()
        scenarioGatewaySocket()
        scenarioParallel(base)
    } catch (e: Exception) {
        result("harness_crash", false, e.message ?: e.javaClass.simpleName)
    }
    println("DONE")
}
