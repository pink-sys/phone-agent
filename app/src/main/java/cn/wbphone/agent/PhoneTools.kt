package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个工具调用的结果。
 *
 * `imageDataUrl` 非空表示「这次调用带了一张图」。它不能塞进 tool 消息——
 * OpenAI 协议里 role:"tool" 的 content 是纯文本，把 data URL 放进去模型看不见，
 * 只是白白吃掉几万字符的额度。正确做法见 AgentLoop：tool 消息之后紧跟一条
 * user 消息，content 是 [{text},{image_url}] 数组，图片才真正进入模型的视觉通道。
 */
data class ToolResult(
    val text: String,
    val imageDataUrl: String? = null,
    /**
     * 这个工具自己烧掉的 token（目前只有 task 会填）。
     *
     * 为什么必须报回 loop：子代理是用真实模型跑的，账单是真的。不把它算进本轮用量，
     * 界面上就会出现「明明聊了十分钟，积分显示只花了一点点」——用户没法判断这个功能贵不贵。
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/** 最近一次截图的坐标系：图片像素 ↔ 真实屏幕像素。 */
data class ShotFrame(
    val shotWidth: Int,
    val shotHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    /** 这张图对应的屏幕区域左上角（整屏截图时为 0,0）。 */
    val originX: Int = 0,
    val originY: Int = 0,
    /** 这张图覆盖的屏幕尺寸（整屏截图时等于屏幕尺寸，局部放大时是截取的那一块）。 */
    val regionWidth: Int = screenWidth,
    val regionHeight: Int = screenHeight,
) {
    fun toScreenX(x: Int): Int =
        (originX + x.toDouble() * regionWidth / shotWidth).toInt().coerceIn(0, screenWidth - 1)

    fun toScreenY(y: Int): Int =
        (originY + y.toDouble() * regionHeight / shotHeight).toInt().coerceIn(0, screenHeight - 1)

    /** 局部放大截的图：坐标要先换算回屏幕，再按整屏比例判边界。 */
    val isCrop: Boolean get() = originX != 0 || originY != 0 || regionWidth != screenWidth
}

/**
 * 工具定义与执行器。
 *
 * 和之前走桥的版本相比，这里少了一层"桥渲染大纲、桥算坐标"——
 * 手机自己既是手也是脑，读屏结果直接进模型上下文，点按序号直接换成坐标。
 *
 * 两条定位路径：
 *  - **序号**：读屏 → 模型报序号 → 手机查 bounds 算中心点。便宜、稳，但无障碍树看不见自绘界面。
 *  - **坐标**：截图 → 模型报像素坐标 → 手机按缩放比换算。贵，但微信/支付宝这类界面只有这一条路。
 *
 * 所有改变状态的动作都先过 [ApprovalGate]。闸门判定用的是「动作 + 目标文本 + 真实屏幕」，
 * 不看模型自述——提示词管不住的事，只能靠代码管。
 */
class PhoneToolbox(
    private val deviceProvider: () -> DeviceApi?,
    /** 应用名 → 包名。Android 侧注入 PackageManager 查询，测试里注入固定表。 */
    private val resolvePackage: (String) -> String? = { null },
    /** 审批闸门。null 表示不设防（只在测试里这么用）。 */
    private val approval: ApprovalGate? = null,
    /**
     * 工作台（文件 / 搜索 / 网页）。null 表示只暴露手机工具。
     *
     * 故意做成可选：手机操作这套逻辑不该被文件工具牵连，测试里也能分开验证。
     */
    private val workbench: Workbench? = null,
    /**
     * 派子代理。null 表示不给这个工具（纯 JVM 测试、没有模型的宿主）。
     *
     * 做成 lambda 而不是直接持有 SubAgent：这里不该知道模型怎么建、历史怎么放，
     * 那些是宿主的事。工具层只负责「把目标交出去、把结论带回来、顺手报一下进度」。
     */
    private val delegate: ((goal: String, focus: String, onProgress: (String) -> Unit, isCancelled: () -> Boolean) -> ToolResult)? = null,
) : Toolbox {

    @Volatile private var lastOutline: ScreenOutline.Rendered? = null

    /** 动作前的界面大纲快照，供「点了没生效」对比。只在动作执行前一刻抓。 */
    @Volatile private var beforeActionOutline: String? = null

    @Volatile private var lastFacts: ScreenFacts = ScreenFacts()

    @Volatile private var lastShot: ShotFrame? = null

    /**
     * 本任务已经派出过几个子代理。
     *
     * 为什么要记账：子代理是**真金白银**——每个都是若干轮完整请求。不设上限的话，
     * 一个「帮我看看这三个网站」的任务可能派出十几个，账单在用户完全无感的情况下翻几倍。
     * 用完了也不再摘 schema（中途改工具列表会打碎缓存、还容易让模型以为工具坏了）。
     */
    @Volatile private var subTasks = 0

    override fun schemas(): JSONArray {
        val base = JSONArray(SCHEMA_JSON)
        // 派子代理这件事只有宿主真接了才有——不然模型会去调一个必然失败的 task，
        // 白烧一轮，还会以为是自己参数写错了反复重试。
        if (delegate != null) base.put(TASK_SCHEMA)
        val bench = workbench ?: return base
        // 只有真挂了工作台才把工具暴露给模型。否则模型会去调一个必然失败的工具，
        // 白烧一轮 token，还会以为是自己用错了。
        // shell 是否在里面由 Workbench 自己决定（没开启就不给）。
        val extra = bench.schemas()
        for (i in 0 until extra.length()) base.put(extra.get(i))
        return base
    }

    /** 清掉上一屏的序号表与坐标系，强制模型重新看。 */
    fun invalidate() {
        lastOutline = null
        lastShot = null
    }

    /**
     * 每个任务开始：清序号、清坐标系、清审批授权、清「已加载技能」。
     *
     * 清序号这一条要分情况：现在可能还有别的会话正拿着手机在跑（见 [PhoneLease]），
     * 这时候把 `lastOutline` / `lastShot` 清掉，等于把人家手里的序号和坐标一起废了——
     * 那正是「点到了不该点的东西」的来源。所以只有手机空闲、或者本来就是自己在用时才作废。
     */
    override fun beginTask(owner: String) {
        val holding = PhoneLease.holder()
        if (holding == null || holding == owner) invalidate()
        approval?.beginTask()
        // 技能正文是进这个会话的历史的；跨会话留着「已加载」标记会让新会话拿到空壳
        workbench?.beginTask()
        // 每个任务重新给满子代理额度：上一个任务派出去几个，不该算在这个任务头上
        subTasks = 0
    }

    /** 连续被拒太多次，agent loop 该收手了。 */
    override fun shouldAbort(): Boolean = approval?.shouldAbort == true

    /**
     * 执行一个工具调用。
     * 失败也返回文本（带 ok:false），不要抛异常——抛出会让模型看不到原因。
     */
    override fun execute(
        name: String,
        args: JSONObject,
        owner: String,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): ToolResult {
        return try {
            // 派子代理：不碰屏幕、不占租约，走自己的上下文，只把结论带回来。
            // 放在最前面：这件事跟工作台/闸门都无关，宿主也不一定给了工作台。
            if (name == "task") return subTask(args, onProgress, isCancelled)

            // 工作台工具不碰设备、不过闸门：它们被限制在自己的沙箱目录里，
            // 写坏东西的范围有边界，不需要每次都让人点头。放在闸门之前分发。
            if (name.startsWith("fs_") || name == "web_fetch" || name == "web_search" ||
                name == "pdf_write" ||
                name == "pptx_write" || name == "docx_write" || name == "xlsx_write" ||
                name == "chart_write" || name == "html_preview" ||
                name == "text_shot" || name == "python_run" ||
                name == "memory_save" || name == "skill_load"
            ) {
                val bench = workbench
                    ?: return ToolResult(err("NO_WORKBENCH", "工作台没有启用"))
                // onProgress 目前只有 python_run 用（运行中的秒数上报），透传给所有
                // 工作台工具是安全的——其余工具的签名直接忽略它。
                return ToolResult(bench.execute(name, args, onProgress))
            }

            // 系统意图不碰屏幕：设闹钟、拨号盘、分享这类交给系统应用去办。不读序号、
            // 不占屏幕租约——另一个会话正拿着手机操作时也能用，这是它和无障碍动作的区别。
            if (name == "phone_intent") {
                val d = device() ?: return ToolResult(err("NO_ACCESSIBILITY", "无障碍服务未开启"))
                return ToolResult(attachScreenAfter(name, intent(d, args)))
            }

            // 碰屏幕之前先拿租约。手机只有一块屏幕，两个任务交错操作会让序号表和坐标系
            // 互相作废——每一步单看都合法，合起来却点到了别的地方。拿不到就老实拒绝。
            if (!ensureLease(owner)) {
                return ToolResult(err("PHONE_BUSY", BUSY_TEXT))
            }

            // 参数问题先挡掉，别让用户为一次注定失败的动作去点审批
            if (name == "phone_tap_at" && lastShot == null) {
                return ToolResult(
                    err(
                        "STALE_SCREEN",
                        "还没有可用的截图。坐标点按必须先 phone_screenshot，用那张图上的像素坐标来点"
                    )
                )
            }
            if (name == "phone_long_press" && lastShot == null) {
                return ToolResult(
                    err(
                        "STALE_SCREEN",
                        "还没有可用的截图。长按必须先 phone_screenshot，用那张图上的像素坐标来按"
                    )
                )
            }

            // 闸门在最前面：先把「要不要问人」解决掉，再谈怎么执行。
            // 判定前先抓一次当前屏幕——用上一屏的事实做判断等于用过期地图过雷区。
            val guarded = approval != null && name in RISKY_ACTIONS
            val facts = if (guarded) refreshFacts() else lastFacts
            val target = when (name) {
                "phone_tap" -> tapTarget(args.optInt("index", -1))
                "phone_tap_at" -> "坐标点按"
                "phone_long_press" -> "坐标长按"
                else -> ""
            }
            val blocked = approval?.guard(
                action = name,
                target = target,
                input = args.optString("text"),
                facts = facts,
                coordinate = name == "phone_tap_at" || name == "phone_long_press",
            )
            if (blocked != null) return ToolResult(blocked)

            if (name == "phone_screenshot") {
                return screenshot(
                    args.optInt("x", -1), args.optInt("y", -1),
                    args.optInt("w", -1), args.optInt("h", -1),
                )
            }
            // 动作前的界面快照：给 attachScreenAfter 对比「点了到底动没动」用
            if (name in MUTATING_ACTIONS) beforeActionOutline = lastOutline?.text
            val text = when (name) {
                "phone_status" -> status()
                "phone_read_screen" -> readScreen()
                "phone_tap" -> tap(args.optInt("index", -1))
                "phone_tap_at" -> tapAt(args.optInt("x", -1), args.optInt("y", -1))
                "phone_long_press" -> longPress(args.optInt("x", -1), args.optInt("y", -1))
                "phone_input" -> input(args.optString("text"), args.optBoolean("submit", false))
                "phone_press_key" -> pressKey(args.optString("key", "back"))
                "phone_launch_app" -> launchApp(args.optString("target"))
                "phone_swipe" -> swipe(args.optString("direction", "up"))
                else -> err("UNSUPPORTED", "手机端没有 $name 这个工具")
            }
            ToolResult(attachScreenAfter(name, text))
        } catch (e: Exception) {
            ToolResult(err("EXEC_FAILED", e.message ?: "执行异常"))
        }
    }

    /**
     * 不带进度的调用方式。测试、以及所有「不需要报告过程」的工具都走这条，
     * 免得每个调用点都塞一个空 lambda。
     */
    fun execute(name: String, args: JSONObject, owner: String = ""): ToolResult =
        execute(name, args, owner, {}) { false }

    /**
     * 派一个子代理：给它一句能独立看懂的目标，它自己去查、自己烧自己的上下文，
     * 只把结论带回来。
     *
     * 为什么值得：一次 web_search 能抓回三千字，这些字会跟着**每一轮**请求重发。
     * 查资料/大范围审阅这类活，中间过程对主线几乎全是噪声——子代理把它们关在自己的
     * 上下文里，主线只付一份结论的钱。这也是「压缩」做不到的：压缩是事后清理，
     * 噪声已经烧过一遍了。
     */
    private fun subTask(
        args: JSONObject,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): ToolResult {
        val run = delegate ?: return ToolResult(err("NO_DELEGATE", "这个版本没有开启子代理"))
        val goal = args.optString("goal").trim()
        if (goal.length < 4) {
            return ToolResult(
                err("BAD_REQUEST", "goal 要写成一句能独立看懂的话（子代理看不到当前对话）")
            )
        }
        if (subTasks >= MAX_SUB_TASKS) {
            return ToolResult(
                err(
                    "SUB_BUDGET",
                    "这个任务已经派过 $MAX_SUB_TASKS 次子代理了，额度用完（每次都是真花钱的完整请求）。" +
                        "剩下的用现有工具自己做，或者把已有结论整理好交给用户。"
                )
            )
        }
        subTasks += 1
        return run(goal, args.optString("focus").trim(), onProgress, isCancelled)
    }

    /**
     * 确认这个会话现在可以用手机。
     *
     * 第一次碰屏幕时才申请（纯聊天、写文件不需要所有权），拿到之后一直持有到任务结束，
     * 由 [AgentHost] 在收尾时交还。不是「每次工具调用都申请一次」——读屏算序号、再按序号点，
     * 这两步必须发生在同一个所有权窗口里，中间被插一脚那一步就已经错了。
     */
    private fun ensureLease(owner: String): Boolean {
        // 没有会话身份（纯 JVM 测试）：不参与租约，保持老行为
        if (owner.isEmpty()) return true
        // 已经拿着了：同一任务里后续的每一次调用都走这条
        if (PhoneLease.holder() == owner) return true
        if (!PhoneLease.tryAcquire(owner)) return false
        // 刚接手：上一屏的序号和坐标系不属于自己，必须重新看一遍
        invalidate()
        return true
    }

    // ------------------------------------------------------------ 各工具

    private fun status(): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启，手机上无法执行任何操作")
        val size = d.displaySize()
        return "{\"ok\":true,\"device\":\"${escape(d.deviceLabel())}\",\"screen\":{\"width\":${size[0]},\"height\":${size[1]}}," +
            "\"accessibility\":\"enabled\"}"
    }

    private fun readScreen(): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        val payload = d.readScreen()
        if (!payload.optBoolean("ok", false)) {
            return err(payload.optString("code", "READ_FAILED"), payload.optString("error", "读屏失败"))
        }
        lastFacts = ScreenFactsReader.fromPayload(payload)
        val rendered = ScreenOutline.render(payload, withBounds = false)
        lastOutline = rendered
        return rendered.text
    }

    /**
     * 这些动作不附 screen_after：微信的写入/长按是「盲操作」，界面大纲本来就不变，
     * 附上去只会让模型误读成「输入没生效」去重点输入框拿焦点——用户明令不要验证。
     */
    private val NO_VERIFY_ACTIONS = setOf("phone_input", "phone_long_press")

    /**
     * 动作成功后把新屏幕直接附在结果里。
     *
     * 之前的做法是提示词里要求模型「动作后必须重新 read_screen」——它也确实照做了，
     * 结果一个动作要烧两轮：动作一轮、读屏一轮。12 轮上限只够做 6 个动作，
     * 用户看到的就是「超级慢，还没做完就停了」。
     *
     * 读屏这一步引擎本来就要做（刷新 lastOutline / lastFacts），把结果捎带回去是零成本的，
     * 模型在**同一轮**里就能核对并决定下一步。
     */
    private fun attachScreenAfter(name: String, text: String): String {
        if (name !in MUTATING_ACTIONS || name in NO_VERIFY_ACTIONS) return text
        // 按 JSON 解析判成功，不用字符串前缀：org.json 不保证键序，ok 未必排在最前
        val okFlag = runCatching { JSONObject(text).optBoolean("ok", false) }.getOrDefault(false)
        if (!okFlag) return text
        // 界面转场需要一点时间。太短会读到动画中间态，模型据此判断「点了没反应」就开始瞎点。
        Thread.sleep(if (name == "phone_launch_app" || name == "phone_intent") 1200 else SETTLE_MS)
        val after = try { readScreen() } catch (e: Exception) { return text }
        // readScreen 成功时返回的是大纲文本（"1|设置" 这种），失败时才是 {"ok":false,...}。
        // 读屏失败不连坐：动作本身是成功的，别让核对失败把结果变成报错。
        if (after.startsWith("{\"ok\":false") || after.isBlank()) return text

        // 界面零变化检测：动作前后的屏幕大纲一字不差，这个操作多半没生效。
        // WebView（学习通、微信公众号页）里无障碍 ACTION_CLICK 经常「报告成功、实际没反应」，
        // 模型上一版只能瞎猜着换坐标连点二十几轮。把这句话硬塞进结果，它在同一轮就能改道。
        val before = beforeActionOutline
        beforeActionOutline = null
        val unchanged = before != null && before.trim() == after.trim()
        return JSONObject(text).put("screen_after", after).apply {
            if (unchanged) {
                put("screen_changed", false)
                put(
                    "warning",
                    "操作前后界面没有任何变化，这个操作大概率没有生效。不要原样或小改坐标重试：" +
                        "换用 phone_tap 的序号点击、换一个明显不同的位置，" +
                        "或先滑动/返回再看；连续无效就换一条路。"
                )
            }
        }.toString()
    }

    private fun tap(index: Int): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        if (index <= 0) return err("BAD_REQUEST", "index 必须是 read_screen 返回的正整数序号")
        val outline = lastOutline
            ?: return err("STALE_SCREEN", "上一屏的序号已失效，请重新 read_screen 再点")
        val center = outline.center(index)
            ?: return err("NO_SUCH_INDEX", "没有序号 $index 的元素（当前屏共 ${outline.count} 个），请重新读屏")

        val result = d.tap(center[0], center[1])
        // 点完界面就变了，旧序号一律作废
        invalidate()
        return if (result.optBoolean("ok", false)) {
            val label = result.optString("activated")
            val pkg = result.optString("navigatedTo")
            "{\"ok\":true,\"tapped\":\"${escape(label)}\",\"now\":\"${escape(pkg)}\"}"
        } else {
            err(result.optString("code", "TAP_FAILED"), result.optString("error", "点按失败"))
        }
    }

    /**
     * 按截图坐标点按。
     *
     * 这是给自绘界面准备的路：微信、支付宝、游戏、部分 App 的自定义控件在无障碍树里
     * 什么都没有，读屏拿不到序号，但截图看得一清二楚。
     *
     * 换算不能省：截图会被缩到 1080 宽再发给模型，模型报的是缩过的像素坐标，
     * 直接拿去点会偏。缩放比按宽度算——图片是等比缩放的，宽高比例必然一致。
     * 局部放大截图（phone_screenshot 带 x/y/w/h）还要叠加截取区域的偏移，见 [ShotFrame]。
     */
    private fun tapAt(x: Int, y: Int): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        val frame = lastShot
            ?: return err(
                "STALE_SCREEN",
                "还没有可用的截图。坐标点按必须先 phone_screenshot，用那张图上的像素坐标来点"
            )
        if (x < 0 || y < 0 || x >= frame.shotWidth || y >= frame.shotHeight) {
            return err(
                "OUT_OF_BOUNDS",
                "坐标 ($x, $y) 超出截图范围 ${frame.shotWidth}×${frame.shotHeight}。请按图片上的像素位置给坐标，不要按真实屏幕尺寸给"
            )
        }

        val rx = frame.toScreenX(x)
        val ry = frame.toScreenY(y)

        val result = d.tap(rx, ry)
        // 界面已变：序号作废，坐标系也作废。继续用旧图的坐标就是在瞎点。
        invalidate()
        return if (result.optBoolean("ok", false)) {
            val hit = result.optString("activated")
            val pkg = result.optString("navigatedTo")
            "{\"ok\":true,\"from_image\":[$x,$y],\"tapped_at\":[$rx,$ry],\"hit\":\"${escape(hit)}\"," +
                "\"now\":\"${escape(pkg)}\",\"note\":\"界面可能已变化，旧截图作废。要再点请重新 phone_screenshot。\"}"
        } else {
            err(result.optString("code", "TAP_FAILED"), result.optString("error", "点按失败"))
        }
    }

    /**
     * 在 (x,y) 长按唤出「粘贴」菜单。坐标口径与 tapAt 相同：截图上的像素坐标，
     * 服务内换算成真实屏幕坐标。微信这类读不到控件树的应用，输入=写入(phone_input)
     * → 长按(本工具) → 点粘贴(phone_tap_at)，三步各自独立、每步对着截图来。
     */
    private fun longPress(x: Int, y: Int): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        val frame = lastShot
            ?: return err(
                "STALE_SCREEN",
                "还没有可用的截图。长按必须先 phone_screenshot，用那张图上的像素坐标来按"
            )
        if (x < 0 || y < 0 || x >= frame.shotWidth || y >= frame.shotHeight) {
            return err(
                "OUT_OF_BOUNDS",
                "坐标 ($x, $y) 超出截图范围 ${frame.shotWidth}×${frame.shotHeight}。请按图片上的像素位置给坐标，不要按真实屏幕尺寸给"
            )
        }

        val rx = frame.toScreenX(x)
        val ry = frame.toScreenY(y)

        val result = d.longPress(rx, ry)
        invalidate()
        return if (result.optBoolean("ok", false)) {
            val pkg = result.optString("navigatedTo")
            "{\"ok\":true,\"from_image\":[$x,$y],\"pressed_at\":[$rx,$ry],\"now\":\"${escape(pkg)}\"," +
                "\"note\":\"长按完成。菜单出现就用 phone_screenshot 重新截图，再用 phone_tap_at 点「粘贴」；没出现就换个位置再长按一次。\"}"
        } else {
            err(result.optString("code", "LONG_PRESS_FAILED"), result.optString("error", "长按失败"))
        }
    }

    private fun input(text: String, submit: Boolean): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        if (text.isEmpty()) return err("BAD_REQUEST", "text 不能为空")
        invalidate()
        // submit 交给设备层：普通应用在写完字后按回车/IME 发送键，比让模型另调一次
        // phone_press_key 可靠（那时输入法可能已经切回去了）。微信只写剪贴板，不在这里提交。
        val result = d.inputText(text, submit)
        if (!result.optBoolean("ok", false)) {
            return err(result.optString("code", "INPUT_FAILED"), result.optString("error", "输入失败"))
        }
        val note = result.optString("note")
        val notePart = if (note.isNotEmpty()) ",\"note\":\"${escape(note)}\"" else ""
        val submitted = result.optBoolean("submitted", false)
        return "{\"ok\":true,\"typed\":${text.length},\"submit\":$submitted$notePart}"
    }

    private fun pressKey(key: String): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        invalidate()
        val result = d.pressKey(key)
        return if (result.optBoolean("ok", false)) {
            "{\"ok\":true,\"key\":\"${escape(key)}\",\"now\":\"${escape(result.optString("navigatedTo"))}\"}"
        } else {
            err(result.optString("code", "KEY_FAILED"), result.optString("error", "按键失败"))
        }
    }

    private fun launchApp(target: String): String {
        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        if (target.isBlank()) return err("BAD_REQUEST", "target 不能为空，给应用名或包名")
        invalidate()
        val resolved = resolvePackage(target) ?: target
        val result = d.launchApp(resolved)
        return if (result.optBoolean("ok", false)) {
            // note 可能带「启动成了小窗、已自动放大 / 需要手动放大」的关键提示，不能丢
            val note = result.optString("note")
            val notePart = if (note.isNotEmpty()) ",\"note\":\"${escape(note)}\"" else ""
            "{\"ok\":true,\"package\":\"${escape(resolved)}\"$notePart}"
        } else {
            err(result.optString("code", "LAUNCH_FAILED"), result.optString("error", "启动 $target 失败"))
        }
    }

    /**
     * 系统意图：设闹钟、计时器、日历、拨号盘、短信草稿、分享、导航、开链接、跳设置页。
     *
     * 白名单而不是开放「任意 Intent」：模型能拼出的 action 无穷多，个个都起一遍
     * 等于给了它一个未审计的系统调用面。上面这些是日常真正用得上的。
     */
    private fun intent(d: DeviceApi, args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        val known = INTENT_ACTIONS.joinToString("/")
        if (action.isEmpty()) return err("BAD_ARGS", "action 不能为空。可用：$known")
        if (action !in INTENT_ACTIONS) return err("BAD_ACTION", "不支持的 action：$action。可用：$known")

        val result = d.startIntent(action, args)
        if (!result.optBoolean("ok", false)) {
            return err(result.optString("code", "INTENT_FAILED"), result.optString("error", "打不开"))
        }
        // 已经切到系统应用，旧序号和坐标系全部作废
        invalidate()
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("opened", result.optString("opened"))
            .put("now", result.optString("now"))
            .toString()
    }

    private fun swipe(direction: String): String {        val d = device() ?: return err("NO_ACCESSIBILITY", "无障碍服务未开启")
        invalidate()
        val size = d.displaySize()
        val w = size[0]
        val h = size[1]
        val cx = w / 2
        val cy = h / 2
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> intArrayOf(cx, (h * 0.72).toInt(), cx, (h * 0.28).toInt())     // 内容上移 = 看后面的
            "down" -> intArrayOf(cx, (h * 0.28).toInt(), cx, (h * 0.72).toInt())
            "left" -> intArrayOf((w * 0.82).toInt(), cy, (w * 0.18).toInt(), cy)
            "right" -> intArrayOf((w * 0.18).toInt(), cy, (w * 0.82).toInt(), cy)
            else -> return err("BAD_REQUEST", "direction 只能是 up/down/left/right")
        }
        val result = d.swipe(x1, y1, x2, y2, 320)
        return if (result.optBoolean("ok", false)) {
            "{\"ok\":true,\"swiped\":\"${escape(direction)}\"}"
        } else {
            err(result.optString("code", "SWIPE_FAILED"), result.optString("error", "滑动失败"))
        }
    }

    /** 截图。图片不进 tool 文本，由 AgentLoop 另起一条消息承载。 */
    private fun screenshot(x: Int, y: Int, w: Int, h: Int): ToolResult {
        val d = device() ?: return ToolResult(err("NO_ACCESSIBILITY", "无障碍服务未开启"))
        val crop = x >= 0 && y >= 0 && w > 0 && h > 0
        val shot = if (crop) d.screenshot(x, y, w, h) else d.screenshot()
        if (!shot.optBoolean("ok", false)) {
            return ToolResult(
                err(shot.optString("code", "SCREENSHOT_FAILED"), shot.optString("error", "截图失败"))
            )
        }
        val b64 = shot.optString("imageBase64")
        if (b64.isEmpty()) return ToolResult(err("SCREENSHOT_EMPTY", "截图返回了空数据"))

        val mime = shot.optString("mime", "image/jpeg")
        val shotW = shot.optInt("width", 0)
        val shotH = shot.optInt("height", 0)
        val size = d.displaySize()
        // 记下坐标系：模型下一步可以直接报这张图上的像素坐标。
        // 局部放大时还要记住这块图对应屏幕的哪一块，否则点按会整体偏移。
        lastShot = if (shotW > 0 && shotH > 0) {
            ShotFrame(
                shotWidth = shotW,
                shotHeight = shotH,
                screenWidth = size[0],
                screenHeight = size[1],
                originX = shot.optInt("originX", 0),
                originY = shot.optInt("originY", 0),
                regionWidth = shot.optInt("regionWidth", size[0]).takeIf { it > 0 } ?: size[0],
                regionHeight = shot.optInt("regionHeight", size[1]).takeIf { it > 0 } ?: size[1],
            )
        } else {
            null
        }
        // 顺手抓一次无障碍事实：自绘界面里树可能是空的，但包名和残留文本仍是判定风险的重要依据
        refreshFacts()

        val scope = if (crop) "这是局部放大图，覆盖屏幕上 ($x,$y) 起 ${w}×${h} 的区域；" else ""
        val text = "{\"ok\":true,\"width\":$shotW,\"height\":$shotH," +
            "\"imageBytes\":${shot.optInt("bytes", 0)},\"pointable\":${lastShot != null}," +
            "\"note\":\"这张图已附在随后一条消息里，你能直接看到。$scope" +
            "图上叠了浅橙色坐标网格：每 120px 一格、每 240px 标一个数字，数字就是图片坐标。" +
            "报坐标时**先读目标旁边的刻度数字、再数格子**，不要凭感觉估。" +
            "phone_tap_at / phone_long_press 都按这张图的像素坐标报（原点左上角），手机自己换算。" +
            "要看清小字或小按钮，可以 phone_screenshot(x,y,w,h) 只截那一块放大再看。" +
            "若读屏能给序号，优先用 phone_tap，更稳也更省。\"}"
        return ToolResult(text, "data:$mime;base64,$b64")
    }

    // ------------------------------------------------------------ 小工具

    private fun device(): DeviceApi? = deviceProvider()

    /** 抓一次当前屏幕事实。只更新判定用的数据，不动给模型看的序号表。 */
    private fun refreshFacts(): ScreenFacts {
        val d = deviceProvider() ?: return lastFacts
        val payload = try {
            d.readScreen()
        } catch (e: Exception) {
            return lastFacts
        }
        // 读不到就沿用上一次的事实，而不是把判定依据清空。
        // 清空等于"这一屏什么都看不见，所以什么都不危险"——那正好是最不该得出的结论。
        if (!payload.optBoolean("ok", false)) return lastFacts
        val facts = ScreenFactsReader.fromPayload(payload)
        if (facts.isEmpty) return lastFacts
        lastFacts = facts
        return facts
    }

    private fun tapTarget(index: Int): String =
        if (index <= 0) "" else lastOutline?.label(index) ?: ""

    private fun err(code: String, message: String): String =
        "{\"ok\":false,\"code\":\"${escape(code)}\",\"error\":\"${escape(message)}\"}"

    private fun escape(s: String): String = Json.escape(s)

    companion object {
    /** 动作后等界面落定的毫秒数。 */
    private const val SETTLE_MS = 500L

    /**
     * 手机被别的会话占着时回给模型的话。
     *
     * 必须写明「不要重试」：不写的话模型会换个手机工具再试一次，一轮里连撞四五次，
     * 既烧 token，界面上又是一片红字，用户看到的还只是「它坏了」。
     */
    private const val BUSY_TEXT =
        "另一个会话正在操作手机（同一块屏幕只能由一个任务操作），这次调用没有执行。" +
            "不要重试、也不要换别的手机工具再试。直接告诉用户：等那边跑完再让你继续，或者先把那个会话停掉。"

    /** 执行成功后要附带 screen_after 的动作：它们都会改变界面。 */
    private val MUTATING_ACTIONS = setOf(
        "phone_tap", "phone_tap_at", "phone_long_press", "phone_input",
        "phone_press_key", "phone_swipe", "phone_launch_app", "phone_intent",
    )

    /** phone_intent 允许的动作。见 [intent]。 */
    private val INTENT_ACTIONS = setOf(
        "alarm", "timer", "calendar", "dial", "sms", "share",
        "navigate", "open_url", "settings", "app_info",
    )
        /** 需要过闸门的动作：只读和纯移动不在里面。 */
        private val RISKY_ACTIONS = setOf("phone_tap", "phone_tap_at", "phone_long_press", "phone_input")

        private val SCHEMA_JSON = """
        [
          {
            "type": "function",
            "function": {
              "name": "phone_status",
              "description": "查看手机状态：设备型号、屏幕尺寸、无障碍是否可用。开始任何操作前先调一次，确认无障碍已开启。",
              "parameters": {"type": "object", "properties": {}, "required": []}
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_read_screen",
              "description": "读取当前屏幕的可操作元素大纲，每个元素带序号。键盘弹出时大纲会同时包含应用内容和键盘区域（以「── 包名(键盘) ──」分隔）。点按时用序号，不要自己算坐标。每次点按/输入/返回之后旧序号会失效，必须重新调用本工具。",
              "parameters": {"type": "object", "properties": {}, "required": []}
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_tap",
              "description": "点按屏幕上某个元素。index 来自最近一次 phone_read_screen 返回的序号。这是首选的点按方式。",
              "parameters": {
                "type": "object",
                "properties": {"index": {"type": "integer", "description": "元素序号"}},
                "required": ["index"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_tap_at",
              "description": "按截图上的像素坐标点按。只在 phone_read_screen 给不出序号、但截图能看清目标时用——微信、支付宝、游戏这类自绘界面。x/y 取自最近一次 phone_screenshot 返回图片的左上角原点；图上每 120px 有网格、每 240px 有刻度数字，**先读最近刻度的数字再数格子**，不要凭感觉估。如果这张图是局部放大图（phone_screenshot 带 x/y/w/h），就报放大图上的刻度坐标，手机会换算回真实屏幕。每次点按后旧截图作废，要继续操作必须重新截图。",
              "parameters": {
                "type": "object",
                "properties": {
                  "x": {"type": "integer", "description": "图片上的横坐标（像素），原点在左上角"},
                  "y": {"type": "integer", "description": "图片上的纵坐标（像素），原点在左上角"}
                },
                "required": ["x", "y"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_long_press",
              "description": "在 (x,y) 坐标长按约 1 秒，唤出输入框的长按菜单（第一项通常是「粘贴」）。坐标口径与 phone_tap_at 相同：先 phone_screenshot，报那张图上输入框/搜索栏中心的像素坐标。用于微信这类读不到控件树的应用：phone_input 写剪贴板 → 本工具长按 → phone_tap_at 点「粘贴」。菜单没出现就重新截图、换个位置再长按一次。",
              "parameters": {
                "type": "object",
                "properties": {
                  "x": {"type": "integer", "description": "图片上的横坐标（像素），原点在左上角"},
                  "y": {"type": "integer", "description": "图片上的纵坐标（像素），原点在左上角"}
                },
                "required": ["x", "y"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_input",
              "description": "输入文本。普通应用：直接写进当前焦点输入框（先 phone_tap 点一下输入框确保聚焦）。微信这类读不到控件树的应用：本工具只把文字写进剪贴板，完整输入是三步——phone_input(文字) → phone_long_press(输入框坐标) 长按唤出菜单 → phone_tap_at 点菜单里的「粘贴」。同一段文字只写一次，重复调用不重复写；不做任何验证，调完直接做长按那一步。",
              "parameters": {
                "type": "object",
                "properties": {
                  "text": {"type": "string", "description": "要写入剪贴板的内容"}
                },
                "required": ["text"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_press_key",
              "description": "按系统按键。back=返回上一级，home=回桌面，enter=回车，recents=最近任务。",
              "parameters": {
                "type": "object",
                "properties": {"key": {"type": "string", "enum": ["back", "home", "enter", "recents"]}},
                "required": ["key"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_launch_app",
              "description": "按应用名（中文名或包名）启动一个 App，例如「设置」「微信」「com.android.settings」。",
              "parameters": {
                "type": "object",
                "properties": {"target": {"type": "string", "description": "应用名或包名"}},
                "required": ["target"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_intent",
              "description": "用系统接口一步完成「一句话的事」：设闹钟/计时器、建日程、打开拨号盘、写短信草稿、分享文字、启动导航、打开链接、跳设置页。**比在设置页里一层层点快十倍**，凡是有对应 action 的都优先用它。action 取值：alarm(hour,minute,message) / timer(seconds,message) / calendar(title,begin_ms,duration_min) / dial(number) / sms(number,text) / share(text) / navigate(query) / open_url(url) / settings(panel=wifi|bluetooth|apps|display|sound|battery|location|accessibility|storage) / app_info(package)。拨号只填号码不拨打；短信只打开草稿不发送——发送/拨出这一步留给用户自己按。",
              "parameters": {
                "type": "object",
                "properties": {
                  "action": {"type": "string", "enum": ["alarm", "timer", "calendar", "dial", "sms", "share", "navigate", "open_url", "settings", "app_info"]},
                  "hour": {"type": "integer", "description": "alarm：几点（0-23）"},
                  "minute": {"type": "integer", "description": "alarm：几分（0-59）"},
                  "message": {"type": "string", "description": "alarm/timer：显示的标签，如「吃药」「泡面」"},
                  "seconds": {"type": "integer", "description": "timer：时长（秒）"},
                  "skip_ui": {"type": "boolean", "description": "alarm/timer：true 表示直接创建不弹确认（确认页被跳过时看不到效果，一般别用）"},
                  "title": {"type": "string", "description": "calendar：日程标题"},
                  "begin_ms": {"type": "integer", "description": "calendar：开始时间，毫秒时间戳（现在的时间用 shell 的 date +%s%3N 取，不要自己算）"},
                  "duration_min": {"type": "integer", "description": "calendar：时长（分钟），默认 60"},
                  "description": {"type": "string", "description": "calendar：备注"},
                  "number": {"type": "string", "description": "dial/sms：电话号码"},
                  "text": {"type": "string", "description": "sms/share：正文"},
                  "subject": {"type": "string", "description": "share：标题（邮件用）"},
                  "query": {"type": "string", "description": "navigate：地点或地址，如「郑州东站」"},
                  "url": {"type": "string", "description": "open_url：http/https 链接"},
                  "panel": {"type": "string", "description": "settings：直接跳的子页，wifi/bluetooth/apps/display/sound/battery/location/accessibility/storage"},
                  "package": {"type": "string", "description": "app_info：包名"}
                },
                "required": ["action"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_swipe",              "description": "滑动屏幕，用于翻页或滚动列表。",
              "parameters": {
                "type": "object",
                "properties": {"direction": {"type": "string", "enum": ["up", "down", "left", "right"]}},
                "required": ["direction"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "phone_screenshot",
              "description": "截取当前屏幕的画面，你会直接看到这张图，图上叠了坐标网格和刻度数字（数字就是图片像素坐标）。只在 phone_read_screen 给不出有用信息时使用——例如返回的元素很少、都没有文字、或明显是自绘界面（微信、支付宝、游戏）。截图比读屏贵约 15 倍，不要每次操作都截。**要看清小字、小按钮（比如长按后的「粘贴」菜单）时，用 x/y/w/h 只截那一块，图会被放大到 1080 宽**，坐标仍按放大后的图片像素报。",
              "parameters": {
                "type": "object",
                "properties": {
                  "x": {"type": "integer", "description": "局部截图的左上角 x（图片像素，按上一张整屏图估）；不填=整屏"},
                  "y": {"type": "integer", "description": "局部截图的左上角 y；不填=整屏"},
                  "w": {"type": "integer", "description": "局部截图宽度（像素），建议 300~600"},
                  "h": {"type": "integer", "description": "局部截图高度（像素），建议 200~500"}
                },
                "required": []
              }
            }
          }
        ]
        """.trimIndent()

        /**
         * 「派子代理」的工具定义。只有宿主真接了 [delegate] 才会出现在工具列表里。
         *
         * 描述里把「什么时候该派」写得很具体，是因为模型很容易滥用：一句「查一下」
         * 就派一个子代理，等于把本来两轮能答完的事变成十轮。反过来只写「派子代理」
         * 它又永远不会用。所以这里给了三条判据：要读很多页 / 会抓回大量无关内容 /
         * 有明确边界的一次性调研。
         */
        private val TASK_SCHEMA = JSONObject(
            """
            {
              "type": "function",
              "function": {
                "name": "task",
                "description": "派一个子代理去独立完成一件「读得多、产出少」的活：批量查资料、把一批页面/文件通读一遍挑出要点、对比多个来源。它在自己的上下文里干活，中间抓到的东西不会进你的上下文，你只拿到一份结论——所以它特别适合那种「要读十几个页面但最后只要几条结论」的任务。不要用它做：一句话就能答的事、需要操作手机的事（它碰不到屏幕）、需要写文件的事（它只能读，内容让它写进结论里你自己落盘）。它看不到当前对话，goal 必须写成能独立看懂的一句话。",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "goal": {"type": "string", "description": "子任务目标，一句话说清要查什么、到什么程度算完成。子代理看不到当前对话，别用「上面那个」「刚才说的」这类指代。"},
                    "focus": {"type": "string", "description": "可选：只要哪几个字段 / 只回答哪几个问题。比如「每个项目只要：名称、最新版本号、下载地址」。填了它结论会更短、更准。"}
                  },
                  "required": ["goal"]
                }
              }
            }
            """.trimIndent()
        )

        /**
         * 一个任务里最多派几次子代理。
         *
         * 三次是权衡的结果：常见任务（比价、批量查 release、通读几个文件）一两次够用；
         * 再多通常说明任务本身没想清楚，该回到主线重新规划，而不是继续撒钱。
         */
        const val MAX_SUB_TASKS = 3
    }
}
