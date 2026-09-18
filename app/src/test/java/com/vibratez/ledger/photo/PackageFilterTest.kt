package com.vibratez.ledger.photo

import com.vibratez.ledger.security.AppSettings
import com.vibratez.ledger.security.PackageFilterMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageFilterTest {
    @Test
    fun blacklistAllowsUnknownAndNonListedPackages() {
        val settings = AppSettings(
            packageFilterMode = PackageFilterMode.BLACKLIST,
            packageNames = setOf("com.example.blocked"),
        )
        assertFalse(PackageFilter.allows("com.example.blocked", settings))
        assertTrue(PackageFilter.allows("com.example.shop", settings))
        assertTrue(PackageFilter.allows(null, settings))
    }

    @Test
    fun whitelistRequiresExtractedListedPackage() {
        val settings = AppSettings(
            packageFilterMode = PackageFilterMode.WHITELIST,
            packageNames = setOf("COM.EXAMPLE.SHOP"),
        )
        assertTrue(PackageFilter.allows("com.example.shop", settings))
        assertFalse(PackageFilter.allows("com.example.other", settings))
        assertFalse(PackageFilter.allows(null, settings))
    }

    @Test
    fun extractsGenericHyperOsPackageName() {
        assertTrue(
            screenshotSourcePackage(
                "Screenshot_2026-09-14-16-08-59-324_com.xunmeng.pinduoduo.jpg",
            ) == "com.xunmeng.pinduoduo",
        )
    }
}
