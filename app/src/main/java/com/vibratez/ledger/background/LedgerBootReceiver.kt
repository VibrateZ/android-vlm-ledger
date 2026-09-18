package com.vibratez.ledger.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vibratez.ledger.security.SecureSettings

class LedgerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val settings = SecureSettings(context).load()
        LedgerWorkScheduler.reconcile(
            context,
            settings.cloudEnabled && settings.backgroundAutoProcessingEnabled,
        )
        if (settings.cloudEnabled && settings.backgroundAutoProcessingEnabled) {
            runCatching { ScreenshotObserverService.start(context) }
        }
    }
}
