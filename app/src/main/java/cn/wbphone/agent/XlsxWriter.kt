package cn.wbphone.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * .xlsx 生成：纯 JVM，零依赖。和 [DocxWriter] 同一套手艺——xlsx 本质也是 zip 包 XML。
 *
 * 为什么用 inlineStr 而不是共享字符串表：sharedStrings 要求先扫全表收集字符串再编号，
 * 两趟；inlineStr 把文本直接写进单元格（`t="inlineStr"`），一趟写完，WPS/Excel 都认。
 * 省的是实现复杂度，文件大一点点（每格多几个字节），可接受。
 *
 * 样式只做一件事：首行加粗 + 冻结首行（header:true，默认开）——「表格」这两个字的
 * 最小共识。列宽按内容自适应（中文按两列宽算），太窄的列读起来像密码。
 *
 * 单元格类型按值自动判：JSON number → 数值格（Excel 里能求和排序），boolean → 布尔格，
 * 其余 → 文本格。模型把数字写成 "123" 字符串也兜得住：长得像数字就存成数值。
 */
object XlsxWriter {

    /** sheet 数上限。多 sheet 的表格先想想是不是该拆文件。 */
    const val MAX_SHEETS = 8

    /** 单 sheet 行数上限。一万行的表要靠 fs_write + CSV，不靠手工 JSON。 */
    const val MAX_ROWS = 10_000

    /** 单行列数上限。Excel 上限一万六千列，我们到 256 就够——再多是人没在干活。 */
    const val MAX_COLS = 256

    /** 列宽自适应的上下界（Excel 列宽单位 ≈ 字符数）。 */
    private const val MIN_COL_W = 6.0
    private const val MAX_COL_W = 50.0

    fun write(out: File, sheets: JSONArray, header: Boolean): String {
        if (sheets.length() == 0) {
            return err("BAD_ARGS", "sheets 不能为空。给一组 {name:\"...\", rows:[[...],[...]]}")
        }
        if (sheets.length() > MAX_SHEETS) {
            return err("TOO_MANY", "sheet 上限 $MAX_SHEETS 个，收到 ${sheets.length()}。拆文件或合并数据")
        }

        val names = ArrayList<String>(sheets.length())
        val sheetXml = ArrayList<String>(sheets.length())
        var totalRows = 0
        var totalCells = 0

        for (si in 0 until sheets.length()) {
            val s = sheets.optJSONObject(si)
            if (s == null) return err("BAD_ARGS", "sheets[$si] 不是对象")
            val name = s.optString("name", "").trim().ifEmpty { "Sheet${si + 1}" }
            // Excel 禁的字符：: \ / ? * [ ]，名字也限 31 字——照做，别让 WPS 打不开
            val safeName = name.replace(Regex("[\\\\/:?*\\[\\]]"), "_").take(31)
            if (names.any { it.equals(safeName, ignoreCase = true) }) {
                return err("BAD_NAME", "sheet 名重复：$safeName。换个名字")
            }
            val rows = s.optJSONArray("rows")
            if (rows == null || rows.length() == 0) {
                return err("BAD_ARGS", "sheets[$si].rows 不能为空——空 sheet 没有意义")
            }
            if (rows.length() > MAX_ROWS) {
                return err("TOO_MANY", "sheet「$safeName」行数上限 $MAX_ROWS，收到 ${rows.length()}。改用 CSV")
            }
            var widest = 0
            for (ri in 0 until rows.length()) {
                widest = maxOf(widest, rows.optJSONArray(ri)?.length() ?: 0)
            }
            if (widest > MAX_COLS) {
                return err("TOO_MANY", "sheet「$safeName」列数上限 $MAX_COLS，收到 $widest。宽表改用 CSV")
            }
            names.add(safeName)
            val (xml, cells) = sheetXml(rows, header && rows.length() > 0)
            sheetXml.add(xml)
            totalRows += rows.length()
            totalCells += cells
        }

        val entries = LinkedHashMap<String, String>()
        entries["[Content_Types].xml"] = contentTypes(names.size)
        entries["_rels/.rels"] = rootRels()
        entries["xl/workbook.xml"] = workbook(names)
        entries["xl/_rels/workbook.xml.rels"] = workbookRels(names.size)
        for ((i, xml) in sheetXml.withIndex()) {
            entries["xl/worksheets/sheet${i + 1}.xml"] = xml
        }
        entries["xl/styles.xml"] = styles()

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

        return JSONObject()
            .put("ok", true)
            .put("sheets", names.size)
            .put("rows", totalRows)
            .put("cells", totalCells)
            .put("note", "XLSX 已生成（首行加粗冻结、列宽自适应、数字存成数值格，Excel/WPS 可直接编辑）。" +
                "要图表配数据用 chart_write，要文档用 docx_write")
            .toString()
    }

