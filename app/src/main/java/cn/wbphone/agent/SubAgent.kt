package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 子代理的提示词。
 *
 * 和主提示词是两套东西，刻意不共用：主提示词里七成篇幅在讲怎么操作手机屏幕，
 * 而子代理碰不到屏幕——把它塞进去只会让模型分心，还每轮白付钱。
 * 这里只留三条真正影响结论质量的东西：证据要自带、结论要确切、抓取要并行。
 */
object SubPrompt {

    fun system(goal: String, focus: String): String = buildString {
        append("你是「月溯」从主对话派出的子代理，只做这一件事：\n")
        append(goal)
        append("\n\n")
        if (focus.isNotBlank()) {
            append("只要这些内容：\n")
            append(focus)
            append("\n\n")
        }
        append(
            """
            规则：
            - 你只有只读工具（web_search / web_fetch / fs_read / fs_list / fs_search）。不能操作手机，
              也不能写文件——需要落盘的内容写进结论里，主线去写。
            - 你看不到主对话的历史，主对话也看不到你的中间过程，它只收到你的结论。
              所以结论必须自带证据：具体地址、文件名、行号、原文关键句。不要写「见上文」「如前所述」。
            - 「可能有帮助」「可以参考一下」不算结论。要么给出确切答案，要么明说找不到、缺什么。
            - 相互独立的抓取合并到同一轮做完（一次给多个 tool_calls），不要一回合只抓一个。
            - 时间有限（约 4 分钟、10 轮）：从第 7 轮起别再开新页面，把手上的东西写成结论。
              查不全是常态，写清「哪些查到了、哪些没查到」比一直查到超时有用。
            - 抓不到就换来源。同一目标连续失败 3 次就是来源不可达，立即收手写结论
              （查到什么、缺什么）——引擎在第 4 次连续失败时会直接收掉你，
              别把时间烧在重复超时上。
            - 目标是海外官网（openai.com、anthropic.com、ai.google.dev、groq.com 等）时，
              国内网络直连大概率超时。先用 web_search 找可达的中文资料页/官方镜像/文档站，
              再抓搜出来的链接；同一个域名 web_fetch 失败 2 次就别再碰它。
              海外任务宁可最后交「资料站口径 + 已注明来源」，也别把预算烧在超时上。
            - 不要复述任务、不要寒暄、不要解释你准备怎么做。直接给结论。
            """.trimIndent()
        )
    }
}

/**
 * 只读工具箱：子代理能碰的全部能力。
 *
 * 为什么单独一个类而不是给 [PhoneToolbox] 加个开关：
 *  - 子代理最怕的不是「不够强」而是「越界」——它看不到主对话，却能写文件、能点屏幕，
 *    出了事没人能追溯。白名单是硬的：schema 里没有，execute 里再挡一次。
 *  - 这一层是纯 JVM 的，测试里可以直接断言「write 被拒」「没有 phone_*」，
 *    不用起真机。
 */
class ReadOnlyToolbox(
    private val bench: Workbench,
    private val allow: Set<String> = ALLOWED,
) : Toolbox {

    override fun schemas(): JSONArray {
        val all = bench.schemas()
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val fn = all.optJSONObject(i)?.optJSONObject("function") ?: continue
            if (fn.optString("name") in allow) out.put(all.get(i))
        }
        return out
    }

    override fun execute(
        name: String,
        args: JSONObject,
        owner: String,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): ToolResult {
        // 白名单在这里再挡一次：模型有可能凭记忆调一个 schema 里已经不在的工具。
        if (name !in allow) {
            return ToolResult(
                "{\"ok\":false,\"code\":\"SUB_READONLY\",\"error\":\"子代理只能用只读工具：${allow.joinToString(", ")}。$name 不属于它，请把要写的内容放进结论里交给主线。\"}"
            )
        }
        return ToolResult(bench.execute(name, args))
    }

    fun execute(name: String, args: JSONObject): ToolResult =
        execute(name, args, "", {}, { false })

    override fun beginTask(owner: String) {
        // 子代理不碰手机、不写文件，没有需要清的状态。
    }

    override fun isDelivered(path: String): Boolean = bench.isDelivered(path)

    companion object {
        /** 子代理能用的工具：全是读操作，且都不碰屏幕。 */
        val ALLOWED = setOf("fs_read", "fs_list", "fs_search", "web_fetch", "web_search")
    }
}

/**
 * 子代理：把一件「读得多、产出少」的活派出去，只把结论带回来。
 *
 * 它不是一个更聪明的模型，只是**一段被隔开执行的上下文**。价值全在隔离上：
 * 一次 web_search 能抓回三千字，这些字如果进了主线，之后每一轮请求都要重发一遍；
 * 而它对最终决策几乎全是噪声。子代理把这些噪声关在自己的历史里，任务结束、
 * 历史丢弃，主线只付一份结论的钱和注意力。
 *
 * 这也是「压缩」做不到的事：压缩是事后清理，噪声已经烧过一遍了。
 */
object SubAgent {

