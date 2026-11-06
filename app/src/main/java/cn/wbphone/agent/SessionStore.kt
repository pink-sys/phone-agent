package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多会话存储。
 *
 * 每个会话一个 JSON 文件（id.json），外面一层 index.json 记元信息——
 * 列表页不该为显示一个标题去解析几十个几十 KB 的历史文件。
 *
 * 存的是模型视角的 history，截图 base64 在落盘前剥掉（复用 [AgentLoop.stripImages]），
 * 否则一个会话几十 MB。核心逻辑（标题、截断、配对保护）不碰 Android API，
 * 在 JVM 上可以完整测试；Android 侧只是给它一个目录。
 */
class SessionStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    data class Meta(val id: String, val title: String, val updatedAt: Long, val count: Int)

    /** 日志出口。默认静默（JVM 测试里没有宿主）；Android 侧接到 AgentHost 的日志。 */
    var logger: ((String) -> Unit)? = null

    private fun log(msg: String) {
        val sink = logger
        if (sink != null) sink(msg) else println("session: $msg")
    }

    // ------------------------------------------------------------ 列表

    @Synchronized
    fun list(): List<Meta> {
        val index = dir.resolve("index.json")
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isEmpty() || !dir.resolve("$id.json").exists()) return@mapNotNull null
                Meta(id, o.optString("title", "未命名"), o.optLong("updatedAt"), o.optInt("count"))
            }.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------ 读写

    /**
     * 保存一个会话。history 为空等于删除——「新会话开了一下没说话」不值得留一个空文件。
     * 标题只在第一次给（从第一条 user 消息派生），之后保持稳定：
     * 用户是靠标题认会话的，标题每轮都变等于没有标题。
     */
    @Synchronized
    fun save(id: String, history: JSONArray, title: String? = null) {
        try {
            if (history.length() == 0) {
                delete(id)
                return
            }
            val cleaned = AgentLoop.stripImages(history)
            if (cleaned.length() == 0) {
                delete(id)
                return
            }
            val trimmed = trim(cleaned, MAX_MESSAGES)
            dir.resolve("$id.json").writeText(trimmed.toString())

            val index = dir.resolve("index.json")
            val arr = if (index.exists()) JSONArray(index.readText()) else JSONArray()
            val out = JSONArray()
            var existingTitle: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") == id) {
                    existingTitle = o.optString("title", "")
                } else {
                    out.put(o)
                }
            }
            val finalTitle = existingTitle?.takeIf { it.isNotEmpty() && it != "未命名" }
                ?: title?.takeIf { it.isNotBlank() }
                ?: titleOf(cleaned)
            val meta = JSONObject()
                .put("id", id)
                .put("title", finalTitle)
                .put("updatedAt", System.currentTimeMillis())
                .put("count", trimmed.length())
            out.put(meta)
            index.writeText(out.toString())
        } catch (e: Exception) {
            log("保存会话失败：${e.message}")
        }
    }

    /** 读回一个会话的完整历史。没有或损坏都返回 null。 */
    @Synchronized
    fun load(id: String): JSONArray? = try {
        val f = dir.resolve("$id.json")
        if (f.exists()) JSONArray(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun delete(id: String) {
        runCatching { dir.resolve("$id.json").delete() }
        runCatching {
            val index = dir.resolve("index.json")
            if (!index.exists()) return@runCatching
            val arr = JSONArray(index.readText())
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != id) out.put(o)
            }
            index.writeText(out.toString())
        }
    }

    // ------------------------------------------------------------ 纯逻辑（JVM 可测）

    companion object {

        private const val MAX_MESSAGES = 240

        /**
         * 会话标题：第一条 user 消息的第一行，截到 24 字。
         * user 消息可能是多模态（带截图），只取其中的文本段。
         */
        fun titleOf(history: JSONArray): String {
            for (i in 0 until history.length()) {
                val m = history.optJSONObject(i) ?: continue
                if (m.optString("role") != "user") continue
                val text = contentText(m.opt("content"))
                val line = text.lines().firstOrNull { it.isNotBlank() } ?: continue
                val t = line.trim()
                return if (t.length <= 24) t else t.take(24) + "…"
            }
            return "未命名"
        }

        /** content 可能是纯字符串，也可能是带图的多模态数组；只取文本部分。 */
        private fun contentText(content: Any?): String = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }
            else -> ""
        }

        /**
         * 分叉用的前缀：保留 [0..index]（含 index 这条消息）。
         *
         * 切点必须是完整的 assistant 消息——tool 消息永远紧跟它的 assistant，
         * 切在 assistant 上配对必然完整；切在 tool 上就会留下没人认领的结果。
         * 越界或类型不对返回 null：宁可不分叉，也不切出一条内容错乱的会话。
         */
        fun prefixUpTo(history: JSONArray, index: Int): JSONArray? {
            if (index < 0 || index >= history.length()) return null
            val m = history.optJSONObject(index) ?: return null
            if (m.optString("role") != "assistant") return null
            val out = JSONArray()
            for (i in 0..index) history.optJSONObject(i)?.let { out.put(it) }
            return out
        }

        /**
         * 太长的历史从头部截。
         *
         * 配对安全的切点只有一个约束：**不能落在 tool 消息上**——每条 tool 的 assistant
         * 一定紧邻在它前面，切点只要在非 tool 消息之前，配对就必然完整。
         * user 消息是最佳切点（任务边界），但不是唯一合法的：手机任务常常只有一条 user，
         * 尾窗里没有 user 时硬等就会截出空数组（测试逮住过的真 bug）。
         */
        fun trim(history: JSONArray, max: Int = MAX_MESSAGES): JSONArray {
            if (history.length() <= max) return history
            var start = history.length() - max
            while (start < history.length() &&
                history.optJSONObject(start)?.optString("role") == "tool"
            ) start += 1
            if (start >= history.length()) {
                // 尾窗全被 tool 占满（理论上的极端）：从尾部往回找第一条非 tool
                start = history.length() - 1
                while (start > 0 && history.optJSONObject(start)?.optString("role") == "tool") start -= 1
            }
            val out = JSONArray()
            for (i in start until history.length()) out.put(history.optJSONObject(i))
            return out
        }
    }
}
