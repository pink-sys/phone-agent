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
