package com.vibratez.ledger.statement

import com.vibratez.ledger.vlm.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficialStatementParserTest {
    @Test
    fun parsesWechatCsvAndSkipsIncompleteTransactions() {
        val csv = """
            微信支付账单明细
            导出类型：[全部]
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-09-16 08:30:00,商户消费,"测试,便利店",早餐,支出,¥12.34,零钱,支付成功,wx-1,m-1,
            2026-09-16 09:00:00,商户消费,另一商户,午餐,支出,20.00,零钱,交易关闭,wx-2,m-2,
        """.trimIndent()

        val result = OfficialStatementParser.parse("微信支付账单.csv", csv.toByteArray())

        assertTrue(result is StatementParseResult.Valid)
        val preview = (result as StatementParseResult.Valid).preview
        assertEquals(StatementPlatform.WECHAT, preview.platform)
        assertEquals(1, preview.transactions.size)
        assertEquals(1, preview.skippedRows)
        assertEquals(1_234L, preview.transactions.single().amountMinor)
        assertEquals("测试,便利店", preview.transactions.single().merchant)
    }

    @Test
    fun parsesAlipayGb18030AndSeparatesPartialRefund() {
        val csv = """
            支付宝交易记录明细查询
            交易号,商家订单号,交易创建时间,付款时间,最近修改时间,类型,交易对方,商品名称,金额（元）,收/支,交易状态,成功退款（元）
            ali-1,order-1,2026-09-15 10:00:00,2026-09-15 10:00:02,2026-09-16 11:00:00,消费,测试商户,测试商品,100.00,支出,退款成功,20.00
        """.trimIndent()

        val result = OfficialStatementParser.parse(
            "支付宝账单.csv",
            csv.toByteArray(Charset.forName("GB18030")),
        )

        assertTrue(result is StatementParseResult.Valid)
        val transactions = (result as StatementParseResult.Valid).preview.transactions
        assertEquals(2, transactions.size)
        assertEquals(Direction.EXPENSE, transactions[0].direction)
        assertEquals(10_000L, transactions[0].amountMinor)
        assertEquals(Direction.REFUND, transactions[1].direction)
        assertEquals(2_000L, transactions[1].amountMinor)
        assertEquals("ali-1:refund", transactions[1].externalId)
    }

    @Test
    fun parsesCurrentAlipayExportHeaders() {
        val csv = """
            支付宝交易明细
            交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-09-18 09:15:30,消费,测试商户,test@example.com,早餐,支出,12.34,余额,交易成功,ali-current-1,merchant-1,
            2026-09-18 10:15:30,退款,测试商户,test@example.com,退款-早餐,不计收支,12.34,余额,退款成功,ali-current-refund-1,merchant-1,
            2026-09-18 11:15:30,消费,测试商户,test@example.com,午餐,支出,23.45,余额,交易关闭,ali-current-closed-1,merchant-2,
        """.trimIndent()

        val result = OfficialStatementParser.parse("支付宝交易明细.csv", csv.toByteArray())

        assertTrue(result is StatementParseResult.Valid)
        val preview = (result as StatementParseResult.Valid).preview
        assertEquals(StatementPlatform.ALIPAY, preview.platform)
        assertEquals(2, preview.transactions.size)
        assertEquals(1, preview.skippedRows)
        assertEquals(Direction.EXPENSE, preview.transactions[0].direction)
        assertEquals(1_234L, preview.transactions[0].amountMinor)
        assertEquals("ali-current-1", preview.transactions[0].externalId)
        assertEquals("早餐", preview.transactions[0].description)
        assertEquals(Direction.REFUND, preview.transactions[1].direction)
        assertEquals(1_234L, preview.transactions[1].amountMinor)
        assertEquals("ali-current-refund-1", preview.transactions[1].externalId)
        assertEquals("退款-早餐", preview.transactions[1].description)
    }

    @Test
    fun parsesWechatXlsxWorksheet() {
        val rows = listOf(
            listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "当前状态", "交易单号"),
            listOf("2026-09-14 12:00:00", "转账", "测试用户", "转账", "收入", "8.88", "已收钱", "wx-xlsx-1"),
        )
        val xlsx = workbook(rows)

        val result = OfficialStatementParser.parse("微信账单.xlsx", xlsx)

        assertTrue(result is StatementParseResult.Valid)
        val preview = (result as StatementParseResult.Valid).preview
        assertEquals(StatementPlatform.WECHAT, preview.platform)
        assertEquals(Direction.INCOME, preview.transactions.single().direction)
        assertEquals(888L, preview.transactions.single().amountMinor)
    }

    @Test
    fun rejectsCsvWithoutOfficialPlatformColumns() {
        val csv = "交易时间,收/支,金额(元),状态\n2026-09-14 12:00:00,支出,1.00,成功"

        val result = OfficialStatementParser.parse("unknown.csv", csv.toByteArray())

        assertTrue(result is StatementParseResult.Invalid)
    }

    private fun workbook(rows: List<List<String>>): ByteArray {
        val worksheet = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"${rowIndex + 1}\">")
                row.forEachIndexed { columnIndex, value ->
                    append("<c r=\"${columnName(columnIndex)}${rowIndex + 1}\" t=\"inlineStr\"><is><t>")
                    append(escapeXml(value))
                    append("</t></is></c>")
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

    private fun columnName(index: Int): String = ('A'.code + index).toChar().toString()

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
