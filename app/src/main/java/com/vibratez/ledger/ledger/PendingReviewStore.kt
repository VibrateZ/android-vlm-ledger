package com.vibratez.ledger.ledger

import android.content.Context
import com.vibratez.ledger.security.SecureSettings
import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Evidence
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import com.vibratez.ledger.vlm.TimeSource
import java.time.OffsetDateTime
import java.util.UUID

data class PendingReview(
    val id: String,
    val sha256: String,
    val sourceUri: String,
    val ledger: LedgerV1,
    val vlmModel: String?,
    val vlmRequestId: String?,
    val screenshotCapturedAtMillis: Long?,
    val createdAtMillis: Long,
)

class PendingReviewStore(
    context: Context,
    settings: SecureSettings = SecureSettings(context.applicationContext),
) {
    private val database = LedgerDatabase.open(context.applicationContext, settings)
    private val dao = database.pendingReviewDao()

    suspend fun addIfAbsent(
        ledger: LedgerV1,
        sha256: String,
        sourceUri: String,
        vlmModel: String?,
        vlmRequestId: String?,
        screenshotCapturedAtMillis: Long?,
    ): Boolean {
        val entity = PendingReviewEntity(
            id = UUID.randomUUID().toString(),
            sha256 = sha256,
            sourceUri = sourceUri,
            direction = ledger.direction.name,
            amountMinor = ledger.amountMinor,
            currency = ledger.currency,
            merchant = ledger.merchant,
            counterparty = ledger.counterparty,
            occurredAt = ledger.occurredAt?.toString(),
            platform = ledger.platform.name,
            timeSource = ledger.timeSource?.name,
            externalId = ledger.externalId,
            suggestedTag = ledger.suggestedTag,
            confidence = ledger.confidence,
            isPaymentScreenshot = ledger.isPaymentScreenshot,
            positiveFeatures = ledger.evidence.positiveFeatures.joinToString("\u001f"),
            negativeFeatures = ledger.evidence.negativeFeatures.joinToString("\u001f"),
            freshness = ledger.evidence.freshness.name,
            reasonCode = ledger.evidence.reasonCode,
            vlmModel = vlmModel,
            vlmRequestId = vlmRequestId,
            screenshotCapturedAtMillis = screenshotCapturedAtMillis,
            status = STATUS_PENDING,
            createdAtMillis = System.currentTimeMillis(),
        )
        return dao.insert(entity) != -1L
    }

    suspend fun markRejected(
        sha256: String,
        sourceUri: String,
        reasonCode: String,
        vlmModel: String?,
        vlmRequestId: String?,
        screenshotCapturedAtMillis: Long?,
    ): Boolean = dao.insert(
        PendingReviewEntity(
            id = UUID.randomUUID().toString(),
            sha256 = sha256,
            sourceUri = sourceUri,
            direction = Direction.UNKNOWN.name,
            amountMinor = null,
            currency = null,
            merchant = null,
            counterparty = null,
            occurredAt = null,
            platform = Platform.UNKNOWN.name,
            timeSource = null,
            externalId = null,
            suggestedTag = null,
            confidence = 0.0,
            isPaymentScreenshot = false,
            positiveFeatures = "",
            negativeFeatures = "",
            freshness = Freshness.UNKNOWN.name,
            reasonCode = reasonCode,
            vlmModel = vlmModel,
            vlmRequestId = vlmRequestId,
            screenshotCapturedAtMillis = screenshotCapturedAtMillis,
            status = STATUS_DISMISSED,
            createdAtMillis = System.currentTimeMillis(),
        ),
    ) != -1L

    suspend fun pending(): List<PendingReview> = dao.pending().map(::toModel)

    suspend fun containsPending(sha256: String): Boolean = dao.containsPending(sha256)

    suspend fun containsSourceUri(sourceUri: String): Boolean = dao.containsSourceUri(sourceUri)

    suspend fun confirm(id: String) = dao.updateStatus(id, STATUS_CONFIRMED)

    suspend fun dismiss(id: String) = dao.updateStatus(id, STATUS_DISMISSED)

    suspend fun delete(id: String): Boolean = dao.deleteById(id) > 0

    suspend fun close() = database.close()

    private fun toModel(entity: PendingReviewEntity) = PendingReview(
        id = entity.id,
        sha256 = entity.sha256,
        sourceUri = entity.sourceUri,
        ledger = LedgerV1(
            decision = Decision.NEEDS_CONFIRMATION,
            isPaymentScreenshot = entity.isPaymentScreenshot,
            platform = enumOrUnknown(entity.platform, Platform.UNKNOWN),
            direction = enumOrUnknown(entity.direction, Direction.UNKNOWN),
            amountMinor = entity.amountMinor,
            currency = entity.currency,
            merchant = entity.merchant,
            counterparty = entity.counterparty,
            occurredAt = entity.occurredAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
            timeSource = entity.timeSource?.let { runCatching { TimeSource.valueOf(it) }.getOrNull() },
            externalId = entity.externalId,
            suggestedTag = entity.suggestedTag,
            confidence = entity.confidence,
            evidence = Evidence(
                positiveFeatures = entity.positiveFeatures.split("\u001f").filter(String::isNotEmpty),
                negativeFeatures = entity.negativeFeatures.split("\u001f").filter(String::isNotEmpty),
                freshness = enumOrUnknown(entity.freshness, Freshness.UNKNOWN),
                reasonCode = entity.reasonCode,
            ),
        ),
        vlmModel = entity.vlmModel,
        vlmRequestId = entity.vlmRequestId,
        screenshotCapturedAtMillis = entity.screenshotCapturedAtMillis,
        createdAtMillis = entity.createdAtMillis,
    )

    private inline fun <reified T : Enum<T>> enumOrUnknown(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    companion object {
        const val STATUS_PENDING = "PENDING_CONFIRMATION"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_DISMISSED = "DISMISSED"
    }
}
