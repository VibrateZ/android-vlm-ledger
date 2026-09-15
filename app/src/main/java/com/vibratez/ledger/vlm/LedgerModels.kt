package com.vibratez.ledger.vlm

import java.time.OffsetDateTime

enum class Decision {
    AUTO_BOOK,
    NEEDS_CONFIRMATION,
    REJECT,
}

enum class Platform {
    WECHAT,
    ALIPAY,
    OTHER,
    UNKNOWN,
}

enum class Direction {
    EXPENSE,
    INCOME,
    REFUND,
    UNKNOWN,
}

enum class TimeSource {
    PAGE_EXACT,
    NOTIFICATION_MATCHED,
    SCREENSHOT_ESTIMATED,
    STATEMENT_VERIFIED,
    USER_CONFIRMED,
}

enum class Freshness {
    VALID,
    STALE,
    UNKNOWN,
}

data class Evidence(
    val positiveFeatures: List<String>,
    val negativeFeatures: List<String>,
    val freshness: Freshness,
    val reasonCode: String,
)

data class LedgerV1(
    val decision: Decision,
    val isPaymentScreenshot: Boolean,
    val platform: Platform,
    val direction: Direction,
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val counterparty: String?,
    val occurredAt: OffsetDateTime?,
    val timeSource: TimeSource?,
    val externalId: String?,
    val suggestedTag: String?,
    val confidence: Double,
    val evidence: Evidence,
)

sealed interface ParseResult {
    data class Valid(val value: LedgerV1) : ParseResult

    data class Invalid(val reason: String) : ParseResult
}

data class VlmRequest(
    val model: String,
    val mimeType: String,
    val imageBytes: ByteArray,
    val screenshotCapturedAt: String?,
    val deviceTimezone: String,
    val notificationMatchJson: String? = null,
)

data class VlmResponse(
    val ledger: LedgerV1,
    val requestId: String?,
    val model: String?,
)
