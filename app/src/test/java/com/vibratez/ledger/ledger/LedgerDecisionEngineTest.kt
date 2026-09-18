package com.vibratez.ledger.ledger

import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Evidence
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import com.vibratez.ledger.vlm.TimeSource
import com.vibratez.ledger.vlm.VlmResponse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class LedgerDecisionEngineTest {
    private val occurredAt = OffsetDateTime.parse("2026-09-14T12:30:00+08:00")
    private val capturedAtMillis = occurredAt.plusMinutes(2).toInstant().toEpochMilli()

    @Test
    fun autoBooksWhenRemoteAndLocalFreshnessChecksPass() {
        val decision = LedgerDecisionEngine().decide(
            response = response(),
            evidence = evidence("fresh", capturedAtMillis),
        )

        assertTrue(decision is LedgerDecision.AutoBook)
    }

    @Test
    fun autoBooksImmediateWeChatSuccessUsingFreshScreenshotTime() {
        val estimatedLedger = ledger().copy(
            occurredAt = occurredAt.plusMinutes(2),
            timeSource = TimeSource.SCREENSHOT_ESTIMATED,
            evidence = ledger().evidence.copy(
                positiveFeatures = listOf(
                    "PAYMENT_SUCCESS",
                    "PLATFORM_MARKER",
                    "UNIQUE_AMOUNT",
                    "MERCHANT_MARKER",
                    "FRESH_TIME",
                ),
            ),
        )
        val decision = LedgerDecisionEngine().decide(
            response = VlmResponse(estimatedLedger, null, "test-model"),
            evidence = evidence("wechat-immediate", capturedAtMillis),
        )

        assertTrue(decision is LedgerDecision.AutoBook)
    }

    @Test
    fun autoBooksStatusBarMinuteCombinedWithScreenshotDate() {
        val statusBarTime = OffsetDateTime.parse("2026-09-14T12:32:00+08:00")
        val capturedAtWithSeconds = OffsetDateTime.parse("2026-09-14T12:32:56+08:00")
        val estimatedLedger = ledger().copy(
            occurredAt = statusBarTime,
            timeSource = TimeSource.SCREENSHOT_ESTIMATED,
            evidence = ledger().evidence.copy(
                positiveFeatures = listOf(
                    "PAYMENT_SUCCESS",
                    "PLATFORM_MARKER",
                    "UNIQUE_AMOUNT",
                    "MERCHANT_MARKER",
                    "FRESH_TIME",
                ),
            ),
        )

        val decision = LedgerDecisionEngine().decide(
            response = VlmResponse(estimatedLedger, null, "test-model"),
            evidence = evidence(
                "status-bar-minute",
                capturedAtWithSeconds.toInstant().toEpochMilli(),
            ),
        )

        assertTrue(decision is LedgerDecision.AutoBook)
    }

    @Test
    fun autoBooksImmediateAlipaySuccessUsingFreshScreenshotTime() {
        val estimatedLedger = ledger().copy(
            platform = Platform.ALIPAY,
            occurredAt = occurredAt.plusMinutes(2),
            timeSource = TimeSource.SCREENSHOT_ESTIMATED,
            evidence = ledger().evidence.copy(
                positiveFeatures = listOf(
                    "PAYMENT_SUCCESS",
                    "PLATFORM_MARKER",
                    "UNIQUE_AMOUNT",
                    "MERCHANT_MARKER",
                    "FRESH_TIME",
                ),
            ),
        )
        val decision = LedgerDecisionEngine().decide(
            response = VlmResponse(estimatedLedger, null, "test-model"),
            evidence = evidence(
                "alipay-immediate",
                capturedAtMillis,
                "com.eg.android.AlipayGphone",
            ),
        )

        assertTrue(decision is LedgerDecision.AutoBook)
    }

    @Test
    fun requiresConfirmationWhenEstimatedSuccessHasNoPayeeStructure() {
        val estimatedLedger = ledger().copy(
            merchant = null,
            occurredAt = occurredAt.plusMinutes(2),
            timeSource = TimeSource.SCREENSHOT_ESTIMATED,
            evidence = ledger().evidence.copy(
                positiveFeatures = listOf(
                    "PAYMENT_SUCCESS",
                    "PLATFORM_MARKER",
                    "UNIQUE_AMOUNT",
                    "MERCHANT_MARKER",
                    "FRESH_TIME",
                ),
            ),
        )
        val decision = LedgerDecisionEngine().decide(
            response = VlmResponse(estimatedLedger, null, "test-model"),
            evidence = evidence("no-payee-structure", capturedAtMillis),
        )

        assertTrue(decision is LedgerDecision.NeedsConfirmation)
    }

    @Test
    fun requiresConfirmationWhenEstimatedSuccessComesFromHistoryDetail() {
        val estimatedLedger = ledger().copy(
            occurredAt = occurredAt.plusMinutes(2),
            timeSource = TimeSource.SCREENSHOT_ESTIMATED,
            evidence = ledger().evidence.copy(
                positiveFeatures = listOf(
                    "PAYMENT_SUCCESS",
                    "PLATFORM_MARKER",
                    "UNIQUE_AMOUNT",
                    "MERCHANT_MARKER",
                    "FRESH_TIME",
                ),
                negativeFeatures = listOf("HISTORY_DETAIL"),
            ),
        )
        val decision = LedgerDecisionEngine().decide(
            response = VlmResponse(estimatedLedger, null, "test-model"),
            evidence = evidence("history-detail", capturedAtMillis),
        )

        assertTrue(decision is LedgerDecision.NeedsConfirmation)
    }

    @Test
    fun requiresConfirmationWhenMediaTimestampIsOutsideWindow() {
        val decision = LedgerDecisionEngine().decide(
            response = response(),
            evidence = evidence("stale", occurredAt.plusMinutes(31).toInstant().toEpochMilli()),
        )

        assertTrue(decision is LedgerDecision.NeedsConfirmation)
    }

    @Test
    fun requiresConfirmationWhenMediaTimestampIsMissing() {
        val decision = LedgerDecisionEngine().decide(
            response = response(),
            evidence = evidence("missing", null),
        )

        assertTrue(decision is LedgerDecision.NeedsConfirmation)
    }

    @Test
    fun doesNotTrustNotificationTimeWithoutLocalMatch() {
        val ledger = ledger().copy(
            timeSource = TimeSource.NOTIFICATION_MATCHED,
            evidence = Evidence(
                positiveFeatures = listOf(
                    "PAYMENT_SUCCESS",
                    "PLATFORM_MARKER",
                    "UNIQUE_AMOUNT",
                    "NOTIFICATION_MATCH",
                ),
                negativeFeatures = emptyList(),
                freshness = Freshness.VALID,
                reasonCode = "NOTIFICATION_MATCH",
            ),
        )
        val decision = LedgerDecisionEngine().decide(
            response = VlmResponse(ledger, null, "test-model"),
            evidence = evidence("notification", capturedAtMillis),
        )

        assertTrue(decision is LedgerDecision.NeedsConfirmation)
    }

    @Test
    fun doesNotDiscardRepeatBeforeLedgerIsPersisted() {
        val engine = LedgerDecisionEngine()
        val evidence = evidence("duplicate", capturedAtMillis)

        assertTrue(engine.decide(response(), evidence) is LedgerDecision.AutoBook)
        assertTrue(engine.decide(response(), evidence) is LedgerDecision.AutoBook)
    }

    private fun response() = VlmResponse(ledger(), "request-id", "test-model")

    private fun ledger() = LedgerV1(
        decision = Decision.AUTO_BOOK,
        isPaymentScreenshot = true,
        platform = Platform.WECHAT,
        direction = Direction.EXPENSE,
        amountMinor = 1_280L,
        currency = "CNY",
        merchant = "示例商户",
        counterparty = null,
        occurredAt = occurredAt,
        timeSource = TimeSource.PAGE_EXACT,
        externalId = null,
        suggestedTag = "餐饮",
        confidence = 0.95,
        evidence = Evidence(
            positiveFeatures = listOf(
                "PAYMENT_SUCCESS",
                "PLATFORM_MARKER",
                "UNIQUE_AMOUNT",
                "PAGE_EXACT_TIME",
            ),
            negativeFeatures = emptyList(),
            freshness = Freshness.VALID,
            reasonCode = "PAYMENT_PAGE_CONFIRMED",
        ),
    )

    private fun evidence(
        hash: String,
        capturedAt: Long?,
        sourcePackage: String = "com.tencent.mm",
    ) = PhotoEvidence(
        uri = "content://media/$hash",
        sha256 = hash,
        screenshotCapturedAtMillis = capturedAt,
        screenshotSourcePackage = sourcePackage,
    )
}
