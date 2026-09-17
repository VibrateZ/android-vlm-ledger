package com.vibratez.ledger.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerScanWorkerPolicyTest {
    @Test
    fun retriesOnlyBeforeThirdWorkerExecution() {
        assertTrue(shouldRetryWorker(0))
        assertTrue(shouldRetryWorker(1))
        assertFalse(shouldRetryWorker(2))
        assertFalse(shouldRetryWorker(3))
    }
}
