package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * DOCX 生成器（纯 JVM，零 android import——能在 tools/test_agent.py 的 harness 里跑真断言）。
 *
 * 和 [PptxWriter] 同一条路：.docx 就是个 zip + 一组 OOXML，文字版式自己拼得出来。
 * 工程零依赖是铁律，所以不引 docx4j / poi（那俩在手机上还跑不动）。
 *
 * 产物口径：**打开就是一篇像样的中文文档**，不是「能打开就算」的裸 XML——
 * A4 页面、2.5cm 页边距、1.5 倍行距、正文首行缩进两字、标题分级（带大纲级别，
 * WPS/Word 的导航窗格能直接跳）、要点列表用真项目符号（numbering，不是手打 •）、
 * 引用块左侧品牌色竖线。字体走「等线/微软雅黑」，WPS 和 Word 都有。
 *
 * 块结构和 pdf_write 保持一致（h1/h2/h3/p/bullet/quote/pagebreak/spacer），
 * 模型不用学第二套方言；行内 **粗体** 和 `代码` 复用 [Marked.inline]，口径也一致。
 */
object DocxWriter {

    /** 块数上限：一部中篇文档的量，再多说明该拆文件了。 */
    const val MAX_BLOCKS = 400

    /** 总字数上限（含标记）。防止模型把整本书塞进来把手机内存撑爆。 */
    const val MAX_CHARS = 120_000

    // A4（twips）：21cm × 29.7cm。1cm ≈ 567 twips。
    private const val PAGE_W = 11906
    private const val PAGE_H = 16838

    // 版式配色跟雾蓝主题走，和 PPT 的深浅关系一致
    private const val TITLE_INK = "14395B"
    private const val HEAD_INK = "1F4E79"
    private const val ACCENT = "4B67B4"
    private const val BODY_INK = "1F2933"
    private const val QUOTE_INK = "555F6B"
    private const val RULE = "DCE4F2"

    // 字号单位是「半磅」：21=10.5pt（五号），32=16pt，44=22pt
    private const val BODY_SZ = 21

    // ---------------------------------------------------------- 块级样式
    // 论文格式是「题目宋体三号加粗、摘要楷体小四、正文宋体小四、行距 1.5」这种
    // 逐段不同的规格——全局模板对不上，只有让每个块都能带自己的排版参数才行。

    /**
     * 一块的排版参数。全部可选：没给的字段落回文档默认（等线、五号、1.5 倍行距、
     * 首行缩进两字），旧调用一个字都不用改。
     */
    private class BlockStyle(
        val font: String?,       // 中文字体（w:eastAsia），如 宋体 / 黑体 / 楷体 / 仿宋
        val ascii: String?,      // 西文字体；缺省跟 font 同名（论文要 Times New Roman 就显式给）
        val size: Int?,          // 半磅（w:sz 原生单位）
        val bold: Boolean?,      // null=不动（标题本来就粗）；true/false=强制加粗/去粗
        val align: String?,      // left / center / right / both（两端对齐）
        val line: Int?,          // 行距的 w:line 原生值（240=单倍，360=1.5 倍）
        val noIndent: Boolean,   // 居中/居右的段不再首行缩进——缩进的居中段是歪的
    )

    /** 中文字号 → 半磅。论文格式一口一个「三号」「小四」，必须都认得。 */
    private val CN_SIZES = mapOf(
        "初号" to 84, "小初" to 72, "一号" to 52, "小一" to 48,
        "二号" to 44, "小二" to 36, "三号" to 32, "小三" to 30,
        "四号" to 28, "小四" to 24, "五号" to 21, "小五" to 18,
        "六号" to 15, "小六" to 13, "七号" to 10, "八号" to 8,
    )

