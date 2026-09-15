package com.vibratez.ledger.ui

import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Evidence
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import com.vibratez.ledger.vlm.TimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class LedgerConfirmationEditorTest {
    @Test
    fun parsesMajorCnyAmountIntoMinorUnits() {
        assertEquals(1280L, parseAmountMinor("12.80", "CNY"))
    }

    @Test
    fun respectsZeroFractionCurrency() {
        assertEquals(1280L, parseAmountMinor("1280", "JPY"))
        assertNull(parseAmountMinor("12.8", "JPY"))
    }

    @Test
    fun rejectsInvalidOrUnsafeAmounts() {
        assertNull(parseAmountMinor("12.801", "CNY"))
        assertNull(parseAmountMinor("0", "CNY"))
        assertNull(parseAmountMinor("999999999999999999999", "CNY"))
        assertNull(parseAmountMinor("12.80", "NOT_A_CURRENCY"))
    }

    @Test
    fun formatsMinorUnitsForReview() {
        assertEquals("12.80", formatAmountForInput(ledger(amountMinor = 1280L, currency = "CNY")))
        assertEquals("1280", formatAmountForInput(ledger(amountMinor = 1280L, currency = "JPY")))
    }

    private fun ledger(amountMinor: Long, currency: String) = LedgerV1(
        decision = Decision.NEEDS_CONFIRMATION,
        isPaymentScreenshot = true,
        platform = Platform.WECHAT,
        direction = Direction.EXPENSE,
        amountMinor = amountMinor,
        currency = currency,
        merchant = null,
        counterparty = null,
        occurredAt = OffsetDateTime.parse("2026-09-14T12:30:00+08:00"),
        timeSource = TimeSource.PAGE_EXACT,
        externalId = null,
        suggestedTag = null,
        confidence = 0.7,
        evidence = Evidence(emptyList(), emptyList(), Freshness.UNKNOWN, "LOW_CONFIDENCE"),
    )
}
