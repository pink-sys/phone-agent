package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.net.URL

/**
 * 工作台：文件读写、内容检索、网页抓取。
 *
 * 这是「手机助手」和「能干活的 agent」之间那道坎。dsh-Go 靠一个 388MB 的 proot Ubuntu
 * 提供这些能力，我们不需要——文件、文本、搜索这三件事在 JVM 里都做得了，而且更快、更省电。
 *
 * 三条设计原则：
 *
 * 1. **一切落在沙箱里**。所有路径先 canonical 化再比对根目录前缀，`../` 逃逸、
 *    符号链接、绝对路径指向外部，全都拒绝。写坏东西的代价太高，这里不做宽容处理。
 *
 * 2. **每个结果都要能自证**。读文件带行号（模型改文件时要靠行号对齐），编辑回显改动
 *    附近的上下文（模型据此确认改对了），搜索回显命中行（而不是只说"找到 3 个"）。
 *    模型看不见结果就无法自我纠正，这是 agent 变笨的头号原因。
 *
 * 3. **截断必须明说**。任何超限都返回 `truncated:true` 和真实总量，让模型知道
 *    "你看的不是全部"，而不是拿着半截内容下结论。
 *
 * 纯 JVM，不引 Android API——这样这些工具能在 `tools/test_agent.py` 里跑真断言，
 * 而不是等到真机上才发现路径拼错。
 */
