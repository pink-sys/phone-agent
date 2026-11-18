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

    /** 单张图片上限。截图/图表远用不到这么大，超了多半是塞错文件。 */
    const val MAX_IMG_BYTES = 8_000_000

    /** A4 去掉页边距后的正文区（twips）与 EMU（1 twip = 635 EMU，1 cm = 360000 EMU）。 */
    private const val CONTENT_W_TW = PAGE_W - 1418 * 2   // 9070
    private const val CONTENT_H_TW = PAGE_H - 1418 * 2   // 14002
    private const val TWIP_EMU = 635L
    private const val CM_EMU = 360_000L
    private const val PX96_EMU = 9_525L                  // 96dpi 下 1px 的 EMU

    /**
     * 生成一份 .docx。返回 JSON 文本（和 Workbench 里其它工具一个口径）。
     * blocks 里每项 {type, text}，type ∈ h1/h2/h3/p/bullet/quote/pagebreak/spacer/image；
     * 可选样式字段 font / ascii / size / bold / align / line（见 [styleOf]）。
     * image 块 {type:"image", path:"shot.png", width?, text?(图注)}——path 相对 [baseDir]
     * （工作台根），baseDir 为 null 时给 image 块直接报错。
     */
    fun write(
        out: File,
        title: String,
        blocks: JSONArray,
        titleStyle: JSONObject? = null,
        baseDir: File? = null,
    ): String {
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
        // 图片块收集：文档部件里除了字符串还要塞二进制（word/media/*），
        // 关系表也不再是固定的两条——都要等块扫完才知道，所以先攒后装。
        val media = ArrayList<Pair<String, ByteArray>>()      // zipName → bytes
        val imageRels = ArrayList<Pair<String, String>>()     // rId → target
        var docPrId = 0
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
                "image", "img", "picture" -> {
                    if (baseDir == null) {
                        return err("NO_BASE", "image 块需要工作台（找不到图片解析基准目录）")
                    }
                    val imgPath = (b.optString("path", "").ifEmpty { text }).trim()
                    if (imgPath.isEmpty()) {
                        return err("BAD_IMG", "image 块要给 path（工作台里图片的相对路径）")
                    }
                    val f = runCatching { File(baseDir, imgPath).canonicalFile }.getOrNull()
                    val rootPath = baseDir.canonicalFile.path
                    if (f == null || (f.path != rootPath && !f.path.startsWith(rootPath + File.separator))) {
                        return err("IMG_OUTSIDE", "图片路径越界：$imgPath。只能引用工作台目录内的图片")
                    }
                    if (!f.isFile) {
                        return err("IMG_MISSING", "图片不存在：$imgPath。先有图再插（text_shot / 截图保存到工作台）")
                    }
                    val bytes = runCatching { f.readBytes() }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        return err("IMG_MISSING", "图片读不出来：$imgPath")
                    }
                    if (bytes.size > MAX_IMG_BYTES) {
                        return err("IMG_TOO_BIG", "图片超过 ${MAX_IMG_BYTES / 1_000_000}MB：$imgPath。压一压或换小图")
                    }
                    val dim = imageSize(bytes)
                        ?: return err("IMG_FORMAT", "认不出图片格式（只支持 PNG / JPEG）：$imgPath")
                    val ext = if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "jpg" else "png"
                    val idx = media.size + 1
                    val rid = "rIdImg$idx"
                    media.add("word/media/image$idx.$ext" to bytes)
                    imageRels.add(rid to "media/image$idx.$ext")
                    docPrId++
                    body.append(imagePara(imgSizeEmu(dim, b.optString("width").trim()), dim,
                        docPrId, rid, f.name, text, st))
                }
                else -> if (text.isNotEmpty()) body.append(bodyPara(text))
            }
        }
        // 一个字都没有的文档没有意义：模型多半是把内容塞错字段了
        if (body.isEmpty()) {
            return err("EMPTY", "所有块都是空的。给 type 和 text，至少一段正文")
        }

        val entries = LinkedHashMap<String, Any>()
        entries["[Content_Types].xml"] = contentTypes()
        entries["_rels/.rels"] = rootRels()
        entries["docProps/core.xml"] = coreProps(t.ifEmpty { out.nameWithoutExtension })
        entries["word/document.xml"] = document(body.toString())
        entries["word/_rels/document.xml.rels"] = documentRels(imageRels)
        entries["word/styles.xml"] = styles()
        entries["word/numbering.xml"] = numbering()
        for ((name, bytes) in media) entries[name] = bytes

        try {
            out.parentFile?.mkdirs()
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    when (content) {
                        is String -> zip.write(content.toByteArray(Charsets.UTF_8))
                        is ByteArray -> zip.write(content)
                    }
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
                "块上可用 font/size/bold/align/line 逐段指定字体字号行距对齐；" +
                "image 块插工作台图片，text 当图注）。" +
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

    // ------------------------------------------------------------ 图片

    /**
     * 从字节头读图片像素尺寸，返回 [宽, 高]，认不出返回 null。
     * 纯手写解析，不引任何库：PNG 读 IHDR（偏移 16/20 的大端 32 位）；
     * JPEG 扫 SOFn 段（0xC0–0xCF，挖掉 0xC4/0xC8/0xCC 这三个不是 SOF 的）。
     */
    private fun imageSize(bytes: ByteArray): IntArray? {
        if (bytes.size < 24) return null
        // PNG 签名 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return intArrayOf(be32(bytes, 16), be32(bytes, 20))
        }
        // JPEG：FF D8 开头，逐段走，找到 SOF 里的尺寸为止
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) { i++; continue }
                val m = bytes[i + 1].toInt() and 0xFF
                if (m == 0xD8 || m == 0x01 || (m in 0xD0..0xD7)) { i += 2; continue }   // 无长度段
                if (m == 0xD9) break                                                    // EOI
                val len = be16(bytes, i + 2)
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    return intArrayOf(be16(bytes, i + 7), be16(bytes, i + 5))
                }
                i += 2 + len
            }
        }
        return null
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun be16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    /**
     * 算显示尺寸（EMU）。规则：
     * - 没给 width：按 96dpi 的原生大小放；比正文区还宽的（手机截图都这样）缩到正文宽。
     *   小图不放大——放大只会糊。
     * - 给了 width：数字按「正文宽的百分比」算，带 cm 按厘米算；都钳在正文区以内。
     * 高一律按原图比例出，不认 height——强行改比例会把截图拉变形。
     */
    private fun imgSizeEmu(dim: IntArray, widthRaw: String): LongArray {
        val maxW = CONTENT_W_TW * TWIP_EMU
        val maxH = CONTENT_H_TW * TWIP_EMU
        val (pw, ph) = dim[0].coerceAtLeast(1) to dim[1].coerceAtLeast(1)
        var cx = if (widthRaw.isEmpty()) {
            val native = pw * PX96_EMU
            if (native > maxW) maxW else native
        } else {
            val cm = Regex("([\\d.]+)\\s*cm", RegexOption.IGNORE_CASE).find(widthRaw)?.groupValues?.get(1)
                ?.toDoubleOrNull()
            if (cm != null) (cm * CM_EMU).toLong()
            else {
                val pct = Regex("(\\d+(?:\\.\\d+)?)").find(widthRaw)!!.groupValues[1].toDouble()
                    .coerceIn(1.0, 100.0)
                (maxW * pct / 100.0).toLong()
            }
        }
        var cy = (cx.toDouble() * ph / pw).toLong()
        if (cy > maxH) { cy = maxH; cx = (maxH.toDouble() * pw / ph).toLong() }
        return longArrayOf(cx, cy)
    }

    /**
     * 图片段：居中的 inline drawing。子元素顺序是 schema 定死的，照 OOXML 规范拼——
     * 这块写错 Word 会弹「内容有问题」的修复提示。text 非空时底下跟一行居中小字图注。
     */
    private fun imagePara(
        emu: LongArray,
        dim: IntArray,
        docPrId: Int,
        rid: String,
        name: String,
        caption: String,
        @Suppress("UNUSED_PARAMETER") st: BlockStyle?,
    ): String {
        val (cx, cy) = emu[0] to emu[1]
        val drawing = "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
            "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
            "<wp:docPr id=\"$docPrId\" name=\"${esc(name)}\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic>" +
            "<pic:nvPicPr><pic:cNvPr id=\"$docPrId\" name=\"${esc(name)}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline>" +
            "</w:drawing></w:r></w:p>"
        if (caption.isEmpty()) return drawing
        // 图注：居中、小五、灰、不缩进（给它自己的 pPr，不走 bodyPara 的首行缩进）
        return drawing +
            "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"160\"/><w:jc w:val=\"center\"/></w:pPr>" +
            runs(caption, size = 18, color = QUOTE_INK, st = BlockStyle(
                null, null, 18, false, "center", null, true)) + "</w:p>"
    }

    // ------------------------------------------------------------ 部件

    private fun document(body: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body<w:sectPr><w:pgSz w:w="$PAGE_W" w:h="$PAGE_H"/><w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="851" w:footer="992" w:gutter="0"/><w:cols w:space="425"/><w:docGrid w:type="lines" w:linePitch="312"/></w:sectPr></w:body></w:document>"""

    private fun contentTypes(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpg" ContentType="image/jpeg"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    /** 图片关系接在固定的 rId1/rId2 之后（rIdImgN），互不冲突。 */
    private fun documentRels(images: List<Pair<String, String>> = emptyList()): String {
        val sb = StringBuilder(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>"""
        )
        for ((rid, target) in images) {
            sb.append("<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"$target\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

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