    /**
     * 子代理自己的轮数上限。比主线小得多：一个调研任务十轮还没收敛，
     * 通常说明目标写得太糊，让它继续转只是烧钱。
     */
    const val MAX_TURNS = 10

    /**
     * 连续工具失败熔断线。真机实录：GitHub 连不上时，子代理对同一批地址换了
     * 4 种姿势连抓 39 步（每步一个 15 秒超时），用户干等 14 分钟。
     * 「换三次就收手」写在提示词里挡不住这种循环——失败连击到线，引擎直接收，
     * 不再等模型自觉。
     */
    const val FAIL_STREAK_LIMIT = 4

    /**
     * 子代理的墙钟预算。
     *
     * 轮数和步数都管不住真实的等待：一次 web_fetch 最坏 15 秒连接 + 20 秒读取，
     * 十轮下来就是十几分钟（真机实录：3 个子代理并排跑了 19 分钟，用户以为死机）。
     * 到点就收工，把已经查到的部分带回去——比让用户盯着转圈强。
     */
    const val MAX_WALL_MS = 240_000L

    /**
     * 结论回填给主线的字数上限。
     *
     * 给到 8000 是因为结论里常常要带一批地址/文件名——这正是最不能被截断的东西
     * （上一版压缩摘要把 release 链接全截掉，模型只能凭印象写「去找 releases 页」）。
     * 上限存在的意义只是防止子代理把整个抓回来的页面原样贴回来。
     */
    const val RESULT_CHARS = 8_000

