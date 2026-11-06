package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PPTX 生成器（纯 JVM，不引 Android API——这样能在 tools/test_agent.py 里跑真断言）。
 *
 * 为什么手写 OOXML 而不引依赖：工程零依赖是铁律，PPTX 本质是个 zip + 一组 XML，
 * 文字版式（标题 + 要点列表）自己拼完全可行。dsh-Go 用 proot Ubuntu 装 python-pptx，
 * 我们用 200 行 XML 拼装达到同一个结果：模型给结构化内容，应用产出真正的 .pptx。
 *
 * 兼容性口径：以 WPS（国内手机上最常见）和 PowerPoint 的「最小可开」为准——
 * 包含 ContentTypes、presentation、slideMaster、slideLayout、theme 完整骨架，
 * 每页两个文本框（标题 + 要点列表）。做不了花哨排版，但「内容正确的可编辑 PPT」是底线。
 */
object PptxWriter {

    /** 16:9 幻灯片尺寸（EMU）。1 英寸 = 914400 EMU；13.333 × 7.5 英寸。 */
    private const val SLIDE_W = 12192000
    private const val SLIDE_H = 6858000

    // 版式配色（跟月溯雾蓝主题走）。封面深蓝底，内容页白底 + 品牌色点缀。
    private const val COVER_BG = "14395B"
    private const val COVER_SUB = "C9D9F2"
    private const val ACCENT = "4B67B4"
    private const val TITLE_INK = "14395B"
    private const val BODY_INK = "1F2933"
    private const val FOOT_INK = "8A94A6"
    private const val FOOT_LINE = "DCE4F2"

