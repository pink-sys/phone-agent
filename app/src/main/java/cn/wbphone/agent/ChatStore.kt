package cn.wbphone.agent

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 对话历史的磁盘存储。一个 JSON 文件，进程被杀也能接回上文。
 *
 * 存的是模型视角的 history（user/assistant/tool 消息），界面重建也从它来；
 * 截图 base64 在落盘前剥掉（[AgentLoop.stripImages]），否则一个文件几十 MB。
 */
object ChatStore {

    private fun file(context: Context): File = File(context.filesDir, "chat-history.json")

    /** 任务结束后调用。空历史等于清空。 */
    fun save(context: Context, history: JSONArray) {
        try {
            val cleaned = AgentLoop.stripImages(history)
            if (cleaned.length() == 0) {
                clear(context)
                return
            }
            file(context).writeText(trim(cleaned, MAX_MESSAGES).toString())
        } catch (e: Exception) {
            AgentHost.log("保存对话失败：${e.message}")
        }
    }

    /** 进程重启后读回上次的历史。没有或坏了都返回 null，当作没存过。 */
    fun load(context: Context): JSONArray? = try {
        val f = file(context)
        if (f.exists()) JSONArray(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * 太长的历史从头部截。截断点必须落在某条 user 消息上：
     * 任务永远以 user 开头，从任务边界切，assistant 与 tool 的配对就一定完整。
     */
    private fun trim(history: JSONArray, max: Int): JSONArray {
        if (history.length() <= max) return history
        var start = history.length() - max
        while (start < history.length()) {
            if (history.optJSONObject(start)?.optString("role") == "user") break
            start += 1
        }
        val out = JSONArray()
        for (i in start until history.length()) out.put(history.optJSONObject(i))
        return out
    }

    private const val MAX_MESSAGES = 240
}
