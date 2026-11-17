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
            - 抓不到就换来源；换过三次仍不行就收手，把「查到什么、缺什么」写清楚。
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
        onProgress: (String) -> Unit = {},
        parentCancelled: () -> Boolean = { false },
    ): ToolResult {
        val box = ReadOnlyToolbox(bench)
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
            externalCancel = parentCancelled,
        )

        var steps = 0
        var final = ""
        var failure = ""

        val listener = object : AgentLoop.Listener {
            override fun onText(delta: String) {
                // 正文由 onFinished 统一给（这里只累计步数，避免重复拼接）
            }

            override fun onToolStart(name: String, args: JSONObject) {
                steps += 1
                onProgress("子代理：${label(name)}（第 $steps 步）")
            }

            override fun onToolEnd(name: String, result: String, ok: Boolean) {
                if (!ok) onProgress("子代理：${label(name)} 失败，正在换路")
            }

            override fun onTurnEnd(turn: Int) {
                // 轮次不用自己记：loop 结束时会把它算进 onFinished 的统计。
            }

            override fun onFinished(finalText: String, turns: Int, toolCalls: Int) {
                final = finalText
            }

            override fun onError(message: String) {
                failure = message
            }
        }

        try {
            loop.run(goal, listener)
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
