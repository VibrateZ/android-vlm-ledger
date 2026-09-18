package com.vibratez.ledger.background

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class LedgerRetryKickWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {
    override fun doWork(): Result {
        LedgerWorkScheduler.enqueueProcessing(applicationContext)
        return Result.success()
    }
}
