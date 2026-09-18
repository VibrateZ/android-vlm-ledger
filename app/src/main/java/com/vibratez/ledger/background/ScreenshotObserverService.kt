package com.vibratez.ledger.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vibratez.ledger.MainActivity
import com.vibratez.ledger.photo.MediaStorePhotoRepository
import com.vibratez.ledger.security.SecureSettings
import java.io.Closeable

class ScreenshotObserverService : Service() {
    private var observer: Closeable? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        observer = MediaStorePhotoRepository(this).observeChanges {
            LedgerWorkScheduler.enqueueDiscoveryNow(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SecureSettings(this).load()
        if (!settings.cloudEnabled || !settings.backgroundAutoProcessingEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        LedgerWorkScheduler.enqueueNow(this)
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.close()
        observer = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        LedgerWorkScheduler.enqueueDiscoveryNow(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "截图监听", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("自动记账正在监听截图")
            .setContentText("断网时截图会保存在本地等待队列")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ledger_screenshot_observer"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, ScreenshotObserverService::class.java),
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, ScreenshotObserverService::class.java),
            )
        }
    }
}
