package com.vibratez.ledger.ledger

import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import com.vibratez.ledger.vlm.TimeSource
import com.vibratez.ledger.vlm.VlmResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Currency

data class PhotoEvidence(
    val uri: String,
    val sha256: String,
    val screenshotCapturedAtMillis: Long?,
    val notificationMatchedAtMillis: Long? = null,
    val screenshotSourcePackage: String? = null,
)

sealed interface LedgerDecision {
    data class AutoBook(val ledger: LedgerV1) : LedgerDecision
    data class NeedsConfirmation(val ledger: LedgerV1, val reason: String) : LedgerDecision
    data class Rejected(val reason: String) : LedgerDecision
    data object Duplicate : LedgerDecision
}

/**
 * Applies local policy after the untrusted VLM response has been parsed.
 */
class LedgerDecisionEngine {
    fun decide(response: VlmResponse, evidence: PhotoEvidence): LedgerDecision {
        val ledger = response.ledger
        return when {
            ledger.decision == Decision.REJECT -> LedgerDecision.Rejected(
                ledger.evidence.reasonCode,
            )
            canAutoBook(ledger, evidence) -> LedgerDecision.AutoBook(ledger)
            else -> LedgerDecision.NeedsConfirmation(ledger, ledger.evidence.reasonCode)
        }
    }

    private fun canAutoBook(ledger: LedgerV1, evidence: PhotoEvidence): Boolean {
        val sourcePlatform = when (evidence.screenshotSourcePackage?.lowercase()) {
            "com.tencent.mm" -> Platform.WECHAT
            "com.eg.android.alipaygphone" -> Platform.ALIPAY
            null -> return false
            else -> Platform.OTHER
        }
        if (ledger.decision != Decision.AUTO_BOOK ||
            !ledger.isPaymentScreenshot ||
            ledger.platform == Platform.UNKNOWN ||
            ledger.direction == Direction.UNKNOWN ||
            ledger.amountMinor == null || ledger.amountMinor <= 0L ||
            !isSupportedCurrency(ledger.currency) ||
            ledger.occurredAt == null ||
            ledger.confidence < AUTO_BOOK_CONFIDENCE ||
            ledger.evidence.freshness != Freshness.VALID ||
            ledger.evidence.negativeFeatures.isNotEmpty()
        ) {
            return false
        }
        if (ledger.platform != sourcePlatform) return false

        val positive = ledger.evidence.positiveFeatures
        val expectedSuccess = when (ledger.direction) {
            Direction.EXPENSE -> setOf("PAYMENT_SUCCESS")
            Direction.INCOME -> setOf("RECEIPT_SUCCESS", "INCOME_RECEIVED")
            Direction.REFUND -> setOf("REFUND_SUCCESS")
            Direction.UNKNOWN -> emptySet()
        }
        val presentSuccess = positive.filter { it in TRANSACTION_SUCCESS_FEATURES }
        if ("PLATFORM_MARKER" !in positive ||
            "UNIQUE_AMOUNT" !in positive ||
            presentSuccess.isEmpty() ||
            presentSuccess.any { it !in expectedSuccess }
        ) {
            return false
        }

        val reasonMatches = when (ledger.direction) {
            Direction.EXPENSE -> ledger.evidence.reasonCode == "PAYMENT_PAGE_CONFIRMED"
            Direction.INCOME -> ledger.evidence.reasonCode == "INCOME_PAGE_CONFIRMED"
            Direction.REFUND -> ledger.evidence.reasonCode == "REFUND_PAGE_CONFIRMED"
            Direction.UNKNOWN -> false
        }
        val notificationReasonMatches = ledger.evidence.reasonCode == "NOTIFICATION_MATCH" &&
            ledger.timeSource == TimeSource.NOTIFICATION_MATCHED
        if (!reasonMatches && !notificationReasonMatches) return false

        val trustedReferenceMillis = when (ledger.timeSource) {
            TimeSource.PAGE_EXACT -> {
                if ("PAGE_EXACT_TIME" !in positive) return false
                evidence.screenshotCapturedAtMillis
            }
            TimeSource.NOTIFICATION_MATCHED -> {
                if ("NOTIFICATION_MATCH" !in positive) return false
                evidence.notificationMatchedAtMillis
            }
            TimeSource.SCREENSHOT_ESTIMATED -> {
                val isImmediateNativePaymentSuccess =
                    ledger.platform != Platform.UNKNOWN &&
                    ledger.direction in setOf(Direction.EXPENSE, Direction.INCOME, Direction.REFUND) &&
                    (ledger.merchant != null || ledger.counterparty != null) &&
                    ledger.evidence.reasonCode in setOf(
                        "PAYMENT_PAGE_CONFIRMED",
                        "INCOME_PAGE_CONFIRMED",
                        "REFUND_PAGE_CONFIRMED",
                    ) &&
                    "FRESH_TIME" in positive
                if (!isImmediateNativePaymentSuccess) return false
                evidence.screenshotCapturedAtMillis
            }
            else -> null
        } ?: return false

        return runCatching {
            val reference = Instant.ofEpochMilli(trustedReferenceMillis)
            Duration.between(ledger.occurredAt.toInstant(), reference).abs() <= FRESHNESS_WINDOW
        }.getOrDefault(false)
    }

    companion object {
        private const val AUTO_BOOK_CONFIDENCE = 0.90
        private val FRESHNESS_WINDOW = Duration.ofMinutes(30)
        private val TRANSACTION_SUCCESS_FEATURES = setOf(
            "PAYMENT_SUCCESS",
            "RECEIPT_SUCCESS",
            "REFUND_SUCCESS",
            "INCOME_RECEIVED",
        )

        private fun isSupportedCurrency(currency: String?): Boolean = runCatching {
            currency != null && Currency.getInstance(currency).defaultFractionDigits in 0..6
        }.getOrDefault(false)

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
