package com.vibratez.ledger.budget

data class FundDelta(
    val poolMinor: Long,
    val savingsMinor: Long,
)

object BudgetFundPolicy {
    fun settlementCredit(destination: SettlementDestination, amountMinor: Long): FundDelta {
        require(amountMinor > 0L)
        return if (destination == SettlementDestination.POOL) {
            FundDelta(poolMinor = amountMinor, savingsMinor = 0L)
        } else {
            FundDelta(poolMinor = 0L, savingsMinor = amountMinor)
        }
    }

    fun reverse(delta: FundDelta): FundDelta = FundDelta(
        poolMinor = -delta.poolMinor,
        savingsMinor = -delta.savingsMinor,
    )

    fun allocationError(poolBalanceMinor: Long, amountMinor: Long): String? = when {
        amountMinor <= 0L -> "拨款金额必须大于 0"
        poolBalanceMinor <= 0L -> "预算池存在欠额或没有可用余额"
        amountMinor > poolBalanceMinor -> "拨款金额超过预算池余额"
        else -> null
    }

    fun needsReconciliation(latestSnapshotHash: String?, currentSnapshotHash: String): Boolean =
        latestSnapshotHash != null && latestSnapshotHash != currentSnapshotHash
}
