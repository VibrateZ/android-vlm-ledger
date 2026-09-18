package com.vibratez.ledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibratez.ledger.statement.StatementPreview

@Composable
internal fun StatementImportCard(
    preview: StatementPreview?,
    message: String?,
    busy: Boolean,
    onPick: () -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("官方账单导入", style = MaterialTheme.typography.titleMedium)
            Text(
                "选择支付宝或微信支付官方导出的 CSV、XLSX 或 ZIP。应用会先验证格式并预览，确认后才写入账本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                }
                Spacer(Modifier.size(6.dp))
                Text(if (busy) "正在验证" else "导入支付宝/微信账单")
            }
            preview?.let { value ->
                Text("${value.platform.displayName} · ${value.fileName}")
                Text(
                    "可导入 ${value.transactions.size} 条；跳过 ${value.skippedRows} 行；异常 ${value.invalidRows} 行",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value.invalidRows > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                value.warnings.forEach { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClear, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !busy && value.transactions.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("确认导入")
                    }
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
