package com.vibratez.ledger.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vibratez.ledger.ledger.LedgerDecision
import com.vibratez.ledger.ledger.LedgerStore
import com.vibratez.ledger.ledger.PendingReviewStore
import com.vibratez.ledger.ledger.ScreenshotQueueStore
import com.vibratez.ledger.ledger.toCandidate
import com.vibratez.ledger.photo.AutoBookSaveState
import com.vibratez.ledger.photo.MediaStorePhotoRepository
import com.vibratez.ledger.photo.PhotoProcessingResult
import com.vibratez.ledger.photo.PhotoProcessor
import com.vibratez.ledger.security.SecureSettings
import com.vibratez.ledger.vlm.OpenAiVlmClient
import kotlinx.coroutines.CancellationException

class LedgerScanWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val secureSettings = SecureSettings(context)
        val settings = secureSettings.load()
        if (!settings.cloudEnabled || !settings.backgroundAutoProcessingEnabled) return Result.success()
        if (secureSettings.readApiKey().isNullOrBlank() || settings.baseUrl.isBlank() || settings.model.isBlank()) {
            LedgerNotifications.show(context, "后台记账已暂停", "请完善 VLM 配置")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val pausedUntil = secureSettings.apiPausedUntilMillis()
        if (pausedUntil > now) {
            LedgerWorkScheduler.enqueueProcessing(context, pausedUntil - now)
            return Result.success()
        }

        val repository = MediaStorePhotoRepository(context)
        val ledgerStore = LedgerStore(context, secureSettings)
        val reviewStore = PendingReviewStore(context, secureSettings)
        val queue = ScreenshotQueueStore(context, secureSettings)
        var pendingReviews = 0
        var nextRetryAt = Long.MAX_VALUE
        try {
            queue.recoverInterrupted()
            val processor = PhotoProcessor(
                repository = repository,
                settings = secureSettings,
                client = OpenAiVlmClient(loadPrompt(context)),
                ledgerStore = ledgerStore,
            )
            for (job in queue.due(limit = BATCH_SIZE)) {
                queue.markProcessing(job)
                val result = try {
                    processor.processCandidates(listOf(job.toCandidate())).single()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    val next = queue.markRetry(job, settings, "processing_exception", null)
                    if (next > 0L) nextRetryAt = minOf(nextRetryAt, next)
                    secureSettings.pauseApiUntil(next)
                    break
                }

                when (result) {
                    is PhotoProcessingResult.Failed -> {
                        if (result.reason == "package_filtered") {
                            queue.markFinished(job, ScreenshotQueueStore.STATUS_REJECTED, null, false, error = result.reason)
                        } else if (result.retryable) {
                            val next = queue.markRetry(job, settings, result.reason, result.retryAfterMillis)
                            if (next > 0L) {
                                nextRetryAt = minOf(nextRetryAt, next)
                                secureSettings.pauseApiUntil(next)
                            }
                            break
                        } else {
                            queue.markFinished(job, ScreenshotQueueStore.STATUS_FAILED, null, false, error = result.reason)
                        }
                    }
                    is PhotoProcessingResult.Completed -> {
                        when (val decision = result.decision) {
                            is LedgerDecision.AutoBook -> {
                                val stored = result.autoBookSaveState in setOf(
                                    AutoBookSaveState.STORED,
                                    AutoBookSaveState.ALREADY_STORED,
                                )
                                if (!stored) {
                                    val next = queue.markRetry(job, settings, "database_save_failed", null)
                                    if (next > 0L) nextRetryAt = minOf(nextRetryAt, next)
                                    continue
                                }
                                val retained = if (settings.keepOriginalCopies) {
                                    queue.retainOriginal(result.candidate, result.sha256)
                                } else null
                                if (retained != null) ledgerStore.attachStoredImage(result.sha256, retained)
                                queue.markFinished(
                                    job,
                                    if (result.autoBookSaveState == AutoBookSaveState.STORED) {
                                        ScreenshotQueueStore.STATUS_BOOKED
                                    } else ScreenshotQueueStore.STATUS_DUPLICATE,
                                    result.sha256,
                                    deletePending = settings.autoDeleteAfterBook,
                                    storedImagePath = retained,
                                )
                            }
                            is LedgerDecision.NeedsConfirmation -> {
                                reviewStore.addIfAbsent(
                                    decision.ledger,
                                    result.sha256,
                                    result.candidate.uri.toString(),
                                    result.vlmModel,
                                    result.vlmRequestId,
                                    result.candidate.capturedAtMillis,
                                )
                                pendingReviews++
                                queue.markFinished(job, ScreenshotQueueStore.STATUS_NEEDS_CONFIRMATION, result.sha256, false)
                            }
                            is LedgerDecision.Rejected -> {
                                reviewStore.markRejected(
                                    result.sha256,
                                    result.candidate.uri.toString(),
                                    decision.reason,
                                    result.vlmModel,
                                    result.vlmRequestId,
                                    result.candidate.capturedAtMillis,
                                )
                                queue.markFinished(
                                    job,
                                    ScreenshotQueueStore.STATUS_REJECTED,
                                    result.sha256,
                                    false,
                                    error = decision.reason,
                                )
                            }
                            LedgerDecision.Duplicate -> queue.markFinished(
                                job,
                                ScreenshotQueueStore.STATUS_DUPLICATE,
                                result.sha256,
                                deletePending = settings.autoDeleteAfterBook,
                            )
                        }
                    }
                }
            }
            if (nextRetryAt != Long.MAX_VALUE) {
                LedgerWorkScheduler.enqueueProcessing(
                    context,
                    (nextRetryAt - System.currentTimeMillis()).coerceAtLeast(1_000L),
                )
            } else {
                secureSettings.clearApiPause()
                queue.nextAttemptAtMillis()?.let { next ->
                    LedgerWorkScheduler.enqueueProcessing(
                        context,
                        (next - System.currentTimeMillis()).coerceAtLeast(1_000L),
                    )
                }
            }
            if (pendingReviews > 0) {
                LedgerNotifications.show(context, "有待确认账目", "$pendingReviews 条记录需要核对")
            }
            val deleteCount = queue.summary().pendingDelete
            if (deleteCount > 0) {
                LedgerNotifications.show(context, "截图等待删除确认", "$deleteCount 张已入账截图可批量确认删除")
            }
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        } finally {
            queue.close()
            reviewStore.close()
            ledgerStore.close()
        }
    }

    private fun loadPrompt(context: Context): String = runCatching {
        context.assets.open("ledger_system_prompt.txt").bufferedReader().use { it.readText() }
    }.getOrDefault("你是 Ledger VLM。严格按照 ledger.v1 只输出一个 JSON 对象。")

    private companion object {
        const val BATCH_SIZE = 50
    }
}

internal fun shouldRetryWorker(runAttemptCount: Int): Boolean = runAttemptCount < 2
