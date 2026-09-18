package com.vibratez.ledger.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun exponentialDelayIsBoundedAndHonorsRetryAfter() {
        assertEquals(5 * 60_000L, RetryPolicy.delayMillis(1, 5, 180, null))
        assertEquals(20 * 60_000L, RetryPolicy.delayMillis(3, 5, 180, null))
        assertEquals(90 * 60_000L, RetryPolicy.delayMillis(2, 5, 180, 90 * 60_000L))
        assertEquals(180 * 60_000L, RetryPolicy.delayMillis(20, 5, 180, null))
    }
}
