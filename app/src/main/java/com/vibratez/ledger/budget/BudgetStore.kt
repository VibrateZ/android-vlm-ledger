package com.vibratez.ledger.budget

import android.content.Context
import androidx.room.withTransaction
import com.vibratez.ledger.ledger.BudgetFundEntryEntity
import com.vibratez.ledger.ledger.DailySettlementEntity
import com.vibratez.ledger.ledger.LedgerDatabase
import com.vibratez.ledger.ledger.MonthlyBudgetEntity
import com.vibratez.ledger.ledger.TransactionEntity
import com.vibratez.ledger.ledger.TransactionRecord
import com.vibratez.ledger.security.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class BudgetStore internal constructor(
    private val database: LedgerDatabase,
) {
    constructor(
        context: Context,
        settings: SecureSettings = SecureSettings(context.applicationContext),
    ) : this(LedgerDatabase.open(context.applicationContext, settings))

    private val budgetDao = database.budgetDao()
    private val transactionDao = database.transactionDao()

    suspend fun dashboard(date: LocalDate): BudgetDashboard = withContext(Dispatchers.IO) {
        database.withTransaction { dashboardInTransaction(date) }
    }

    suspend fun setMonthlyBudget(month: YearMonth, amountMinor: Long): BudgetMutationResult =
        withContext(Dispatchers.IO) {
            if (amountMinor <= 0L) {
                return@withContext BudgetMutationResult.Invalid("月预算必须大于 0")
            }
            budgetDao.upsertMonthlyBudget(
                MonthlyBudgetEntity(
                    yearMonth = month.toString(),
                    amountMinor = amountMinor,
                    currency = DailyBudgetCalculator.BUDGET_CURRENCY,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            BudgetMutationResult.Success
        }

    suspend fun allocateFromPool(
        targetDate: LocalDate,
        amountMinor: Long,
        today: LocalDate,
    ): BudgetMutationResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val latestSettlement = budgetDao.latestSettlement(targetDate.toString())
            val poolBalance = budgetDao.poolBalance()
            when {
                targetDate > today -> BudgetMutationResult.Invalid("不能提前向未来日期拨款")
                targetDate < today && latestSettlement != null ->
                    BudgetMutationResult.Invalid("不能向已结算的过去日期拨款")
                BudgetFundPolicy.allocationError(poolBalance, amountMinor) != null ->
                    BudgetMutationResult.Invalid(
                        BudgetFundPolicy.allocationError(poolBalance, amountMinor)!!,
                    )
                else -> {
                    budgetDao.insertFundEntry(
                        BudgetFundEntryEntity(
                            id = UUID.randomUUID().toString(),
                            entryType = ENTRY_ALLOCATION,
                            poolDeltaMinor = -amountMinor,
                            savingsDeltaMinor = 0L,
                            allocationMinor = amountMinor,
                            targetDate = targetDate.toString(),
                            settlementId = null,
                            reversesEntryId = null,
                            createdAtMillis = System.currentTimeMillis(),
                        ),
                    )
                    BudgetMutationResult.Success
                }
            }
        }
    }

    suspend fun settleDay(
        date: LocalDate,
        destination: SettlementDestination,
        today: LocalDate,
    ): BudgetMutationResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (date >= today) return@withTransaction BudgetMutationResult.Invalid("当天结束后才能结算盈余")
            val dashboard = dashboardInTransaction(date)
            val calculation = dashboard.day.calculation
            val balance = calculation.balanceMinor
                ?: return@withTransaction BudgetMutationResult.Invalid("请先设置当月预算")
            val previous = budgetDao.latestSettlement(date.toString())
            if (previous?.snapshotHash == calculation.snapshotHash) {
                return@withTransaction BudgetMutationResult.AlreadySettled
            }
            if (balance <= 0L && previous == null) {
                return@withTransaction BudgetMutationResult.Invalid("只有正盈余可以结算")
            }

            val newSettlementId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            if (previous != null) {
                val previousCredit = budgetDao.settlementCredit(previous.id)
                if (previousCredit != null) {
                    val reversal = BudgetFundPolicy.reverse(
                        FundDelta(previousCredit.poolDeltaMinor, previousCredit.savingsDeltaMinor),
                    )
                    budgetDao.insertFundEntry(
                        BudgetFundEntryEntity(
                            id = UUID.randomUUID().toString(),
                            entryType = ENTRY_REVERSAL,
                            poolDeltaMinor = reversal.poolMinor,
                            savingsDeltaMinor = reversal.savingsMinor,
                            allocationMinor = 0L,
                            targetDate = date.toString(),
                            settlementId = newSettlementId,
                            reversesEntryId = previousCredit.id,
                            createdAtMillis = now,
                        ),
                    )
                }
            }

            val revision = (previous?.revision ?: 0) + 1
            budgetDao.insertSettlement(
                DailySettlementEntity(
                    id = newSettlementId,
                    settlementDate = date.toString(),
                    revision = revision,
                    baseBudgetMinor = calculation.baseBudgetMinor!!,
                    allocatedMinor = calculation.allocatedMinor,
                    netExpenseMinor = calculation.netExpenseMinor,
                    incomeMinor = calculation.incomeMinor,
                    balanceMinor = balance,
                    destination = destination.name,
                    settledAmountMinor = balance.coerceAtLeast(0L),
                    snapshotHash = calculation.snapshotHash,
                    reversesSettlementId = previous?.id,
                    createdAtMillis = now,
                ),
            )
            if (balance > 0L) {
                val credit = BudgetFundPolicy.settlementCredit(destination, balance)
                budgetDao.insertFundEntry(
                    BudgetFundEntryEntity(
                        id = UUID.randomUUID().toString(),
                        entryType = if (destination == SettlementDestination.POOL) {
                            ENTRY_SETTLE_POOL
                        } else {
                            ENTRY_SETTLE_SAVINGS
                        },
                        poolDeltaMinor = credit.poolMinor,
                        savingsDeltaMinor = credit.savingsMinor,
                        allocationMinor = 0L,
                        targetDate = date.toString(),
                        settlementId = newSettlementId,
                        reversesEntryId = null,
                        createdAtMillis = now,
                    ),
                )
            }
            BudgetMutationResult.Success
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) { database.close() }

    private suspend fun dashboardInTransaction(date: LocalDate): BudgetDashboard {
        val month = YearMonth.from(date)
        val budgetEntity = budgetDao.monthlyBudget(month.toString())
        val monthlyBudget = budgetEntity?.takeIf { it.currency == DailyBudgetCalculator.BUDGET_CURRENCY }
            ?.let { MonthlyBudget(month, it.amountMinor, it.updatedAtMillis) }
        val dayTransactions = transactionDao.onDate(date.toString()).map(::toRecord)
        val monthTransactions = transactionDao.inMonth(month.toString()).map(::toRecord)
        val allocation = budgetDao.allocatedToDate(date.toString())
        val calculation = DailyBudgetCalculator.calculate(
            date = date,
            monthlyBudgetMinor = monthlyBudget?.amountMinor,
            allocatedMinor = allocation,
            transactions = dayTransactions,
        )
        val settlement = budgetDao.latestSettlement(date.toString())?.toModel()
        return BudgetDashboard(
            day = DailyBudgetView(
                calculation = calculation,
                latestSettlement = settlement,
                needsReconciliation = BudgetFundPolicy.needsReconciliation(
                    settlement?.snapshotHash,
                    calculation.snapshotHash,
                ),
            ),
            monthBudget = monthlyBudget,
            monthNetExpenseMinor = DailyBudgetCalculator.monthNetExpense(monthTransactions, month),
            monthIncomeMinor = DailyBudgetCalculator.monthIncome(monthTransactions, month),
            poolBalanceMinor = budgetDao.poolBalance(),
            savingsBalanceMinor = budgetDao.savingsBalance(),
            recentSavingsEntries = budgetDao.recentSavingsEntries(5).map { entry ->
                SavingsLedgerEntry(
                    sourceDate = entry.targetDate?.let(LocalDate::parse),
                    amountMinor = entry.savingsDeltaMinor,
                    createdAtMillis = entry.createdAtMillis,
                    isAdjustment = entry.entryType == ENTRY_REVERSAL,
                )
            },
        )
    }

    private fun DailySettlementEntity.toModel() = DailySettlement(
        id = id,
        date = LocalDate.parse(settlementDate),
        revision = revision,
        destination = SettlementDestination.valueOf(destination),
        settledAmountMinor = settledAmountMinor,
        snapshotHash = snapshotHash,
        createdAtMillis = createdAtMillis,
    )

    private fun toRecord(entity: TransactionEntity) = TransactionRecord(
        id = entity.id,
        direction = entity.direction,
        amountMinor = entity.amountMinor,
        currency = entity.currency,
        merchant = entity.merchant,
        counterparty = entity.counterparty,
        occurredAt = entity.occurredAt,
        suggestedTag = entity.suggestedTag,
        sha256 = entity.sha256,
        transactionFingerprint = entity.transactionFingerprint,
        sourceUri = entity.sourceUri,
        platform = entity.platform,
        confidence = entity.confidence,
        timeSource = entity.timeSource,
        externalId = entity.externalId,
        vlmModel = entity.vlmModel,
        vlmRequestId = entity.vlmRequestId,
        screenshotCapturedAtMillis = entity.screenshotCapturedAtMillis,
        positiveFeatures = entity.positiveFeatures,
        negativeFeatures = entity.negativeFeatures,
        reasonCode = entity.reasonCode,
        localDecision = entity.localDecision,
        promptVersion = entity.promptVersion,
        contractVersion = entity.contractVersion,
        createdAtMillis = entity.createdAtMillis,
    )

    private companion object {
        const val ENTRY_SETTLE_POOL = "SETTLE_POOL"
        const val ENTRY_SETTLE_SAVINGS = "SETTLE_SAVINGS"
        const val ENTRY_ALLOCATION = "ALLOCATION"
        const val ENTRY_REVERSAL = "REVERSAL"
    }
}
