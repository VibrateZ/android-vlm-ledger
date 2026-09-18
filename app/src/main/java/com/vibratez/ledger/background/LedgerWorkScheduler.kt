package com.vibratez.ledger.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LedgerWorkScheduler {
    private const val PERIODIC_DISCOVERY_NAME = "ledger-periodic-discovery"
    private const val IMMEDIATE_DISCOVERY_NAME = "ledger-discovery-now"
    private const val PROCESSING_NAME = "ledger-process-queue"
    private const val RETRY_KICK_NAME = "ledger-retry-kick"

    fun reconcile(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(PERIODIC_DISCOVERY_NAME)
            manager.cancelUniqueWork(IMMEDIATE_DISCOVERY_NAME)
            manager.cancelUniqueWork(PROCESSING_NAME)
            manager.cancelUniqueWork(RETRY_KICK_NAME)
            return
        }
        manager.enqueueUniquePeriodicWork(
            PERIODIC_DISCOVERY_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScreenshotDiscoveryWorker>(15, TimeUnit.MINUTES)
                .build(),
        )
        enqueueDiscoveryNow(context)
        enqueueProcessing(context)
    }

    fun enqueueNow(context: Context) {
        enqueueDiscoveryNow(context)
        enqueueProcessing(context)
    }

    fun enqueueDiscoveryNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_DISCOVERY_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ScreenshotDiscoveryWorker>().build(),
        )
    }

    fun enqueueProcessing(context: Context, initialDelayMillis: Long = 0L) {
        if (initialDelayMillis > 0L) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                RETRY_KICK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LedgerRetryKickWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                    .build(),
            )
            return
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            PROCESSING_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LedgerScanWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    fun restartProcessing(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(RETRY_KICK_NAME)
        manager.enqueueUniqueWork(
            PROCESSING_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LedgerScanWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build(),
        )
    }
}
