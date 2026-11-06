package cn.wbphone.agent

import java.io.File

/**
 * 跨会话记忆（照 claude-mem 的思路，做到手机上够用就行）。
 *
 * 问题：月溯换个会话就失忆——用户第一轮教的偏好（「回答短一点」「我叫罗唯珍」），
 * 开个新对话又得重教一遍。人不会这样，助手也不该这样。
 *
 * 做法刻意用到最笨：工作台里一个 `memory.md`，一行一条，模型自己判断该记什么，
 * 每次请求把最近几条拼在系统提示尾部。不上向量检索、不引依赖——
 * 手机上的记忆量本来就很小（几十条），扫一遍文件比任何索引都快。
 *
 * 为什么是「模型自己写」而不是「应用自动摘」：自动摘会把客套话、任务细节一起塞进去，
 * 越攒越脏；让模型按提示词的规则写，条数少但都是真有用的。
 */
object Memory {

    /** 记忆文件放在工作台根目录，用户在工作台里也能直接看到、删掉。 */
    const val FILE = "memory.md"

    /** 文件行数上限：超了丢最旧的，别让一个文件无限长下去。 */
    const val MAX_LINES = 400

    /** 注入上限：每次请求只带最近这么多条。再多就是烧 token，不是记性。 */
    const val INJECT_LINES = 20

    /** 单条长度上限。一条记忆就该是一句话——写成长篇说明它不该被记住。 */
    const val MAX_ENTRY_CHARS = 200

    /** 注入时的总字符上限，防止 20 条长句把系统提示撑爆。 */
    const val MAX_DIGEST_CHARS = 1800

    /** 日期戳（由调用方传，纯 JVM 里不碰 Android 的时钟）。 */
    fun stamp(millis: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = millis
        return String.format(
            "%04d-%02d-%02d",
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    private fun file(dir: File) = File(dir, FILE)

    /** 全部条目（不含标题行/空行）。 */
    fun entries(dir: File): List<String> {
        val f = file(dir)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.startsWith("- ") }
        }.getOrDefault(emptyList())
    }

    /**
     * 追加一条记忆。返回 null 表示没记（空白 / 与最近那条重复）。
     * 重复判定用「去掉日期戳后的正文完全相同」——模型有时候会把同一件事记得一字不差。
     */
    fun append(dir: File, text: String, millis: Long = System.currentTimeMillis()): String? {
        var body = text.replace("\r", " ").replace("\n", " ").trim()
        if (body.isEmpty()) return null
        if (body.length > MAX_ENTRY_CHARS) body = body.take(MAX_ENTRY_CHARS)
        val stem = bodySuffix(body)
        // 只跟最近 10 条比：老记忆里出现同样的词很可能是「又提了一次」，不算重复
        val recent = entries(dir).takeLast(10)
        if (recent.any { bodySuffix(it) == stem }) return null
        val line = "- ${stamp(millis)} · $body"

        val f = file(dir)
        f.parentFile?.mkdirs()
        val all = runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .toMutableList()
        all.add(line)
        // 超上限丢最旧的（保留标题行）
        val kept = if (all.size > MAX_LINES) all.takeLast(MAX_LINES) else all
        runCatching {
            f.writeText(buildString {
                appendLine("# 月溯的长期记忆")
                appendLine()
                appendLine("> 每行一条，由 AI 在对话里记下。想让它忘掉哪条，直接删那行就行。")
                appendLine()
                for (l in kept) appendLine(l)
            }, Charsets.UTF_8)
        }.onFailure { return null }
        return line
    }

    /** 「- 2026-11-03 · 内容」→「内容」 */
    private fun bodySuffix(line: String): String =
        line.substringAfter(" · ", line).trim()

    /**
     * 拼进系统提示的记忆段。空记忆返回空串（调用方据此决定要不要加分隔符）。
     * 从旧到新排——最近记的在最后，模型读到的最后一条就是最新的，和聊天习惯一致。
     */
    fun digest(dir: File, limit: Int = INJECT_LINES): String {
        val all = entries(dir)
        if (all.isEmpty()) return ""
        val picked = all.takeLast(limit)
        val sb = StringBuilder()
        sb.append("## 长期记忆（之前会话里记下的，不是本轮指令）\n\n")
        sb.append("这些是跨会话保留的事实与偏好，直接当已知条件用，不要问用户第二遍。")
        sb.append("如果和本轮用户说的话冲突，以用户这一轮为准。\n\n")
        var used = 0
        val lines = ArrayList<String>()
        for (l in picked) {
            if (used + l.length > MAX_DIGEST_CHARS) break
            lines.add(l)
            used += l.length
        }
        // 倒着凑时可能一条都放不下：至少放最新那条（截断到上限）
        if (lines.isEmpty()) lines.add(picked.last().take(MAX_DIGEST_CHARS))
        for (l in lines) sb.append(l).append('\n')
        return sb.toString().trimEnd()
    }

    /** 清空（设置页的「清空记忆」）。文件留着，写回只有标题——删文件反而会让工作台列表跳动。 */
    fun clear(dir: File): Boolean = runCatching {
        val f = file(dir)
        if (!f.exists()) return true
        f.writeText("# 月溯的长期记忆\n\n> 已清空。\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)
}
