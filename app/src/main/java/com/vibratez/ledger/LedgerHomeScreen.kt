package com.vibratez.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vibratez.ledger.budget.BudgetDashboard
import com.vibratez.ledger.budget.BudgetMutationResult
import com.vibratez.ledger.budget.BudgetStore
import com.vibratez.ledger.budget.DailyBudgetCalculator
import com.vibratez.ledger.budget.SettlementDestination
import com.vibratez.ledger.ledger.TransactionRecord
import com.vibratez.ledger.ledger.LedgerStore
import com.vibratez.ledger.ui.LedgerConfirmationEditor
import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Evidence
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import com.vibratez.ledger.vlm.TimeSource
import com.vibratez.ledger.ui.parseAmountMinor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun LedgerHomeScreen(
    budgetStore: BudgetStore,
    ledgerStore: LedgerStore,
    refreshKey: Any,
    modifier: Modifier = Modifier,
    showMessage: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var selectedDate by remember { mutableStateOf(today) }
    var windowEnd by remember { mutableStateOf(today) }
    var dashboard by remember { mutableStateOf<BudgetDashboard?>(null) }
    var undatedTransactions by remember { mutableStateOf<List<TransactionRecord>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var budgetDialog by remember { mutableStateOf(false) }
    var allocationDialog by remember { mutableStateOf(false) }
    var transactionDetail by remember { mutableStateOf<TransactionRecord?>(null) }
    var editingTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    var creatingTransaction by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDate, refresh, refreshKey) {
        dashboard = null
        dashboard = budgetStore.dashboard(selectedDate)
        undatedTransactions = ledgerStore.withoutTime()
    }

    val data = dashboard
    if (data == null) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            MonthOverviewCard(
                dashboard = data,
                onSetBudget = { budgetDialog = true },
            )
        }
        item {
            PoolAndSavingsCard(
                dashboard = data,
                canAllocate = data.poolBalanceMinor > 0L &&
                    selectedDate <= today &&
                    !(selectedDate < today && data.day.latestSettlement != null),
                onAllocate = { allocationDialog = true },
            )
        }
        item {
            DateStrip(
                windowEnd = windowEnd,
                selectedDate = selectedDate,
                today = today,
                onPrevious = {
                    windowEnd = windowEnd.minusDays(7)
                    selectedDate = windowEnd
                },
                onNext = {
                    windowEnd = minOf(today, windowEnd.plusDays(7))
                    selectedDate = windowEnd
                },
                onSelect = { selectedDate = it },
            )
        }
        item {
            DayBudgetCard(
                dashboard = data,
                date = selectedDate,
                today = today,
                onSetBudget = { budgetDialog = true },
                onSettle = { destination ->
                    scope.launch {
                        val result = budgetStore.settleDay(selectedDate, destination, today)
                        showMessage(result.message("已完成每日结算"))
                        refresh++
                    }
                },
                onAllocate = { allocationDialog = true },
            )
        }
        if (undatedTransactions.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "时间待补充（${undatedTransactions.size}）",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "这些账目已保存在本地，但未计入任何日期或预算。点击账目可补充时间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(undatedTransactions, key = { "undated:${it.id}" }) { transaction ->
                TransactionPreviewRow(transaction) { transactionDetail = transaction }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当日流水",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { creatingTransaction = true }) { Text("新增") }
            }
        }
        if (data.day.calculation.transactions.isEmpty()) {
            item {
                Text(
                    "这一天还没有账目。识别并确认的账目会自动出现在这里。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(data.day.calculation.transactions, key = TransactionRecord::id) { transaction ->
                TransactionPreviewRow(transaction) { transactionDetail = transaction }
            }
        }
        if (data.day.calculation.excludedTransactionCount > 0) {
            item {
                Text(
                    "${data.day.calculation.excludedTransactionCount} 条非 CNY 账目仅展示，不计入预算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    if (budgetDialog) {
        AmountDialog(
            title = "设置 ${YearMonth.from(selectedDate)} 月预算",
            initialMinor = data.monthBudget?.amountMinor,
            confirmText = "保存月预算",
            onDismiss = { budgetDialog = false },
            onConfirm = { amount ->
                scope.launch {
                    val result = budgetStore.setMonthlyBudget(YearMonth.from(selectedDate), amount)
                    showMessage(result.message("月预算已保存"))
                    budgetDialog = false
                    refresh++
                }
            },
        )
    }

    if (allocationDialog) {
        val balance = data.day.calculation.balanceMinor
        val suggested = if (balance != null && balance < 0L) -balance else null
        AmountDialog(
            title = "从预算池拨入 ${selectedDate.format(DateTimeFormatter.ofPattern("M月d日"))}",
            initialMinor = suggested?.coerceAtMost(data.poolBalanceMinor),
            confirmText = "确认拨款",
            supportingText = "当前预算池：${formatCny(data.poolBalanceMinor)}",
            onDismiss = { allocationDialog = false },
            onConfirm = { amount ->
                scope.launch {
                    val result = budgetStore.allocateFromPool(selectedDate, amount, today)
                    showMessage(result.message("预算已拨入"))
                    if (result is BudgetMutationResult.Success) allocationDialog = false
                    refresh++
                }
            },
        )
    }

    transactionDetail?.let { transaction ->
        TransactionDetailDialog(
            transaction = transaction,
            onDismiss = { transactionDetail = null },
            onEdit = {
                transactionDetail = null
                editingTransaction = transaction
            },
            onDelete = {
                scope.launch {
                    ledgerStore.delete(transaction.id)
                    transactionDetail = null
                    refresh++
                    showMessage("账目已删除")
                }
            },
        )
    }

    if (creatingTransaction || editingTransaction != null) {
        val existing = editingTransaction
        LedgerEditDialog(
            initial = existing?.toLedgerV1() ?: emptyManualLedger(),
            initialRecord = existing,
            title = if (existing == null) "新增账目" else "修改账目",
            onDismiss = {
                creatingTransaction = false
                editingTransaction = null
            },
            onSave = { corrected, itemName, account, paymentMethod, note ->
                scope.launch {
                    val saved = if (existing == null) {
                        ledgerStore.addIfAbsent(
                            ledger = corrected,
                            sha256 = "manual:${UUID.randomUUID()}",
                            localDecision = "MANUAL",
                            itemName = itemName,
                            account = account,
                            paymentMethod = paymentMethod,
                            note = note,
                        ) != null
                    } else {
                        ledgerStore.update(
                            existing.copy(
                                direction = corrected.direction.name,
                                amountMinor = corrected.amountMinor,
                                currency = corrected.currency,
                                merchant = corrected.merchant,
                                counterparty = corrected.counterparty,
                                occurredAt = corrected.occurredAt?.toString(),
                                suggestedTag = corrected.suggestedTag,
                                platform = corrected.platform.name,
                                timeSource = corrected.timeSource?.name,
                                externalId = corrected.externalId,
                                itemName = itemName,
                                account = account,
                                paymentMethod = paymentMethod,
                                note = note,
                            ),
                        )
                    }
                    if (saved) {
                        creatingTransaction = false
                        editingTransaction = null
                        refresh++
                        showMessage("账目已保存")
                    } else {
                        showMessage("账目保存失败或已存在")
                    }
                }
            },
        )
    }
}

@Composable
private fun MonthOverviewCard(dashboard: BudgetDashboard, onSetBudget: () -> Unit) {
    val budget = dashboard.monthBudget
    val month = YearMonth.from(dashboard.day.calculation.date)
    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("${month} 月度总预算", style = MaterialTheme.typography.titleMedium)
                Text(
                    budget?.let { formatCny(it.amountMinor) } ?: "尚未设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TextButton(onClick = onSetBudget) { Text(if (budget == null) "设置" else "修改") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        if (budget != null) {
            val budgetUsage = DailyBudgetCalculator.monthBudgetUsage(
                netExpenseMinor = dashboard.monthNetExpenseMinor,
                incomeMinor = dashboard.monthIncomeMinor,
            )
            val displayedUsage = budgetUsage.coerceAtLeast(0L)
            val remaining = budget.amountMinor - budgetUsage
            val overBudget = remaining < 0L
            val usagePercent = if (budget.amountMinor > 0L) {
                ((displayedUsage.toDouble() / budget.amountMinor.toDouble()) * 100.0)
                    .coerceAtLeast(0.0)
                    .roundToInt()
            } else {
                0
            }
            LinearProgressIndicator(
                progress = { (displayedUsage.toFloat() / budget.amountMinor.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "预算占用 ${formatCny(displayedUsage)} / ${formatCny(budget.amountMinor)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "$usagePercent%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (overBudget) "已超支 ${formatCny(-remaining)}" else "剩余 ${formatCny(remaining)}",
                style = MaterialTheme.typography.titleSmall,
                color = if (overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                "日预算拨入和结余仅调整每日额度；本月收入会抵扣月预算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SummaryRow("本月支出（退款后）", formatCny(dashboard.monthNetExpenseMinor))
        SummaryRow("本月收入（抵扣月预算）", formatCny(dashboard.monthIncomeMinor))
    }
}

@Composable
private fun PoolAndSavingsCard(
    dashboard: BudgetDashboard,
    canAllocate: Boolean,
    onAllocate: () -> Unit,
) {
    LedgerCard {
        Text("长期资金", style = MaterialTheme.typography.titleMedium)
        SummaryRow(
            if (dashboard.poolBalanceMinor < 0L) "预算池调整欠额" else "预算池",
            formatCny(dashboard.poolBalanceMinor),
            if (dashboard.poolBalanceMinor < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        SummaryRow("累计存款", formatCny(dashboard.savingsBalanceMinor))
        if (dashboard.recentSavingsEntries.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            Text("最近存款记录", style = MaterialTheme.typography.labelLarge)
            dashboard.recentSavingsEntries.forEach { entry ->
                val settledAt = Instant.ofEpochMilli(entry.createdAtMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("M-d HH:mm"))
                SummaryRow(
                    label = (entry.sourceDate?.toString() ?: "未知日期") +
                        if (entry.isAdjustment) " · 调整" else " · $settledAt",
                    value = (if (entry.amountMinor >= 0L) "+" else "") + formatCny(entry.amountMinor),
                    valueColor = if (entry.amountMinor < 0L) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        if (dashboard.poolBalanceMinor < 0L) {
            Text(
                "历史结算发生变化，新增盈余会优先抵消欠额；欠额清零前不能继续拨款。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = onAllocate,
            enabled = canAllocate,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("从预算池拨入所选日期") }
    }
}

@Composable
private fun DateStrip(
    windowEnd: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val dates = (6L downTo 0L).map(windowEnd::minusDays)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "前七天")
            }
            Text("${dates.first()} — ${dates.last()}", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onNext, enabled = windowEnd < today) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "后七天")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dates.forEach { date ->
                val selected = date == selectedDate
                if (selected) {
                    Button(
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) { Text(date.dayOfMonth.toString()) }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) { Text(date.dayOfMonth.toString()) }
                }
            }
        }
    }
}

@Composable
private fun DayBudgetCard(
    dashboard: BudgetDashboard,
    date: LocalDate,
    today: LocalDate,
    onSetBudget: () -> Unit,
    onSettle: (SettlementDestination) -> Unit,
    onAllocate: () -> Unit,
) {
    val day = dashboard.day
    val calculation = day.calculation
    LedgerCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (date == today) "今日概览" else date.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)),
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                day.needsReconciliation -> Text("需要重新结算", color = MaterialTheme.colorScheme.error)
                day.isSettled -> Text("已结算 · 第${day.latestSettlement?.revision}版", color = MaterialTheme.colorScheme.primary)
                date == today -> Text("实时预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (calculation.baseBudgetMinor == null) {
            Text("设置月预算后即可计算每日盈余或超支。")
            Button(onClick = onSetBudget, modifier = Modifier.fillMaxWidth()) { Text("设置月预算") }
            return@LedgerCard
        }
        val balance = calculation.balanceMinor ?: 0L
        Text(
            when {
                balance > 0L -> "盈余 ${formatCny(balance)}"
                balance < 0L -> "超支 ${formatCny(-balance)}"
                else -> "今日预算刚好用完"
            },
            style = MaterialTheme.typography.titleLarge,
            color = if (balance < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        SummaryRow("基础预算", formatCny(calculation.baseBudgetMinor))
        SummaryRow("预算池拨入", formatCny(calculation.allocatedMinor))
        SummaryRow("支出", formatCny(calculation.expenseMinor))
        SummaryRow("退款", formatCny(calculation.refundMinor))
        SummaryRow("净支出", formatCny(calculation.netExpenseMinor))
        SummaryRow("收入（单列）", formatCny(calculation.incomeMinor))

        if (
            balance < 0L &&
            dashboard.poolBalanceMinor > 0L &&
            date <= today &&
            !(date < today && day.latestSettlement != null)
        ) {
            OutlinedButton(onClick = onAllocate, modifier = Modifier.fillMaxWidth()) {
                Text("从预算池覆盖超支")
            }
        }
        if (date < today && balance > 0L && (!day.isSettled || day.needsReconciliation)) {
            if (day.needsReconciliation) {
                Text(
                    "账目或月预算已变化。重新结算会冲正旧结果并保留审计记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSettle(SettlementDestination.POOL) },
                    modifier = Modifier.weight(1f),
                ) { Text("转入预算池") }
                OutlinedButton(
                    onClick = { onSettle(SettlementDestination.SAVINGS) },
                    modifier = Modifier.weight(1f),
                ) { Text("记为存款") }
            }
        } else if (date < today && day.needsReconciliation) {
            Text(
                "账目或月预算已变化。确认后将冲正旧结果；当前没有新的正盈余。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = {
                    onSettle(day.latestSettlement?.destination ?: SettlementDestination.POOL)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("确认重新结算") }
        }
    }
}

@Composable
private fun TransactionPreviewRow(transaction: TransactionRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(transaction.merchant ?: transaction.counterparty ?: transaction.suggestedTag ?: "未命名账目")
                Text(
                    runCatching {
                        OffsetDateTime.parse(transaction.occurredAt).format(DateTimeFormatter.ofPattern("HH:mm"))
                    }.getOrDefault(transaction.occurredAt ?: "时间待补充"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                directionPrefix(transaction.direction) + formatAmount(transaction.amountMinor, transaction.currency),
                fontWeight = FontWeight.SemiBold,
                color = if (transaction.direction == "EXPENSE") {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun TransactionDetailDialog(
    transaction: TransactionRecord,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账目详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryRow("金额", directionPrefix(transaction.direction) + formatAmount(transaction.amountMinor, transaction.currency))
                SummaryRow("交易时间", transaction.occurredAt ?: "待补充")
                SummaryRow("平台", transaction.platform)
                transaction.merchant?.let { SummaryRow("商户", it) }
                transaction.counterparty?.let { SummaryRow("交易对方", it) }
                transaction.suggestedTag?.let { SummaryRow("标签", it) }
                transaction.itemName?.let { SummaryRow("项目", it) }
                transaction.account?.let { SummaryRow("账户", it) }
                transaction.paymentMethod?.let { SummaryRow("付款方式", it) }
                transaction.note?.let { SummaryRow("备注", it) }
                Text(
                    if (transaction.currency == "CNY") "计入 CNY 预算" else "非 CNY 账目，不计入预算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("修改") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun LedgerEditDialog(
    initial: LedgerV1,
    initialRecord: TransactionRecord?,
    title: String,
    onDismiss: () -> Unit,
    onSave: (LedgerV1, String?, String?, String?, String?) -> Unit,
) {
    var itemName by remember(initialRecord?.id) { mutableStateOf(initialRecord?.itemName.orEmpty()) }
    var account by remember(initialRecord?.id) { mutableStateOf(initialRecord?.account.orEmpty()) }
    var paymentMethod by remember(initialRecord?.id) { mutableStateOf(initialRecord?.paymentMethod.orEmpty()) }
    var note by remember(initialRecord?.id) { mutableStateOf(initialRecord?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { if (it.length <= 120) itemName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("项目/商品（可选）") },
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { if (it.length <= 80) account = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("账户（可选）") },
                )
                OutlinedTextField(
                    value = paymentMethod,
                    onValueChange = { if (it.length <= 80) paymentMethod = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("付款方式（可选）") },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 500) note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注（可选）") },
                    minLines = 2,
                )
                LedgerConfirmationEditor(
                    ledger = initial,
                    saving = false,
                    saved = false,
                    alreadyStored = false,
                    onConfirm = { ledger ->
                        onSave(
                            ledger,
                            itemName.trim().ifEmpty { null },
                            account.trim().ifEmpty { null },
                            paymentMethod.trim().ifEmpty { null },
                            note.trim().ifEmpty { null },
                        )
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun emptyManualLedger() = LedgerV1(
    decision = Decision.NEEDS_CONFIRMATION,
    isPaymentScreenshot = true,
    platform = Platform.UNKNOWN,
    direction = Direction.UNKNOWN,
    amountMinor = null,
    currency = null,
    merchant = null,
    counterparty = null,
    occurredAt = null,
    timeSource = null,
    externalId = null,
    suggestedTag = null,
    confidence = 1.0,
    evidence = Evidence(emptyList(), emptyList(), Freshness.UNKNOWN, "INVALID_CONTEXT"),
)

private fun TransactionRecord.toLedgerV1() = LedgerV1(
    decision = Decision.NEEDS_CONFIRMATION,
    isPaymentScreenshot = true,
    platform = runCatching { Platform.valueOf(platform) }.getOrDefault(Platform.UNKNOWN),
    direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.UNKNOWN),
    amountMinor = amountMinor,
    currency = currency,
    merchant = merchant,
    counterparty = counterparty,
    occurredAt = occurredAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
    timeSource = occurredAt?.let { TimeSource.USER_CONFIRMED },
    externalId = externalId,
    suggestedTag = suggestedTag,
    confidence = confidence,
    evidence = Evidence(emptyList(), emptyList(), Freshness.UNKNOWN, "INVALID_CONTEXT"),
)

@Composable
private fun AmountDialog(
    title: String,
    initialMinor: Long?,
    confirmText: String,
    supportingText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var amount by remember(title, initialMinor) {
        mutableStateOf(initialMinor?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty())
    }
    var error by remember(title) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { value ->
                        if (value.length <= 16 && value.count { it == '.' } <= 1 && value.all { it.isDigit() || it == '.' }) {
                            amount = value
                            error = null
                        }
                    },
                    label = { Text("金额（CNY）") },
                    placeholder = { Text("1000.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val minor = parseAmountMinor(amount, "CNY")
                    if (minor == null) error = "请输入大于 0 且最多两位小数的金额" else onConfirm(minor)
                },
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LedgerCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

private fun BudgetMutationResult.message(success: String): String = when (this) {
    BudgetMutationResult.Success -> success
    BudgetMutationResult.AlreadySettled -> "当前数据已经结算，无需重复操作"
    is BudgetMutationResult.Invalid -> message
}

private fun formatCny(amountMinor: Long): String = formatAmount(amountMinor, "CNY")

private fun directionPrefix(direction: String): String = when (direction) {
    "EXPENSE" -> "-"
    "INCOME", "REFUND" -> "+"
    else -> ""
}
