package com.vibratez.ledger.budget

import com.vibratez.ledger.ledger.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyBudgetCalculatorTest {
    @Test
    fun distributesRemainderAcrossFirstDaysAndPreservesMonthlyTotal() {
        val january = LocalDate.of(2026, 1, 1)
        val daily = (0L until 31L).map { DailyBudgetCalculator.baseBudgetForDate(10_000L, january.plusDays(it))!! }

        assertEquals(10_000L, daily.sum())
        assertEquals(323L, daily.first())
        assertEquals(322L, daily.last())
    }

    @Test
    fun usesLeapYearDayCount() {
        val february = LocalDate.of(2028, 2, 1)
        val daily = (0L until 29L).map { DailyBudgetCalculator.baseBudgetForDate(2_900L, february.plusDays(it))!! }

        assertEquals(2_900L, daily.sum())
        assertTrue(daily.all { it == 100L })
    }

    @Test
    fun separatesIncomeAndForeignCurrencyFromNetExpense() {
        val date = LocalDate.of(2026, 9, 17)
        val result = DailyBudgetCalculator.calculate(
            date = date,
            monthlyBudgetMinor = 30_000L,
            allocatedMinor = 200L,
            transactions = listOf(
                transaction("expense", "EXPENSE", 800L, "CNY", "2026-09-17T08:00:00+08:00"),
                transaction("refund", "REFUND", 100L, "CNY", "2026-09-17T09:00:00+08:00"),
                transaction("income", "INCOME", 500L, "CNY", "2026-09-17T10:00:00+08:00"),
                transaction("foreign", "EXPENSE", 900L, "USD", "2026-09-17T11:00:00+08:00"),
            ),
        )

        assertEquals(800L, result.expenseMinor)
        assertEquals(100L, result.refundMinor)
        assertEquals(700L, result.netExpenseMinor)
        assertEquals(500L, result.incomeMinor)
        assertEquals(1, result.excludedTransactionCount)
        assertEquals(500L, result.balanceMinor)
    }

    @Test
    fun refundMayProduceNegativeNetExpense() {
        val result = DailyBudgetCalculator.calculate(
            date = LocalDate.of(2026, 9, 17),
            monthlyBudgetMinor = 3_000L,
            allocatedMinor = 0L,
            transactions = listOf(
                transaction("expense", "EXPENSE", 20L, "CNY", "2026-09-17T08:00:00+08:00"),
                transaction("refund", "REFUND", 50L, "CNY", "2026-09-17T09:00:00+08:00"),
            ),
        )

        assertEquals(-30L, result.netExpenseMinor)
        assertEquals(130L, result.balanceMinor)
    }

    @Test
    fun incomeOffsetsMonthlyBudgetUsage() {
        assertEquals(86_764L, DailyBudgetCalculator.monthBudgetUsage(504_064L, 417_300L))
    }

    @Test
    fun monthlyBudgetUsageMayBeNegativeWhenIncomeExceedsExpense() {
        assertEquals(-2_000L, DailyBudgetCalculator.monthBudgetUsage(3_000L, 5_000L))
    }

    @Test
    fun noBudgetStillReturnsTransactionsWithoutBalance() {
        val result = DailyBudgetCalculator.calculate(
            date = LocalDate.of(2026, 9, 17),
            monthlyBudgetMinor = null,
            allocatedMinor = 0L,
            transactions = listOf(
                transaction("expense", "EXPENSE", 20L, "CNY", "2026-09-17T08:00:00+08:00"),
            ),
        )

        assertNull(result.baseBudgetMinor)
        assertNull(result.balanceMinor)
        assertEquals(1, result.transactions.size)
    }

    @Test
    fun snapshotChangesWhenTransactionOrBudgetChanges() {
        val date = LocalDate.of(2026, 9, 17)
        val first = DailyBudgetCalculator.calculate(date, 3_000L, 0L, emptyList())
        val changedBudget = DailyBudgetCalculator.calculate(date, 3_001L, 0L, emptyList())
        val changedTransactions = DailyBudgetCalculator.calculate(
            date,
            3_000L,
            0L,
            listOf(transaction("expense", "EXPENSE", 20L, "CNY", "2026-09-17T08:00:00+08:00")),
        )

        assertTrue(first.snapshotHash != changedBudget.snapshotHash)
        assertTrue(first.snapshotHash != changedTransactions.snapshotHash)
    }

    private fun transaction(
        id: String,
        direction: String,
        amountMinor: Long,
        currency: String,
        occurredAt: String,
    ) = TransactionRecord(
        id = id,
        direction = direction,
        amountMinor = amountMinor,
        currency = currency,
        merchant = null,
        counterparty = null,
        occurredAt = occurredAt,
        suggestedTag = null,
        sha256 = id.padEnd(64, '0'),
        transactionFingerprint = id,
        sourceUri = "",
        platform = "OTHER",
        confidence = 1.0,
        timeSource = "USER_CONFIRMED",
        externalId = null,
        vlmModel = null,
        vlmRequestId = null,
        screenshotCapturedAtMillis = null,
        positiveFeatures = "",
        negativeFeatures = "",
        reasonCode = "TEST",
        localDecision = "TEST",
        promptVersion = "ledger.prompt.v1",
        contractVersion = "ledger.v1",
        createdAtMillis = 0L,
    )
}
