package com.vibratez.ledger.ledger

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LedgerExportFormat { CSV, XLSX }

class LedgerExporter(
    private val context: Context,
    private val ledgerStore: LedgerStore,
) {
    suspend fun export(uri: Uri, format: LedgerExportFormat, enabledFields: Set<String>): Int =
        withContext(Dispatchers.IO) {
            val records = ledgerStore.all()
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("Unable to open export destination")
            output.use {
                when (format) {
                    LedgerExportFormat.CSV -> writeCsv(it, records, enabledFields)
                    LedgerExportFormat.XLSX -> writeXlsx(it, records, enabledFields)
                }
            }
            records.size
        }

    private fun writeCsv(output: OutputStream, records: List<TransactionRecord>, fields: Set<String>) {
        val columns = columns(fields)
        output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        output.writer(StandardCharsets.UTF_8).use { writer ->
            writer.appendLine(columns.joinToString(",") { csv(it.title) })
            records.forEach { record ->
                writer.appendLine(columns.joinToString(",") { csv(it.value(record)) })
            }
        }
    }

    private fun writeXlsx(output: OutputStream, records: List<TransactionRecord>, fields: Set<String>) {
        val columns = columns(fields)
        ZipOutputStream(output).use { zip ->
            zip.entry("[Content_Types].xml", CONTENT_TYPES)
            zip.entry("_rels/.rels", ROOT_RELS)
            zip.entry("xl/workbook.xml", WORKBOOK)
            zip.entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            val rows = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                appendRow(1, columns.map { it.title })
                records.forEachIndexed { index, record ->
                    appendRow(index + 2, columns.map { it.value(record) })
                }
                append("</sheetData></worksheet>")
            }
            zip.entry("xl/worksheets/sheet1.xml", rows)
        }
    }

    private fun StringBuilder.appendRow(index: Int, values: List<String>) {
        append("<row r=\"").append(index).append("\">")
        values.forEachIndexed { column, value ->
            append("<c r=\"").append(columnName(column)).append(index)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(xml(value)).append("</t></is></c>")
        }
        append("</row>")
    }

    private fun ZipOutputStream.entry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private data class Column(
        val key: String,
        val title: String,
        val value: (TransactionRecord) -> String,
    )

    private fun columns(enabled: Set<String>): List<Column> {
        val normalized = enabled.mapTo(mutableSetOf()) { it.lowercase() }
        val optional = listOf(
            Column("direction", "收支方向") { it.direction },
            Column("amount", "金额（最小货币单位）") { it.amountMinor?.toString().orEmpty() },
            Column("currency", "币种") { it.currency.orEmpty() },
            Column("occurred_at", "交易时间") { it.occurredAt.orEmpty() },
            Column("merchant", "商户") { it.merchant.orEmpty() },
            Column("counterparty", "交易对方") { it.counterparty.orEmpty() },
            Column("item_name", "项目") { it.itemName.orEmpty() },
            Column("platform", "平台") { it.platform },
            Column("external_id", "订单/交易号") { it.externalId.orEmpty() },
            Column("tag", "标签") { it.suggestedTag.orEmpty() },
            Column("account", "账户") { it.account.orEmpty() },
            Column("payment_method", "付款方式") { it.paymentMethod.orEmpty() },
            Column("note", "备注") { it.note.orEmpty() },
        ).filter { it.key in normalized }
        return listOf(Column("id", "ID") { it.id }) + optional + listOf(
            Column("source", "来源") { it.localDecision },
            Column("created", "创建时间戳") { it.createdAtMillis.toString() },
        )
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun columnName(index: Int): String {
        var value = index + 1
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    private companion object {
        const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
        const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="账本" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
    }
}
