package com.vibratez.ledger.ledger

import android.content.Context
import com.vibratez.ledger.security.SecureSettings
import com.vibratez.ledger.vlm.LedgerV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class TransactionRecord(
    val id: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val counterparty: String?,
    val occurredAt: String,
    val suggestedTag: String?,
    val sha256: String,
    val transactionFingerprint: String?,
    val sourceUri: String,
    val platform: String,
    val confidence: Double,
    val timeSource: String?,
    val externalId: String?,
    val vlmModel: String?,
    val vlmRequestId: String?,
    val screenshotCapturedAtMillis: Long?,
    val positiveFeatures: String,
    val negativeFeatures: String,
    val reasonCode: String,
    val localDecision: String,
    val promptVersion: String,
    val contractVersion: String,
    val createdAtMillis: Long,
)

/**
 * SQLCipher-backed Room store. The passphrase is generated once and wrapped by
 * an Android Keystore key in SecureSettings.
 */
class LedgerStore(
    context: Context,
    settings: SecureSettings = SecureSettings(context.applicationContext),
) {
    private val database = LedgerDatabase.open(context.applicationContext, settings)
    private val dao = database.transactionDao()

    suspend fun addIfAbsent(
        ledger: LedgerV1,
        sha256: String,
        sourceUri: String = "",
        vlmModel: String? = null,
        vlmRequestId: String? = null,
        screenshotCapturedAtMillis: Long? = null,
        localDecision: String,
    ): TransactionRecord? =
        withContext(Dispatchers.IO) {
            val amount = ledger.amountMinor ?: return@withContext null
            val currency = ledger.currency ?: return@withContext null
            val occurredAt = ledger.occurredAt?.toString() ?: return@withContext null
            val transactionFingerprint = transactionFingerprint(ledger)
                ?: return@withContext null
            val record = TransactionRecord(
                id = UUID.randomUUID().toString(),
                direction = ledger.direction.name,
                amountMinor = amount,
                currency = currency,
                merchant = ledger.merchant,
                counterparty = ledger.counterparty,
                occurredAt = occurredAt,
                suggestedTag = ledger.suggestedTag,
                sha256 = sha256,
                transactionFingerprint = transactionFingerprint,
                sourceUri = sourceUri,
                platform = ledger.platform.name,
                confidence = ledger.confidence,
                timeSource = ledger.timeSource?.name,
                externalId = ledger.externalId,
                vlmModel = vlmModel,
                vlmRequestId = vlmRequestId,
                screenshotCapturedAtMillis = screenshotCapturedAtMillis,
                positiveFeatures = ledger.evidence.positiveFeatures.joinToString(","),
                negativeFeatures = ledger.evidence.negativeFeatures.joinToString(","),
                reasonCode = ledger.evidence.reasonCode,
                localDecision = localDecision,
                promptVersion = "ledger.prompt.v1",
                contractVersion = "ledger.v1",
                createdAtMillis = System.currentTimeMillis(),
            )
            val inserted = dao.insert(
                TransactionEntity(
                    id = record.id,
                    direction = record.direction,
                    amountMinor = record.amountMinor,
                    currency = record.currency,
                    merchant = record.merchant,
                    counterparty = record.counterparty,
                    occurredAt = record.occurredAt,
                    suggestedTag = record.suggestedTag,
                    sha256 = record.sha256,
                    transactionFingerprint = record.transactionFingerprint,
                    sourceUri = record.sourceUri,
                    platform = record.platform,
                    confidence = record.confidence,
                    timeSource = record.timeSource,
                    externalId = record.externalId,
                    vlmModel = record.vlmModel,
                    vlmRequestId = record.vlmRequestId,
                    screenshotCapturedAtMillis = record.screenshotCapturedAtMillis,
                    positiveFeatures = record.positiveFeatures,
                    negativeFeatures = record.negativeFeatures,
                    reasonCode = record.reasonCode,
                    localDecision = record.localDecision,
                    promptVersion = record.promptVersion,
                    contractVersion = record.contractVersion,
                    createdAtMillis = record.createdAtMillis,
                ),
            )
            if (inserted == -1L) null else record
        }

    suspend fun all(): List<TransactionRecord> = withContext(Dispatchers.IO) {
        dao.all().map { entity ->
            TransactionRecord(
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
        }
    }

    suspend fun containsHash(sha256: String): Boolean = withContext(Dispatchers.IO) {
        dao.containsHash(sha256)
    }

    suspend fun close() {
        withContext(Dispatchers.IO) { database.close() }
    }

    companion object {
        internal fun transactionFingerprint(ledger: LedgerV1): String? {
            val platform = ledger.platform.name
            val direction = ledger.direction.name
            val amount = ledger.amountMinor ?: return null
            val currency = ledger.currency ?: return null
            val occurredAt = ledger.occurredAt?.toInstant()?.toString() ?: return null
            val externalId = ledger.externalId?.trim()?.takeIf(String::isNotEmpty)
            val canonical = if (externalId != null) {
                listOf(
                    "ledger.tx.v1",
                    "external",
                    platform,
                    direction,
                    amount.toString(),
                    currency,
                    externalId,
                )
            } else {
                listOf(
                    "ledger.tx.v1",
                    "fields",
                    platform,
                    direction,
                    amount.toString(),
                    currency,
                    occurredAt,
                )
            }.joinToString("\u001f")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
