package com.vibratez.ledger.photo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?,
)

class InstalledAppRepository(private val context: Context) {
    fun launcherApps(): List<InstalledApp> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        }
        return activities
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName?.lowercase() ?: return@mapNotNull null
                InstalledApp(
                    label = runCatching { info.loadLabel(manager).toString() }
                        .getOrDefault(packageName).ifBlank { packageName },
                    packageName = packageName,
                    icon = runCatching {
                        info.loadIcon(manager).toBitmap(72, 72).asImageBitmap()
                    }.getOrNull(),
                )
            }
            .distinctBy(InstalledApp::packageName)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledApp::label))
            .toList()
    }
}
