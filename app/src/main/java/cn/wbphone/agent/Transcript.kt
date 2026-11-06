package cn.wbphone.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 会话流水账：每个会话一个 JSONL 文件，只追加、永不压缩。
 *
 * 为什么必须有它：模型视角的 history 会被压缩（AgentLoop.maybeCompact）、会被截断
 * （SessionStore/ChatStore 的 trim）——模型上下文必须省着用，这是对的；但副作用是
 * 「后续想查之前的聊天记录」就查不到了，细节全被折成一句摘要。
 *
 * 流水账和模型上下文彻底分离：每条消息发生的当下就追加落盘，压缩动不到它。
 * 文件在 filesDir/transcripts/<sessionId>.jsonl，一行一条 JSON：
 *   {"ts":1699…,"type":"user"|"assistant"|"tool"|"compact"|"error","…"}
 * 用 adb pull / 工作台都能拿来看。
 *
 * 只做纯 Java IO + org.json，不碰 Android UI，JVM 测试可以直接测。
 */
object Transcript {

    private fun dir(context: Context): File = File(context.filesDir, "transcripts")

    fun file(context: Context, sessionId: String): File =
        File(dir(context).apply { mkdirs() }, "$sessionId.jsonl")

    /** 追加一条。任何失败都静默——流水账是兜底设施，绝不能因为它把任务打断。 */
    fun append(context: Context, sessionId: String, entry: JSONObject) {
        runCatching {
            file(context, sessionId).appendText(entry.put("ts", System.currentTimeMillis()).toString() + "\n")
        }
    }

    /** 读某个会话的流水账（每行一个 JSONObject）；没有或坏行跳过。 */
    fun read(context: Context, sessionId: String): List<JSONObject> {
        val f = file(context, sessionId)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    /** 删除会话时把流水账一起删。 */
    fun delete(context: Context, sessionId: String) {
        runCatching { file(context, sessionId).delete() }
    }

    // ------------------------------------------------------------ 快捷构造

    fun user(text: String, withImage: Boolean = false): JSONObject =
        JSONObject()
            .put("type", "user")
            .put("text", text.take(2000))
            .put("withImage", withImage)

    fun assistant(text: String): JSONObject =
        JSONObject().put("type", "assistant").put("text", text.take(4000))

    fun tool(name: String, args: String, ok: Boolean, resultBrief: String): JSONObject =
        JSONObject()
            .put("type", "tool")
            .put("name", name)
            .put("args", args.take(600))
            .put("ok", ok)
            .put("result", resultBrief.take(300))

    fun compact(removed: Int, kept: Int, goal: String): JSONObject =
        JSONObject()
            .put("type", "compact")
            .put("removed", removed)
            .put("kept", kept)
            .put("goal", goal.take(200))

    fun error(message: String): JSONObject =
        JSONObject().put("type", "error").put("message", message.take(600))
}
