package cn.wbphone.agent

import java.io.File

/**
 * 附件文件 → 模型能读的内容。纯 JVM（测试 harness 里直接跑），不碰 android。
 *
 * 按扩展名分流：
 *   文本类（md/txt/代码/数据）→ 直读 UTF-8
 *   docx / pptx → OfficeText 抽成 Markdown（工程里现成的解析器）
 *   pdf → 交给 Android 侧渲染成图（见 MainActivity.renderPdf）——网关只认文字和图，
 *         本地没有 PDF 文字抽取，转图走视觉通道是唯一现实的路
 *   其他（xlsx/二进制/扫描件）→ 明说不支持，不让模型对着乱码猜
 *
 * 文本一律设上限：附件是给模型「看」的，几万字塞进去烧 token 还稀释重点，
 * 超了就截断并注明，模型自己会告诉用户「我只看到前 N 字」。
 */
object FileText {

    /** 抽出来的东西：kind 是给界面/调用方的路由提示，text 是给模型的正文。 */
    data class Extracted(val kind: String, val text: String)

    /** 正文字符上限。 */
    const val MAX_CHARS = 48000

    /** 文件字节上限：再大读取和内存都不划算，直接拒。 */
    const val MAX_BYTES: Long = 20L * 1024 * 1024

    /** 直读文本的扩展名：文档、数据、常见代码。 */
    private val TEXT_EXT = setOf(
        "md", "markdown", "txt", "text", "log", "csv", "tsv", "json", "xml", "html", "htm",
        "js", "ts", "jsx", "tsx", "kt", "kts", "java", "py", "c", "h", "cpp", "hpp", "cs",
        "go", "rs", "rb", "php", "swift", "m", "sh", "bat", "ps1", "sql", "css", "scss",
        "yml", "yaml", "toml", "ini", "conf", "properties", "gradle", "vue", "dart",
    )

    /** 按文件名路由："text" / "docx" / "pptx" / "pdf" / "unsupported"。 */
    fun route(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "pdf"
            "docx" -> "docx"
            "pptx" -> "pptx"
            in TEXT_EXT -> "text"
            else -> "unsupported"
        }
    }

    /**
     * 抽取入口。文件必须已经落在本地（调用方负责把 Uri 拷进 cache）。
     * 失败（超限、解析不出文字）text 会带一句人话说明，不抛异常——
     * 调用方只要看 kind 和 text.isBlank() 就能决定提示什么。
     */
    fun extract(file: File, name: String): Extracted {
        if (file.length() > MAX_BYTES) {
            return Extracted("unsupported", "文件超过 20MB，读不了")
        }
        return when (val kind = route(name)) {
            "text" -> Extracted(kind, cap("【文件 $name】\n" + file.readText(Charsets.UTF_8).trim()))
            "docx" -> Extracted(kind, cap(office(name, OfficeText.docxToMarkdown(file))))
            "pptx" -> Extracted(kind, cap(office(name, OfficeText.pptxToMarkdown(file))))
            "pdf" -> Extracted(kind, "")   // 正文由 Android 侧渲染，这里不给文字
            else -> Extracted(kind, "这个格式（${name.substringAfterLast('.', "?")}）暂时读不了，" +
                "支持 md/txt/代码/docx/pptx/pdf")
        }
    }

    /** Office 抽取失败（空结果/异常）给一句人话，而不是让模型收到空串。 */
    private fun office(name: String, md: String?): String {
        val body = md?.trim().orEmpty()
        if (body.isEmpty()) {
            return "文件 $name 里抽不出文字——它多半是扫描件或整页都是图片，请用户换 PDF 版或直接拍照发图。"
        }
        return "【文件 $name】\n$body"
    }

    /** 超长截断，注明只取了多少，模型转述时能说清楚。 */
    private fun cap(text: String): String {
        val t = text.trim()
        if (t.length <= MAX_CHARS) return t
        return t.take(MAX_CHARS) + "\n\n（文件太长，只取了前 $MAX_CHARS 字）"
    }

    /**
     * PDF 页码范围解析："7-12"、"5"、"1-3,8" 都行，全角逗号/破折号也认。
     * 返回的是 0 基页下标（升序去重），最多取 [maxPages] 页——PDF 拼图一次
     * 装不了太多页，超了从前面截。写错（越界、倒序、看不懂）返回 null，
     * 调用方提示重填，不猜用户意图。纯 JVM，测试里直接跑。
     */
    fun parsePages(input: String, total: Int, maxPages: Int): List<Int>? {
        if (total <= 0 || maxPages <= 0) return null
        // 全角数字转半角——中文输入法的 ７８９ 不归一化，toIntOrNull 会直接判错
        val normalized = input.trim().map { c ->
            if (c in '０'..'９') ('0' + (c - '０')) else c
        }.joinToString("")
            .replace("，", ",").replace("、", ",")
            .replace("－", "-").replace("—", "-").replace("–", "-")
            .replace("～", "-").replace("~", "-")
        if (normalized.isEmpty()) return null
        val out = LinkedHashSet<Int>()
        for (tok in normalized.split(",")) {
            val t = tok.trim()
            if (t.isEmpty()) continue
            val seg = t.split("-").map { it.trim() }
            val a: Int
            val b: Int
            when (seg.size) {
                1 -> {
                    a = seg[0].toIntOrNull() ?: return null
                    b = a
                }
                2 -> {
                    a = seg[0].toIntOrNull() ?: return null
                    b = seg[1].toIntOrNull() ?: return null
                    if (b < a) return null
                }
                else -> return null
            }
            // 页码是给人看的 1 基；0 或越界直接判错，不悄悄夹取——用户要的就是那一页
            if (a < 1 || b > total) return null
            for (p in a..b) out.add(p - 1)
            if (out.size >= maxPages) break
        }
        if (out.isEmpty()) return null
        return out.sorted().take(maxPages)
    }
}
