package com.vibratez.ledger.budget

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibratez.ledger.ledger.LedgerDatabase
import com.vibratez.ledger.ledger.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BudgetStoreInstrumentedTest {
    private lateinit var database: LedgerDatabase
    private lateinit var store: BudgetStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = BudgetStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun poolCrossesMonthBoundaryAndAllocationTargetsNextMonth() = runBlocking {
        val januaryDay = LocalDate.of(2026, 1, 31)
        val februaryDay = LocalDate.of(2026, 2, 1)
        assertTrue(store.setMonthlyBudget(januaryDay.withDayOfMonth(1).let(java.time.YearMonth::from), 3_100L) is BudgetMutationResult.Success)
        assertTrue(store.settleDay(januaryDay, SettlementDestination.POOL, februaryDay) is BudgetMutationResult.Success)
        assertEquals(100L, store.dashboard(januaryDay).poolBalanceMinor)

        assertTrue(store.allocateFromPool(februaryDay, 60L, februaryDay) is BudgetMutationResult.Success)
        val february = store.dashboard(februaryDay)
        assertEquals(40L, february.poolBalanceMinor)
        assertEquals(60L, february.day.calculation.allocatedMinor)
    }

    @Test
    fun savingsDoNotIncreasePoolAndDuplicateSettlementIsRejected() = runBlocking {
        val date = LocalDate.of(2026, 1, 30)
        val today = date.plusDays(1)
        store.setMonthlyBudget(java.time.YearMonth.from(date), 3_100L)

        assertTrue(store.settleDay(date, SettlementDestination.SAVINGS, today) is BudgetMutationResult.Success)
        assertEquals(0L, store.dashboard(date).poolBalanceMinor)
        assertEquals(100L, store.dashboard(date).savingsBalanceMinor)
        assertTrue(store.settleDay(date, SettlementDestination.SAVINGS, today) is BudgetMutationResult.AlreadySettled)
    }

    @Test
    fun lateTransactionRequiresReconciliationAndWritesReversal() = runBlocking {
        val date = LocalDate.of(2026, 1, 30)
        val today = date.plusDays(1)
        store.setMonthlyBudget(java.time.YearMonth.from(date), 3_100L)
        store.settleDay(date, SettlementDestination.POOL, today)
        database.transactionDao().insert(transaction("late", date, 80L))

        assertTrue(store.dashboard(date).day.needsReconciliation)
        assertTrue(store.settleDay(date, SettlementDestination.POOL, today) is BudgetMutationResult.Success)
        val reconciled = store.dashboard(date)
        assertEquals(20L, reconciled.poolBalanceMinor)
        assertTrue(reconciled.day.isSettled)
        assertEquals(2, reconciled.day.latestSettlement?.revision)
    }

    @Test
    fun consumedOldCreditCreatesDebtAndBlocksFurtherAllocation() = runBlocking {
        val date = LocalDate.of(2026, 1, 29)
        val allocationDate = date.plusDays(1)
        val today = date.plusDays(2)
        store.setMonthlyBudget(java.time.YearMonth.from(date), 3_100L)
        store.settleDay(date, SettlementDestination.POOL, today)
        store.allocateFromPool(allocationDate, 100L, today)
        database.transactionDao().insert(transaction("late", date, 80L))

        store.settleDay(date, SettlementDestination.POOL, today)
        assertEquals(-80L, store.dashboard(date).poolBalanceMinor)
        assertTrue(store.allocateFromPool(today, 1L, today) is BudgetMutationResult.Invalid)
    }

    @Test
    fun changingMonthlyBudgetMarksSettlementForReconciliation() = runBlocking {
        val date = LocalDate.of(2026, 1, 29)
        val today = date.plusDays(1)
        store.setMonthlyBudget(java.time.YearMonth.from(date), 3_100L)
        store.settleDay(date, SettlementDestination.POOL, today)

        store.setMonthlyBudget(java.time.YearMonth.from(date), 6_200L)

        assertTrue(store.dashboard(date).day.needsReconciliation)
    }

    @Test
    fun allocationToSettledPastDateIsRejected() = runBlocking {
        val date = LocalDate.of(2026, 1, 29)
        val today = date.plusDays(1)
        store.setMonthlyBudget(java.time.YearMonth.from(date), 3_100L)
        store.settleDay(date, SettlementDestination.POOL, today)

        val result = store.allocateFromPool(date, 1L, today)

        assertTrue(result is BudgetMutationResult.Invalid)
        assertEquals(100L, store.dashboard(date).poolBalanceMinor)
        assertEquals(0L, store.dashboard(date).day.calculation.allocatedMinor)
    }

    private fun transaction(id: String, date: LocalDate, amountMinor: Long) = TransactionEntity(
        id = id,
        direction = "EXPENSE",
        amountMinor = amountMinor,
        currency = "CNY",
        merchant = "测试商户",
        counterparty = null,
        occurredAt = "${date}T12:00:00+08:00",
        suggestedTag = "测试",
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
