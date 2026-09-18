package com.vibratez.ledger.background

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vibratez.ledger.ledger.ScreenshotQueueStore
import com.vibratez.ledger.photo.MediaStorePhotoRepository
import com.vibratez.ledger.security.SecureSettings

class ScreenshotDiscoveryWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val secureSettings = SecureSettings(context)
        val settings = secureSettings.load()
        if (!settings.cloudEnabled || !settings.backgroundAutoProcessingEnabled) return Result.success()
        if (!hasFullPhotoReadPermission(context)) return Result.success()
        val now = System.currentTimeMillis()
        val last = secureSettings.lastDiscoveryAtMillis()
        val since = if (last == 0L) now - INITIAL_LOOKBACK_MILLIS else last - OVERLAP_MILLIS
        val candidates = try {
            MediaStorePhotoRepository(context).screenshotsSince(since, DISCOVERY_LIMIT)
        } catch (_: Exception) {
            return Result.retry()
        }
        val queue = ScreenshotQueueStore(context, secureSettings)
        return try {
            val added = queue.enqueue(candidates, settings)
            val watermark = if (candidates.size >= DISCOVERY_LIMIT) {
                candidates.mapNotNull { it.addedAtMillis }.maxOrNull() ?: now
            } else now
            secureSettings.setLastDiscoveryAtMillis(watermark)
            if (added > 0 || queue.summary().active > 0) LedgerWorkScheduler.enqueueProcessing(context)
            if (candidates.size >= DISCOVERY_LIMIT) LedgerWorkScheduler.enqueueDiscoveryNow(context)
            Result.success()
        } finally {
            queue.close()
        }
    }

    private fun hasFullPhotoReadPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val DISCOVERY_LIMIT = 2_000
        const val INITIAL_LOOKBACK_MILLIS = 7L * 24 * 60 * 60_000
        const val OVERLAP_MILLIS = 5L * 60_000
    }
}