    /**
     * 跑一次子代理。阻塞调用，必须在后台线程执行（它内部会连发多轮请求）。
     *
     * @param parentCancelled 主线被用户点了停止。子代理必须跟着停——
     *   不然用户按了停止，屏幕上还在花钱跑另一个循环，这是最招人恨的一种「停不下来」。
     */
    fun run(
        client: CloudClient,
        bench: Workbench,
        goal: String,
        focus: String = "",
        maxTurns: Int = MAX_TURNS,
        // 墙钟预算。默认 4 分钟；测试里注入几十毫秒来验证「到点收工」这条路。
        maxWallMs: Long = MAX_WALL_MS,
        onProgress: (String) -> Unit = {},
        parentCancelled: () -> Boolean = { false },
    ): ToolResult {
        val box = ReadOnlyToolbox(bench)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + maxWallMs

        fun elapsed(): String {
            val s = (System.currentTimeMillis() - startedAt) / 1000
            return if (s < 60) "${s} 秒" else "${s / 60} 分 ${s % 60} 秒"
        }

        // 连续失败计数：ok 归零，失败 +1。到 [FAIL_STREAK_LIMIT] 就拉闸——
        // 这是对「换着 URL 试到天黑」的引擎侧保险，提示词管不住的循环由它管。
        var failStreak = 0
        // 拉闸那一刻的连击数要单独记住：之后若有一次工具成功，failStreak 会归零，
        // 报错却还写着「连续 0 次失败」（真机实录），前后矛盾得让人不敢信。
        var stuckStreak = 0
        var lastFailTool = ""
        var gaveUp = false
        var timedOut = false

        val loop = AgentLoop(
            client = client,
            toolbox = box,
            maxTurns = maxTurns,
            // 子代理不注入记忆和技能：它的上下文只有一个任务，
            // 别的话题的记忆进来只会把结论带偏（「问主题四却答主题三」就是这么来的）。
            memory = { "" },
            skills = { "" },
            // 空 owner：子代理不参与手机所有权（它根本没有手机工具）
            owner = "",
            systemPrompt = SubPrompt.system(goal, focus),
            externalCancel = {
                parentCancelled() || gaveUp ||
                    if (System.currentTimeMillis() > deadline) { timedOut = true; true } else false
            },
        )

        var steps = 0
        var final = ""
        var failure = ""
        // 超时收工时要带点东西回去：模型在工具之间说的话就是它查到的线索，
        // 留在尾巴上（只留最后 1500 字，前面多半是「我准备…」这种铺垫）
        val narration = StringBuilder()

        val listener = object : AgentLoop.Listener {
            override fun onText(delta: String) {
                // 正文由 onFinished 统一给（这里只留尾巴，供超时兜底）
                narration.append(delta)
                if (narration.length > 3000) narration.delete(0, narration.length - 1500)
            }

            override fun onToolStart(name: String, args: JSONObject) {
                steps += 1
                onProgress("子代理：${label(name)}（第 $steps 步 · 已 ${elapsed()}）")
            }

            override fun onToolEnd(name: String, args: JSONObject, result: String, ok: Boolean) {
                if (ok) {
                    failStreak = 0
                    return
                }
                failStreak += 1
                lastFailTool = name
                if (failStreak >= FAIL_STREAK_LIMIT && !gaveUp) {
                    gaveUp = true
                    stuckStreak = failStreak
                    onProgress("子代理：连续 $failStreak 次失败，提前收工")
                } else {
                    onProgress("子代理：${label(name)} 失败，正在换路")
                }
            }

            override fun onTurnEnd(turn: Int) {
                // 每轮给一行心跳：用户看到的不是卡住，而是「还在跑、跑了多久」
                onProgress("子代理：第 $turn 轮，已 ${elapsed()}")
            }

            override fun onFinished(finalText: String, turns: Int, toolCalls: Int) {
                final = finalText
            }

            override fun onError(message: String) {
                failure = message
            }
        }

        try {
            // 子代理绝不自动续跑：它自己就有轮数上限，再续 2 次等于三倍时长，
            // 而「十轮还没收敛」本来就该收手（真机实录：10 轮被续成 30 轮，跑了 19 分钟）。
            loop.run(goal, listener, maxContinues = 0)
        } catch (e: Exception) {
            failure = Errors.humanize(e.message ?: e.javaClass.simpleName)
        }

        val prompt = loop.promptTokens
        val completion = loop.completionTokens

        // 被用户叫停：这不是「结论」，也不是「子代理不会做」。分开报，主线才知道
        // 该接着做还是换个方法——混成 SUB_INCOMPLETE 会让它以为目标太大。
        if (parentCancelled()) {
            return ToolResult(
                text = "{\"ok\":false,\"code\":\"SUB_CANCELLED\",\"error\":\"用户点了停止，子代理跟着停了；已经查到的东西没有带回\"}",
                promptTokens = prompt,
                completionTokens = completion,
            )
        }

        // 到点收工：和「目标太大」分开报——主线据此才知道该拆范围、自己接手，
        // 还是换个来源。混成 SUB_INCOMPLETE 它会以为再派一次就行。
        if (timedOut && !parentCancelled()) {
            val tail = narration.toString().trim()
            val out = JSONObject()
                .put("ok", false)
                .put("code", "SUB_TIMEOUT")
                .put(
                    "error",
                    "子代理跑满 ${maxWallMs / 1000} 秒（已 ${elapsed()}，$steps 步）还没收敛，先收工。" +
                        "别原样再派一遍：把范围拆小（一次只查 3–5 个对象），或者自己用工具做。"
                )
            if (tail.isNotEmpty()) out.put("partial", tail.take(1_200))
            return ToolResult(
                text = out.toString(),
                promptTokens = prompt,
                completionTokens = completion,
            )
        }

        // 引擎熔断收工：连续失败说明目标来源大概率不可达（站点连不上/全部超时）。
        // 把诊断写明白交给主线——它拿同一批地址原样再派一次，只会把超时再烧一遍。
        if (gaveUp) {
            val why = "子代理连续 $stuckStreak 次工具失败，被引擎收掉（最后一次是 $lastFailTool）。" +
                "目标来源大概率不可达：站点连不上或请求全部超时。不要原样重试同一批地址——" +
                "换别的来源或镜像，或把已经确认的信息直接告诉用户。"
            return ToolResult(
                text = JSONObject()
                    .put("ok", false)
                    .put("code", "SUB_STUCK")
                    .put("error", why)
                    .put("fail_streak", stuckStreak)
                    .toString(),
                promptTokens = prompt,
                completionTokens = completion,
            )
        }

        // 到轮数上限被收掉的那次，onFinished 里是一段「还没完成」的说明，不是结论。
        // 把它当答案交回去，主线会以为自己拿到了结果——那比失败更糟。
        if (final.isNotBlank() && !loop.lastCompleted) {
            val why = "子代理跑了 " + maxTurns + " 轮还没给出结论，可能是目标太大。" +
                "把它拆小一点再派，或者自己用工具做。"
            return ToolResult(
                text = JSONObject()
                    .put("ok", false)
                    .put("code", "SUB_INCOMPLETE")
                    .put("error", why)
                    .put("partial", final.take(1_200))
                    .toString(),
                promptTokens = prompt,
                completionTokens = completion,
            )
        }

        if (final.isBlank()) {
            val why = failure.ifBlank { if (parentCancelled()) "主任务已停止" else "子代理没有给出结论" }
            return ToolResult(
                text = "{\"ok\":false,\"code\":\"SUB_EMPTY\",\"error\":\"${Json.escape(why)}\"}",
                promptTokens = prompt,
                completionTokens = completion,
            )
        }

        val body = if (final.length <= RESULT_CHARS) final else final.take(RESULT_CHARS) +
            "\n…（子代理结论过长，此处只保留前 $RESULT_CHARS 字）"

        val out = JSONObject()
            .put("ok", true)
            .put("content", body)
            .put("steps", steps)
            .put(
                "note",
                "以上是子代理的结论。它的中间过程（抓了哪些页面、读了哪些文件）没有进你的上下文——" +
                    "要引用其中的地址/文件名直接用；需要细节就再派一次或自己抓一次，不要凭印象补充。"
            )
        return ToolResult(out.toString(), promptTokens = prompt, completionTokens = completion)
    }

    /** 进度里用的短名。子代理跑十几轮时，这行字是用户唯一能看出「它在干嘛」的东西。 */
    private fun label(name: String): String = when (name) {
        "web_search" -> "联网搜索"
        "web_fetch" -> "抓网页"
        "fs_read" -> "读文件"
        "fs_list" -> "列目录"
        "fs_search" -> "搜索内容"
        else -> name
    }
}
