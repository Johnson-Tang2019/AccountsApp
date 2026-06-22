package com.abyssredemption.accounts

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedRecord(
    val paidAt: Long,
    val kind: String,
    val category: String,
    val merchant: String,
    val amount: Double,
    val source: String,
    val note: String
)

object XlsxManager {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
        timeZone = TimeZone.getDefault()
        isLenient = false
    }
    private val headers = listOf("时间", "类型", "分类", "名称", "金额", "来源", "备注")

    @Synchronized
    fun export(output: OutputStream, records: List<PaymentRecord>) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.writeEntry("[Content_Types].xml", contentTypes())
            zip.writeEntry("_rels/.rels", rootRelationships())
            zip.writeEntry("xl/workbook.xml", workbook())
            zip.writeEntry("xl/_rels/workbook.xml.rels", workbookRelationships())
            zip.writeEntry("xl/styles.xml", styles())
            zip.writeEntry("xl/worksheets/sheet1.xml", worksheet(records.sortedBy { it.paidAt }))
        }
    }

    @Synchronized
    fun import(input: InputStream): List<ImportedRecord> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name == "xl/sharedStrings.xml" || entry.name == "xl/worksheets/sheet1.xml")) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: error("XLSX 中未找到第一个工作表")
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return parseSheet(sheet, sharedStrings)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val document = document(bytes)
        val nodes = document.getElementsByTagName("si")
        return (0 until nodes.length).map { nodes.item(it).textContent.orEmpty() }
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): List<ImportedRecord> {
        val rows = document(bytes).getElementsByTagName("row")
        val result = mutableListOf<ImportedRecord>()
        for (rowIndex in 1 until rows.length) {
            val row = rows.item(rowIndex) as? Element ?: continue
            val values = MutableList(7) { "" }
            val cells = row.getElementsByTagName("c")
            for (cellIndex in 0 until cells.length) {
                val cell = cells.item(cellIndex) as? Element ?: continue
                val column = columnIndex(cell.getAttribute("r"))
                if (column !in values.indices) continue
                val type = cell.getAttribute("t")
                val raw = when (type) {
                    "inlineStr" -> cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                    else -> cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                }
                values[column] = if (type == "s") sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
            }
            val amount = values[4].replace(",", "").toDoubleOrNull() ?: continue
            val paidAt = runCatching { dateFormat.parse(values[0])?.time }.getOrNull() ?: continue
            val kind = when (values[1].trim()) {
                "收入", "income" -> "income"
                else -> "expense"
            }
            result += ImportedRecord(
                paidAt = paidAt,
                kind = kind,
                category = values[2].ifBlank { "其他" },
                merchant = values[3].ifBlank { values[2].ifBlank { "导入记录" } },
                amount = amount,
                source = values[5].ifBlank { "XLSX 导入" },
                note = values[6]
            )
        }
        return result
    }

    private fun document(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile(Char::isLetter)
        var value = 0
        letters.forEach { value = value * 26 + (it.uppercaseChar() - 'A' + 1) }
        return value - 1
    }

    private fun worksheet(records: List<PaymentRecord>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft"/></sheetView></sheetViews>""")
        append("""<cols><col min="1" max="1" width="21" customWidth="1"/><col min="2" max="3" width="12" customWidth="1"/><col min="4" max="4" width="24" customWidth="1"/><col min="5" max="5" width="14" customWidth="1"/><col min="6" max="7" width="18" customWidth="1"/></cols><sheetData>""")
        append("<row r=\"1\" ht=\"24\" customHeight=\"1\">")
        headers.forEachIndexed { index, value -> append(textCell(index, 1, value, 1)) }
        append("</row>")
        records.forEachIndexed { index, record ->
            val row = index + 2
            append("<row r=\"$row\">")
            append(textCell(0, row, dateFormat.format(Date(record.paidAt))))
            append(textCell(1, row, if (record.kind == "income") "收入" else "支出"))
            append(textCell(2, row, record.category))
            append(textCell(3, row, record.merchant))
            append(numberCell(4, row, record.amount))
            append(textCell(5, row, record.source))
            append(textCell(6, row, record.note))
            append("</row>")
        }
        append("</sheetData><autoFilter ref=\"A1:G${records.size + 1}\"/></worksheet>")
    }

    private fun textCell(column: Int, row: Int, value: String, style: Int = 0) =
        "<c r=\"${cellReference(column, row)}\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"

    private fun numberCell(column: Int, row: Int, value: Double) =
        "<c r=\"${cellReference(column, row)}\" s=\"2\"><v>$value</v></c>"

    private fun cellReference(column: Int, row: Int): String {
        var number = column + 1
        var letters = ""
        while (number > 0) {
            val remainder = (number - 1) % 26
            letters = ('A' + remainder) + letters
            number = (number - 1) / 26
        }
        return "$letters$row"
    }

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""
    private fun rootRelationships() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private fun workbook() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="账单" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    private fun workbookRelationships() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE9829E"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