    /** 一个 sheet 的 XML 与单元格计数。 */
    private fun sheetXml(rows: JSONArray, header: Boolean): Pair<String, Int> {
        // 按「列数上限」开数组，不是按行数——列下标访问行数大小的数组，
        // 1 行 2 列的表就会越界（写过就崩的那种错，测试真抓到过）
        val maxCols = IntArray(MAX_COLS)
        var cells = 0
        val rowXml = StringBuilder()
        for (ri in 0 until rows.length()) {
            val row = rows.optJSONArray(ri) ?: continue
            val sb = StringBuilder()
            for (ci in 0 until row.length()) {
                val v = row.opt(ci) ?: continue
                val ref = colName(ci) + (ri + 1)
                val cell = cellXml(ref, v, header && ri == 0) ?: continue
                sb.append(cell)
                cells++
                maxCols[ci] = maxOf(maxCols[ci], displayWidth(v))
            }
            if (sb.isNotEmpty()) {
                rowXml.append("<row r=\"").append(ri + 1).append("\">").append(sb).append("</row>")
            }
        }

        val cols = StringBuilder()
        var any = false
        for (ci in 0 until maxCols.size) {
            if (maxCols[ci] <= 0) continue
            any = true
            // 内容宽 + 2 的呼吸感；表头行也在 maxCols 里（displayWidth 已含）
            val w = (maxCols[ci] + 2).coerceIn(MIN_COL_W.toInt(), MAX_COL_W.toInt()).toDouble()
            cols.append("<col min=\"").append(ci + 1).append("\" max=\"").append(ci + 1)
                .append("\" width=\"").append(w).append("\" customWidth=\"1\"/>")
        }

        val view = if (header) {
            "<sheetViews><sheetView workbookViewId=\"0\">" +
                "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>" +
                "</sheetView></sheetViews>"
        } else ""

        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            view +
            (if (any) "<cols>$cols</cols>" else "") +
            "<sheetData>$rowXml</sheetData></worksheet>"
        return Pair(xml, cells)
    }

    /** 单元格 → XML。返回 null 表示这个值没法存（跳过，不留坏格）。 */
    private fun cellXml(ref: String, v: Any, bold: Boolean): String? {
        val style = if (bold) " s=\"1\"" else ""
        return when (v) {
            is Number -> {
                if (v.toDouble().isNaN() || v.toDouble().isInfinite()) return null
                "<c r=\"$ref\"$style><v>$v</v></c>"
            }
            is Boolean -> "<c r=\"$ref\"$style t=\"b\"><v>${if (v) 1 else 0}</v></c>"
            is String -> {
                val t = v.trim()
                if (t.isEmpty()) return null
                // 模型把数字写成字符串很常见；长得像数字就存数值格，用户才能求和
                val asNum = t.toBigDecimalOrNull()
                if (asNum != null && t.length <= 15 && asNum.toDouble().isFinite()) {
                    return "<c r=\"$ref\"$style><v>${t.toDouble()}</v></c>"
                }
                "<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(t)}</t></is></c>"
            }
            else -> null
        }
    }

    /** 显示宽度：中文按 2 列算（Excel 的列宽语义本来就是「能放下几个半角字符」）。 */
    private fun displayWidth(v: Any): Int = when (v) {
        is Number -> v.toString().length
        is Boolean -> 5
        is String -> v.map { if (it.code > 0x2E7F) 2 else 1 }.sum().coerceAtMost(60)
        else -> 0
    }

    /** 0 基列号 → A、B、…、Z、AA、AB。 */
    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun err(code: String, message: String): String = JSONObject()
        .put("ok", false).put("error", code).put("message", message).toString()

    // ------------------------------------------------------------ 部件

    private fun workbook(names: List<String>): String {
        val sheets = names.mapIndexed { i, n ->
            "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>"
        }.joinToString("")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets>$sheets</sheets></workbook>"
    }

    private fun workbookRels(sheetCount: Int): String {
        val rels = (1..sheetCount).map { i ->
            "<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                "Target=\"worksheets/sheet$i.xml\"/>"
        }.joinToString("")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels" +
            "<Relationship Id=\"rId${sheetCount + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" " +
            "Target=\"styles.xml\"/></Relationships>"
    }

    private fun contentTypes(sheetCount: Int): String {
        val sheets = (1..sheetCount).map { i ->
            "<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }.joinToString("")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            sheets +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"
    }

    private fun rootRels(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
            "Target=\"xl/workbook.xml\"/></Relationships>"

    /** 字体 0 常规、字体 1 加粗；xf 0 常规、xf 1 加粗。最小合法样式表。 */
    private fun styles(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "</cellXfs></styleSheet>"
}
