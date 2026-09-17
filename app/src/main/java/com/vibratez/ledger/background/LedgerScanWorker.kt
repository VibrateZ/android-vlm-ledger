package com.vibratez.ledger.background

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vibratez.ledger.ledger.LedgerDecision
import com.vibratez.ledger.ledger.LedgerStore
import com.vibratez.ledger.ledger.PendingReviewStore
import com.vibratez.ledger.photo.MediaStorePhotoRepository
import com.vibratez.ledger.photo.PhotoProcessingResult
import com.vibratez.ledger.photo.PhotoProcessor
import com.vibratez.ledger.security.SecureSettings
import com.vibratez.ledger.vlm.OpenAiVlmClient
import kotlinx.coroutines.CancellationException

class LedgerScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = SecureSettings(context)
        val appSettings = settings.load()
        if (!appSettings.cloudEnabled || !appSettings.backgroundAutoProcessingEnabled) {
            return Result.success()
        }
        if (!hasFullPhotoReadPermission(context)) {
            LedgerNotifications.show(context, "后台记账已暂停", "请授予完整照片读取权限")
            LedgerWorkScheduler.reconcile(context, false)
            return Result.success()
        }
        if (settings.readApiKey().isNullOrBlank() || appSettings.baseUrl.isBlank() || appSettings.model.isBlank()) {
            LedgerNotifications.show(context, "后台记账已暂停", "请完善 VLM 配置")
            LedgerWorkScheduler.reconcile(context, false)
            return Result.success()
        }

        val repository = MediaStorePhotoRepository(context)
        val ledgerStore = LedgerStore(context, settings)
        val reviewStore = PendingReviewStore(context, settings)
        return try {
            val processor = PhotoProcessor(
                repository = repository,
                settings = settings,
                client = OpenAiVlmClient(loadPrompt(context)),
                ledgerStore = ledgerStore,
            )
            val candidates = repository.recentScreenshots(windowMinutes = 24 * 60)
                .filterNot { reviewStore.containsSourceUri(it.uri.toString()) }
            val results = processor.processCandidates(candidates)
            var pendingCount = 0
            var retryableFailure = false
            var permanentFailure = false
            results.forEach { result ->
                when (result) {
                    is PhotoProcessingResult.Completed -> {
                        val decision = result.decision
                        if (decision is LedgerDecision.NeedsConfirmation) {
                            if (reviewStore.addIfAbsent(
                                    decision.ledger,
                                    result.sha256,
                                    result.candidate.uri.toString(),
                                    result.vlmModel,
                                    result.vlmRequestId,
                                    result.candidate.capturedAtMillis,
                                )
                            ) pendingCount++
                        } else if (decision is LedgerDecision.Rejected) {
                            reviewStore.markRejected(
                                result.sha256,
                                result.candidate.uri.toString(),
                                decision.reason,
                                result.vlmModel,
                                result.vlmRequestId,
                                result.candidate.capturedAtMillis,
                            )
                        } else if (decision is LedgerDecision.AutoBook &&
                            result.autoBookSaveState == com.vibratez.ledger.photo.AutoBookSaveState.FAILED
                        ) {
                            retryableFailure = true
                        }
                    }
                    is PhotoProcessingResult.Failed -> {
                        retryableFailure = retryableFailure || result.retryable
                        permanentFailure = permanentFailure || !result.retryable
                    }
                }
            }
            if (pendingCount > 0) {
                LedgerNotifications.show(context, "有待确认账目", "$pendingCount 条记录需要核对")
            }
            if (permanentFailure) {
                LedgerNotifications.show(context, "部分截图未处理", "请打开应用检查配置、权限或图片格式")
            }
            if (retryableFailure && shouldRetryWorker(runAttemptCount)) {
                Result.retry()
            } else {
                if (retryableFailure) {
                    LedgerNotifications.show(context, "后台识别失败", "自动重试已结束，可打开应用手动重试")
                }
                Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (shouldRetryWorker(runAttemptCount)) {
                Result.retry()
            } else {
                LedgerNotifications.show(context, "后台识别失败", "自动重试已结束，可打开应用手动重试")
                Result.success()
            }
        } finally {
            reviewStore.close()
            ledgerStore.close()
        }
    }

    private fun hasFullPhotoReadPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else Manifest.permission.READ_EXTERNAL_STORAGE
        val fullGrant = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !fullGrant) {
            return false
        }
        return fullGrant
    }

    private fun loadPrompt(context: Context): String = runCatching {
        context.assets.open("ledger_system_prompt.txt").bufferedReader().use { it.readText() }
    }.getOrDefault("你是 Ledger VLM。严格按照 ledger.v1 只输出一个 JSON 对象。")
}

internal fun shouldRetryWorker(runAttemptCount: Int): Boolean = runAttemptCount < 2