class Workbench(
    root: File,
    /** 取网页。测试里注入假实现，真机上走 HTTP。 */
    private val fetcher: (String, Int) -> FetchResult = { url, max -> httpGet(url, max) },
    /**
     * shell 是否启用。默认关——这是唯一一个能跑出沙箱的能力，得让用户自己点头。
     * 做成 lambda 而不是 Boolean：设置里改完立刻生效，不用重建 Workbench。
     */
    private val shellEnabled: () -> Boolean = { false },
    /**
     * shell 解释器的路径。真机上是 `/system/bin/sh`；测试在桌面上跑，得换成桌面上的 sh，
     * 否则这条代码路径永远测不到——而它恰恰是最需要测的一条。
     */
    private val shellPath: String = DEFAULT_SHELL,
    /**
     * PDF 生成引擎。纯 JVM 环境（桌面测试）没有 android.graphics.pdf，传 null；
     * 真机上 AgentHost 注入 [PdfDoc.Engine]。为 null 时 pdf_write 从 schema 里摘掉，
     * 模型不会看到一个必然失败的工具。
     */
    private val docEngine: DocEngine? = null,
) {

    /** PDF 引擎的接口。实现要 Android，声明留在这层是为了让 Workbench 保持纯 JVM。 */
    interface DocEngine {
        fun writePdf(out: File, title: String, blocks: JSONArray): String
    }

    val root: File = root.canonicalFile

    init {
        if (!this.root.exists()) this.root.mkdirs()
    }

    // ---------------------------------------------------------------- 分发

    fun execute(name: String, args: JSONObject): String = try {
        when (name) {
            "fs_list" -> list(args.optString("path", "."), args.optInt("depth", 1))
            "fs_read" -> read(args.optString("path", ""), args.optInt("from", 1), args.optInt("lines", DEFAULT_READ_LINES))
            "fs_write" -> write(args.optString("path", ""), args.optString("content", ""))
            "fs_edit" -> edit(
                args.optString("path", ""),
                args.optString("old", ""),
                args.optString("new", ""),
            )
            "fs_search" -> search(
                args.optString("pattern", ""),
                args.optString("path", "."),
                args.optString("glob", ""),
            )
            "web_fetch" -> fetch(args.optString("url", ""), args.optInt("max_chars", MAX_FETCH_CHARS))
            "pdf_write" -> pdfWrite(args)
            "pptx_write" -> pptxWrite(args)
            "docx_write" -> docxWrite(args)
            "memory_save" -> memorySave(args.optString("text", ""))
            "skill_load" -> skillLoad(args.optString("name", ""))
            "shell" -> shell(
                args.optString("command", ""),
                args.optString("cwd", "."),
                args.optInt("timeout_sec", DEFAULT_TIMEOUT_SEC),
            )
            else -> err("UNKNOWN_TOOL", "没有这个工具：$name")
        }
    } catch (e: Exception) {
        // 和手机工具保持一致：失败也返回文本，不抛异常。
        // 抛出去模型看不到原因，只会重试同样的调用。
        err(e.javaClass.simpleName, e.message ?: "执行失败")
    }

    /**
     * 当前该暴露给模型的工具定义。
     *
     * shell 没开启就自动摘掉——否则模型会调一个必然返回 SHELL_OFF 的工具，白烧一轮，
     * 还会误以为是自己参数写错了，反复重试。
     */
    fun schemas(): JSONArray {
        val all = JSONArray(SCHEMA_JSON)
        val skip = HashSet<String>()
        if (!shellEnabled()) skip.add("shell")
        if (docEngine == null) skip.add("pdf_write")
        if (skip.isEmpty()) return all
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val fn = all.getJSONObject(i).getJSONObject("function")
            if (fn.getString("name") !in skip) out.put(all.get(i))
        }
        return out
    }

    // ---------------------------------------------------------------- 列目录
    private fun list(path: String, depth: Int): String {
        val dir = resolve(path)
        if (!dir.exists()) return err("NOT_FOUND", "目录不存在：${rel(dir)}")
        if (!dir.isDirectory) return err("NOT_DIR", "${rel(dir)} 是文件，不是目录")

        val entries = JSONArray()
        var count = 0
        var truncated = false
        val max = depth.coerceIn(1, 3)

        fun walk(d: File, prefix: String, level: Int) {
            val kids = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (f in kids) {
                if (count >= MAX_LIST_ENTRIES) {
                    truncated = true
                    return
                }
                count++
                entries.put(
                    JSONObject()
                        .put("name", prefix + f.name)
                        .put("dir", f.isDirectory)
                        .put("size", if (f.isDirectory) 0 else f.length()),
                )
                // 只下钻两层，再多对模型没用，只是把上下文塞满
                if (f.isDirectory && level < max) walk(f, prefix + f.name + "/", level + 1)
            }
        }
        walk(dir, "", 1)

        return JSONObject()
            .put("ok", true)
            .put("path", rel(dir))
            .put("count", count)
            .put("truncated", truncated)
            .put("entries", entries)
            .toString()
    }

    // ---------------------------------------------------------------- 读文件

    private fun read(path: String, from: Int, lines: Int): String {
        if (path.isBlank()) return err("BAD_ARGS", "必须给 path")
        val f = resolve(path)
        if (!f.exists()) return err("NOT_FOUND", "文件不存在：${rel(f)}")
        if (f.isDirectory) return err("NOT_FILE", "${rel(f)} 是目录，用 fs_list 看")

        if (f.length() > MAX_READ_BYTES) {
            return err("TOO_BIG", "文件 ${f.length()} 字节，超过 ${MAX_READ_BYTES} 上限。用 fs_search 定位，或分段读")
        }

        val all = f.readLines()
        val start = (from.coerceAtLeast(1) - 1).coerceAtMost(all.size)
        val want = lines.coerceIn(1, MAX_READ_LINES)
        val end = (start + want).coerceAtMost(all.size)

        // 带行号：模型改文件时靠它对齐，比让它数空行可靠得多
        val body = StringBuilder()
        for (i in start until end) {
            body.append(i + 1).append('|').append(all[i]).append('\n')
        }

        return JSONObject()
            .put("ok", true)
            .put("path", rel(f))
            .put("total", all.size)
            .put("from", start + 1)
            .put("to", end)
            .put("truncated", end < all.size)
            .put("content", body.toString().trimEnd('\n'))
            .toString()
    }

    // ---------------------------------------------------------------- 写文件

    private fun write(path: String, content: String): String {
        if (path.isBlank()) return err("BAD_ARGS", "必须给 path")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            return err("TOO_BIG", "内容 ${bytes.size} 字节，超过 ${MAX_WRITE_BYTES} 上限")
        }

        val f = resolve(path)
        if (f.isDirectory) return err("IS_DIR", "${rel(f)} 是目录")

        val existed = f.exists()
        val before = if (existed && f.length() < MAX_READ_BYTES) f.readText() else ""

        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)

        return JSONObject()
            .put("ok", true)
            .put("path", rel(f))
            .put("created", !existed)
            .put("bytes", bytes.size)
            .put("lines", content.count { it == '\n' } + if (content.isEmpty()) 0 else 1)
            // 覆盖时回一句话说明旧内容多少行，模型才知道自己毁掉了什么
            .put("replaced_lines", if (existed) before.count { it == '\n' } + 1 else 0)
            .toString()
    }

    // ---------------------------------------------------------------- 精确替换

    /**
     * 这是模型最常用的"改代码"动作，也是最容易搞坏文件的一个。
     *
     * 规则定死：old 必须**恰好出现一次**。出现 0 次说明模型记错了内容（该先读文件），
     * 出现多次说明它给的定位不够（该带更多上下文）。这两种情况都拒绝执行——
     * 猜一个位置改下去，比不改糟糕得多。
     */
    private fun edit(path: String, old: String, new: String): String {
        if (path.isBlank()) return err("BAD_ARGS", "必须给 path")
        if (old.isEmpty()) return err("BAD_ARGS", "old 不能为空")

        val f = resolve(path)
        if (!f.exists()) return err("NOT_FOUND", "文件不存在：${rel(f)}")
        if (f.length() > MAX_WRITE_BYTES) return err("TOO_BIG", "文件太大，不适合整体编辑")

        val text = f.readText()
        val hits = countOccurrences(text, old)
        if (hits == 0) {
            return err("NO_MATCH", "文件里找不到这段内容。先用 fs_read 看一下实际内容，不要凭记忆改")
        }
        if (hits > 1) {
            return err(
                "AMBIGUOUS",
                "这段内容出现了 $hits 次，无法确定改哪一处。把 old 扩到包含上下几行、足以唯一确定的位置",
            )
        }

        val updated = text.replaceFirst(old, new)
        if (updated.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) {
            return err("TOO_BIG", "改完之后超过大小上限")
        }
        f.writeText(updated, Charsets.UTF_8)

        // 回显命中位置前后几行：模型据此确认改对了地方，不用再读一次整个文件
        val at = text.indexOf(old)
        val lineNo = text.substring(0, at).count { it == '\n' } + 1
        val lines = updated.lines()
        val from = (lineNo - 3).coerceAtLeast(1)
        val to = (lineNo + new.count { it == '\n' } + 2).coerceAtMost(lines.size)
        val context = StringBuilder()
        for (i in from..to) {
            val mark = if (i == lineNo) ">" else " "
            context.append(mark).append(i).append('|').append(lines[i - 1]).append('\n')
        }

        return JSONObject()
            .put("ok", true)
            .put("path", rel(f))
            .put("line", lineNo)
            .put("removed_lines", old.count { it == '\n' } + 1)
            .put("added_lines", new.count { it == '\n' } + 1)
            .put("context", context.toString().trimEnd('\n'))
            .toString()
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var n = 0
        var i = text.indexOf(needle)
        while (i >= 0) {
            n++
            i = text.indexOf(needle, i + needle.length)
            if (n > 99) break
        }
        return n
    }

    // ---------------------------------------------------------------- 搜索

    private fun search(pattern: String, path: String, glob: String): String {
        if (pattern.isBlank()) return err("BAD_ARGS", "必须给 pattern")
        val base = resolve(path)
        if (!base.exists()) return err("NOT_FOUND", "路径不存在：${rel(base)}")

        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            return err("BAD_PATTERN", "正则写错了：${e.message}")
        }

        val suffix = glob.removePrefix("*").let { if (it.startsWith(".")) it else "" }
        val results = JSONArray()
        var hits = 0
        var scanned = 0
        var truncated = false

        fun scan(f: File) {
            if (hits >= MAX_SEARCH_HITS || scanned >= MAX_SEARCH_FILES) {
                truncated = truncated || hits >= MAX_SEARCH_HITS
                return
            }
            if (f.isDirectory) {
                f.listFiles()?.forEach { scan(it) }
                return
            }
            if (suffix.isNotEmpty() && !f.name.endsWith(suffix)) return
            if (f.length() > MAX_READ_BYTES) return
            scanned++

            val lines = try {
                f.readLines()
            } catch (e: Exception) {
                return
            }
            for ((i, line) in lines.withIndex()) {
                if (hits >= MAX_SEARCH_HITS) {
                    truncated = true
                    return
                }
                if (regex.containsMatchIn(line)) {
                    hits++
                    results.put(
                        JSONObject()
                            .put("file", rel(f))
                            .put("line", i + 1)
                            .put("text", line.trim().take(200)),
                    )
                }
            }
        }
        scan(base)

        return JSONObject()
            .put("ok", true)
            .put("pattern", pattern)
            .put("hits", hits)
            .put("files_scanned", scanned)
            .put("truncated", truncated)
            .put("results", results)
            .toString()
    }

    // ---------------------------------------------------------------- 抓网页

    private fun fetch(url: String, maxChars: Int): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return err("BAD_URL", "只支持 http/https")
        }
        val res = try {
            fetcher(url, MAX_FETCH_BYTES)
        } catch (e: Exception) {
            return err("FETCH_FAILED", e.message ?: "请求失败")
        }
        if (res.status !in 200..299) return err("HTTP_${res.status}", "服务器返回 ${res.status}")

        // HTML 转正文。不做完美解析，够用就行——模型要的是文字，不是 DOM。
        val text = if (res.contentType.contains("html")) htmlToText(res.body) else res.body
        val cap = maxChars.coerceIn(500, MAX_FETCH_CHARS)
        val out = if (text.length > cap) text.substring(0, cap) else text

        return JSONObject()
            .put("ok", true)
            .put("url", url)
            .put("status", res.status)
            .put("chars", text.length)
            .put("truncated", text.length > cap)
            .put("content", out)
            .toString()
    }

    // ---------------------------------------------------------------- 生成文档

    /**
     * PDF：模型给结构化块（h1/h2/p/bullet/quote/pagebreak），引擎排成 A4 分页文档。
     * 不收 HTML——那是把排版难题原样丢回来；结构化块分页才算得准。
     */
    private fun pdfWrite(args: JSONObject): String {
        val engine = docEngine ?: return err("NO_ENGINE", "这个环境没有 PDF 引擎")
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return err("BAD_ARGS", "必须给 path，以 .pdf 结尾")
        if (!path.endsWith(".pdf", ignoreCase = true)) {
            return err("BAD_EXT", "文件名要以 .pdf 结尾（收到：$path）")
        }
        val blocks = args.optJSONArray("blocks")
        if (blocks == null || blocks.length() == 0) {
            return err("BAD_ARGS", "blocks 不能为空。给一组 {type:\"h1|h2|h3|p|bullet|quote|pagebreak\", text:\"...\"}")
        }
        if (blocks.length() > MAX_DOC_BLOCKS) {
            return err("TOO_MANY", "块数上限 $MAX_DOC_BLOCKS，收到 ${blocks.length()}。拆成几段或先写 Markdown")
        }
        val f = resolve(path)
        val result = engine.writePdf(f, args.optString("title", ""), blocks)
        // 引擎返回的 path 是文件名，换成工作台相对路径，跟 fs_write 口径一致
        return runCatching { JSONObject(result).put("path", rel(f)).toString() }.getOrDefault(result)
    }

    /** PPTX：纯 JVM 实现（[PptxWriter]），标题 + 要点列表的可编辑演示文稿。 */
    private fun pptxWrite(args: JSONObject): String {
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return err("BAD_ARGS", "必须给 path，以 .pptx 结尾")
        if (!path.endsWith(".pptx", ignoreCase = true)) {
            return err("BAD_EXT", "文件名要以 .pptx 结尾（收到：$path）")
        }
        val slides = args.optJSONArray("slides")
        if (slides == null || slides.length() == 0) {
            return err("BAD_ARGS", "slides 不能为空。每页 {title:\"...\", bullets:[\"...\"]}，至少一页")
        }
        val f = resolve(path)
        val result = PptxWriter.write(f, slides)
        return runCatching { JSONObject(result).put("path", rel(f)).toString() }.getOrDefault(result)
    }

    /** DOCX：纯 JVM 实现（[DocxWriter]），块结构和 pdf_write 同一套，产物是可编辑的 Word 文档。 */
    private fun docxWrite(args: JSONObject): String {
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return err("BAD_ARGS", "必须给 path，以 .docx 结尾")
        if (!path.endsWith(".docx", ignoreCase = true)) {
            return err("BAD_EXT", "文件名要以 .docx 结尾（收到：$path）")
        }
        val blocks = args.optJSONArray("blocks")
        if (blocks == null || blocks.length() == 0) {
            return err("BAD_ARGS", "blocks 不能为空。给一组 {type:\"h1|h2|h3|p|bullet|quote|pagebreak\", text:\"...\"}")
        }
        val f = resolve(path)
        val result = DocxWriter.write(f, args.optString("title", ""), blocks)
        return runCatching { JSONObject(result).put("path", rel(f)).toString() }.getOrDefault(result)
    }

    // ---------------------------------------------------------------- 长期记忆

    /**
     * 记一条跨会话记忆。
     *
     * 为什么给它单独一个工具而不是让模型 fs_write memory.md：直接写文件会覆盖、
     * 会忘格式、会把整篇读进来再写回去（几百行白烧 token）。这里只追加一行，最省。
     */
    private fun memorySave(text: String): String {
        val line = Memory.append(root, text)
            ?: return err("NOT_SAVED", "这条没记：内容为空，或者和最近记过的重复了。换成新的、具体的事实再试，别硬记一遍")
        return JSONObject()
            .put("ok", true)
            .put("saved", line)
            .put("total", Memory.entries(root).size)
            .put("note", "已记到长期记忆，下次开新会话也会带上。只有真的值得长期保留的才记")
            .toString()
    }

    // ---------------------------------------------------------------- 技能包

    /**
     * 本任务内已经加载过的技能。
     *
     * 为什么要记：技能正文两三千字，模型有时会在同一轮里反复加载同一份（尤其在被
     * 上下文压缩影响之后），纯粹烧 token。这里第二次起只回一句「已经加载过」。
     *
     * 为什么在任务开始清空：技能正文是进**这个会话**的历史的。如果跨任务/跨会话保留
     * 这个集合，新会话里模型会收到「已加载」却根本没见过正文——比重复加载糟得多。
     */
    private val loadedSkills = LinkedHashSet<String>()

    /** 每个任务开始调一次（由 [PhoneToolbox.beginTask] 转达）。 */
    fun beginTask() {
        synchronized(loadedSkills) { loadedSkills.clear() }
    }

    /**
     * 取一份技能的正文。
     *
     * 名字来自系统提示里的索引，但**不信任模型给的字符串**：先 safeName 规范化，
     * 再在扫描结果里找。找不到就报错并把可用的名字列出来——模型拼错时，看到清单
     * 就能自己改对，比让它猜省一轮。
     */
    private fun skillLoad(name: String): String {
        if (name.isBlank()) return err("BAD_ARGS", "必须给 name，来自系统提示里技能包清单的名字")
        val all = Skill.scan(root)
        if (all.isEmpty()) {
            return err("NO_SKILLS", "工作台 skills/ 目录里还没有技能。让用户在设置里看，或直接做，不要反复试")
        }
        val hit = all.firstOrNull { it.name == name.trim().lowercase() || it.name == name.trim() }
            ?: all.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        if (hit == null) {
            return err(
                "NOT_FOUND",
                "没有叫「$name」的技能。现有的是：${all.joinToString("、") { it.name }}",
            )
        }
        synchronized(loadedSkills) {
            if (loadedSkills.contains(hit.name)) {
                return JSONObject()
                    .put("ok", true)
                    .put("name", hit.name)
                    .put("already_loaded", true)
                    .put("note", "《${hit.title}》这一轮已经加载过，正文在上面的工具结果里。直接用，不要重复读")
                    .toString()
            }
            loadedSkills.add(hit.name)
        }
        val body = Skill.body(hit)
        return JSONObject()
            .put("ok", true)
            .put("name", hit.name)
            .put("title", hit.title)
            .put("chars", body.length)
            .put("body", body)
            .put("note", "按这份技能的步骤做。它讲的是方法，具体内容还得从用户和工具里取")
            .toString()
    }

    // ---------------------------------------------------------------- 执行命令

    /**
     * 在工作台目录里跑一条 shell 命令。
     *
     * 手机上没有 bash、没有 python、没有 node——只有 `/system/bin/sh`（mksh）和一套 toybox。
     * 所以这个工具的能力边界是「文本与文件处理」，不是「跑别人的项目」。dsh-Go 那种
     * proot Ubuntu 才需要装软件；我们这里刻意不提供，也就不需要那 388MB。
     *
     * 四道实际有效的约束（不是摆设）：
     *  1. **进程权限就是 app 权限**。没有 root，最坏情况是动到 app 自己能看到的东西。
     *  2. **超时**。20 秒默认、60 秒上限，到点 `destroyForcibly`。卡住的进程比失败的进程麻烦。
     *  3. **输出截断**。超过 30K 字符就丢弃后续，但继续读——不读会让子进程写满管道后死锁。
     *  4. **cwd 限定**。命令的工作目录只能是工作台内的路径。
     *
     * 有一点必须说清：**命令字符串里的绝对路径管不住**。想靠正则拦 `rm -rf /sdcard` 是不可靠的
     * （换个写法就绕过去了），而且会给用户一种虚假的安全感。所以这里只拦那几个「在手机上
     * 必然失败、只浪费轮次」的命令，真正的防线是「默认关闭 + 用户自己打开」。
     */
    private fun shell(command: String, cwd: String, timeoutSec: Int): String {
        if (!shellEnabled()) {
            return err("SHELL_OFF", "shell 没有开启。让用户在设置里打开「允许执行命令」再用")
        }
        if (command.isBlank()) return err("BAD_ARGS", "command 不能为空")
        if (command.length > MAX_COMMAND_CHARS) {
            return err("TOO_LONG", "命令太长（${command.length} 字符）。拆成几条，或写进脚本再执行")
        }
        NO_POINT.firstOrNull { it.containsMatchIn(command) }?.let {
            return err("BLOCKED", "这条命令在手机上跑不了，别试了：${it.pattern}")
        }

        val dir = resolve(cwd)
        if (!dir.isDirectory) return err("NOT_DIR", "工作目录不存在：${rel(dir)}")

        val proc = try {
            ProcessBuilder(shellPath, "-c", command)
                .directory(dir)
                // 合并 stderr：模型需要知道错在哪，分开读还得自己拼，没意义
                .redirectErrorStream(true)
                .apply {
                    // 清空继承来的环境变量。不清的话行为取决于谁启动了 app，不可复现。
                    environment().clear()
                    environment()["PATH"] = shellPathEnv()
                    environment()["HOME"] = dir.absolutePath
                    environment()["TMPDIR"] = dir.absolutePath
                }
                .start()
        } catch (e: Exception) {
            return err("SPAWN_FAILED", "起不来：${e.message}")
        }

        // 不给 stdin。给了的话 `cat` 这类命令会一直等输入，直到超时。
        try {
            proc.outputStream.close()
        } catch (e: Exception) {
        }

        val out = StringBuilder()
        var truncated = false
        val pump = Thread {
            try {
                // 用 Reader 而不是裸字节：UTF-8 中文跨缓冲区边界会被切碎
                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val room = MAX_OUTPUT_CHARS - out.length
                    if (room > 0) {
                        val take = minOf(n, room)
                        out.appendRange(buf, 0, take)
                        // 这一批没放下，说明后面还有内容看不到
                        if (take < n) truncated = true
                    } else {
                        // 超限之后继续读、继续丢。不读的话子进程写满管道会永久阻塞。
                        truncated = true
                    }
                }
            } catch (e: Exception) {
            }
        }
        pump.isDaemon = true
        pump.start()

        val limit = timeoutSec.coerceIn(1, MAX_TIMEOUT_SEC).toLong()
        val finished = try {
            proc.waitFor(limit, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            proc.destroyForcibly()
            return err("INTERRUPTED", "被中断了")
        }
        if (!finished) {
            proc.destroyForcibly()
            try {
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
            }
        }
        pump.join(1000)

        val code = if (finished) runCatching { proc.exitValue() }.getOrDefault(-1) else -1
        val body = out.toString().trimEnd()

        return JSONObject()
            .put("ok", finished && code == 0)
            .put("exit", code)
            .put("timed_out", !finished)
            .put("truncated", truncated)
            .put("cwd", rel(dir))
            .put("chars", body.length)
            .put("output", body)
            .toString()
    }

    /**
     * 子进程的 PATH。
     *
     * 真机上固定用 Android 的系统路径：不继承宿主环境，行为才可复现。
     * 但 shellPath 是注入的时候（桌面上跑测试），必须把解释器所在目录放到最前、并保留宿主
     * PATH——否则 `cat`、`sleep` 这些外部命令全找不到，测出来的失败是假的。
     */
    private fun shellPathEnv(): String {
        val androidPath = "/system/bin:/system/xbin:/vendor/bin"
        if (shellPath == DEFAULT_SHELL) return androidPath
        val dir = File(shellPath).parent ?: return System.getenv("PATH") ?: androidPath
        val host = System.getenv("PATH") ?: ""
        return if (host.isEmpty()) dir else dir + File.pathSeparator + host
    }

    // ---------------------------------------------------------------- 路径沙箱

    /**
     * 把模型给的路径变成文件对象，越界就抛。
     *
     * 三种越界都得拦：`../` 相对逃逸、符号链接指到外面（canonicalFile 会解析掉）、
     * 直接给一个外部绝对路径。canonical 化之后比前缀是最省事也最不容易写错的判法。
     */
    private fun resolve(path: String): File {
        val raw = path.trim().ifEmpty { "." }
        val f = if (raw.startsWith("/")) File(raw) else File(root, raw)
        val canon = f.canonicalFile
        val rootPath = root.path
        if (canon.path != rootPath && !canon.path.startsWith(rootPath + File.separator)) {
            throw SecurityException("路径越界：$path。只能访问工作台目录内的文件")
        }
        return canon
    }

    /**
     * 相对路径统一用正斜杠输出，即使在 Windows 上跑测试也一样。
     *
     * 两个理由：模型见过的路径几乎都是 POSIX 风格，给反斜杠它会犹豫；而且同一个工具在
     * 手机上（Linux）和桌面测试里（Windows）如果吐出两种形状，断言和用户看到的就对不上。
     */
    private fun rel(f: File): String {
        val r = root.path
        val p = when {
            f.path == r -> "."
            f.path.startsWith(r + File.separator) -> f.path.substring(r.length + 1)
            else -> f.name
        }
        return p.replace(File.separatorChar, '/')
    }

    private fun err(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("error", message).toString()

    companion object {
        const val DEFAULT_READ_LINES = 200
        const val MAX_READ_LINES = 600
        const val MAX_READ_BYTES = 256 * 1024
        const val MAX_WRITE_BYTES = 512 * 1024
        const val MAX_LIST_ENTRIES = 200
        const val MAX_SEARCH_HITS = 50
        const val MAX_SEARCH_FILES = 400
        const val MAX_FETCH_BYTES = 512 * 1024
        const val MAX_FETCH_CHARS = 20_000
        const val MAX_COMMAND_CHARS = 4_000
        const val MAX_OUTPUT_CHARS = 30_000

        /** 文档生成的块数上限。一个任务生成几千块的 PDF 多半是跑偏了。 */
        const val MAX_DOC_BLOCKS = 400
        const val DEFAULT_TIMEOUT_SEC = 20
        const val MAX_TIMEOUT_SEC = 60

        /** Android 自带的 shell。不叫 bash——这里没有 bash。 */
        const val DEFAULT_SHELL = "/system/bin/sh"

        /**
         * 在手机上必然失败、只会浪费时间的那几条。
         *
         * 这不是安全边界，是「别让模型把轮次花在注定失败的事上」。真正的安全边界是
         * 「默认关闭 + app 权限」——命令字符串里的绝对路径本来就不该靠正则去拦。
         */
        val NO_POINT = listOf(
            Regex("""\bmkfs(\.[a-z0-9]+)?\b"""),
            Regex("""\bdd\b[^|;]*\bof=/dev/"""),
            Regex("""\b(fdisk|parted|mke2fs)\b"""),
            Regex("""\b(sudo|su)\s"""),
            Regex("""\b(reboot|shutdown)\b"""),
            Regex(""">\s*/dev/block"""),
            Regex("""\bpm\s+(uninstall|clear)\b"""),
        )

        /** 工作台工具的定义。和手机工具分开拼，方便按开关裁剪（shell、pdf_write 会按环境摘掉）。 */
        val SCHEMA_JSON = """
        [
          {
            "type": "function",
            "function": {
              "name": "fs_list",
              "description": "列出工作台目录下的文件。先看目录再动手，不要猜路径。",
              "parameters": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "相对路径，默认当前目录 \".\""},
                  "depth": {"type": "integer", "description": "下钻层数 1-3，默认 1"}
                },
                "required": []
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "fs_read",
              "description": "读文本文件，返回带行号的内容。改文件之前必须先读，凭记忆改会改错地方。",
              "parameters": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "文件相对路径"},
                  "from": {"type": "integer", "description": "起始行号，从 1 开始，默认 1"},
                  "lines": {"type": "integer", "description": "读多少行，默认 200，上限 600"}
                },
                "required": ["path"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "fs_write",
              "description": "写入文件，会整个覆盖已有内容。只想改一处就用 fs_edit，不要用这个重写全文件。",
              "parameters": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "文件相对路径，父目录会自动创建"},
                  "content": {"type": "string", "description": "完整的新内容"}
                },
                "required": ["path", "content"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "fs_edit",
              "description": "把文件里一段唯一的内容替换成新内容。old 必须在文件里恰好出现一次，否则会拒绝执行并告诉你原因。改完会回显命中处上下文供你确认。",
              "parameters": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "文件相对路径"},
                  "old": {"type": "string", "description": "要被替换的原文，需带足够上下文保证唯一"},
                  "new": {"type": "string", "description": "替换成什么，空字符串表示删除"}
                },
                "required": ["path", "old", "new"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "fs_search",
              "description": "按正则搜索工作台目录里的文本内容，返回命中的文件、行号和那一行。找东西用这个，不要一个个文件读。",
              "parameters": {
                "type": "object",
                "properties": {
                  "pattern": {"type": "string", "description": "正则表达式，不区分大小写"},
                  "path": {"type": "string", "description": "搜索起点，默认当前目录"},
                  "glob": {"type": "string", "description": "按后缀过滤，如 \".kt\"，留空搜全部文本文件"}
                },
                "required": ["pattern"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "web_fetch",
              "description": "抓取一个网页并转成纯文本。只支持 http/https。",
              "parameters": {
                "type": "object",
                "properties": {
                  "url": {"type": "string", "description": "完整网址"},
                  "max_chars": {"type": "integer", "description": "最多返回多少字符，默认 20000"}
                },
                "required": ["url"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "pdf_write",
              "description": "把结构化内容排成 A4 分页的 PDF 文件。适合报告、简历、竞赛材料。块类型：h1/h2/h3（各级标题）、p（正文段落）、bullet（要点）、quote（引用）、pagebreak（强制换页）、spacer（空行）。不要塞整篇 HTML，按块给内容。",
              "parameters": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "文件相对路径，以 .pdf 结尾"},
                  "title": {"type": "string", "description": "文档大标题（显示在首页顶部，可省略）"},
                  "blocks": {
                    "type": "array",
                    "description": "内容块数组，按阅读顺序",
                    "items": {
                      "type": "object",
                      "properties": {
                        "type": {"type": "string", "description": "h1|h2|h3|p|bullet|quote|pagebreak|spacer"},
                        "text": {"type": "string", "description": "这一块的内容（pagebreak/spacer 可省略）"}
                      },
                      "required": ["type"]
                    }
                  }
                },
                "required": ["path", "blocks"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "pptx_write",
              "description": "生成可编辑的 .pptx 演示文稿（16:9，雾蓝主题）。第 1 页自动渲染成封面（深蓝底 + 居中大标题，它的 bullets 显示为副标题行），其余页为内容页（品牌色竖条标题、要点列表、页码）。适合提纲、汇报、答辩类内容；要图表或自由排版就用 pdf_write 或 HTML。",
              "parameters": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "文件相对路径，以 .pptx 结尾"},
                  "slides": {
                    "type": "array",
                    "description": "幻灯片数组，按顺序",
                    "items": {
                      "type": "object",
                      "properties": {
                        "title": {"type": "string", "description": "本页标题"},
                        "bullets": {"type": "array", "items": {"type": "string"}, "description": "本页要点，每条一行"}
                      },
                      "required": ["title"]
                    }
                  }
                },
                "required": ["path", "slides"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "docx_write",
              "description": "生成可编辑的 .docx Word 文档（A4、1.5 倍行距）。块类型和 pdf_write 一致：h1/h2/h3（标题，带大纲级别，WPS/Word 导航窗格可跳）、p（正文段落，自动首行缩进两字）、bullet（项目符号要点）、quote（引用块）、pagebreak（换页）、spacer（空行）。正文里的 **粗体** 和 `代码` 会渲染成真样式。适合报告、总结、简历、通知、论文初稿这类要交给别人改的文档；要精确排版/图表用 pdf_write，要演示用 pptx_write。",
              "parameters": {
                "type": "object",
                "properties": {
                  "path": {"type": "string", "description": "文件相对路径，以 .docx 结尾"},
                  "title": {"type": "string", "description": "文档大标题（居中显示在首行，可省略）"},
                  "blocks": {
                    "type": "array",
                    "description": "内容块数组，按阅读顺序",
                    "items": {
                      "type": "object",
                      "properties": {
                        "type": {"type": "string", "description": "h1|h2|h3|p|bullet|quote|pagebreak|spacer"},
                        "text": {"type": "string", "description": "这一块的内容（pagebreak/spacer 可省略）"}
                      },
                      "required": ["type"]
                    }
                  }
                },
                "required": ["path", "blocks"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "memory_save",
              "description": "记一条跨越会话的长期记忆（写进工作台 memory.md，下次开新对话也会带上）。只记真正长期有用的事实与偏好：用户的身份/职业/长期目标、明确说过的做事偏好、纠正过你的地方、某个长期项目的关键结论。不要记一次性的任务细节、屏幕内容、客套话。一条一句话，最多 200 字。重复的内容会被拒绝。",
              "parameters": {
                "type": "object",
                "properties": {
                  "text": {"type": "string", "description": "一句话记忆，第三人称或陈述句，例如「用户叫罗唯珍，河南财经政法大学数据科学专业大二」"}
                },
                "required": ["text"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "skill_load",
              "description": "加载一份技能的详细步骤。只有在系统提示的「技能包」清单里命中时才调，名字用清单里那个。返回该技能的做法、结构和注意事项。同一轮不要重复加载同一份。",
              "parameters": {
                "type": "object",
                "properties": {
                  "name": {"type": "string", "description": "技能名，例如 weekly-report"}
                },
                "required": ["name"]
              }
            }
          },
          {
            "type": "function",
            "function": {
              "name": "shell",
              "description": "在工作台目录里执行一条 shell 命令。手机上是 Android 自带的 sh + toybox：能做文件与文本处理（ls/cat/grep/sed/find/wc/sort 等），没有 bash、没有 python、没有 node，也装不了软件。默认超时 20 秒，输出超过 3 万字符会截断。",
              "parameters": {
                "type": "object",
                "properties": {
                  "command": {"type": "string", "description": "要执行的命令。一次做一件事，不要把很长的一串用 && 连起来"},
                  "cwd": {"type": "string", "description": "工作目录（相对工作台），默认 \".\""},
                  "timeout_sec": {"type": "integer", "description": "超时秒数，默认 20，上限 60"}
                },
                "required": ["command"]
              }
            }
          }
        ]
        """.trimIndent()

        /** 网页抓取的结果。正文已解码成字符串，编码由 [httpGet] 处理。 */
        data class FetchResult(val status: Int, val contentType: String, val body: String)

        internal fun httpGet(url: String, maxBytes: Int): FetchResult {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html,application/json,text/plain;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                // 不少站点对 JDK 默认 UA 直接返回 403，这里给一个普通浏览器标识
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val status = conn.responseCode
                val type = conn.contentType ?: ""
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.use { input ->
                    val buf = ByteArray(8192)
                    val out = java.io.ByteArrayOutputStream()
                    while (out.size() < maxBytes) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)

                val cs = Regex("(?i)charset=([\\w-]+)").find(type)?.groupValues?.get(1)
                    ?: sniffCharset(bytes)
                    ?: "utf-8"
                val body = try {
                    bytes.toString(Charset.forName(cs))
                } catch (e: Exception) {
                    bytes.toString(Charsets.UTF_8)
                }
                return FetchResult(status, type, body)
            } finally {
                conn.disconnect()
            }
        }

        /** 从 HTML 的 meta 里猜编码。国内站点 gb2312/gbk 仍然不少。 */
        private fun sniffCharset(bytes: ByteArray): String? {
            val head = bytes.take(2048).toByteArray().toString(Charsets.ISO_8859_1)
            val m = Regex("(?i)<meta[^>]+charset=[\"']?([\\w-]+)").find(head) ?: return null
            return when (m.groupValues[1].lowercase()) {
                "gb2312", "gbk", "gb18030" -> "gb18030"
                else -> m.groupValues[1]
            }
        }

        /** HTML → 纯文本。粗糙但够用：模型要的是字，不是结构。 */
        internal fun htmlToText(html: String): String {
            var s = html
            s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
            s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
            s = Regex("(?is)<noscript[^>]*>.*?</noscript>").replace(s, " ")
            s = Regex("(?is)<svg[^>]*>.*?</svg>").replace(s, " ")
            s = Regex("(?is)<!--.*?-->").replace(s, " ")
            s = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)[^>]*>").replace(s, "\n")
            s = Regex("(?i)<(td|th)[^>]*>").replace(s, " ")
            s = Regex("<[^>]+>").replace(s, "")
            s = s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
            return s.lines()
                .map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
    }
}
