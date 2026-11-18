package cn.wbphone.agent

import java.io.File
import java.util.zip.ZipFile

/**
 * Office 二进制产物的文本提取：pptx → 每页标题+要点、docx → 段落、zip → 条目清单。
 *
 * pptx/docx 本质是 zip 包着一堆 XML，纯 JVM 就能解出正文——解出来的文本
 * 喂给 ArtifactReader 渲染成阅读视图，用户不用跳出应用就能看到内容。
 * 这不是「按版式渲染」（没有形状定位、没有图片），是内容预览；
 * 要原版式仍然走 ↗ 分享给 WPS。全部正则解析，测试里 JVM 直接跑。
 */
object OfficeText {

    private val XML_ENTITIES = mapOf(
        "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&apos;" to "'", "&amp;" to "&",  // &amp; 必须最后替换，避免二次解码
    )

    fun xmlUnescape(s: String): String {
        var r = s
        for ((k, v) in XML_ENTITIES) r = r.replace(k, v)
        return r
    }

    // ══ PPTX ══════════════════════════════════════════════

    /** 幻灯片文本 → Markdown。失败（不是 zip/没有幻灯片）返回 null，调用方退说明卡。 */
    fun pptxToMarkdown(f: File): String? = try {
        ZipFile(f).use { z ->
            val slideNames = z.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: 0 }
                .toList()
            if (slideNames.isEmpty()) return null
            val sb = StringBuilder("# ${f.name}\n\n")
            for ((i, name) in slideNames.withIndex()) {
                val xml = z.getInputStream(z.getEntry(name)).readBytes().toString(Charsets.UTF_8)
                val paras = extractParagraphs(xml, "a:p", "a:t")
                sb.append("## 第 ${i + 1} 页\n\n")
                if (paras.isEmpty()) {
                    sb.append("（本页没有文字）\n\n")
                    continue
                }
                // 第一段当标题，其余当要点——和 PptxWriter 的版式约定一致
                sb.append("**${paras[0]}**\n\n")
                // 内容页右下角有页码文本框（"2 / 5"），那是版式装饰不是内容，别收
                for (p in paras.drop(1)) {
                    if (p.matches(Regex("\\d+\\s*/\\s*\\d+"))) continue
                    sb.append("- $p\n")
                }
                sb.append("\n")
            }
            sb.toString().trimEnd() + "\n"
        }
    } catch (e: Exception) {
        null
    }

    // ══ DOCX ══════════════════════════════════════════════

    /** Word 正文段落 → Markdown。失败返回 null。 */
    fun docxToMarkdown(f: File): String? = try {
        ZipFile(f).use { z ->
            val entry = z.getEntry("word/document.xml") ?: return null
            val xml = z.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            val paras = extractParagraphs(xml, "w:p", "w:t")
            if (paras.all { it.isBlank() }) return null
            val sb = StringBuilder("# ${f.name}\n\n")
            for (p in paras) {
                if (p.isBlank()) continue
                // 约定俗成：全角方括号编号的行当小标题（用户文档里常见），其余为正文
                if (p.length <= 30 && Regex("^[（(]?[一二三四五六七八九十0-9]+[）)、.:：]").containsMatchIn(p)) {
                    sb.append("## $p\n\n")
                } else {
                    sb.append(p).append("\n\n")
                }
            }
            sb.toString().trimEnd() + "\n"
        }
    } catch (e: Exception) {
        null
    }

    // ══ XLSX ═══════════════════════════════════════════════

    /** 每个 sheet 预览的行数上限——给模型看的数据，不是全量导出。 */
    private const val XLSX_MAX_ROWS = 200

    /** 预览的列数上限；列名超宽的表撑爆 Markdown 行，先截再给。 */
    private const val XLSX_MAX_COLS = 20

    /**
     * xlsx 各 sheet → Markdown 表格。失败返回 null。
     *
     * 单元格三种来源都要接：`t="inlineStr"`（我们自己写的产物）、`t="s"`（共享字符串
     * 索引，Excel/WPS 存的常态）、无 t 的数值/公式缓存 `<v>`。正则解析和上面两个
     * 函数同一个理由：JVM 测试环境没有 Android 的 pull 解析器。
     */
    fun xlsxToMarkdown(f: File): String? = try {
        ZipFile(f).use { z ->
            val shared = sharedStrings(z)
            val wbEntry = z.getEntry("xl/workbook.xml") ?: return null
            val wb = z.getInputStream(wbEntry).readBytes().toString(Charsets.UTF_8)
            val rels = sheetTargets(z)

            // workbook 里 sheet 的出现顺序就是用户看到的标签页顺序，别用文件名排序。
            // sheet 标签是自闭合的（<sheet .../>），正则两种形态都要接。
            val sheetRe = Regex("<sheet\\s[^>]*?/?>", RegexOption.DOT_MATCHES_ALL)
            val sheets = sheetRe.findAll(wb).map { m ->
                Pair(attr(m.value, "name"), attr(m.value, "r:id"))
            }.toList()
            if (sheets.isEmpty()) return null

            val sb = StringBuilder("# ${f.name}\n\n")
            for ((name, rid) in sheets) {
                val target = rels[rid] ?: continue
                val path = if (target.startsWith("/")) target.removePrefix("/")
                else "xl/" + target.removePrefix("xl/")
                val entry = z.getEntry(path) ?: continue
                val xml = z.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                val table = sheetTable(xml, shared)
                if (table.isEmpty()) continue
                sb.append("## ${name.ifBlank { "表格" }}\n\n")
                for (line in table) sb.append(line).append("\n")
                sb.append("\n")
            }
            val out = sb.toString().trimEnd()
            if (out.length <= f.name.length + 2) return null
            out + "\n"
        }
    } catch (e: Exception) {
        null
    }

    /** 共享字符串表 → 按索引取。没有这个部件（全 inlineStr 的文件）返回空表。 */
    private fun sharedStrings(z: ZipFile): List<String> {
        val e = z.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val xml = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
        return Regex("<si[ >].*?</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            // 一个 si 里可能有多个 t（富文本拆 run），全拼起来
            Regex("<t[ >].*?</t>", RegexOption.DOT_MATCHES_ALL).findAll(m.value)
                .map { it.value.substringAfter('>').substringBeforeLast('<') }
                .joinToString("")
        }.toList()
    }

    /** workbook 的 rId → sheet 文件路径。 */
    private fun sheetTargets(z: ZipFile): Map<String, String> {
        val e = z.getEntry("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val xml = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
        val out = HashMap<String, String>()
        // rels 里的 Relationship 是自闭合标签，和 sheet 同一个坑
        for (m in Regex("<Relationship\\s[^>]*?/?>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val id = attr(m.value, "Id")
            val target = attr(m.value, "Target")
            if (id.isNotEmpty() && target.isNotEmpty()) out[id] = target
        }
        return out
    }

    /** 一个 sheet 的 XML → Markdown 表行（| a | b | 形式，带截断说明）。 */
    private fun sheetTable(xml: String, shared: List<String>): List<String> {
        val rows = ArrayList<List<String>>()
        val rowRe = Regex("<row[ >].*?</row>", RegexOption.DOT_MATCHES_ALL)
        val cellRe = Regex("<c[ >].*?</c>|<c[^>]*/>", RegexOption.DOT_MATCHES_ALL)
        for (rm in rowRe.findAll(xml)) {
            if (rows.size >= XLSX_MAX_ROWS) break
            val cells = ArrayList<String>()
            for (cm in cellRe.findAll(rm.value)) {
                if (cells.size >= XLSX_MAX_COLS) break
                val tag = cm.value
                val type = attr(tag, "t")
                val text = when (type) {
                    "inlineStr" -> Regex("<t[ >].*?</t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(tag).map { it.value.substringAfter('>').substringBeforeLast('<') }
                        .joinToString("")
                    "s" -> tag.substringAfter("<v>").substringBefore("</v>")
                        .trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "b" -> if (tag.substringAfter("<v>").substringBefore("</v>").trim() == "1") "TRUE" else "FALSE"
                    else -> tag.substringAfter("<v>").substringBefore("</v>").trim()
                }
                cells.add(xmlUnescape(text).replace("|", "\\|").replace("\n", " ").take(60))
            }
            if (cells.isNotEmpty()) rows.add(cells)
        }
        if (rows.isEmpty()) return emptyList()
        val width = rows.maxOf { it.size }
        val pad = { r: List<String> -> List(width) { r.getOrNull(it) ?: "" } }
        val out = ArrayList<String>()
        // Markdown 表头行 + 分隔行 + 数据行；截断要说明，模型拿着半张表不能当全量
        out.add("| " + pad(rows[0]).joinToString(" | ") + " |")
        out.add("|" + List(width) { " --- " }.joinToString("|") + "|")
        for (r in rows.drop(1)) out.add("| " + pad(r).joinToString(" | ") + " |")
        val rowTotal = Regex("<row[ >]").findAll(xml).count()
        if (rowTotal > XLSX_MAX_ROWS) {
            out.add("")
            out.add("（表格共 $rowTotal 行，只读了前 $XLSX_MAX_ROWS 行；要全量数据让用户导出 CSV）")
        }
        return out
    }

    /** 从一个开标签 XML 里抠属性值（name="..."），带实体不还原——先原样，调用方 unescape。 */
    private fun attr(tag: String, name: String): String {
        val m = Regex("$name=\"([^\"]*)\"").find(tag) ?: return ""
        return m.groupValues[1]
    }

    // ══ ZIP ════════════════════════════════════════════════

    /** 压缩包里有什么 → Markdown 清单。 */
    fun zipToMarkdown(f: File): String = try {
        ZipFile(f).use { z ->
            val sb = StringBuilder("# ${f.name}\n\n共 ${z.size()} 个条目：\n\n")
            val entries = z.entries().asSequence().filter { !it.isDirectory }.take(200).toList()
            for (e in entries) sb.append("- ${e.name}（${humanSize(e.size)}）\n")
            if (z.size() > entries.size) sb.append("\n…还有 ${z.size() - entries.size} 个条目没列出\n")
            sb.toString()
        }
    } catch (e: Exception) {
        "# ${f.name}\n\n打不开这个压缩包。"
    }

    // ══ 公用 ═══════════════════════════════════════════════

    /**
     * 从 OOXML 里抠段落文本：按整段切块（<a:p>…</a:p>），段内收集文本 run（<a:t>…</a:t>）。
     * 段落不嵌套，非贪婪匹配到各自的闭标签是安全的。
     * 开标签用 `<tag[ >]` 兼容带属性与无属性的写法；闭标签永远是纯 `</tag>`。
     * 用正则而不是 XmlPullParser 是刻意的——测试在 JVM 上跑，pull 解析器是 Android 的。
     * 注意：模式必须写 `<$tag[ >]` 而不是 `<$tag[^>]*>`——字符串模板里少一个字面
     * 「>」时贪婪的 [^>]* 会把正文连同闭标签一起吞掉，整段永远匹配不上。
     */
    private fun extractParagraphs(xml: String, paraTag: String, runTag: String): List<String> {
        val out = ArrayList<String>()
        val paraRe = Regex("<$paraTag[ >].*?</$paraTag>", RegexOption.DOT_MATCHES_ALL)
        val runRe = Regex("<$runTag[ >].*?</$runTag>", RegexOption.DOT_MATCHES_ALL)
        for (m in paraRe.findAll(xml)) {
            val runs = runRe.findAll(m.value)
                .map { it.value.substringAfter('>').substringBeforeLast('<') }
                .toList()
            val line = runs.joinToString("").trim()
            if (line.isNotEmpty()) out.add(xmlUnescape(line))
        }
        return out
    }

    private fun humanSize(n: Long): String = when {
        n >= 1_000_000 -> "%.1f MB".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1f KB".format(n / 1_000.0)
        else -> "$n B"
    }
}
