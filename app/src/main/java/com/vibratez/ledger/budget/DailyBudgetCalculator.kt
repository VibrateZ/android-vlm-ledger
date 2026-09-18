package com.vibratez.ledger.budget

import com.vibratez.ledger.ledger.TransactionRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.OffsetDateTime

data class DailyBudgetCalculation(
    val date: LocalDate,
    val monthlyBudgetMinor: Long?,
    val baseBudgetMinor: Long?,
    val allocatedMinor: Long,
    val expenseMinor: Long,
    val refundMinor: Long,
    val incomeMinor: Long,
    val netExpenseMinor: Long,
    val balanceMinor: Long?,
    val excludedTransactionCount: Int,
    val transactions: List<TransactionRecord>,
    val snapshotHash: String,
)

object DailyBudgetCalculator {
    fun baseBudgetForDate(monthlyBudgetMinor: Long?, date: LocalDate): Long? {
        if (monthlyBudgetMinor == null || monthlyBudgetMinor < 0L) return null
        val days = date.lengthOfMonth().toLong()
        val quotient = monthlyBudgetMinor / days
        val remainder = monthlyBudgetMinor % days
        return quotient + if (date.dayOfMonth.toLong() <= remainder) 1L else 0L
    }

    fun calculate(
        date: LocalDate,
        monthlyBudgetMinor: Long?,
        allocatedMinor: Long,
        transactions: List<TransactionRecord>,
    ): DailyBudgetCalculation {
        require(allocatedMinor >= 0L)
        val dated = transactions.filter { transactionDate(it) == date }
        val budgeted = dated.filter { it.currency == BUDGET_CURRENCY && it.amountMinor != null }
        val expense = budgeted.filter { it.direction == "EXPENSE" }.sumOf { it.amountMinor ?: 0L }
        val refund = budgeted.filter { it.direction == "REFUND" }.sumOf { it.amountMinor ?: 0L }
        val income = budgeted.filter { it.direction == "INCOME" }.sumOf { it.amountMinor ?: 0L }
        val netExpense = expense - refund
        val base = baseBudgetForDate(monthlyBudgetMinor, date)
        val balance = base?.let { Math.addExact(it, allocatedMinor) - netExpense }
        return DailyBudgetCalculation(
            date = date,
            monthlyBudgetMinor = monthlyBudgetMinor,
            baseBudgetMinor = base,
            allocatedMinor = allocatedMinor,
            expenseMinor = expense,
            refundMinor = refund,
            incomeMinor = income,
            netExpenseMinor = netExpense,
            balanceMinor = balance,
            excludedTransactionCount = dated.count { it.currency != BUDGET_CURRENCY },
            transactions = dated.sortedByDescending { it.occurredAt.orEmpty() },
            snapshotHash = snapshotHash(date, monthlyBudgetMinor, allocatedMinor, dated),
        )
    }

    fun monthNetExpense(transactions: List<TransactionRecord>, month: YearMonth): Long =
        transactions.asSequence()
            .filter { it.currency == BUDGET_CURRENCY && transactionDate(it)?.let(YearMonth::from) == month }
            .sumOf {
                when (it.direction) {
                    "EXPENSE" -> it.amountMinor ?: 0L
                    "REFUND" -> -(it.amountMinor ?: 0L)
                    else -> 0L
                }
            }

    fun monthIncome(transactions: List<TransactionRecord>, month: YearMonth): Long =
        transactions.asSequence()
            .filter {
                it.currency == BUDGET_CURRENCY &&
                    it.direction == "INCOME" &&
                    transactionDate(it)?.let(YearMonth::from) == month
            }
            .sumOf { it.amountMinor ?: 0L }

    fun monthBudgetUsage(netExpenseMinor: Long, incomeMinor: Long): Long =
        netExpenseMinor - incomeMinor

    fun transactionDate(record: TransactionRecord): LocalDate? =
        record.occurredAt?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
            ?: java.time.Instant.ofEpochMilli(record.createdAtMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()

    private fun snapshotHash(
        date: LocalDate,
        monthlyBudgetMinor: Long?,
        allocatedMinor: Long,
        transactions: List<TransactionRecord>,
    ): String {
        val canonical = buildList {
            add("ledger.daily-budget.v1")
            add(date.toString())
            add(monthlyBudgetMinor?.toString() ?: "none")
            add(allocatedMinor.toString())
            transactions.sortedBy(TransactionRecord::id).forEach { record ->
                add(
                    listOf(
                        record.id,
                        record.direction,
                        record.amountMinor.toString(),
                        record.currency,
                        record.occurredAt,
                        record.transactionFingerprint.orEmpty(),
                    ).joinToString("\u001e"),
                )
            }
        }.joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    const val BUDGET_CURRENCY = "CNY"
}
