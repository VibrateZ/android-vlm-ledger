package com.vibratez.ledger.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetFundPolicyTest {
    @Test
    fun poolCreditsRemainAvailableAcrossMonthBoundaries() {
        val januaryCredit = BudgetFundPolicy.settlementCredit(SettlementDestination.POOL, 500L)
        val februaryCredit = BudgetFundPolicy.settlementCredit(SettlementDestination.POOL, 300L)

        assertEquals(800L, januaryCredit.poolMinor + februaryCredit.poolMinor)
        assertNull(BudgetFundPolicy.allocationError(800L, 600L))
    }

    @Test
    fun savingsAreIndependentFromPool() {
        val credit = BudgetFundPolicy.settlementCredit(SettlementDestination.SAVINGS, 500L)

        assertEquals(0L, credit.poolMinor)
        assertEquals(500L, credit.savingsMinor)
    }

    @Test
    fun reversingConsumedPoolCreditCreatesDebtAndBlocksAllocation() {
        val oldCredit = BudgetFundPolicy.settlementCredit(SettlementDestination.POOL, 500L)
        val reversal = BudgetFundPolicy.reverse(oldCredit)
        val poolAfterPriorAllocationAndReversal = 0L + reversal.poolMinor

        assertEquals(-500L, poolAfterPriorAllocationAndReversal)
        assertEquals(
            "预算池存在欠额或没有可用余额",
            BudgetFundPolicy.allocationError(poolAfterPriorAllocationAndReversal, 100L),
        )
    }

    @Test
    fun allocationCannotExceedPool() {
        assertEquals("拨款金额超过预算池余额", BudgetFundPolicy.allocationError(99L, 100L))
    }

    @Test
    fun unchangedSnapshotDoesNotNeedReconciliation() {
        assertTrue(!BudgetFundPolicy.needsReconciliation("same", "same"))
        assertTrue(BudgetFundPolicy.needsReconciliation("old", "new"))
        assertTrue(!BudgetFundPolicy.needsReconciliation(null, "new"))
    }
}
