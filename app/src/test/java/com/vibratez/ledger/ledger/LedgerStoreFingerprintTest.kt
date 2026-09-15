package com.vibratez.ledger.ledger

import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Evidence
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import com.vibratez.ledger.vlm.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.OffsetDateTime

class LedgerStoreFingerprintTest {
    @Test
    fun sameInstantWithDifferentOffsetsHasSameFingerprint() {
        val first = ledger(occurredAt = "2026-09-14T12:30:00+08:00")
        val second = ledger(occurredAt = "2026-09-14T04:30:00Z")

        assertEquals(
            LedgerStore.transactionFingerprint(first),
            LedgerStore.transactionFingerprint(second),
        )
    }

    @Test
    fun fieldFingerprintChangesForDifferentTransaction() {
        val first = ledger()
        val second = first.copy(amountMinor = 1_281L)

        assertNotEquals(
            LedgerStore.transactionFingerprint(first),
            LedgerStore.transactionFingerprint(second),
        )
    }

    @Test
    fun platformExternalIdIsStableAcrossPresentationChanges() {
        val first = ledger().copy(externalId = "order-123", merchant = "商户 A")
        val second = first.copy(
            occurredAt = first.occurredAt?.plusMinutes(1),
            merchant = "商户 B",
        )

        assertEquals(
            LedgerStore.transactionFingerprint(first),
            LedgerStore.transactionFingerprint(second),
        )
        assertNotEquals(
            LedgerStore.transactionFingerprint(first),
            LedgerStore.transactionFingerprint(second.copy(externalId = "order-124")),
        )
        assertNotEquals(
            LedgerStore.transactionFingerprint(first),
            LedgerStore.transactionFingerprint(second.copy(amountMinor = 9_999L)),
        )
    }

    private fun ledger(occurredAt: String = "2026-09-14T12:30:00+08:00") = LedgerV1(
        decision = Decision.AUTO_BOOK,
        isPaymentScreenshot = true,
        platform = Platform.WECHAT,
        direction = Direction.EXPENSE,
        amountMinor = 1_280L,
        currency = "CNY",
        merchant = "示例商户",
        counterparty = null,
        occurredAt = OffsetDateTime.parse(occurredAt),
        timeSource = TimeSource.PAGE_EXACT,
        externalId = null,
        suggestedTag = "餐饮",
        confidence = 0.95,
        evidence = Evidence(
            positiveFeatures = emptyList(),
            negativeFeatures = emptyList(),
            freshness = Freshness.VALID,
            reasonCode = "PAYMENT_PAGE_CONFIRMED",
        ),
    )
}
