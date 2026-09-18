package com.vibratez.ledger.statement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vibratez.ledger.ledger.LedgerDecisionEngine
import com.vibratez.ledger.ledger.LedgerStore
import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Evidence
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class OfficialStatementImporter(
    context: Context,
    private val ledgerStore: LedgerStore,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun preview(uri: Uri): StatementParseResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "官方账单"
        val bytes = resolver.openInputStream(uri)?.use(::readLimited)
            ?: return@withContext StatementParseResult.Invalid("无法打开所选账单")
        when (val parsed = OfficialStatementParser.parse(name, bytes)) {
            is StatementParseResult.Valid -> parsed.copy(
                preview = parsed.preview.copy(sourceUri = uri.toString()),
            )
            is StatementParseResult.Invalid -> parsed
        }
    }

    suspend fun commit(preview: StatementPreview): StatementImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var duplicates = 0
        preview.transactions.forEach { transaction ->
            val ledger = LedgerV1(
                decision = Decision.AUTO_BOOK,
                isPaymentScreenshot = false,
                platform = transaction.platform.ledgerPlatform,
                direction = transaction.direction,
                amountMinor = transaction.amountMinor,
                currency = "CNY",
                merchant = transaction.merchant,
                counterparty = null,
                occurredAt = transaction.occurredAt,
                timeSource = TimeSource.STATEMENT_VERIFIED,
                externalId = transaction.externalId,
                suggestedTag = transaction.description,
                confidence = 1.0,
                evidence = Evidence(
                    positiveFeatures = listOf("OFFICIAL_STATEMENT_EXPORT"),
                    negativeFeatures = emptyList(),
                    freshness = Freshness.VALID,
                    reasonCode = "STATEMENT_VERIFIED",
                ),
            )
            val rowHash = LedgerDecisionEngine.sha256(
                listOf(
                    "ledger.statement-row.v1",
                    transaction.platform.name,
                    transaction.direction.name,
                    transaction.amountMinor.toString(),
                    transaction.occurredAt.toInstant().toString(),
                    transaction.externalId,
                ).joinToString("\u001f").toByteArray(StandardCharsets.UTF_8),
            )
            val record = ledgerStore.addIfAbsent(
                ledger = ledger,
                sha256 = rowHash,
                sourceUri = preview.sourceUri,
                localDecision = "STATEMENT_IMPORT",
            )
            if (record == null) duplicates++ else imported++
        }
        StatementImportResult(imported = imported, duplicates = duplicates)
    }

    private fun displayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_INPUT_BYTES) { "账单文件超过 20 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    }
}
