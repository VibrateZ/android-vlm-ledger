package com.vibratez.ledger.ledger

import android.content.Context
import android.net.Uri
import com.vibratez.ledger.photo.PackageFilter
import com.vibratez.ledger.photo.ScreenshotCandidate
import com.vibratez.ledger.photo.resolvedScreenshotCapturedAtMillis
import com.vibratez.ledger.photo.screenshotSourcePackage
import com.vibratez.ledger.security.AppSettings
import com.vibratez.ledger.security.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.min

object RetryPolicy {
    fun delayMillis(
        attemptCount: Int,
        baseMinutes: Int,
        maxMinutes: Int,
        retryAfterMillis: Long?,
    ): Long {
        val exponential = baseMinutes.coerceAtLeast(1) * 60_000L *
            (1L shl min((attemptCount - 1).coerceAtLeast(0), 10))
        return maxOf(retryAfterMillis ?: 0L, exponential)
            .coerceAtMost(maxMinutes.coerceAtLeast(baseMinutes) * 60_000L)
    }
}

data class ScreenshotQueueSummary(
    val active: Int,
    val pendingDelete: Int,
)

class ScreenshotQueueStore(
    private val context: Context,
    settings: SecureSettings = SecureSettings(context.applicationContext),
) {
    private val database = LedgerDatabase.open(context.applicationContext, settings)
    private val dao = database.screenshotJobDao()
    private val auditDao = database.auditEventDao()

    suspend fun enqueue(candidates: List<ScreenshotCandidate>, settings: AppSettings): Int =
        withContext(Dispatchers.IO) {
            var added = 0
            candidates.forEach { candidate ->
                val sourcePackage = screenshotSourcePackage(candidate.displayName)
                if (!PackageFilter.allows(sourcePackage, settings)) return@forEach
                val now = System.currentTimeMillis()
                val inserted = dao.insert(
                    ScreenshotJobEntity(
                        id = UUID.randomUUID().toString(),
                        sourceUri = candidate.uri.toString(),
                        displayName = candidate.displayName,
                        mimeType = candidate.mimeType,
                        sizeBytes = candidate.sizeBytes,
                        capturedAtMillis = resolvedScreenshotCapturedAtMillis(
                            candidate.displayName,
                            candidate.capturedAtMillis,
                            candidate.addedAtMillis,
                            now,
                        ),
                        addedAtMillis = candidate.addedAtMillis,
                        width = candidate.width,
                        height = candidate.height,
                        sourcePackage = sourcePackage,
                        sha256 = null,
                        status = STATUS_PENDING,
                        attemptCount = 0,
                        nextAttemptAtMillis = now,
                        lastError = null,
                        transactionId = null,
                        storedImagePath = null,
                        deleteState = DELETE_NONE,
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    ),
                )
                if (inserted != -1L) added++
            }
            if (added > 0) audit("QUEUE", "discovery", "ENQUEUED", added.toString())
            added
        }

    suspend fun due(nowMillis: Long = System.currentTimeMillis(), limit: Int = 50): List<ScreenshotJobEntity> =
        withContext(Dispatchers.IO) { dao.due(nowMillis, limit) }

    suspend fun markProcessing(job: ScreenshotJobEntity) = update(job, STATUS_PROCESSING)

    suspend fun markFinished(
        job: ScreenshotJobEntity,
        status: String,
        sha256: String?,
        deletePending: Boolean,
        storedImagePath: String? = null,
        error: String? = null,
    ) {
        update(
            job = job,
            status = status,
            sha256 = sha256,
            error = error,
            storedImagePath = storedImagePath,
            deleteState = if (deletePending) DELETE_PENDING else job.deleteState,
        )
        audit("SCREENSHOT_JOB", job.id, status, error)
    }

    suspend fun markRetry(job: ScreenshotJobEntity, settings: AppSettings, reason: String, retryAfterMillis: Long?): Long {
        val attempts = job.attemptCount + 1
        if (attempts >= settings.maxRetryAttempts) {
            update(job, STATUS_FAILED, error = reason, attemptCount = attempts)
            audit("SCREENSHOT_JOB", job.id, "FAILED", reason)
            return 0L
        }
        val delayMillis = RetryPolicy.delayMillis(
            attempts,
            settings.retryBaseMinutes,
            settings.retryMaxMinutes,
            retryAfterMillis,
        )
        val next = System.currentTimeMillis() + delayMillis
        update(
            job,
            STATUS_RETRY_WAIT,
            error = reason,
            attemptCount = attempts,
            nextAttemptAtMillis = next,
        )
        audit("SCREENSHOT_JOB", job.id, "RETRY_SCHEDULED", "$reason:$next")
        return next
    }

    suspend fun pendingDeletes(limit: Int = 500): List<ScreenshotJobEntity> =
        withContext(Dispatchers.IO) { dao.pendingDeletes(limit) }

    suspend fun markDeleteState(sourceUris: List<String>, state: String) = withContext(Dispatchers.IO) {
        if (sourceUris.isNotEmpty()) {
            dao.updateDeleteState(sourceUris, state, System.currentTimeMillis())
            sourceUris.forEach { audit("SCREENSHOT", it, "DELETE_$state", null) }
        }
    }

    suspend fun summary(): ScreenshotQueueSummary = withContext(Dispatchers.IO) {
        ScreenshotQueueSummary(dao.activeCount(), dao.pendingDeleteCount())
    }

    suspend fun nextAttemptAtMillis(): Long? = withContext(Dispatchers.IO) {
        dao.nextAttemptAtMillis()
    }

    suspend fun recoverInterrupted(nowMillis: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        dao.recoverStaleProcessing(nowMillis - 15 * 60_000L, nowMillis)
    }

    suspend fun retainOriginal(candidate: ScreenshotCandidate, sha256: String): String? = withContext(Dispatchers.IO) {
        val extension = candidate.displayName.substringAfterLast('.', "img")
            .lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "img"
        val directory = File(context.filesDir, "ledger_images")
        if (!directory.exists() && !directory.mkdirs()) return@withContext null
        val target = File(directory, "$sha256.$extension")
        if (!target.exists()) {
            val input = context.contentResolver.openInputStream(candidate.uri) ?: return@withContext null
            input.use { source -> target.outputStream().use(source::copyTo) }
        }
        target.absolutePath
    }

    suspend fun close() = withContext(Dispatchers.IO) { database.close() }

    private suspend fun update(
        job: ScreenshotJobEntity,
        status: String,
        sha256: String? = job.sha256,
        error: String? = job.lastError,
        attemptCount: Int = job.attemptCount,
        nextAttemptAtMillis: Long = job.nextAttemptAtMillis,
        storedImagePath: String? = job.storedImagePath,
        deleteState: String = job.deleteState,
    ) = withContext(Dispatchers.IO) {
        dao.updateState(
            id = job.id,
            status = status,
            sha256 = sha256,
            lastError = error,
            attemptCount = attemptCount,
            nextAttemptAtMillis = nextAttemptAtMillis,
            transactionId = job.transactionId,
            storedImagePath = storedImagePath,
            deleteState = deleteState,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun audit(entityType: String, entityId: String, action: String, detail: String?) {
        auditDao.insert(AuditEventEntity(UUID.randomUUID().toString(), entityType, entityId, action, detail, System.currentTimeMillis()))
    }

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_RETRY_WAIT = "RETRY_WAIT"
        const val STATUS_BOOKED = "BOOKED"
        const val STATUS_NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_DUPLICATE = "DUPLICATE"
        const val STATUS_FAILED = "FAILED"
        const val DELETE_NONE = "NONE"
        const val DELETE_PENDING = "PENDING"
        const val DELETE_DELETED = "DELETED"
        const val DELETE_FAILED = "FAILED"
    }
}

fun ScreenshotJobEntity.toCandidate() = ScreenshotCandidate(
    uri = Uri.parse(sourceUri),
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    capturedAtMillis = resolvedScreenshotCapturedAtMillis(
        displayName,
        capturedAtMillis,
        addedAtMillis,
        createdAtMillis,
    ),
    width = width,
    height = height,
    addedAtMillis = addedAtMillis,
)
