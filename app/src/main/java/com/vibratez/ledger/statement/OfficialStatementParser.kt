package com.vibratez.ledger.statement

import com.vibratez.ledger.vlm.Direction
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object OfficialStatementParser {
    fun parse(fileName: String, bytes: ByteArray): StatementParseResult {
        if (bytes.isEmpty()) return StatementParseResult.Invalid("账单文件为空")
        if (bytes.size > MAX_INPUT_BYTES) return StatementParseResult.Invalid("账单文件超过 20 MB")
        return runCatching {
            val rows = if (isZip(bytes)) rowsFromZip(bytes) else parseCsv(decodeText(bytes))
            parseRows(fileName, rows)
        }.getOrElse { error ->
            StatementParseResult.Invalid(error.message ?: "无法读取账单文件")
        }
    }

    private fun parseRows(fileName: String, rows: List<List<String>>): StatementParseResult {
        if (rows.isEmpty()) return StatementParseResult.Invalid("账单中没有可读取的表格")
        val headerIndex = rows.take(MAX_HEADER_SCAN_ROWS).indexOfFirst(::looksLikeHeader)
        if (headerIndex < 0) {
            return StatementParseResult.Invalid("未找到支付宝或微信官方账单表头")
        }
        val header = rows[headerIndex].map(::normalizeHeader)
        val platform = detectPlatform(header)
            ?: return StatementParseResult.Invalid("账单表头无法识别为支付宝或微信")
        val columns = ColumnMap(header, platform)
        columns.validationError()?.let { return StatementParseResult.Invalid(it) }

        val transactions = mutableListOf<StatementTransaction>()
        val warnings = mutableListOf<String>()
        var sourceRows = 0
        var skippedRows = 0
        var invalidRows = 0
        rows.drop(headerIndex + 1).take(MAX_DATA_ROWS).forEachIndexed { index, row ->
            val sourceRow = headerIndex + index + 2
            if (row.all(String::isBlank) || row.joinToString("").all { it == '-' }) return@forEachIndexed
            sourceRows++
            when (val parsed = parseTransactionRow(row, columns, platform, sourceRow)) {
                is RowResult.Valid -> transactions += parsed.transactions
                RowResult.Skipped -> skippedRows++
                is RowResult.Invalid -> {
                    invalidRows++
                    if (warnings.size < MAX_WARNINGS) warnings += "第 $sourceRow 行：${parsed.reason}"
                }
            }
        }
        if (sourceRows == 0) return StatementParseResult.Invalid("账单表头后没有交易记录")
        return StatementParseResult.Valid(
            StatementPreview(
                fileName = fileName,
                platform = platform,
                sourceRows = sourceRows,
                skippedRows = skippedRows,
                invalidRows = invalidRows,
                transactions = transactions,
                warnings = warnings,
            ),
        )
    }

    private fun parseTransactionRow(
        row: List<String>,
        columns: ColumnMap,
        platform: StatementPlatform,
        sourceRow: Int,
    ): RowResult {
        val directionText = columns.value(row, columns.direction)
        val status = columns.value(row, columns.status)
        if (!isCompleted(status)) return RowResult.Skipped
        val externalId = columns.value(row, columns.externalId).trim()
        if (externalId.isBlank()) return RowResult.Invalid("缺少交易号")
        val amount = parseAmount(columns.value(row, columns.amount))
            ?: return RowResult.Invalid("金额格式无效")
        val occurredAt = parseDateTime(columns.value(row, columns.occurredAt))
            ?: return RowResult.Invalid("交易时间格式无效")
        val merchant = columns.value(row, columns.merchant).cleanOptional()
        val description = columns.value(row, columns.description).cleanOptional()
        val type = columns.value(row, columns.type)
        val refundLike = status.contains("退款") || type.contains("退款")

        // Current Alipay exports represent refunds as standalone "不计收支" rows.
        // They still affect net expenses and must not be discarded by the generic filter below.
        if (
            platform == StatementPlatform.ALIPAY &&
            directionText.contains("不计收支") &&
            refundLike
        ) {
            return RowResult.Valid(
                listOf(
                    StatementTransaction(
                        platform = platform,
                        direction = Direction.REFUND,
                        amountMinor = amount,
                        occurredAt = occurredAt,
                        merchant = merchant,
                        description = description,
                        externalId = externalId,
                        sourceRow = sourceRow,
                    ),
                ),
            )
        }

        if (directionText.isBlank() || directionText.contains("不计收支")) return RowResult.Skipped

        if (platform == StatementPlatform.ALIPAY && directionText.contains("支出")) {
            val result = mutableListOf(
                StatementTransaction(
                    platform = platform,
                    direction = Direction.EXPENSE,
                    amountMinor = amount,
                    occurredAt = occurredAt,
                    merchant = merchant,
                    description = description,
                    externalId = externalId,
                    sourceRow = sourceRow,
                ),
            )
            val refundAmount = columns.refundAmount
                ?.let { parseOptionalAmount(columns.value(row, it)) }
            if (refundAmount != null && refundAmount > 0L) {
                val refundTime = columns.modifiedAt
                    ?.let { parseDateTime(columns.value(row, it)) }
                    ?: occurredAt
                result += StatementTransaction(
                    platform = platform,
                    direction = Direction.REFUND,
                    amountMinor = refundAmount,
                    occurredAt = refundTime,
                    merchant = merchant,
                    description = description,
                    externalId = "$externalId:refund",
                    sourceRow = sourceRow,
                )
            } else if (refundLike && columns.refundAmount != null) {
                return RowResult.Invalid("退款状态缺少有效退款金额")
            }
            return RowResult.Valid(result)
        }

        val direction = when {
            refundLike -> Direction.REFUND
            directionText.contains("支出") -> Direction.EXPENSE
            directionText.contains("收入") -> Direction.INCOME
            else -> return RowResult.Skipped
        }
        return RowResult.Valid(
            listOf(
                StatementTransaction(
                    platform = platform,
                    direction = direction,
                    amountMinor = amount,
                    occurredAt = occurredAt,
                    merchant = merchant,
                    description = description,
                    externalId = externalId,
                    sourceRow = sourceRow,
                ),
            ),
        )
    }

    private fun looksLikeHeader(row: List<String>): Boolean {
        val normalized = row.map(::normalizeHeader).toSet()
        return ("收/支" in normalized || "收支" in normalized) &&
            normalized.any { it == "金额(元)" || it == "金额" } &&
            normalized.any { it == "交易时间" || it == "交易创建时间" }
    }

    private fun detectPlatform(header: List<String>): StatementPlatform? = when {
        "当前状态" in header && header.any { it in WECHAT_TRANSACTION_ID_HEADERS } ->
            StatementPlatform.WECHAT
        "交易状态" in header && header.any { it in ALIPAY_TRANSACTION_ID_HEADERS } ->
            StatementPlatform.ALIPAY
        else -> null
    }

    private class ColumnMap(header: List<String>, private val platform: StatementPlatform) {
        private val indices = header.withIndex().associate { it.value to it.index }
        val occurredAt = find(
            if (platform == StatementPlatform.WECHAT) listOf("交易时间")
            else listOf("交易创建时间", "交易时间", "付款时间"),
        )
        val amount = find(listOf("金额(元)", "金额"))
        val direction = find(listOf("收/支", "收支", "收支类型", "资金流向"))
        val status = find(if (platform == StatementPlatform.WECHAT) listOf("当前状态") else listOf("交易状态"))
        val externalId = find(
            if (platform == StatementPlatform.WECHAT) WECHAT_TRANSACTION_ID_HEADERS
            else ALIPAY_TRANSACTION_ID_HEADERS,
        )
        val merchant = find(listOf("交易对方"))
        val description = find(listOf("商品", "商品名称", "商品说明"))
        val type = find(listOf("交易类型", "类型", "交易分类"))
        val refundAmount = find(listOf("成功退款(元)", "退款金额(元)", "退款金额"))
        val modifiedAt = find(listOf("最近修改时间", "退款时间"))

        fun validationError(): String? = when {
            occurredAt == null -> "账单缺少交易时间列"
            amount == null -> "账单缺少金额列"
            direction == null -> "账单缺少收/支列"
            status == null -> "账单缺少交易状态列"
            externalId == null -> "账单缺少交易号列"
            else -> null
        }

        fun value(row: List<String>, index: Int?): String = index?.let { row.getOrNull(it) }.orEmpty().trim()

        private fun find(names: List<String>): Int? = names.firstNotNullOfOrNull(indices::get)
    }

    private sealed interface RowResult {
        data class Valid(val transactions: List<StatementTransaction>) : RowResult
        data class Invalid(val reason: String) : RowResult
        data object Skipped : RowResult
    }

    private fun isCompleted(status: String): Boolean {
        if (status.isBlank()) return false
        if (FAILED_STATUS_MARKERS.any(status::contains)) return false
        return COMPLETED_STATUS_MARKERS.any(status::contains)
    }

    private fun parseAmount(raw: String): Long? {
        val normalized = raw.trim()
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace("元", "")
            .trim()
        return runCatching {
            BigDecimal(normalized).abs().setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact()
                .takeIf { it > 0L }
        }.getOrNull()
    }

    private fun parseOptionalAmount(raw: String): Long? = if (raw.isBlank() || raw == "-") null else parseAmount(raw)

    private fun parseDateTime(raw: String): OffsetDateTime? {
        val value = raw.trim()
        DATE_TIME_FORMATTERS.forEach { formatter ->
            try {
                return LocalDateTime.parse(value, formatter).atOffset(CHINA_OFFSET)
            } catch (_: DateTimeParseException) {
                // Try the next official export format.
            }
        }
        DATE_FORMATTERS.forEach { formatter ->
            try {
                return LocalDate.parse(value, formatter).atTime(LocalTime.MIDNIGHT).atOffset(CHINA_OFFSET)
            } catch (_: DateTimeParseException) {
                // Try an Excel serial below.
            }
        }
        val serial = value.toDoubleOrNull()?.takeIf { it in 1.0..2_958_465.0 } ?: return null
        val days = serial.toLong()
        val nanos = ((serial - days) * NANOS_PER_DAY).toLong()
        return EXCEL_EPOCH.plusDays(days).atStartOfDay().plusNanos(nanos).atOffset(CHINA_OFFSET)
    }

    private fun String.cleanOptional(): String? = trim().takeIf { it.isNotEmpty() && it != "/" && it != "-" }

    private fun normalizeHeader(value: String): String = value.trim()
        .removePrefix("\uFEFF")
        .replace("（", "(")
        .replace("）", ")")
        .replace(Regex("\\s+"), "")

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("GB18030"))
        }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length && rows.size <= MAX_DATA_ROWS + MAX_HEADER_SCAN_ROWS) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += field.toString()
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += field.toString()
                    field.clear()
                    rows += row
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }

    private fun rowsFromZip(bytes: ByteArray): List<List<String>> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/').lowercase(Locale.ROOT)
                    if (name.endsWith(".csv") || name.endsWith(".xml")) {
                        val data = readLimited(zip, MAX_ZIP_ENTRY_BYTES)
                        totalBytes += data.size
                        require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "解压后的账单文件过大" }
                        entries[name] = data
                    }
                }
                zip.closeEntry()
            }
        }
        entries.entries.firstOrNull { it.key.endsWith(".csv") }?.let {
            return parseCsv(decodeText(it.value))
        }
        val sheet = entries.entries
            .filter { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }
            .minByOrNull { it.key }
            ?: throw IllegalArgumentException("压缩包中没有可读取的 CSV 或 XLSX 工作表")
        val sharedStrings = entries["xl/sharedstrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return parseWorksheet(sheet.value, sharedStrings)
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val document = parseXml(xml)
        return document.getElementsByTagNameNS("*", "si").let { nodes ->
            (0 until nodes.length).map { index ->
                val element = nodes.item(index) as Element
                val textNodes = element.getElementsByTagNameNS("*", "t")
                buildString {
                    for (textIndex in 0 until textNodes.length) append(textNodes.item(textIndex).textContent)
                }
            }
        }
    }

    private fun parseWorksheet(xml: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val document = parseXml(xml)
        val rowNodes = document.getElementsByTagNameNS("*", "row")
        return buildList {
            for (rowIndex in 0 until minOf(rowNodes.length, MAX_DATA_ROWS + MAX_HEADER_SCAN_ROWS)) {
                val rowElement = rowNodes.item(rowIndex) as Element
                val cells = rowElement.getElementsByTagNameNS("*", "c")
                val values = mutableMapOf<Int, String>()
                var maxColumn = -1
                for (cellIndex in 0 until cells.length) {
                    val cell = cells.item(cellIndex) as Element
                    val column = columnIndex(cell.getAttribute("r"))
                    maxColumn = maxOf(maxColumn, column)
                    val type = cell.getAttribute("t")
                    val raw = if (type == "inlineStr") {
                        cell.getElementsByTagNameNS("*", "t").item(0)?.textContent.orEmpty()
                    } else {
                        cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                    }
                    values[column] = if (type == "s") raw.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty() else raw
                }
                if (maxColumn >= 0) add((0..maxColumn).map { values[it].orEmpty() })
            }
        }
    }

    private fun documentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { isXIncludeAware = false }
        isExpandEntityReferences = false
    }.newDocumentBuilder()

    private fun parseXml(xml: ByteArray) = run {
        require(!containsAsciiIgnoreCase(xml, "<!doctype")) { "账单 XML 包含不受支持的声明" }
        documentBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun containsAsciiIgnoreCase(bytes: ByteArray, token: String): Boolean {
        val expected = token.toByteArray(StandardCharsets.US_ASCII)
        if (bytes.size < expected.size) return false
        for (start in 0..bytes.size - expected.size) {
            var matches = true
            for (offset in expected.indices) {
                val actual = bytes[start + offset].toInt().and(0xFF).or(0x20)
                val wanted = expected[offset].toInt().and(0xFF).or(0x20)
                if (actual != wanted) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile(Char::isLetter).uppercase(Locale.ROOT)
        if (letters.isEmpty()) return 0
        return letters.fold(0) { value, char -> value * 26 + (char - 'A' + 1) } - 1
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "账单内容过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val CHINA_OFFSET = ZoneOffset.ofHours(8)
    private val EXCEL_EPOCH = LocalDate.of(1899, 12, 30)
    private const val NANOS_PER_DAY = 86_400_000_000_000.0
    private const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 30 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 50 * 1024 * 1024
    private const val MAX_HEADER_SCAN_ROWS = 40
    private const val MAX_DATA_ROWS = 50_000
    private const val MAX_WARNINGS = 5

    private val DATE_TIME_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
    )
    private val DATE_FORMATTERS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    )
    private val COMPLETED_STATUS_MARKERS = listOf(
        "交易成功",
        "支付成功",
        "退款成功",
        "已全额退款",
        "已退款",
        "已收钱",
        "已转账",
        "已存入",
        "已入账",
        "已完成",
    )
    private val FAILED_STATUS_MARKERS = listOf(
        "失败",
        "关闭",
        "取消",
        "撤销",
        "待付款",
        "等待付款",
        "处理中",
    )
    private val WECHAT_TRANSACTION_ID_HEADERS = listOf("交易单号", "微信支付单号")
    private val ALIPAY_TRANSACTION_ID_HEADERS = listOf(
        "交易号",
        "交易订单号",
        "支付宝交易号",
        "订单号",
    )
}