    /**
     * 块字段 → 样式。解析往宽里做：模型会写「小三号」「12」「12pt」「1.5倍」「居中」，
     * 认得出就认，认不出当没给（落回默认）——为一个看不懂的字段报错，
     * 结果只会是模型把整篇内容重发一遍。
     */
    private fun styleOf(b: JSONObject): BlockStyle {
        val font = b.optString("font").trim().ifEmpty { null }
        val ascii = b.optString("ascii").trim().ifEmpty { null }
        val sizeRaw = b.optString("size").trim()
        val size: Int? = if (sizeRaw.isEmpty()) null else {
            val cn = listOf(sizeRaw, sizeRaw.removeSuffix("号"), sizeRaw.removeSuffix("号字"))
                .firstOrNull { CN_SIZES.containsKey(it) }?.let { CN_SIZES[it] }
            cn ?: Regex("(\\d+(?:\\.\\d+)?)").find(sizeRaw)?.groupValues?.get(1)
                ?.toDoubleOrNull()?.let { (it * 2).roundToInt().coerceIn(8, 160) }
        }
        val bold: Boolean? = if (b.has("bold")) b.optBoolean("bold") else null
        val alignRaw = b.optString("align").trim().lowercase()
        val align: String? = when {
            alignRaw.isEmpty() -> null
            "center" in alignRaw || "middle" in alignRaw || "居中" in alignRaw || "中间" in alignRaw -> "center"
            "right" in alignRaw || "居右" in alignRaw || "右对齐" in alignRaw -> "right"
            "both" in alignRaw || "justify" in alignRaw || "两端" in alignRaw -> "both"
            "left" in alignRaw || "居左" in alignRaw || "左对齐" in alignRaw -> "left"
            else -> null
        }
        val lineRaw = if (b.has("line")) b.opt("line").toString() else ""
        val line: Int? = Regex("(\\d+(?:\\.\\d+)?)").find(lineRaw)?.groupValues?.get(1)
            ?.toDoubleOrNull()?.let { (it * 240).roundToInt().coerceIn(240, 960) }
        return BlockStyle(font, ascii, size, bold, align, line,
            noIndent = align == "center" || align == "right")
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\n' -> sb.append(' ')   // 段落内换行交给块结构，不塞裸换行
                '\r' -> {}
                '\t' -> sb.append(' ')
                else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 生成一份 .docx。返回 JSON 文本（和 Workbench 里其它工具一个口径）。
     * blocks 里每项 {type, text}，type ∈ h1/h2/h3/p/bullet/quote/pagebreak/spacer；
     * 可选样式字段 font / ascii / size / bold / align / line（见 [styleOf]）。
     */
    fun write(out: File, title: String, blocks: JSONArray, titleStyle: JSONObject? = null): String {
        if (blocks.length() == 0) {
            return err("EMPTY", "blocks 不能为空。至少给一块 {type:\"h1\", text:\"...\"}")
        }
        if (blocks.length() > MAX_BLOCKS) {
            return err("TOO_MANY", "块数上限 $MAX_BLOCKS，收到 ${blocks.length()} 块。拆成两份文件")
        }
        var chars = 0
        for (i in 0 until blocks.length()) {
            chars += blocks.optJSONObject(i)?.optString("text").orEmpty().length
        }
        if (chars > MAX_CHARS) {
            return err("TOO_LONG", "正文太长（$chars 字），上限 $MAX_CHARS 字。拆成多份文档")
        }

        val body = StringBuilder()
        val t = title.trim()
        if (t.isNotEmpty()) body.append(titlePara(t, titleStyle?.let { styleOf(it) }))
        var headings = 0
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val type = b.optString("type").trim().lowercase()
            val text = b.optString("text").trim()
            val st = styleOf(b)
            when (type) {
                "h1", "h2", "h3" -> {
                    if (text.isEmpty()) continue
                    val lv = type.removePrefix("h").toInt()
                    if (lv == 1) headings++
                    body.append(headingPara(text, lv, st))
                }
                "p", "para", "text", "" -> if (text.isNotEmpty()) body.append(bodyPara(text, st))
                "bullet" -> if (text.isNotEmpty()) body.append(bulletPara(text, st))
                "quote" -> if (text.isNotEmpty()) body.append(quotePara(text, st))
                "pagebreak", "page_break" -> body.append(PAGE_BREAK)
                "spacer", "blank" -> body.append("<w:p/>")
                else -> if (text.isNotEmpty()) body.append(bodyPara(text))
            }
        }
        // 一个字都没有的文档没有意义：模型多半是把内容塞错字段了
        if (body.isEmpty()) {
            return err("EMPTY", "所有块都是空的。给 type 和 text，至少一段正文")
        }

        val entries = LinkedHashMap<String, String>()
        entries["[Content_Types].xml"] = contentTypes()
        entries["_rels/.rels"] = rootRels()
        entries["docProps/core.xml"] = coreProps(t.ifEmpty { out.nameWithoutExtension })
        entries["word/document.xml"] = document(body.toString())
        entries["word/_rels/document.xml.rels"] = documentRels()
        entries["word/styles.xml"] = styles()
        entries["word/numbering.xml"] = numbering()

        try {
            out.parentFile?.mkdirs()
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return err("WRITE_FAILED", "写文件失败：${e.message}")
        }

        val words = chars
        return JSONObject()
            .put("ok", true)
            .put("path", out.name)
            .put("blocks", blocks.length())
            .put("bytes", out.length())
            .put("note", "DOCX 已生成（A4、1.5 倍行距、正文首行缩进两字、标题带大纲级别，WPS/Word 可直接编辑；" +
                "块上可用 font/size/bold/align/line 逐段指定字体字号行距对齐）。" +
                "共 $words 字。" +
                "要图表或精确分页改 pdf_write；要演示文稿改 pptx_write")
            .toString()    }

