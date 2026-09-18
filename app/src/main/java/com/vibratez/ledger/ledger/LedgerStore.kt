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
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val counterparty: String?,
    val occurredAt: String?,
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
    val itemName: String? = null,
    val account: String? = null,
    val paymentMethod: String? = null,
    val note: String? = null,
    val customFieldsJson: String = "{}",
    val storedImagePath: String? = null,
    val updatedAtMillis: Long = createdAtMillis,
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
    private val auditDao = database.auditEventDao()

    suspend fun addIfAbsent(
        ledger: LedgerV1,
        sha256: String,
        sourceUri: String = "",
        vlmModel: String? = null,
        vlmRequestId: String? = null,
        screenshotCapturedAtMillis: Long? = null,
        localDecision: String,
        itemName: String? = null,
        account: String? = null,
        paymentMethod: String? = null,
        note: String? = null,
        customFieldsJson: String = "{}",
        storedImagePath: String? = null,
    ): TransactionRecord? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val transactionFingerprint = transactionFingerprint(ledger, itemName)
            val record = TransactionRecord(
                id = UUID.randomUUID().toString(),
                direction = ledger.direction.name,
                amountMinor = ledger.amountMinor,
                currency = ledger.currency,
                merchant = ledger.merchant,
                counterparty = ledger.counterparty,
                occurredAt = ledger.occurredAt?.toString(),
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
                createdAtMillis = now,
                itemName = itemName,
                account = account,
                paymentMethod = paymentMethod,
                note = note,
                customFieldsJson = customFieldsJson,
                storedImagePath = storedImagePath,
                updatedAtMillis = now,
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
                    itemName = record.itemName,
                    account = record.account,
                    paymentMethod = record.paymentMethod,
                    note = record.note,
                    customFieldsJson = record.customFieldsJson,
                    storedImagePath = record.storedImagePath,
                    updatedAtMillis = record.updatedAtMillis,
                ),
            )
            if (inserted == -1L) null else record.also {
                audit("TRANSACTION", record.id, "CREATED", localDecision)
            }
        }

    suspend fun all(): List<TransactionRecord> = withContext(Dispatchers.IO) {
        dao.all().map(::toRecord)
    }

    suspend fun withoutTime(): List<TransactionRecord> = withContext(Dispatchers.IO) {
        dao.withoutTime().map(::toRecord)
    }

    suspend fun addManual(record: TransactionRecord): TransactionRecord? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val normalized = record.copy(
            id = record.id.ifBlank { UUID.randomUUID().toString() },
            sha256 = record.sha256.ifBlank { "manual:${UUID.randomUUID()}" },
            localDecision = "MANUAL",
            createdAtMillis = if (record.createdAtMillis > 0L) record.createdAtMillis else now,
            updatedAtMillis = now,
        )
        val inserted = dao.insert(normalized.toEntity())
        if (inserted == -1L) null else normalized.also {
            audit("TRANSACTION", it.id, "CREATED", "MANUAL")
        }
    }

    suspend fun update(record: TransactionRecord): Boolean = withContext(Dispatchers.IO) {
        val current = dao.byId(record.id) ?: return@withContext false
        val changed = record.copy(
            sha256 = current.sha256,
            transactionFingerprint = null,
            createdAtMillis = current.createdAtMillis,
            updatedAtMillis = System.currentTimeMillis(),
            localDecision = "USER_EDITED",
        )
        val updated = dao.update(changed.toEntity()) > 0
        if (updated) audit("TRANSACTION", changed.id, "UPDATED", null)
        updated
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = dao.deleteById(id) > 0
        if (deleted) audit("TRANSACTION", id, "DELETED", null)
        deleted
    }

    suspend fun containsHash(sha256: String): Boolean = withContext(Dispatchers.IO) {
        dao.containsHash(sha256)
    }

    suspend fun attachStoredImage(sha256: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val updated = dao.updateStoredImagePath(sha256, path, System.currentTimeMillis()) > 0
        if (updated) audit("TRANSACTION", sha256, "IMAGE_RETAINED", null)
        updated
    }

    suspend fun close() {
        withContext(Dispatchers.IO) { database.close() }
    }

    companion object {
        internal fun transactionFingerprint(ledger: LedgerV1, itemName: String? = null): String? {
            val platform = ledger.platform.name
            val direction = ledger.direction.name
            val amount = ledger.amountMinor ?: return null
            val currency = ledger.currency ?: return null
            val occurredAt = ledger.occurredAt?.toInstant()?.toString() ?: return null
            val externalId = ledger.externalId?.trim()?.takeIf(String::isNotEmpty)
            val project = listOf(itemName, ledger.merchant, ledger.counterparty, ledger.suggestedTag)
                .firstNotNullOfOrNull { it?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
                ?: "unknown"
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
                    project,
                )
            }.joinToString("\u001f")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
        }
    }

    private suspend fun audit(entityType: String, entityId: String, action: String, detail: String?) {
        auditDao.insert(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                action = action,
                detail = detail,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun TransactionRecord.toEntity() = TransactionEntity(
        id = id,
        direction = direction,
        amountMinor = amountMinor,
        currency = currency,
        merchant = merchant,
        counterparty = counterparty,
        occurredAt = occurredAt,
        suggestedTag = suggestedTag,
        sha256 = sha256,
        transactionFingerprint = transactionFingerprint,
        sourceUri = sourceUri,
        platform = platform,
        confidence = confidence,
        timeSource = timeSource,
        externalId = externalId,
        vlmModel = vlmModel,
        vlmRequestId = vlmRequestId,
        screenshotCapturedAtMillis = screenshotCapturedAtMillis,
        positiveFeatures = positiveFeatures,
        negativeFeatures = negativeFeatures,
        reasonCode = reasonCode,
        localDecision = localDecision,
        promptVersion = promptVersion,
        contractVersion = contractVersion,
        createdAtMillis = createdAtMillis,
        itemName = itemName,
        account = account,
        paymentMethod = paymentMethod,
        note = note,
        customFieldsJson = customFieldsJson,
        storedImagePath = storedImagePath,
        updatedAtMillis = updatedAtMillis,
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
        itemName = entity.itemName,
        account = entity.account,
        paymentMethod = entity.paymentMethod,
        note = entity.note,
        customFieldsJson = entity.customFieldsJson,
        storedImagePath = entity.storedImagePath,
        updatedAtMillis = entity.updatedAtMillis,
    )
}
