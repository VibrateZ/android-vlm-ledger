package com.vibratez.ledger.statement

import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Platform
import java.time.OffsetDateTime

enum class StatementPlatform(
    val displayName: String,
    val ledgerPlatform: Platform,
) {
    WECHAT("微信支付", Platform.WECHAT),
    ALIPAY("支付宝", Platform.ALIPAY),
}

data class StatementTransaction(
    val platform: StatementPlatform,
    val direction: Direction,
    val amountMinor: Long,
    val occurredAt: OffsetDateTime,
    val merchant: String?,
    val description: String?,
    val externalId: String,
    val sourceRow: Int,
)

data class StatementPreview(
    val fileName: String,
    val sourceUri: String = "",
    val platform: StatementPlatform,
    val sourceRows: Int,
    val skippedRows: Int,
    val invalidRows: Int,
    val transactions: List<StatementTransaction>,
    val warnings: List<String>,
)

sealed interface StatementParseResult {
    data class Valid(val preview: StatementPreview) : StatementParseResult
    data class Invalid(val reason: String) : StatementParseResult
}

data class StatementImportResult(
    val imported: Int,
    val duplicates: Int,
)
