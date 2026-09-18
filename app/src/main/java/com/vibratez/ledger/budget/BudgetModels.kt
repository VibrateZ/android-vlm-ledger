package com.vibratez.ledger.budget

import java.time.LocalDate
import java.time.YearMonth

enum class SettlementDestination {
    POOL,
    SAVINGS,
}

data class MonthlyBudget(
    val month: YearMonth,
    val amountMinor: Long,
    val updatedAtMillis: Long,
)

data class DailySettlement(
    val id: String,
    val date: LocalDate,
    val revision: Int,
    val destination: SettlementDestination,
    val settledAmountMinor: Long,
    val snapshotHash: String,
    val createdAtMillis: Long,
)

data class DailyBudgetView(
    val calculation: DailyBudgetCalculation,
    val latestSettlement: DailySettlement?,
    val needsReconciliation: Boolean,
) {
    val isSettled: Boolean
        get() = latestSettlement != null && !needsReconciliation
}

data class BudgetDashboard(
    val day: DailyBudgetView,
    val monthBudget: MonthlyBudget?,
    val monthNetExpenseMinor: Long,
    val monthIncomeMinor: Long,
    val poolBalanceMinor: Long,
    val savingsBalanceMinor: Long,
    val recentSavingsEntries: List<SavingsLedgerEntry>,
)

data class SavingsLedgerEntry(
    val sourceDate: LocalDate?,
    val amountMinor: Long,
    val createdAtMillis: Long,
    val isAdjustment: Boolean,
)

sealed interface BudgetMutationResult {
    data object Success : BudgetMutationResult
    data object AlreadySettled : BudgetMutationResult
    data class Invalid(val message: String) : BudgetMutationResult
}
