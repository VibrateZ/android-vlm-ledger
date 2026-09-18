package com.vibratez.ledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import com.vibratez.ledger.vlm.TimeSource
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerConfirmationEditor(
    ledger: LedgerV1,
    saving: Boolean,
    saved: Boolean,
    alreadyStored: Boolean,
    onConfirm: (LedgerV1) -> Unit,
) {
    var amount by remember(ledger) { mutableStateOf(formatAmountForInput(ledger)) }
    var currency by remember(ledger) { mutableStateOf(ledger.currency.orEmpty()) }
    var platform by remember(ledger) { mutableStateOf(ledger.platform) }
    var direction by remember(ledger) { mutableStateOf(ledger.direction) }
    var occurredAt by remember(ledger) { mutableStateOf(ledger.occurredAt?.toString().orEmpty()) }
    var merchant by remember(ledger) { mutableStateOf(ledger.merchant.orEmpty()) }
    var counterparty by remember(ledger) { mutableStateOf(ledger.counterparty.orEmpty()) }
    var externalId by remember(ledger) { mutableStateOf(ledger.externalId.orEmpty()) }
    var suggestedTag by remember(ledger) { mutableStateOf(ledger.suggestedTag.orEmpty()) }
    var validationError by remember(ledger) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            label = "平台",
            selected = platform,
            values = Platform.entries,
            displayName = { it.displayName() },
            onSelected = { platform = it },
        )
        EnumDropdown(
            label = "方向",
            selected = direction,
            values = Direction.entries,
            displayName = { it.displayName() },
            onSelected = { direction = it },
        )
        OutlinedTextField(
            value = currency,
            onValueChange = { value ->
                val normalized = value.uppercase(Locale.ROOT)
                if (normalized.length <= 3 && normalized.all(Char::isLetter)) currency = normalized
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("币种") },
            placeholder = { Text("CNY") },
            singleLine = true,
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { value ->
                if (value.length <= 24 &&
                    value.count { it == '.' } <= 1 &&
                    value.all { it.isDigit() || it == '.' }
                ) {
                    amount = value
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("金额") },
            placeholder = { Text("12.80") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        OutlinedTextField(
            value = occurredAt,
            onValueChange = { if (it.length <= 40) occurredAt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("交易时间（RFC3339，含时区）") },
            placeholder = { Text("2026-09-14T12:30:00+08:00") },
            singleLine = true,
        )
        OutlinedTextField(
            value = merchant,
            onValueChange = { if (it.length <= 120 && '\n' !in it && '\r' !in it) merchant = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("商户或收款方（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = counterparty,
            onValueChange = { if (it.length <= 120 && '\n' !in it && '\r' !in it) counterparty = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("交易对方（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = externalId,
            onValueChange = { if (it.length <= 128 && '\n' !in it && '\r' !in it) externalId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("交易号（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = suggestedTag,
            onValueChange = { if (it.length <= 40 && '\n' !in it && '\r' !in it) suggestedTag = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标签（可选）") },
            singleLine = true,
        )
        validationError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val amountMinor = if (amount.isBlank()) null else parseAmountMinor(amount, currency)
                val time = if (occurredAt.isBlank()) null else {
                    runCatching { OffsetDateTime.parse(occurredAt) }.getOrNull()
                }
                val error = when {
                    currency.isNotBlank() &&
                        (!Regex("[A-Z]{3}").matches(currency) || currencyFractionDigits(currency) == null) ->
                        "币种必须是有效的三位 ISO-4217 代码"
                    amount.isNotBlank() && amountMinor == null ->
                        "填写金额时必须同时填写有效币种，且金额需大于 0"
                    occurredAt.isNotBlank() && time == null -> "交易时间必须是带时区的 RFC3339 时间"
                    merchant != merchant.trim() || merchant.any(Char::isISOControl) ->
                        "商户字段不能包含首尾空格或控制字符"
                    counterparty != counterparty.trim() || counterparty.any(Char::isISOControl) ->
                        "交易对方不能包含首尾空格或控制字符"
                    externalId != externalId.trim() || externalId.any(Char::isISOControl) ->
                        "交易号不能包含首尾空格或控制字符"
                    suggestedTag != suggestedTag.trim() || suggestedTag.any(Char::isISOControl) ->
                        "标签不能包含首尾空格或控制字符"
                    else -> null
                }
                validationError = error
                if (error == null) {
                    onConfirm(
                        ledger.copy(
                            decision = Decision.NEEDS_CONFIRMATION,
                            isPaymentScreenshot = true,
                            platform = platform,
                            direction = direction,
                            amountMinor = amountMinor,
                            currency = currency.ifBlank { null },
                            merchant = merchant.ifEmpty { null },
                            counterparty = counterparty.ifEmpty { null },
                            occurredAt = time,
                            timeSource = if (time == null) null else TimeSource.USER_CONFIRMED,
                            externalId = externalId.ifEmpty { null },
                            suggestedTag = suggestedTag.ifEmpty { null },
                        ),
                    )
                }
            },
            enabled = !saving && !saved && !alreadyStored,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    saving -> "保存中"
                    saved -> "已确认录入"
                    alreadyStored -> "已存在相同账目"
                    else -> "保存账目（允许缺失字段）"
                },
            )
        }
    }
}

internal fun formatAmountForInput(ledger: LedgerV1): String {
    val minor = ledger.amountMinor ?: return ""
    val currency = ledger.currency ?: return ""
    val fractionDigits = currencyFractionDigits(currency) ?: return ""
    return BigDecimal.valueOf(minor)
        .movePointLeft(fractionDigits)
        .setScale(fractionDigits)
        .toPlainString()
}

internal fun parseAmountMinor(amount: String, currency: String): Long? {
    val fractionDigits = currencyFractionDigits(currency) ?: return null
    val major = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
    return runCatching {
        major.setScale(fractionDigits, RoundingMode.UNNECESSARY)
            .movePointRight(fractionDigits)
            .longValueExact()
    }.getOrNull()
}

private fun currencyFractionDigits(currency: String): Int? = runCatching {
    Currency.getInstance(currency).defaultFractionDigits.takeIf { it in 0..6 }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    selected: T,
    values: List<T>,
    displayName: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = displayName(selected),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(displayName(value)) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun Platform.displayName(): String = when (this) {
    Platform.WECHAT -> "微信"
    Platform.ALIPAY -> "支付宝"
    Platform.OTHER -> "其他"
    Platform.UNKNOWN -> "未确定"
}

private fun Direction.displayName(): String = when (this) {
    Direction.EXPENSE -> "支出"
    Direction.INCOME -> "收入"
    Direction.REFUND -> "退款"
    Direction.UNKNOWN -> "未确定"
}