    /** 单页要点上限。超出会被截断并明说——模型需要知道「你看的不是全部」。 */
    const val MAX_BULLETS = 12
    const val MAX_SLIDES = 50

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun write(out: File, slides: JSONArray): String {
        val n = slides.length()
        if (n == 0) {
            return err("EMPTY", "slides 不能为空。至少给一页 {title, bullets}")
        }
        if (n > MAX_SLIDES) {
            return err("TOO_MANY", "页数上限 $MAX_SLIDES，收到 $n 页。拆成两个文件")
        }

        val entries = LinkedHashMap<String, String>()
        entries["[Content_Types].xml"] = contentTypes(n)
        entries["_rels/.rels"] = rootRels()
        entries["ppt/presentation.xml"] = presentation(n)
        entries["ppt/_rels/presentation.xml.rels"] = presentationRels(n)
        entries["ppt/slideMasters/slideMaster1.xml"] = master()
        entries["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = masterRels()
        entries["ppt/slideLayouts/slideLayout1.xml"] = layout()
        entries["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = layoutRels()
        entries["ppt/theme/theme1.xml"] = theme()
        for (i in 0 until n) {
            entries["ppt/slides/slide${i + 1}.xml"] = slide(slides.optJSONObject(i), i, n)
            entries["ppt/slides/_rels/slide${i + 1}.xml.rels"] = slideRels()
        }

        try {
            out.parentFile?.mkdirs()
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for ((name, body) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return err("WRITE_FAILED", "写文件失败：${e.message}")
        }

        return JSONObject()
            .put("ok", true)
            .put("path", out.name)
            .put("slides", n)
            .put("bytes", out.length())
            .put("note", "PPTX 已生成：第 1 页自动作为封面（深蓝底居中大标题），内容页带品牌色竖条标题与页码，文字均可编辑。要图表或自由排版请用 pdf_write 或写 HTML")
            .toString()
    }

    // ------------------------------------------------------------ 各部件

    private fun contentTypes(n: Int): String {
        val slides = StringBuilder()
        for (i in 1..n) {
            slides.append("<Override PartName=\"/ppt/slides/slide$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>$slides</Types>"""
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>"""

    private fun presentation(n: Int): String {
        val ids = StringBuilder()
        for (i in 1..n) {
            ids.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx="$SLIDE_W" cy="$SLIDE_H"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""
    }

    private fun presentationRels(n: Int): String {
        val rels = StringBuilder()
        rels.append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        for (i in 1..n) {
            rels.append("<Relationship Id=\"rId").append(i + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
                .append(i).append(".xml\"/>")
        }
        rels.append("<Relationship Id=\"rId").append(n + 2)
            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>"""
    }

    private fun master(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>"""

    private fun masterRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>"""

    private fun layout(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private fun layoutRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

    private fun slideRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""

    /** 矩形色块（无描边）。位置尺寸走 EMU。 */
    private fun rect(id: Int, x: Int, y: Int, cx: Int, cy: Int, color: String): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Shape$id\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"zh-CN\"/></a:p></p:txBody></p:sp>"

    /** 文本框。align: l/ctr/r；lines 是已拼好的 <a:p> 序列。 */
    private fun txBox(id: Int, name: String, x: Int, y: Int, cx: Int, cy: Int, body: String): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>$body</p:txBody></p:sp>"

    /** 第 1 页当封面：深蓝满版底 + 居中大标题 + 副标题行 + 底部品牌色条。 */
    private fun coverSlide(s: JSONObject?): String {
        val title = s?.optString("title").orEmpty().trim().ifEmpty { "（无标题）" }
        val subs = ArrayList<String>()
        val arr = s?.optJSONArray("bullets")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.optString(i).trim()
                if (t.isNotEmpty()) subs.add(t)
                if (subs.size >= 4) break
            }
        }
        val subBody = if (subs.isEmpty()) {
            "<a:p><a:endParaRPr lang=\"zh-CN\"/></a:p>"
        } else {
            subs.joinToString("") {
                "<a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr lang=\"zh-CN\" sz=\"1600\">" +
                    "<a:solidFill><a:srgbClr val=\"$COVER_SUB\"/></a:solidFill></a:rPr>" +
                    "<a:t>" + esc(it) + "</a:t></a:r></a:p>"
            }
        }
        val titleBox = txBox(
            3, "CoverTitle", 914400, 2200000, SLIDE_W - 1828800, 1600000,
            "<a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr lang=\"zh-CN\" sz=\"4400\" b=\"1\">" +
                "<a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:rPr>" +
                "<a:t>" + esc(title) + "</a:t></a:r></a:p>"
        )
        val subTitle = txBox(
            4, "CoverSub", 1371600, 3900000, SLIDE_W - 2743200, 1600000, subBody
        )
        // 绘制顺序 = 叠放顺序：背景最先，色条最后压在上面
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""" +
            rect(2, 0, 0, SLIDE_W, SLIDE_H, COVER_BG) +
            rect(5, 0, SLIDE_H - 91440, SLIDE_W, 91440, ACCENT) +
            titleBox + subTitle +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    /**
     * 内容页：左侧品牌色竖条 + 标题、要点带行距和彩色项目符号、页脚细线 + 页码。
     * 文本都是真文本框，WPS / PowerPoint 里照样可编辑。
     */
    private fun slide(s: JSONObject?, index: Int, total: Int): String {
        if (index == 0) return coverSlide(s)

        val title = s?.optString("title").orEmpty().trim()
        val bulletsRaw = s?.optJSONArray("bullets") ?: JSONArray()
        val bullets = StringBuilder()
        var shown = 0
        for (i in 0 until bulletsRaw.length()) {
            if (shown >= MAX_BULLETS) break
            val t = bulletsRaw.optString(i).trim()
            if (t.isEmpty()) continue
            bullets.append(
                "<a:p><a:pPr marL=\"292100\" indent=\"-292100\"><a:spcBef><a:spcPts val=\"1400\"/></a:spcBef>" +
                    "<a:buClr><a:srgbClr val=\"$ACCENT\"/></a:buClr><a:buChar char=\"•\"/></a:pPr>" +
                    "<a:r><a:rPr lang=\"zh-CN\" sz=\"2000\"><a:solidFill><a:srgbClr val=\"$BODY_INK\"/></a:solidFill></a:rPr>" +
                    "<a:t>" + esc(t) + "</a:t></a:r></a:p>"
            )
            shown++
        }
        if (shown == 0) {
            bullets.append("<a:p><a:endParaRPr lang=\"zh-CN\"/></a:p>")
        }

        val titleBox = txBox(
            3, "Title", 1143000, 628650, SLIDE_W - 2057400, 900000,
            "<a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"2800\" b=\"1\">" +
                "<a:solidFill><a:srgbClr val=\"$TITLE_INK\"/></a:solidFill></a:rPr>" +
                "<a:t>" + esc(title.ifEmpty { "（无标题）" }) + "</a:t></a:r></a:p>"
        )
        val bodyBox = txBox(
            4, "Content", 1000000, 1900000, SLIDE_W - 2000000, SLIDE_H - 1900000 - 900000, bullets.toString()
        )
        val pageBox = txBox(
            6, "PageNo", SLIDE_W - 1600000, SLIDE_H - 480000, 900000, 300000,
            "<a:p><a:pPr algn=\"r\"/><a:r><a:rPr lang=\"zh-CN\" sz=\"1100\">" +
                "<a:solidFill><a:srgbClr val=\"$FOOT_INK\"/></a:solidFill></a:rPr>" +
                "<a:t>" + (index + 1) + " / " + total + "</a:t></a:r></a:p>"
        )

        // 绘制顺序 = 叠放顺序：竖条 → 标题 → 正文 → 页脚线 → 页码
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""" +
            rect(2, 914400, 700000, 91440, 740000, ACCENT) +
            rect(5, 914400, SLIDE_H - 460000, SLIDE_W - 1828800, 12700, FOOT_LINE) +
            titleBox + bodyBox + pageBox +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private fun theme(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="yuesu"><a:themeElements><a:clrScheme name="yuesu"><a:dk1><a:srgbClr val="1F2933"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="14395B"/></a:dk2><a:lt2><a:srgbClr val="DEE8FF"/></a:lt2><a:accent1><a:srgbClr val="4B67B4"/></a:accent1><a:accent2><a:srgbClr val="3D569D"/></a:accent2><a:accent3><a:srgbClr val="6B7280"/></a:accent3><a:accent4><a:srgbClr val="A8C4DD"/></a:accent4><a:accent5><a:srgbClr val="E3E7EC"/></a:accent5><a:accent6><a:srgbClr val="8A94A6"/></a:accent6><a:hlink><a:srgbClr val="4B67B4"/></a:hlink><a:folHlink><a:srgbClr val="3D569D"/></a:folHlink></a:clrScheme><a:fontScheme name="yuesu"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface="微软雅黑"/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface="微软雅黑"/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="yuesu"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""

    private fun err(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("error", message).toString()
}