    // ------------------------------------------------------------ 段落

    private const val PAGE_BREAK = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"

    /**
     * 行内格式：**粗体** 和 `代码` 都成真样式，不是留在正文里的记号。
     *
     * rPr 子元素也讲顺序（rFonts → b → color → sz），而且 color 只能出现一次——
     * 所以代码段的配色先合并再写，不能在标题里写两遍 color 让 Word 判非法。
     */
    private fun runs(
        text: String,
        size: Int = BODY_SZ,
        color: String? = null,
        bold: Boolean = false,
        st: BlockStyle? = null,
    ): String {
        val sb = StringBuilder()
        val effSize = st?.size ?: size
        val effFont = st?.font
        val effAscii = st?.ascii ?: effFont
        for (seg in Marked.inline(text)) {
            if (seg.text.isEmpty()) continue
            val ink = if (seg.code) ACCENT else color
            val sz = if (seg.code) maxOf(effSize - 1, BODY_SZ - 1) else effSize
            val rPr = StringBuilder()
            // rPr 子元素顺序同样是 schema 定死的：rFonts → b → color → sz
            if (seg.code) {
                rPr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:eastAsia=\"等线\"/>")
            } else if (effFont != null) {
                rPr.append(
                    if (effAscii != null && effAscii != effFont)
                        "<w:rFonts w:ascii=\"$effAscii\" w:hAnsi=\"$effAscii\" w:eastAsia=\"$effFont\"/>"
                    else "<w:rFonts w:ascii=\"$effFont\" w:hAnsi=\"$effFont\" w:eastAsia=\"$effFont\"/>"
                )
            }
            // 块级 bold 是「定死」：显式给了 true/false 就以它为准，没给才沿用段落默认（标题粗、正文不粗）
            val wantBold = st?.bold ?: (bold || seg.bold)
            if (wantBold) rPr.append("<w:b/><w:bCs/>")
            if (ink != null) rPr.append("<w:color w:val=\"$ink\"/>")
            if (sz != BODY_SZ) rPr.append("<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/>")
            sb.append("<w:r>")
            if (rPr.isNotEmpty()) sb.append("<w:rPr>").append(rPr).append("</w:rPr>")
            // xml:space=preserve：中文段首空格、英文单词间空格才不会被吃掉
            sb.append("<w:t xml:space=\"preserve\">").append(esc(seg.text)).append("</w:t></w:r>")
        }
        return sb.toString()
    }

    /**
     * 文档大标题：居中、22pt 深蓝、标题下一条细线。
     *
     * 给了 title_style 就按它来（论文题目「宋体三号加粗」这类），并且**去掉装饰线**——
     * 题目下划横线过不了格式审查。颜色也回到纯黑。
     *
     * pPr 的子元素顺序是 schema 定死的：pBdr → spacing → jc。顺序写反 Word 会
     * 认为是「内容有错误」弹修复提示，WPS 更敏感——所以这里刻意按序拼。
     */
    private fun titlePara(title: String, st: BlockStyle? = null): String {
        val rule = if (st == null)
            "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"8\" w:color=\"$RULE\"/></w:pBdr>"
        else ""
        val spacing = "<w:spacing w:before=\"240\" w:after=\"360\"/>"
        val jc = "<w:jc w:val=\"${st?.align ?: "center"}\"/>"
        return "<w:p><w:pPr>$rule$spacing$jc</w:pPr>" +
            runs(title, size = st?.size ?: 44, color = if (st == null) TITLE_INK else null,
                bold = st?.bold ?: true, st = st) + "</w:p>"
    }

    /** h1/h2/h3：走内置标题样式（带大纲级别，导航窗格能跳），块级样式直接覆写字号与字体。 */
    private fun headingPara(text: String, level: Int, st: BlockStyle? = null): String {
        val style = "Heading" + level.coerceIn(1, 3)
        val size = st?.size ?: when (level) { 1 -> 32; 2 -> 28; else -> 24 }
        val color = if (level == 1) TITLE_INK else if (level == 2) HEAD_INK else ACCENT
        return "<w:p><w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" +
            runs(text, size = size, color = color, bold = true, st = st) + "</w:p>"
    }

    /** 正文：首行缩进两字（firstLineChars 按字符算，换字号也不会缩错）；显式给对齐就不再缩。 */
    private fun bodyPara(text: String, st: BlockStyle? = null): String {
        val pPr = StringBuilder()
        // pPr 顺序：spacing → ind → jc
        st?.line?.let { pPr.append("<w:spacing w:line=\"$it\" w:lineRule=\"auto\"/>") }
        if (st?.noIndent != true) pPr.append("<w:ind w:firstLineChars=\"200\" w:firstLine=\"420\"/>")
        st?.align?.let { pPr.append("<w:jc w:val=\"$it\"/>") }
        return "<w:p><w:pPr>$pPr</w:pPr>" + runs(text, st = st) + "</w:p>"
    }

    /** 要点：真项目符号（numbering.xml 里定义的 numId=1），不是手打的 •。 */
    private fun bulletPara(text: String, st: BlockStyle? = null): String {
        val line = st?.line?.let { " w:line=\"$it\" w:lineRule=\"auto\"" } ?: ""
        val jc = st?.align?.let { "<w:jc w:val=\"$it\"/>" } ?: ""
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>" +
            "<w:spacing w:after=\"80\"$line/>" + jc + "</w:pPr>" +
            runs(text, st = st) + "</w:p>"
    }

    /** 引用：左侧品牌色竖线 + 缩进 + 灰字（同样按 pBdr → spacing → ind → jc 的 schema 顺序）。 */
    private fun quotePara(text: String, st: BlockStyle? = null): String {
        val line = st?.line?.let { " w:line=\"$it\" w:lineRule=\"auto\"" } ?: ""
        val jc = st?.align?.let { "<w:jc w:val=\"$it\"/>" } ?: ""
        return "<w:p><w:pPr>" +
            "<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"$ACCENT\"/></w:pBdr>" +
            "<w:spacing w:before=\"120\" w:after=\"160\"$line/>" +
            "<w:ind w:left=\"480\" w:right=\"240\"/>" + jc +
            "</w:pPr>" + runs(text, color = QUOTE_INK, st = st) + "</w:p>"
    }

    // ------------------------------------------------------------ 部件

    private fun document(body: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="$PAGE_W" w:h="$PAGE_H"/><w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="851" w:footer="992" w:gutter="0"/><w:cols w:space="425"/><w:docGrid w:type="lines" w:linePitch="312"/></w:sectPr></w:body></w:document>"""

    private fun contentTypes(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private fun documentRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/></Relationships>"""

    private fun coreProps(title: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title)}</dc:title><dc:creator>月溯</dc:creator><cp:lastModifiedBy>月溯</cp:lastModifiedBy><dcterms:created xsi:type="dcterms:W3CDTF">${Instant.now()}</dcterms:created></cp:coreProperties>"""

    /**
     * 样式表：默认字体 + 行距，三个标题级别（带 outlineLvl，导航窗格/TOC 认），
     * 引用样式留空壳（实际格式在段落上直接给，WPS 各版本表现更稳）。
     */
    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="等线" w:cs="Times New Roman"/><w:sz w:val="$BODY_SZ"/><w:szCs w:val="$BODY_SZ"/><w:lang w:val="en-US" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:line="360" w:lineRule="auto" w:after="120"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="180"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b/><w:color w:val="$TITLE_INK"/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="280" w:after="140"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b/><w:color w:val="$HEAD_INK"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="220" w:after="120"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:rFonts w:eastAsia="微软雅黑"/><w:b/><w:color w:val="$ACCENT"/><w:sz w:val="24"/><w:szCs w:val="24"/></w:rPr></w:style></w:styles>"""

    /** 项目符号定义：一个符号层级就够，缩进 420 twips（约两字）。 */
    private fun numbering(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="420" w:hanging="420"/></w:pPr><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:hint="default"/></w:rPr></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>"""

    private fun err(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("error", message).toString()
}
