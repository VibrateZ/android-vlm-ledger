package com.vibratez.ledger.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HyperOsScreenshotSourceTest {
    @Test
    fun acceptsOriginalWechatAndAlipayScreenshots() {
        assertEquals(
            WECHAT_PACKAGE,
            verifiedPaymentSourcePackage(
                "Screenshot_2026-09-17-18-26-56-478_com.tencent.mm.jpg",
            ),
        )
        assertEquals(
            ALIPAY_PACKAGE,
            verifiedPaymentSourcePackage(
                "Screenshot_2026-09-02-11-51-08-444_com.eg.android.AlipayGphone.jpg",
            ),
        )
    }

    @Test
    fun rejectsEditedForwardedAndOtherAppImages() {
        assertNull(
            verifiedPaymentSourcePackage(
                "Screenshot_2026-06-30-09-40-18-056_com.tencent.mm-edit.jpg",
            ),
        )
        assertNull(verifiedPaymentSourcePackage("mmexport1788237329345.jpg"))
        assertNull(
            verifiedPaymentSourcePackage(
                "Screenshot_2026-09-17-22-55-08-416_com.vibratez.ledger.jpg",
            ),
        )
    }

    @Test
    fun usesHyperOsFileNameTimeBeforeMediaStoreAndAlwaysFallsBack() {
        val name = "Screenshot_2026-09-17-18-26-56-478_com.tencent.mm.jpg"
        val expected = LocalDateTime.of(2026, 9, 17, 18, 26, 56, 478_000_000)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        assertEquals(expected, resolvedScreenshotCapturedAtMillis(name, null, null, 123L))
        assertEquals(456L, resolvedScreenshotCapturedAtMillis("Screenshot_unknown.jpg", null, null, 456L))
    }
}
