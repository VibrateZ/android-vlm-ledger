package com.vibratez.ledger.photo

import com.vibratez.ledger.security.AppSettings
import com.vibratez.ledger.security.PackageFilterMode

object PackageFilter {
    fun allows(sourcePackage: String?, settings: AppSettings): Boolean {
        val normalized = sourcePackage?.trim()?.lowercase()
        val configured = settings.packageNames.mapTo(mutableSetOf()) { it.lowercase() }
        return when (settings.packageFilterMode) {
            PackageFilterMode.ALL -> true
            PackageFilterMode.WHITELIST -> normalized != null && normalized in configured
            PackageFilterMode.BLACKLIST -> normalized == null || normalized !in configured
        }
    }
}
