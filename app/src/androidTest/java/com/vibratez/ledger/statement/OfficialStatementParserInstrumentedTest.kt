package com.vibratez.ledger.statement

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibratez.ledger.vlm.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OfficialStatementParserInstrumentedTest {
    @Test
    fun validatesWechatCsvOnAndroidRuntime() {
        val csv = """
            微信支付账单明细
            交易时间,交易类型,交易对方,商品,收/支,金额(元),当前状态,交易单号
            2026-09-16 08:30:00,商户消费,测试商户,早餐,支出,12.34,支付成功,wx-android-1
        """.trimIndent()

        val result = OfficialStatementParser.parse("微信账单.csv", csv.toByteArray())

        assertTrue(result is StatementParseResult.Valid)
        val transaction = (result as StatementParseResult.Valid).preview.transactions.single()
        assertEquals(Direction.EXPENSE, transaction.direction)
        assertEquals(1_234L, transaction.amountMinor)
    }

    @Test
    fun validatesAlipayXlsxOnAndroidRuntime() {
        val rows = listOf(
            listOf("交易号", "交易创建时间", "最近修改时间", "类型", "交易对方", "商品名称", "金额(元)", "收/支", "交易状态", "成功退款(元)"),
            listOf("ali-android-1", "2026-09-15 10:00:00", "2026-09-16 11:00:00", "消费", "测试商户", "测试商品", "10.00", "支出", "退款成功", "2.00"),
        )

        val result = OfficialStatementParser.parse("支付宝账单.xlsx", workbook(rows))

        assertTrue(result is StatementParseResult.Valid)
        val transactions = (result as StatementParseResult.Valid).preview.transactions
        assertEquals(listOf(Direction.EXPENSE, Direction.REFUND), transactions.map { it.direction })
        assertEquals(listOf(1_000L, 200L), transactions.map { it.amountMinor })
    }

    private fun workbook(rows: List<List<String>>): ByteArray {
        val worksheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"${rowIndex + 1}\">")
                row.forEachIndexed { columnIndex, value ->
                    val column = ('A'.code + columnIndex).toChar()
                    append("<c r=\"$column${rowIndex + 1}\" t=\"inlineStr\"><is><t>$value</t></is></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(worksheet.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            output.toByteArray()
        }
    }
}
