package cn.wbphone.agent

import java.io.File

/**
 * 跨会话记忆（照 claude-mem 的思路，做到手机上够用就行）。
 *
 * 问题：月溯换个会话就失忆——用户第一轮教的偏好（「回答短一点」「我叫罗唯珍」），
 * 开个新对话又得重教一遍。人不会这样，助手也不该这样。
 *
 * 做法刻意用到最笨：工作台里一个 `memory.md`，一行一条，模型自己判断该记什么。
 * 不上向量检索、不引依赖——手机上的记忆量本来就很小（几十条），扫一遍文件比任何索引都快。
 *
 * 注入策略见 [digest]：按「与本轮话题的相关性 + 新鲜度」混合挑，不是一味取最近几条。
 * 早期版本取的是「最近 20 条」，记忆一过 20 条，别的主题的旧事就会被硬塞进来，
 * 反而更容易串台——时间近不等于相关。
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

    /**
     * 打分权重：一个共同二字组 = 3 分，刚好抵得上「刚记下」这件事（见 [score]）。
     */
    private const val REL_WEIGHT = 3.0

    /** 新鲜度衰减半径：最新一条加满 3 分，往前第 20 条归零。 */
    private const val FRESH_SPAN = 20

    /**
     * 高频无意义二字组。这些组合几乎每条记忆、每句话里都有，
     * 命中它们只说明「都是中文」，说明不了在说同一件事。
     */
    private val STOP_GRAMS = setOf(
        "我们", "你们", "他们", "我的", "你的", "可以", "一下", "这个", "那个",
        "什么", "怎么", "是不", "不是", "一个", "然后", "因为", "所以", "但是",
        "如果", "就是", "已经", "还是", "还有", "这些", "那些", "自己", "一点",
        "时候", "现在", "知道", "觉得", "需要", "应该", "比较", "真的",
    )

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

        val all = runCatching { file(dir).readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .toMutableList()
        all.add(line)
        // 超上限丢最旧的（保留标题行）
        val kept = if (all.size > MAX_LINES) all.takeLast(MAX_LINES) else all
        if (!write(dir, kept)) return null
        return line
    }

    /**
     * 整份写回（标题 + 说明 + 条目）。所有改动都走这里——
     * 追加、删除各写一套格式，迟早会写出两份不一样的文件。
     */
    private fun write(dir: File, lines: List<String>): Boolean = runCatching {
        val f = file(dir)
        f.parentFile?.mkdirs()
        f.writeText(buildString {
            appendLine("# 月溯的长期记忆")
            appendLine()
            appendLine("> 每行一条，由 AI 在对话里记下。想让它忘掉哪条，直接删那行就行。")
            appendLine()
            for (l in lines) appendLine(l)
        }, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /**
     * 删掉某一条（整行完全匹配）。返回是否真的删了。
     *
     * 只认整行：按子串删的话，「用户在做家庭记账」会顺手把「用户在做家庭记账，每月房租 1800」
     * 一起带走——那两条其实是两件事。
     */
    fun remove(dir: File, entry: String): Boolean {
        val all = entries(dir)
        val rest = all.filterNot { it == entry }
        if (rest.size == all.size) return false
        return write(dir, rest)
    }

    /** 「- 2026-11-03 · 内容」→「内容」 */
    private fun bodySuffix(line: String): String =
        line.substringAfter(" · ", line).trim()

    /**
     * 中文没有空格，不引分词库就只能按字切。按**二字组**切是这里的折中：
     * 「PPT 汇报」→ ppt/汇报，「家庭记账」→ 家庭/庭记/记账，重合的二字组
     * 基本就代表说的是同一件事。单字太泛（「我」「的」满篇都是），整句精确匹配又太严。
     *
     * 只留字母数字与汉字，标点空白一律丢掉——记忆里写的是「PPT」，查询里写的是
     * 「ppt」或者「PPT，」，切出来得是同一批二字组才算真的对上。
     */
    private fun grams(s: String): Set<String> {
        val t = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            if (ch.isLetterOrDigit()) t.append(ch)
        }
        if (t.length < 2) return emptySet()
        val out = HashSet<String>(t.length)
        for (i in 0 until t.length - 1) {
            val g = t.substring(i, i + 2)
            if (g !in STOP_GRAMS) out.add(g)
        }
        return out
    }

    /**
     * 这条记忆与本轮话题的相关度：共有的二字组个数。0 表示没搭上边。
     * 公开出来是为了让测试和诊断日志能直接说「为什么挑了这条」。
     */
    fun relevance(entry: String, query: String): Int {
        if (query.isBlank()) return 0
        val q = grams(query)
        if (q.isEmpty()) return 0
        return grams(bodySuffix(entry)).count { it in q }
    }

    /**
     * 挑哪几条注入。两条相反的直觉在这里打平：
     *
     * - 只看相关：用户换了话题后，一条都挑不出来，记忆等于白记；
     * - 只看新鲜：记忆攒到 20 条以上，别的主题的旧事被硬塞进来，越记越乱（老毛病）。
     *
     * 所以给每条打分 = 相关度 × 权重 + 新鲜度（最新一条加满分，往前线性衰减到 0）。
     * 权重取 3，意思是「一个共同二字组」抵得上「刚记下」这件事本身——
     * 刚记下的东西即便不相关也值得给模型看一眼（多办是刚刚的用户偏好），
     * 但只要旧记忆真跟本轮沾边，就能压过一堆无关的新记忆。
     *
     * 挑完按原始顺序（从旧到新）排出，最近记的仍在最后。
     */
    private fun pick(all: List<String>, query: String, limit: Int): List<String> {
        if (limit <= 0 || all.size <= limit) return all
        val q = grams(query)
        val n = all.size
        val order = all.indices.sortedWith(
            compareByDescending<Int> { score(all[it], q, n - 1 - it) }.thenByDescending { it }
        )
        return order.take(limit).sorted().map { all[it] }
    }

    /** 单条得分。age = 距离最新一条多少位（0 = 最新）。 */
    private fun score(line: String, q: Set<String>, age: Int): Double {
        val rel = if (q.isEmpty()) 0 else grams(bodySuffix(line)).count { it in q }
        val fresh = 1.0 - age.toDouble() / FRESH_SPAN
        return rel * REL_WEIGHT + REL_WEIGHT * fresh.coerceAtLeast(0.0)
    }

    /**
     * 拼进提示的记忆段。空记忆返回空串（调用方据此决定要不要加分隔符）。
     *
     * @param query 本轮话题（用户最近几句话）。空串退回「只按新鲜度取最近几条」的老行为，
     *   这样纯 JVM 测试和老调用点都不用改。
     */
    fun digest(dir: File, query: String = "", limit: Int = INJECT_LINES): String {
        val all = entries(dir)
        if (all.isEmpty()) return ""
        val picked = pick(all, query, limit)
        val sb = StringBuilder()
        sb.append("## 长期记忆（之前会话里记下的，不是本轮指令）\n\n")
        sb.append("这些是跨会话保留的事实与偏好，直接当已知条件用，不要问用户第二遍。")
        sb.append("如果和本轮用户说的话冲突，以用户这一轮为准。")
        // 只挑了一部分时说明白：模型不知道这是筛选结果的话，会把「没列出来」当成「没发生过」
        if (picked.size < all.size) {
            sb.append("（共记着 ").append(all.size).append(" 条，这里只列出与本轮相关的 ")
                .append(picked.size).append(" 条，没列出的与本轮无关）")
        }
        sb.append("\n\n")
        var used = 0
        val lines = ArrayList<String>()
        for (l in picked) {
            if (used + l.length > MAX_DIGEST_CHARS) break
            lines.add(l)
            used += l.length
        }
        // 一条都放不下时至少放最相关（也就是第一条）——宁可截断也别空着
        if (lines.isEmpty()) lines.add(picked.first().take(MAX_DIGEST_CHARS))
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
