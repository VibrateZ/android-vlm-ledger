package com.vibratez.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

class AmountFormattingTest {
    @Test
    fun formatsCurrencyUsingItsMinorUnitScale() {
        assertEquals("¥12.80", formatAmount(1_280L, "CNY"))
        assertEquals("USD 12.80", formatAmount(1_280L, "USD"))
        assertEquals("JPY 1280", formatAmount(1_280L, "JPY"))
    }
}
