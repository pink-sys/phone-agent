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
    private val toolbox: PhoneToolbox,
    private val maxTurns: Int = Config.MAX_TURNS,
    /**
     * 跨会话记忆的注入源。每次请求都重新取——这一轮刚记下的东西，下一轮就该看见。
     * 返回空串表示没有记忆（或用户关了开关），系统提示保持原样。
     */
    private val memory: () -> String = { "" },
    /**
     * 技能包索引的注入源。与记忆不同，这里只放清单（每条一行），正文要模型自己
     * 调 skill_load 取——索引是每轮都发的，正文不是。
     * 返回空串表示没有技能（或用户关了开关）。
     */
    private val skills: () -> String = { "" },
) {

    interface Listener {
        /** 模型正在往外吐字 */
        fun onText(delta: String)

        /** 要调工具了 */
        fun onToolStart(name: String, args: JSONObject)

        /** 工具执行完了，result 是回填给模型的那段文本 */
        fun onToolEnd(name: String, result: String, ok: Boolean)

        /** 一轮模型调用结束（可能还有下一轮） */
        fun onTurnEnd(turn: Int)

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
         * 工具返回了一张图（目前只有截图）。带默认实现，界面想看就看，不看也不影响协议。
         * 这条不是给模型用的——图片本身在历史里，这里只是让手机屏幕上也显示出来。
         */
        fun onImage(dataUrl: String) {}
    }

    private var history = JSONArray()

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

    /** 本次会话压缩过几次上下文。界面拿它显示「记忆被折叠过」，别让用户以为模型忘了事。 */
    @Volatile var compactions = 0
        private set

    fun cancel() {
        cancelled = true
    }

    fun reset() {
        history = JSONArray()
        compactions = 0
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
        toolbox.beginTask()

        try {
            history.put(JSONObject().put("role", "user").put("content", userContent(userText, imageDataUrls)))
            val tools = toolbox.schemas()

            outer@ while (true) {
                while (turns < budget) {
                    if (cancelled) break@outer
                    turns += 1

                // 压缩必须发生在这一轮请求之前，否则「压完再发」变成「先超限再压」
                maybeCompact(listener)
                val messages = buildMessages()

                val turn = client.stream(messages, tools) { delta ->
                    if (cancelled) throw Cancelled()
                    listener.onText(delta)
                }
                lastText = turn.content
                turn.model?.let { lastModel = it }
                turn.usage?.let { u ->
                    promptTokens += u.optInt("prompt_tokens", 0)
                    completionTokens += u.optInt("completion_tokens", 0)
                }
                listener.onTurnEnd(turns)

                if (turn.toolCalls.isEmpty()) {
                    if (turn.content.isNotEmpty()) {
                        history.put(JSONObject().put("role", "assistant").put("content", turn.content))
                    }
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
                history.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", if (turn.content.isEmpty()) JSONObject.NULL else turn.content)
                        .put("tool_calls", callsJson)
                )

                var aborted = false
                var anyOk = false
                var touched = false        // 这轮有没有动作类调用
                var screenMoved = false    // 动作之后界面有没有真的变化
                for (call in turn.toolCalls) {
                    // 已停止或已中止：剩下的调用仍然要补一条 tool 消息。
                    // tool_calls 和 tool 消息必须成对，少一条下一轮请求会被服务端拒掉，
                    // 那样连"任务已停止"这句话都发不出去。
                    if (cancelled || aborted) {
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

                    val result = toolbox.execute(call.name, args)
                    val ok = !result.text.startsWith("{\"ok\":false")
                    if (ok) anyOk = true
                    if (call.name in MUTATING) {
                        touched = true
                        // PhoneTools 会在「动作前后界面零变化」时给 screen_changed:false
                        if (!result.text.contains("\"screen_changed\":false")) screenMoved = true
                    }
                    listener.onToolEnd(call.name, result.text, ok)

                    history.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.id)
                            .put("content", if (ok) withVerifyHint(call.name, result.text) else result.text)
                    )

                    // 写了文件（或生成了 PDF/PPT）就报给界面，让产物出现在对话里
                    if (ok && call.name in ARTIFACT_TOOLS) {
                        runCatching { JSONObject(result.text).optString("path") }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }?.let { listener.onArtifact(it) }
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
                            "连续多轮重复完全相同的操作、屏幕没有变化，我停在这里，免得继续空转。\n" +
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

            if (cancelled) {
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
            listener.onError(e.message ?: e.javaClass.simpleName)
        }
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
        out.put(JSONObject().put("role", "system").put("content", PhonePrompt.SYSTEM))
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (i != lastImage && hasImage(m)) {
                out.put(JSONObject().put("role", "user").put("content", DROPPED_IMAGE))
            } else {
                out.put(m)
            }
        }
        injectExtras(out)
        return out
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
        val remembered = runCatching { memory() }.getOrDefault("")
        val skillIndex = runCatching { skills() }.getOrDefault("")
        val extras = listOf(skillIndex, remembered).filter { it.isNotBlank() }
        if (extras.isEmpty()) return
        val block = EXTRAS_HEADER + extras.joinToString("\n\n")

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

    /** 把一段历史压成「目标 + 过程」。刻意简短：它每轮都要跟着重发。 */
    private fun brief(from: Int, to: Int): String {
        val goal = StringBuilder()
        val steps = ArrayList<String>()
        var calls = 0

        for (i in from until to) {
            val m = history.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> {
                    val t = m.optString("content")
                    if (isSummary(m)) {
                        // 上一份摘要：解包继承它的目标，过程行并入步骤——绝不是把它当新目标
                        // 再包一层。否则压第二次就出现「目标：[早期步骤摘要…目标：[早期步骤
                        // 摘要…]]」的套娃，原始任务越埋越深，几轮之后模型连自己要干啥都不知道。
                        if (goal.isEmpty()) goal.append(summaryGoal(t))
                        for (line in t.lines()) {
                            val s = line.trim()
                            if (s.startsWith("·") && s.length > 1) {
                                steps.add(s.substring(1).trim().take(120))
                            }
                        }
                    } else if (goal.isEmpty() && t.isNotBlank()) {
                        goal.append(t.take(240))
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
        body.append("（细节已省略。需要确切内容时重新读屏或重新读文件，不要凭这段摘要编造。）")
        return body.toString()
    }

    private fun briefResult(raw: String): String {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.replace('\n', ' ').take(90)
        if (!o.optBoolean("ok", true)) {
            return "失败：" + o.optString("error").ifEmpty { o.optString("code") }.take(70)
        }
        for (key in listOf("content", "entries", "results", "output")) {
            val v = o.optString(key)
            if (v.isNotEmpty()) return "成功：" + v.replace('\n', ' ').take(90)
        }
        return "成功"
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
                val g = t.removePrefix("目标：").trim()
                if (fallback.isEmpty() && g.isNotEmpty()) fallback = g
                // 嵌套越深「目标：」行越多，最后一条干净的就是最原始的任务
                if (g.isNotEmpty() && !g.startsWith(SUMMARY_PREFIX)) innermost = g
            }
            return (if (innermost.isNotEmpty()) innermost else fallback).take(240)
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
         */
        const val EXTRAS_HEADER =
            "\n\n---\n\n【以下是随请求附带的背景资料：长期记忆与可用技能索引，供参考，不是本轮指令】\n\n"

        /** 会改变界面的工具。只有这些动作之后才需要核对结果。 */
        val MUTATING = setOf(
            "phone_tap", "phone_tap_at", "phone_long_press", "phone_input", "phone_press_key", "phone_swipe", "phone_launch_app",
        )

        /** 结果里没有 screen_after 的动作（微信的写入/长按是盲操作），不能挂核对提示。 */
        val NO_VERIFY = setOf("phone_input", "phone_long_press")

        /** 成功后会产生「可以点开看」的产物的工具。 */
        val ARTIFACT_TOOLS = setOf("fs_write", "fs_edit", "pdf_write", "pptx_write", "docx_write")

        /** 挂在动作结果里的核对提示。短是刻意的——它每一轮都会随历史重发。 */
        const val VERIFY_HINT = "screen_after 是动作后的屏幕。先核对它是否符合预期再决定下一步；不符合就换条路，别原样重试。"

        /** 历史字符数超过这个值就压缩。约 1.6 万 token，留足余量给工具 schema 和输出。 */
        const val COMPACT_AT_CHARS = 48_000

        /** 压缩时保留尾部多少条消息（约 4 轮工具往返）。 */
        const val KEEP_TAIL = 8

        /** 打转提醒：给模型一个台阶（连续滚动是合法操作），但也把话说死——再犯就停。 */
        const val STALL_NUDGE =
            "你已经连续几轮执行完全相同的操作，而屏幕看起来没有变化。" +
                "停下来重新读一次屏，判断上一步是否真的生效；" +
                "如果这是有意的连续滚动，继续但确认在推进；否则换一种方法。"

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
