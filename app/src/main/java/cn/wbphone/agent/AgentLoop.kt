package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent loop：模型 → 工具调用 → 结果回填 → 再问模型，直到它不再要工具。
 *
 * 这是与「遥控电脑」最本质的差别所在。桥那套里，这个循环跑在桌面端的引擎里，
 * 手机只是被调用的一端；现在循环跑在手机上，手机是完整的一台 agent 主机。
 *
 * 状态（对话历史）留在内存里，一次任务结束后 assistant 的最终回复也进历史，
 * 所以下一句话模型记得上文。要不要长期保存到磁盘是另一个问题，故意没做。
 */
class AgentLoop(
    private val client: CloudClient,
    private val toolbox: Toolbox,
    private val maxTurns: Int = Config.MAX_TURNS,
    /**
     * 跨会话记忆的注入源。每次请求都重新取——这一轮刚记下的东西，下一轮就该看见。
     * 参数是「本轮话题」（用户最近几句话）：记忆不是全量灌进来的，要按相关性挑，
     * 否则记忆一多，别的主题的旧事被硬塞进这一轮，比不记还糟。
     * 返回空串表示没有记忆（或用户关了开关），系统提示保持原样。
     */
    private val memory: (String) -> String = { "" },
    /**
     * 技能包索引的注入源。与记忆不同，这里只放清单（每条一行），正文要模型自己
     * 调 skill_load 取——索引是每轮都发的，正文不是。
     * 返回空串表示没有技能（或用户关了开关）。
     */
    private val skills: () -> String = { "" },
    /**
     * 「人情味」开关的语气段注入源（[PhonePrompt.WARM_TONE]）。
     * 开着就返回语气规范，附在末条 user 消息的最后——离回答最近、注意力最足，
     * 也不动 system 正文（缓存的账见 [injectExtras]）。空串 = 关闭，一字不烧。
     * 子代理不吃这个：干活的轮子不需要共情。
     */
    private val tone: () -> String = { "" },
    /**
     * 会话身份，用来申请「手机所有权」（见 [PhoneLease]）。
     *
     * 手机只有一块屏幕，两个会话交错操作会让序号表和截图坐标系互相作废——单看每一步
     * 都合法，合起来却点到了别处。空串表示不参与租约（纯 JVM 测试、无会话身份的调用方）。
     */
    private val owner: String = "",
    /**
     * 系统提示词。默认是手机助手那一套（[PhonePrompt.SYSTEM]）。
     *
     * 子代理要换成自己的：同样一个 loop，跑的是「只做一件事、只读、只回结论」的另一套准则。
     * 做成参数而不是在 loop 里分支，是因为 loop 本身不该知道「这次是谁在干活」。
     */
    private val systemPrompt: String = PhonePrompt.SYSTEM,
    /**
     * 外部取消源。子代理用：主线被用户点了停止，子代理必须立刻跟着停。
     *
     * 不这么做的话会出现最招人恨的一种「停不下来」——用户按了停止，
     * 屏幕上没动静，钱还在另一个循环里继续花。
     */
    private val externalCancel: () -> Boolean = { false },
) {

    interface Listener {
        /** 模型正在往外吐字 */
        fun onText(delta: String)

        /**
         * 模型在「想」的过程。
         *
         * 上游把思考单独发在 reasoning_content 里，正文要等思考结束才开始。不显示它，
         * 用户看到的就是一段几秒的空白——这就是「最开始太慢」的真实来源。
         * 默认忽略（测试和后台宿主不需要）。
         */
        fun onThinking(delta: String) {}

        /** 要调工具了 */
        fun onToolStart(name: String, args: JSONObject)

        /** 工具执行完了，result 是回填给模型的那段文本 */
        fun onToolEnd(name: String, result: String, ok: Boolean)

        /** 一轮模型调用结束（可能还有下一轮） */
        fun onTurnEnd(turn: Int)

        /**
         * 一轮请求的耗时拆解：拿到响应头多久、首字多久、整轮多久。
         *
         * 为什么单独一个回调：用户说「感觉没变快」时，只看总时间分不清是网络、
         * 预填充还是模型在思考。拆开记进流水账，下次改性能才有依据。默认忽略。
         */
        fun onTurnTiming(turn: CloudClient.Turn) {}

        /** 整个任务结束，finalText 是模型最后那段话 */
        fun onFinished(finalText: String, turns: Int, toolCalls: Int)

        fun onError(message: String)

        /**
         * 上下文被压缩了。
         *
         * 这件事用户必须知道：压缩意味着模型对早期步骤的记忆变成了摘要，它可能重复做过的事。
         * 悄悄压缩、然后让用户疑惑「它怎么忘了」，比不压缩更糟。
         */
        fun onCompact(removed: Int, kept: Int) {}

        /**
         * 模型写出了一个产物文件（fs_write / fs_edit 成功）。
         *
         * 产物必须主动送到用户眼前，而不是让他去 Android/data 里翻。
         * path 是工作台内的相对路径。
         */
        fun onArtifact(path: String) {}

        /**
         * 模型调了 html_preview：工作台里的网页要在手机上全屏渲染出来。
         *
         * 和 onArtifact 分开：产物卡只是「有个文件」，这条是「立刻给用户看」——
         * 渲染是用户请求的最终交付物，必须主动弹，不能让用户去工作台里翻。
         * 默认忽略（测试和后台宿主不需要）。
         */
        fun onOpenHtml(path: String) {}

        /**
         * 工具返回了一张图（目前只有截图）。带默认实现，界面想看就看，不看也不影响协议。
         * 这条不是给模型用的——图片本身在历史里，这里只是让手机屏幕上也显示出来。
         */
        fun onImage(dataUrl: String) {}

        /**
         * 随请求附带的背景资料（长期记忆 / 技能索引）**内容变了**。
         *
         * 为什么要单独报一次：模型「问的是主题四却整段答主题三」这类问题，只翻用户原文
         * 是查不出来的——真正的诱因往往在被注入的那段背景里。之前这段东西不进任何日志，
         * 出了事只能靠猜。只在内容变化时触发，不会每轮刷屏。默认忽略。
         */
        fun onExtras(chars: Int, preview: String) {}

        /**
         * 网络层正在退避重发（限流 / 连不上 / 流断在半路）。
         *
         * 必须报：重试期间界面上一个字都不会动，不解释一句，用户只会以为卡死了，
         * 然后手动点停止——那才是真的白跑一趟。
         */
        fun onRetry(why: String) {}

        /**
         * 工具内部的进度（目前只有 task / 派子代理会用）。
         *
         * 和 onText 分开：子代理的中间过程不是主线的回答，绝不能混进回复气泡里。
         * 界面拿它去更新状态条那一行字就够了。
         */
        fun onSubProgress(note: String) {}

        /**
         * 这一轮作废重来：把已经显示出来的半截内容撤掉。
         *
         * 只在「已经吐了字、又断了、决定重发」时触发。不撤的话，重发的内容会接在
         * 半截后面，读起来是一段重复开头、前后不搭的话。
         */
        fun onRestart() {}
    }

    private var history = JSONArray()

    /** 本轮要附在末条 user 消息后面的背景资料（不含分隔头）。buildMessages 里现算。 */
    private var lastExtras = ""

    /** 本轮开始时取的系统时间，随每轮请求附在末条 user 消息上（见 injectExtras）。 */
    private var timeLine = ""

    /**
     * 本轮话题：用户最近几句话拼起来，给记忆检索当查询词。
     * 每轮开头算一次、整轮不变——轮内多请求时记忆段逐字节稳定，尾部缓存才不会碎。
     * 为什么带上前几句：像「那第二个呢」这种追问，单看这一句什么都匹配不到。
     */
    private var memQuery = ""

    /** 上一次报给宿主的背景资料内容。用于「只在变化时记一笔」。 */
    private var extrasReported = ""

    @Volatile private var cancelled = false

    /** 本次任务累计的 token 用量。截图是这里涨幅最大的来源，所以要能看见。 */
    @Volatile var promptTokens = 0
        private set

    @Volatile var completionTokens = 0
        private set

    /**
     * 上一轮服务端回传的模型名。界面拿它显示「这轮到底是谁答的」——
     * 不靠顶栏那个我们自己的记录，靠服务端自己说的。
     */
    @Volatile var lastModel: String? = null
        private set

    /**
     * 上一次 run 是不是「模型自己说完了」。
     *
     * 子代理靠它区分「结论」和「到点收工」：两者都会调 onFinished，但后者那段话是
     * 「跑了 N 轮还没完成」——把它当成结论交给主线，主线会以为自己拿到了一份答案。
     */
    @Volatile var lastCompleted = false
        private set

    /** 本次会话压缩过几次上下文。界面拿它显示「记忆被折叠过」，别让用户以为模型忘了事。 */
    @Volatile var compactions = 0
        private set

    fun cancel() {
        cancelled = true
    }

    /** 自己停的，或者调用方（主线）让停的。 */
    private fun stopped(): Boolean = cancelled || externalCancel()

    fun reset() {
        history = JSONArray()
        compactions = 0
        lastExtras = ""
        extrasReported = ""
        timeLine = ""
        memQuery = ""
    }

    fun historySize(): Int = history.length()

    /** 深拷贝一份历史，给持久化用——持久化线程改不到正在跑的这份。 */
    fun exportHistory(): JSONArray = JSONArray(history.toString())

    /** 进程重启后把上次的历史装回来，对话就能接着上文继续。 */
    fun restoreHistory(saved: JSONArray) {
        history = JSONArray(saved.toString())
    }

    /**
     * 跑一个任务。阻塞调用，必须在后台线程执行。
     * @param imageDataUrls 用户附带的图片（data:image/jpeg;base64,...），可多张。
     *   有图时这条 user 消息用多模态 parts 承载（一段文字 + N 张图）——
     *   和工具截图走同一种格式，服务端只认这一种。
     */
    fun run(userText: String, listener: Listener, imageDataUrls: List<String>? = null) {
        cancelled = false
        lastCompleted = false
        // 每轮开始取一次。轮内多次请求里保持逐字节稳定，不打碎尾部缓存。
        timeLine = nowLine()
        promptTokens = 0
        completionTokens = 0
        lastModel = null
        var turns = 0
        var toolCount = 0
        var lastText = ""

        // 卡死检测的账本（见循环体内的解释）
        var lastSig = ""
        var repeatRounds = 0
        var nudged = false
        var failedRounds = 0
        var ineffectiveRounds = 0

        // 自动续跑：到段上限还没收敛就替用户说一句「继续」，最多 Config.MAX_CONTINUES 次
        var budget = maxTurns
        var continuations = 0

        // 新任务：旧序号、旧截图、上一轮给的审批授权全部作废
        toolbox.beginTask(owner)

        try {
            history.put(JSONObject().put("role", "user").put("content", userContent(userText, imageDataUrls)))
            // 话题词要在记忆检索之前定下来（含刚追加的这条）
            memQuery = topicQuery()
            val tools = toolbox.schemas()

            outer@ while (true) {
                while (turns < budget) {
                    if (stopped()) break@outer
                    turns += 1

                // 压缩必须发生在这一轮请求之前，否则「压完再发」变成「先超限再压」
                maybeCompact(listener)
                val messages = buildMessages()
                // 背景资料变了就留一条痕：出事时这是唯一能证明「它当时被喂了什么」的证据。
                if (lastExtras != extrasReported) {
                    extrasReported = lastExtras
                    listener.onExtras(lastExtras.length, lastExtras.take(200))
                }

                val turn = client.stream(
                    messages,
                    tools,
                    onText = { delta ->
                        if (stopped()) throw Cancelled()
                        listener.onText(delta)
                    },
                    onReasoning = { delta ->
                        if (!stopped()) listener.onThinking(delta)
                    },
                    // 网络层要退避重发时说一声：不说的话，界面停在「正在回复…」一动不动，
                    // 用户只会以为卡死了，然后手动点停止——那才是真的白跑。
                    onRetry = { why -> listener.onRetry(why) },
                    // 已经吐了半截、这次作废重来：界面必须把那半截撤掉，
                    // 否则重发的内容接在半截后面，读起来是一段重复开头、前后不搭的话。
                    onRestart = { listener.onRestart() },
                    isCancelled = { stopped() },
                )
                lastText = turn.content
                turn.model?.let { lastModel = it }
                turn.usage?.let { u ->
                    promptTokens += u.optInt("prompt_tokens", 0)
                    completionTokens += u.optInt("completion_tokens", 0)
                }
                listener.onTurnTiming(turn)
                listener.onTurnEnd(turns)

                if (turn.toolCalls.isEmpty()) {
                    // 撞了输出上限：模型没说完，而且它是**知道自己被截断的**。
                    // 之前完全不看 finish_reason，8192 一撞就停在半句上，界面上像正常结束。
                    // 这里替用户把「接着写」说出口，最多续 Config.MAX_CONTINUES 次。
                    if (turn.finishReason == "length" && continuations < Config.MAX_CONTINUES) {
                        continuations += 1
                        budget += 1
                        if (turn.content.isNotEmpty()) {
                            history.put(assistantMessage(turn, turn.content))
                        }
                        history.put(JSONObject().put("role", "user").put("content", LENGTH_NUDGE))
                        continue
                    }
                    if (turn.content.isNotEmpty()) {
                        history.put(assistantMessage(turn, turn.content))
                    }
                    lastCompleted = true
                    listener.onFinished(turn.content, turns, toolCount)
                    return
                }

                // 助手这一轮的消息先入历史，连同它要调的工具
                val callsJson = JSONArray()
                for (call in turn.toolCalls) {
                    callsJson.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put("function", JSONObject().put("name", call.name).put("arguments", call.args))
                    )
                }
                history.put(assistantMessage(turn, turn.content).put("tool_calls", callsJson))

                var aborted = false
                // 手机被别的会话占着：这一轮剩下的调用直接作废（也不能留空配对）
                var phoneBusy = false
                var anyOk = false
                var touched = false        // 这轮有没有动作类调用
                var screenMoved = false    // 动作之后界面有没有真的变化
                for (call in turn.toolCalls) {
                    // 已停止或已中止：剩下的调用仍然要补一条 tool 消息。
                    // tool_calls 和 tool 消息必须成对，少一条下一轮请求会被服务端拒掉，
                    // 那样连"任务已停止"这句话都发不出去。
                    if (stopped() || aborted || phoneBusy) {
                        history.put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", call.id)
                                .put("content", SKIPPED)
                        )
                        continue
                    }

                    toolCount += 1
                    val args = parseArgs(call.args)
                    listener.onToolStart(call.name, args)

                    val result = toolbox.execute(call.name, args, owner, { note ->
                        // 子代理这类「工具里还套着若干轮请求」的调用，过程必须往外报一句：
                        // 否则界面在十几轮里一直停在「派子代理…」上，用户以为卡死了。
                        listener.onSubProgress(note)
                    }, { stopped() })
                    // 工具自己烧的 token（目前只有 task 会填）也要算进本轮：
                    // 不报的话，界面上会出现「聊了十分钟，积分几乎没动」的假象。
                    promptTokens += result.promptTokens
                    completionTokens += result.completionTokens
                    val ok = !result.text.startsWith("{\"ok\":false")
                    if (ok) anyOk = true
                    // 手机被别人占着：记下这一条（下面是真实原因），本轮剩下的调用作废。
                    // 判据用 code 而不是整段文案——文案以后会改，code 是契约。
                    if (!ok && result.text.contains("\"code\":\"PHONE_BUSY\"")) phoneBusy = true
                    if (call.name in MUTATING) {
                        touched = true
                        // PhoneTools 会在「动作前后界面零变化」时给 screen_changed:false
                        if (!result.text.contains("\"screen_changed\":false")) screenMoved = true
                    }
                    listener.onToolEnd(call.name, result.text, ok)

                    // 单条结果不能无限进上下文：read_file 一次能读 256KB，那差不多是 3 万
                    // token，而且之后**每一轮**都要重发一遍——一个 40 轮的任务，光这一条就
                    // 能烧掉上百万 token。原文在流水账里，模型要细节可以分段重读。
                    history.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.id)
                            .put(
                                "content",
                                if (ok) withVerifyHint(call.name, clipToolResult(result.text))
                                else clipToolResult(result.text)
                            )
                    )

                    // 写了文件（或生成了 PDF/PPT）就报给界面，让产物出现在对话里
                    if (ok && call.name in ARTIFACT_TOOLS) {
                        runCatching { JSONObject(result.text).optString("path") }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }?.let { listener.onArtifact(it) }
                    }

                    // html_preview 成功 = 用户要看到的网页渲染好了，立刻全屏打开
                    if (ok && call.name == "html_preview") {
                        runCatching { JSONObject(result.text).optString("path") }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }?.let { listener.onOpenHtml(it) }
                    }

                    // 截图这类工具返回图片。role:"tool" 只能放文本，所以紧跟一条 user 消息承载图片。
                    result.imageDataUrl?.let { url ->
                        listener.onImage(url)
                        val parts = JSONArray()
                            .put(JSONObject().put("type", "text").put("text", "（这是当前屏幕的截图）"))
                            .put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", url))
                            )
                        history.put(JSONObject().put("role", "user").put("content", parts))
                    }

                    if (toolbox.shouldAbort()) aborted = true
                }

                // 手机被另一个会话占着：别再绕了。
                // 不在这里收手的话，模型会换一个手机工具接着试，一轮里连撞四五次，
                // 每次都是同一条拒绝——白烧 token，界面上还全是一样的话。
                if (phoneBusy) {
                    listener.onFinished(
                        "另一个会话正在操作手机，这个我先停在这里（没做任何操作）。\n" +
                            "等那边跑完说「继续」，我接着做；或者先去把它停掉。",
                        turns, toolCount,
                    )
                    return
                }

                // 连着被拒好几次，说明这个任务卡在需要人点头的地方，再转下去只是烧 token
                if (aborted) {
                    listener.onFinished(
                        "连续几次操作都没得到你的同意，我停在这里，没有继续尝试别的办法。\n" +
                            "剩下的步骤需要你自己在手机上完成。",
                        turns,
                        toolCount,
                    )
                    return
                }

                // —— 卡死检测 ——
                // 口径一：连续几轮执行「一模一样」的操作（名字+参数全同）。
                // 连续滚动列表时同一手势连划是正常操作，所以先提醒一次给模型台阶；
                // 提醒完还是原样打转，才认定真卡死，止损。
                val sig = turn.toolCalls.joinToString("|") { it.name + ":" + it.args }
                if (sig == lastSig) repeatRounds += 1 else repeatRounds = 0
                lastSig = sig
                if (repeatRounds >= Config.STALL_REPEAT_ROUNDS) {
                    if (!nudged) {
                        nudged = true
                        repeatRounds = 0
                        history.put(JSONObject().put("role", "user").put("content", STALL_NUDGE))
                    } else {
                        listener.onFinished(
                            "连续多轮重复完全相同的操作但没有进展，我停在这里，免得继续空转。\n" +
                                "已做的步骤都在，你可以说「继续」并提示我换个方法。",
                            turns, toolCount,
                        )
                        return
                    }
                }

                // 口径二：连续几轮所有工具调用都失败——模型在反复撞同一堵墙。
                if (anyOk) failedRounds = 0 else failedRounds += 1
                if (failedRounds >= Config.STALL_FAIL_ROUNDS) {
                    listener.onFinished(
                        "连续 $failedRounds 轮每个操作都失败了，多半是这条路走不通。\n" +
                            "你可以说「继续」并换个思路指点我，或者自己上手做剩下的部分。",
                        turns, toolCount,
                    )
                    return
                }

                // 口径三：动作做了、报告 ok、但界面零变化——学习通这类 WebView 的经典坑
                // （无障碍点击「成功」，实际没反应）。这比口径一更隐蔽：参数每轮都在换，
                // 签名比对抓不到。先提醒一次，再犯就止损。
                if (touched) {
                    if (screenMoved) ineffectiveRounds = 0 else ineffectiveRounds += 1
                } else {
                    ineffectiveRounds = 0
                }
                if (ineffectiveRounds >= Config.STALL_INEFFECTIVE_ROUNDS) {
                    if (!nudged) {
                        nudged = true
                        ineffectiveRounds = 0
                        history.put(
                            JSONObject().put("role", "user").put("content", STALL_INEFFECTIVE_NUDGE)
                        )
                    } else {
                        listener.onFinished(
                            "连续多轮操作都没有让界面发生任何变化，多半是点在了不响应的图层上。" +
                                "我停在这里免得继续空转。\n" +
                                "你可以说「继续」并给点提示（比如先关掉弹层、换个入口），或者自己上手完成。",
                            turns, toolCount,
                        )
                        return
                    }
                }
            }

            // —— 到达本段轮数上限还没收敛 ——
            // 先别停：替用户把「继续」说出口。之前是硬停等用户点「继续」，
            // 多数时候人根本不在看，任务就晾在那了。
            if (continuations < Config.MAX_CONTINUES) {
                continuations += 1
                budget += maxTurns
                history.put(
                    JSONObject().put("role", "user")
                        .put("content", "继续做，从当前状态接着来，直到完成或确认做不了。别重复已经失败的步骤。")
                )
                continue@outer
            }
            break@outer
            }

            if (stopped()) {
                listener.onFinished(if (lastText.isEmpty()) "已停止。" else "$lastText\n\n（已停止）", turns, toolCount)
            } else {
                // 到轮数上限还没收敛：把话说清楚，别让用户以为完成了
                listener.onFinished(
                    "任务跑了 $turns 轮（含 $continuations 次自动续跑）还没完成，先停在当前状态。" +
                        "历史和进度都还在，直接说「继续」我会接着做；带上指点更好，比如「换个方法」。" +
                        if (lastText.isEmpty()) "" else "\n\n最后一次输出：$lastText",
                    turns,
                    toolCount,
                )
            }
        } catch (e: Cancelled) {
            listener.onFinished("已停止。", turns, toolCount)
        } catch (e: Exception) {
            // 这一轮没答完，刚追加的那条 user 消息就没人接话了。留着它，用户重发一次
            // 就变成同一句话在历史里说了两遍，模型会跟着重复作答，历史也越滚越脏。
            dropDanglingUser()
            // 用户点了停止（可能是退避等待中被叫停的）：这不是错误，别弹一句红色报错吓人。
            if (stopped()) listener.onFinished("已停止。", turns, toolCount)
            // 原始异常进日志（CloudClient 那边已经记过），给用户的是「该等、该换网还是该重登」
            else listener.onError(Errors.humanize(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 失败收尾时，撤掉这条没人回答的 user 消息。
     *
     * 只在「最后一条是 user」时才动手：如果已经执行过工具，末尾是 tool 消息，
     * 那些结果是真发生过的事（点过的屏幕、写过的文件），不能跟着删。
     */
    private fun dropDanglingUser() {
        val last = history.optJSONObject(history.length() - 1) ?: return
        if (last.optString("role") == "user") history.remove(history.length() - 1)
    }

    /**
     * 组装发给模型的 messages。
     *
     * 两件对「快」和「省」影响最大的事都在这里：
     *
     *  1. **系统提示保持字节级稳定**。它是每轮请求里唯一从头到尾不变的东西，
     *     服务端的上下文缓存只有在前缀逐字节相同时才命中——把会变的内容拼进 system，
     *     等于每轮把缓存打碎重来，12k tokens 的前缀全部按全量输入重新预填，
     *     首字延迟和积分一起遭殃。
     *  2. **除最后一张图之外，历史里所有旧截图都替换成一行文本。**
     *     历史是每轮重发的，留着三张图就是每轮多烧约 2700 tokens，而它们对当前决策早已无用。
     *     用「替换」而不是「删除」，是为了保住消息结构合法：user/assistant 交替、
     *     tool_call_id 必须成对——直接删一条会让服务端解析失败。
     */
    private fun buildMessages(): JSONArray {
        var lastImage = -1
        for (i in 0 until history.length()) {
            if (hasImage(history.optJSONObject(i))) lastImage = i
        }
        val out = JSONArray()
        out.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (i != lastImage && hasImage(m)) {
                out.put(JSONObject().put("role", "user").put("content", DROPPED_IMAGE))
            } else if (m.has(THINK_KEY)) {
                // 思考只留给用户回看，**不**发回模型：多带一份纯属白烧 token，
                // 严格的网关还会因为不认识的字段直接 400。拷贝一份再摘，history 里的留着。
                out.put(JSONObject(m.toString()).apply { remove(THINK_KEY) })
            } else {
                out.put(m)
            }
        }
        injectExtras(out)
        return out
    }

    /**
     * 一条助手消息，顺带把这一轮的思考全文存进 `_think`。
     *
     * 为什么放在 history 里而不是另存一份：思考天然属于「某一条回答」，
     * 跟着消息走，截断、压缩、存盘、恢复都不用再对齐一次——另存一份就得靠下标对锚，
     * 历史一截断，锚点全错位，最后会张冠李戴到别人的回答上。
     *
     * 发请求前由 [buildMessages] 剥掉，模型永远看不到它（那是它自己想的，再喂一遍毫无意义）。
     */
    private fun assistantMessage(turn: CloudClient.Turn, content: String?): JSONObject {
        val m = JSONObject()
            .put("role", "assistant")
            .put("content", if (content.isNullOrEmpty()) JSONObject.NULL else content)
        val think = turn.reasoningText.trim()
        if (think.isNotEmpty()) {
            m.put(THINK_KEY, if (think.length <= THINK_KEEP) think else think.take(THINK_KEEP) + "\n…（思考过长，已截断）")
        }
        return m
    }

    /**
     * 长期记忆与技能索引：每轮现取，但**不**拼进系统提示，附在最后一条 user 消息上。
     *
     * 为什么换位置：它们是会变的内容（memory_save 一写就变、技能开关一拨就变），
     * 拼在 system 里等于每轮请求都换前缀。附在尾部则不然——内容不变时，
     * 被附的那条消息逐字节不动，缓存从消息 0 一路命中到接近末尾；
     * 真变了也只作废从那条消息起的一段，比作废整个前缀便宜得多。
     * 注意力位置几乎没变：这条消息本来就贴着对话的当前端。
     *
     * 两者都为空时什么都不附。顺序仍是技能索引在前、记忆在后（与旧版一致）。
     * 兜底：找不到 user 消息（理论上不可能，第一条就是 user）就退回拼 system 的老路。
     */
    private fun injectExtras(out: JSONArray) {
        val remembered = runCatching { memory(memQuery) }.getOrDefault("")
        val skillIndex = runCatching { skills() }.getOrDefault("")
        val toneText = runCatching { tone() }.getOrDefault("")
        val extras = listOf(skillIndex, remembered).filter { it.isNotBlank() }
        lastExtras = ""
        // 当前时间也走这条路：不进 system（保住前缀缓存）、不进 history（不污染上下文），
        // 只深拷贝到末条 user 消息上。它不算进 lastExtras——那份数据的语义是
        // 「记忆/技能变了才记一笔」，时间每轮必变，混进去会让诊断日志每一轮都刷屏。
        var block = if (timeLine.isNotBlank()) TIME_HEADER + timeLine else ""
        if (extras.isNotEmpty()) {
            block += EXTRAS_HEADER + extras.joinToString("\n\n")
            // 留给宿主一份「没带分隔头」的原文，日志里看得清到底塞了什么
            lastExtras = extras.joinToString("\n\n")
        }
        // 语气段排最后：它是行为指令不是背景资料，挂得离回答越近越听话；
        // 开关一拨，变的是尾部一小段，前缀缓存照旧命中。
        if (toneText.isNotBlank()) {
            block += TONE_HEADER + toneText
            lastExtras = if (lastExtras.isEmpty()) toneText
            else lastExtras + "\n\n" + toneText
        }
        if (block.isEmpty()) return

        var target = -1
        for (i in out.length() - 1 downTo 0) {
            if (out.optJSONObject(i)?.optString("role") == "user") { target = i; break }
        }
        if (target < 0) {
            // 兜底：没有 user 消息可挂（不该发生），退回拼 system——慢一点总比丢记忆强
            val sys = out.optJSONObject(0)
            if (sys != null && sys.optString("role") == "system") {
                sys.put("content", sys.optString("content") + block)
            }
            return
        }
        // 必须深拷贝再改：out 里的消息对象与 history 共享引用，
        // 原地追加会把记忆块永久写进历史，下一轮再追加一遍，越滚越大。
        val src = out.optJSONObject(target) ?: return
        val m = JSONObject(src.toString())
        when (val c = m.opt("content")) {
            is String -> m.put("content", c + block)
            is JSONArray -> m.put("content", c.put(JSONObject().put("type", "text").put("text", block)))
            else -> return  // user 消息不可能是别的形态；宁可不附也别发出脏内容
        }
        out.put(target, m)
    }

    /**
     * 当前时间，格式「2026-11-09 周一 17:50」。
     * 星期几手动映射而不用 SimpleDateFormat 的 E：桌面 JVM 与 Android 对中文
     * 星期缩写的实现不一致（「周一」「星期一」都出现过），固定映射让测试可断言。
     * 只精确到分钟——模型不需要秒，也不会有人拿它对表。
     */
    private fun nowLine(): String {
        val c = java.util.Calendar.getInstance()
        val week = when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SUNDAY -> "周日"
            java.util.Calendar.MONDAY -> "周一"
            java.util.Calendar.TUESDAY -> "周二"
            java.util.Calendar.WEDNESDAY -> "周三"
            java.util.Calendar.THURSDAY -> "周四"
            java.util.Calendar.FRIDAY -> "周五"
            else -> "周六"
        }
        return "%04d-%02d-%02d %s %02d:%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
            week,
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
        )
    }

    /**
     * 本轮话题串：最近几条 user 消息（含刚追加的这条）拼起来，给记忆检索当查询词。
     *
     * 为什么要带上前面几句：像「那第二个呢」「再改一下」这种追问，只看这一句
     * 一个实词都没有，什么记忆都匹配不上，等于退回「按新鲜度瞎挑」。
     *
     * 摘要消息要跳过：压缩摘要里塞满了别的主题的关键词，拿它当查询词只会把话题带偏——
     * 「问主题四却答主题三」那类串台，一半就是这么来的。
     */
    private fun topicQuery(): String {
        val parts = ArrayList<String>()
        for (i in history.length() - 1 downTo 0) {
            if (parts.size >= TOPIC_MESSAGES) break
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            if (isSummary(m)) continue
            val t = briefUserText(m).trim()
            if (t.isNotEmpty()) parts.add(t)
        }
        return parts.joinToString(" ").take(TOPIC_CHARS)
    }

    private fun hasImage(m: JSONObject?): Boolean {
        val parts = m?.optJSONArray("content") ?: return false
        for (i in 0 until parts.length()) {
            if (parts.optJSONObject(i)?.optString("type") == "image_url") return true
        }
        return false
    }

    private fun parseArgs(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) {
        JSONObject()
    }

    /**
     * 动作类工具的结果后面挂一句「接下来先核对」。
     *
     * 为什么不能只靠 system prompt 里那条「动作之后必须重新读屏」：
     * 那是写在几千字开头的**远规则**，模型在连续十几轮工具调用里很容易把它丢掉，
     * 表现就是点一下、点一下、点一下，从不回头确认。而挂在结果里的这句话处在
     * 注意力最近的位置，遵从度明显更高，成本只有十几个 token。
     *
     * 只挂给会改变界面的动作。读屏类工具后面再挂「请读屏」是废话，纯浪费上下文。
     */
    /**
     * 单条工具结果进上下文前的大小上限。
     *
     * 32K 字符 ≈ 一次读完 600 行代码，够用；再往上就是「一次读整个文件」那种用法，
     * 而那笔钱会按轮次反复付——历史每轮整条重发。
     */
    private fun clipToolResult(text: String): String {
        if (text.length <= MAX_TOOL_CHARS) return text
        return text.take(MAX_TOOL_CHARS) +
            "\n…（结果共 ${text.length} 字，这里只给了前 $MAX_TOOL_CHARS 字，" +
            "末尾可能被切断。需要后面的部分就分段重读，别整份读）"
    }

    private fun withVerifyHint(name: String, raw: String): String {
        if (name !in MUTATING || name in NO_VERIFY) return raw
        return try {
            JSONObject(raw).put("_next", VERIFY_HINT).toString()
        } catch (e: Exception) {
            // 结果不是 JSON 对象就退化成追加一行，不能让提示把整条结果搞坏
            raw + "\n" + VERIFY_HINT
        }
    }

    /**
     * 上下文太长就把前半段压成摘要。
     *
     * 为什么必须做：历史每轮整条重发。一次读屏一百来 token、一张截图九百、一次文件读动辄
     * 上千——十几轮之后，光重发历史就能把上下文撑满、把积分烧掉；更要紧的是模型在噪声里
     * 会丢重点，表现就是「越到后面越糊涂」。
     *
     * 为什么不调模型来总结：那要额外一次请求（约 2K token），在 12 轮的预算里不划算。
     * 规则压缩够用——保住「目标 + 做过什么 + 结果如何」，丢掉的细节本来也能重新读一次。
     */
    private fun maybeCompact(listener: Listener) {
        if (history.length() < KEEP_TAIL + 4) return
        if (history.toString().length < COMPACT_AT_CHARS) return

        // 切点只能是「非 tool」的消息。
        //
        // 一开始我把切点限定在 user 上，看起来最安全，但手机上根本行不通：一个任务通常只有
        // 一条 user 指令，后面全是 assistant/tool 往返。限定 user 的话切点永远落在第 0 条，
        // 等于没压。真正的约束只有一条——别把 tool 消息和它对应的 assistant(tool_calls) 劈开，
        // 因为 tool 消息必须紧跟在调用它的那条 assistant 后面，劈开服务端会直接拒掉整个请求。
        var tailStart = -1
        val floor = (history.length() - KEEP_TAIL).coerceAtLeast(1)
        for (i in floor downTo 1) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") != "tool") {
                tailStart = i
                break
            }
        }
        if (tailStart < 2) return

        val summary = brief(0, tailStart)
        val kept = history.length() - tailStart
        // compact 标记：让下一轮压缩能认出「这条是摘要不是用户说的话」，
        // 解包继承它的目标，而不是把它当新目标再包一层（套娃 bug 的来源）。
        // 字符串前缀匹配是给旧会话兜底的，双保险。
        val out = JSONArray().put(
            JSONObject().put("role", "user").put("content", summary).put("compact", true)
        )
        for (i in tailStart until history.length()) out.put(history.get(i))
        history = out
        compactions++
        listener.onCompact(tailStart, kept)
    }

    /** 把一段历史压成「目标 + 要求 + 过程」。刻意简短：它每轮都要跟着重发。 */
    private fun brief(from: Int, to: Int): String {
        val goal = StringBuilder()
        // 用户中途补的要求（「顺便给下载地址」「整理成 md」）一条都不能丢——
        // 只留第一条当目标的话，模型压完就「忘了」这些要求，用户只能一步步再提示。
        val asks = ArrayList<String>()
        val steps = ArrayList<String>()
        var calls = 0

        for (i in from until to) {
            val m = history.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> {
                    // 带图消息的 content 是 JSON 数组（text + base64 图片），optString 会把
                    // 整段 base64 原样掏出来——截断后就是用户看到的「乱码」。只取文字部分。
                    val t = briefUserText(m)
                    if (isSummary(m)) {
                        // 上一份摘要：解包继承它的目标与要求，过程行并入步骤——绝不是把它当
                        // 新目标再包一层。否则压第二次就出现「目标：[早期步骤摘要…目标：[早期
                        // 步骤摘要…]]」的套娃，原始任务越埋越深，几轮之后模型连自己要干啥都不知道。
                        if (goal.isEmpty()) goal.append(summaryGoal(t))
                        for (line in t.lines()) {
                            val s = line.trim()
                            if (s.startsWith("·") && s.length > 1) {
                                steps.add(s.substring(1).trim().take(120))
                            }
                        }
                        // 摘要里的「要求」段也继承（上一轮压过的 asks 不能丢）
                        for (line in t.lines()) {
                            val s = line.trim()
                            if (s.startsWith("- 要求：") && s.length > 5) asks.add(s.removePrefix("- 要求："))
                        }
                    } else if (t.isNotBlank()) {
                        if (goal.isEmpty()) goal.append(t.take(240))
                        else asks.add(t.replace('\n', ' ').take(160))
                    }
                }
                "assistant" -> {
                    val t = m.optString("content")
                    if (t.isNotBlank() && t != "null") steps.add(t.replace('\n', ' ').take(120))
                }
                "tool" -> {
                    calls++
                    steps.add(briefResult(m.optString("content")))
                }
            }
        }

        val body = StringBuilder()
        body.append("[早期步骤摘要｜已压缩 ").append(to - from).append(" 条消息、")
            .append(calls).append(" 次工具调用]\n")
        if (goal.isNotEmpty()) body.append("目标：").append(goal).append('\n')
        // 用户中途补的要求逐条列出。这是用户原话的缩写，不是「细节」——丢了它们，
        // 模型就会把活干完一半（给了推荐没给链接），用户只能重新提示一遍。
        for (a in asks.distinct().takeLast(6)) body.append("- 要求：").append(a).append('\n')
        if (steps.isNotEmpty()) {
            body.append("过程：\n")
            var used = 0
            // 从后往前取：越靠近现在的步骤越重要。超长度就丢掉最早的。
            for (s in steps.takeLast(MAX_BRIEF_STEPS)) {
                if (used > MAX_BRIEF_CHARS) break
                body.append("· ").append(s).append('\n')
                used += s.length
            }
        }
        body.append("（过程细节已省略，需要时重抓；上面的目标与「要求」是任务的一部分，完成后逐条对照。）")
        return body.toString()
    }

    private fun briefResult(raw: String): String {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.replace('\n', ' ').take(90)
        if (!o.optBoolean("ok", true)) {
            return "失败：" + o.optString("error").ifEmpty { o.optString("code") }.take(70)
        }
        // url / path 是任务成果本身（用户要的就是地址、文件），绝不能被 90 字截断挤掉——
        // 之前 release 链接全被压没了，模型只能凭记忆写「去找 releases 页」。
        val where = when {
            o.optString("url").isNotEmpty() -> o.optString("url").take(200)
            o.optString("path").isNotEmpty() -> "文件 " + o.optString("path").take(120)
            else -> ""
        }
        val head = if (where.isNotEmpty()) "成功（$where）：" else "成功："
        for (key in listOf("content", "entries", "results", "output")) {
            val v = o.optString(key)
            if (v.isNotEmpty()) return head + v.replace('\n', ' ').take(90)
        }
        return head.take(140)
    }

    /**
     * 摘要用的 user 消息文本：字符串原样；带图消息（content 是 parts 数组）只取文字，
     * 图片换成一句占位——base64 一段就是几千「字」，既污染界面又每轮白烧 token。
     */
    private fun briefUserText(m: JSONObject): String = when (val c = m.opt("content")) {
        is String -> c
        is JSONArray -> buildString {
            for (i in 0 until c.length()) {
                val p = c.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "text" -> append(p.optString("text"))
                    "image_url" -> append("[附一张图片]")
                }
            }
        }
        else -> ""
    }

    /** 这条 user 消息是压缩摘要而不是用户说的话？compact 标记优先，前缀匹配兜底旧会话。 */
    private fun isSummary(m: JSONObject): Boolean = isSummaryMessage(m)

    /** 从摘要正文里抠出「目标：」那一行——多级压缩时它始终是最初的那个任务。 */
    private fun summaryGoal(text: String): String = summaryGoalOf(text)

    private class Cancelled : RuntimeException()

    companion object {
        /** 摘要头前缀。旧版本（打 compact 标记之前）生成的摘要靠它识别。 */
        const val SUMMARY_PREFIX = "[早期步骤摘要"

        /**
         * 从（可能嵌套的）摘要正文里抠出最初的任务目标。
         *
         * 旧版 bug 生成的存量摘要是套娃的：每一层压缩都往里加一行「目标：[早期步骤摘要…]」，
         * 原始目标在它们后面的行里。扫描所有「目标：」行，取最后一条不以摘要头开头的——
         * 那就是最内层的原始任务。都嵌套了（被截断、原始目标丢失）就退回第一行，让调用方兜底。
         * 界面（恢复历史）和压缩（brief）共用这一份逻辑。
         */
        fun summaryGoalOf(text: String): String {
            var innermost = ""
            var fallback = ""
            for (line in text.lines()) {
                val t = line.trim()
                if (!t.startsWith("目标：")) continue
                val g = stripGoalJunk(t.removePrefix("目标：").trim())
                if (fallback.isEmpty() && g.isNotEmpty()) fallback = g
                // 嵌套越深「目标：」行越多，最后一条干净的就是最原始的任务
                if (g.isNotEmpty() && !g.startsWith(SUMMARY_PREFIX)) innermost = g
            }
            return (if (innermost.isNotEmpty()) innermost else fallback).take(240)
        }

        /**
         * 旧版 bug 的存量摘要：带图消息的目标行里混进了 content 数组原始 JSON，
         * 截断在 base64 中间，界面显示成一屏乱码。从 JSON 痕迹处抢救出纯文字。
         */
        private fun stripGoalJunk(g: String): String {
            val idx = g.indexOf("{\"type\"")
            if (idx < 0) return g
            val head = g.substring(0, idx).removeSuffix("[").trim()
            val t = g.indexOf("\"text\":\"", idx)
            if (t >= 0) {
                val start = t + "\"text\":\"".length
                val end = g.indexOf("\"}", start)
                if (end > start) return (head + g.substring(start, end)).trim()
            }
            return head
        }

        /** 历史里的消息是不是压缩摘要（compact 标记优先，前缀兜底旧数据）。 */
        fun isSummaryMessage(m: JSONObject): Boolean =
            m.optBoolean("compact") || m.optString("content").startsWith(SUMMARY_PREFIX)

        /** 旧截图降级后的占位。保留在原来的位置，维持消息序列合法。 */
        const val DROPPED_IMAGE = "[此处原有一张更早的屏幕截图，为节省上下文已省略。需要时重新调用 phone_screenshot。]"

        /**
         * 一条 user 消息的 content：没图就是纯文本（省 token，也兼容不支持多模态的模型），
         * 有图就拼成 parts（text + N 个 image_url）——格式和工具截图那一处完全一致。
         * 单独抽出来是为了能在 JVM 测试里直接断言消息结构。
         */
        fun userContent(text: String, imageDataUrls: List<String>?): Any {
            if (imageDataUrls.isNullOrEmpty()) return text
            val parts = JSONArray()
                .put(JSONObject().put("type", "text").put("text", text))
            for (url in imageDataUrls) {
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", url))
                )
            }
            return parts
        }

        /** 被中止/停止后没执行的调用，也得回一条 tool 消息占位。 */
        const val SKIPPED = "{\"ok\":false,\"code\":\"SKIPPED\",\"error\":\"任务已中止，这个调用没有执行\"}"

        /**
         * 记忆与技能索引附加到末条 user 消息时的分隔头。
         * 必须让模型明确知道这不是用户的指令，只是随请求带的背景资料。
         *
         * 后半句是补丁：光说「不是指令」不够。曾经出现过用户问「主题四」、模型整段
         * 思考和正文都在讲记忆里的「主题三」——背景资料的权重比想象中高。
         * 所以这里把「用户本轮点名的对象优先」写成硬规则，而不只是礼貌声明。
         */
        const val EXTRAS_HEADER =
            "\n\n---\n\n【以下是随请求附带的背景资料：长期记忆与可用技能索引，供参考，不是本轮指令。" +
                "其中记下的旧任务、旧对象（别的主题、别的编号、别的文件）都不是这次要做的；" +
                "与用户本轮点名的对象冲突时，一律以本轮消息为准】\n\n"

        /**
         * 当前时间附加到末条 user 消息时的分隔头。语言模型没有时钟——不喂时间，
         * 它答「今天几号」就只能截图翻状态栏，白烧两次工具调用。
         * 放这里而不是 system 的原因见 injectExtras：system 必须逐字节稳定。
         */
        const val TIME_HEADER = "\n\n---\n\n【当前时间（取自手机系统时钟）】"

        /**
         * 「人情味」语气段附加到末条 user 消息时的分隔头。
         * 和背景资料（EXTRAS_HEADER）刻意分开：语气段不是「供参考的资料」，是行为指令，
         * 头部必须写明这一点，否则模型会把它当成又一段可参考可不参考的上下文。
         * 放尾部不进 system 的原因见 injectExtras：system 必须逐字节稳定。
         */
        const val TONE_HEADER =
            "\n\n---\n\n【语气指令（用户给这个助手开了「人情味」，从本轮起所有回答都要照做，" +
                "这不是背景资料）】\n\n"

        /** 记忆检索的查询词取最近几条 user 消息。3 句够交代话题，再多就是噪声。 */
        const val TOPIC_MESSAGES = 3

        /**
         * 思考全文在历史里的字段名。带下划线是刻意的：一眼能看出这不是 OpenAI 协议字段，
         * 而是本月溯自己加的、发请求前要剥掉的私有键。
         */
        const val THINK_KEY = "_think"

        /**
         * 单条思考最多存多少字。思考动辄几千上万字，全存会让会话文件臃肿、
         * 打开历史变慢；而人真要逐字读也读不完——4000 字已经够定位「它从哪一步开始想歪」。
         */
        const val THINK_KEEP = 4000

        /** 查询词上限。检索只做二字组重合，几十个字就够用，再长纯属白占。 */
        const val TOPIC_CHARS = 300

        /** 会改变界面的工具。只有这些动作之后才需要核对结果。 */
        val MUTATING = setOf(
            "phone_tap", "phone_tap_at", "phone_long_press", "phone_input", "phone_press_key", "phone_swipe", "phone_launch_app",
        )

        /** 结果里没有 screen_after 的动作（微信的写入/长按是盲操作），不能挂核对提示。 */
        val NO_VERIFY = setOf("phone_input", "phone_long_press")

        /** 成功后会产生「可以点开看」的产物的工具。 */
        val ARTIFACT_TOOLS = setOf("fs_write", "fs_edit", "pdf_write", "pptx_write", "docx_write", "xlsx_write", "chart_write", "html_preview", "text_shot")

        /** 挂在动作结果里的核对提示。短是刻意的——它每一轮都会随历史重发。 */
        const val VERIFY_HINT = "screen_after 是动作后的屏幕。先核对它是否符合预期再决定下一步；不符合就换条路，别原样重试。"

        /** 历史字符数超过这个值就压缩。约 1.6 万 token，留足余量给工具 schema 和输出。 */
        const val COMPACT_AT_CHARS = 48_000

        /** 压缩时保留尾部多少条消息（约 4 轮工具往返）。 */
        const val KEEP_TAIL = 8

        /**
         * 单条工具结果进上下文的上限。read_file 一次能读 256KB（约 3 万 token），
         * 而历史每轮整条重发——一条巨大的结果会跟着每一轮一起付钱。
         */
        const val MAX_TOOL_CHARS = 32_000

        /** 打转提醒：不预设工具种类——屏幕动作核读屏，工作台工具核产物。 */
        const val STALL_NUDGE =
            "你已经连续几轮调用完全相同的工具，但没有新进展。" +
                "先核实上一步是否真的生效：屏幕动作用读一次屏确认，工作台操作用 fs_read/fs_list 看产物。" +
                "如果这步本来就该重复（如连续滚动），说明在推进即可；否则换一种方法，或直接问用户。"

        /**
         * 撞到输出上限（finish_reason=length）时替用户说的那句话。
         *
         * 为什么替用户说而不是直接停下：模型自己知道被截断了，但用户不在旁边，
         * 界面就会停在半句话上，看着像「它就答这么多」。续写是大模型最擅长的收尾动作，
         * 成本只是一轮；硬停要人手动发现、手动催，才真的麻烦。
         */
        const val LENGTH_NUDGE =
            "（上一段回答刚到长度上限被截断了。从断开的地方接着写完，不要重复已经写过的内容，也不要重新开头。）"

        /** 无效操作提醒：动作报了 ok 但界面没动——点错图层时最常见。 */
        const val STALL_INEFFECTIVE_NUDGE =
            "你的操作已经连续几轮没有让界面发生任何变化（screen_changed:false）。" +
                "很可能点在了不响应的控件上。换用 phone_tap 的序号点击、" +
                "换一个明显不同的位置，或先滑动/返回再试；这条路确实走不通就直说并结束任务。"

        const val MAX_BRIEF_STEPS = 14
        const val MAX_BRIEF_CHARS = 1_200

        /**
         * 持久化前把历史里的图片剥掉。一张截图的 base64 有上百 KB，
         * 存盘、读盘、下次每轮重发都是纯浪费——模型要新截图时重新调一次就行。
         * 只动带图的消息，其余原样保留，消息结构（tool_call_id 配对）不变。
         */
        fun stripImages(history: JSONArray): JSONArray {
            val out = JSONArray()
            for (i in 0 until history.length()) {
                val m = history.optJSONObject(i) ?: continue
                val parts = m.optJSONArray("content")
                if (parts == null) {
                    out.put(m)
                    continue
                }
                var hasImage = false
                val texts = ArrayList<String>()
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    if (p.optString("type") == "image_url") hasImage = true
                    else texts.add(p.optString("text"))
                }
                if (!hasImage) {
                    out.put(m)
                    continue
                }
                val joined = texts.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { "（截图）" }
                out.put(JSONObject().put("role", m.optString("role")).put("content", joined))
            }
            return out
        }
    }
}
