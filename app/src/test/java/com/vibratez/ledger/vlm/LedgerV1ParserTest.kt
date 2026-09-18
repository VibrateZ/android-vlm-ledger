package com.vibratez.ledger.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerV1ParserTest {
    @Test
    fun rejectsUnknownIsoCurrencyCode() {
        val result = LedgerV1Parser.parse(
            validPayload().replace("\"currency\":\"CNY\"", "\"currency\":\"ZZZ\""),
        )

        assertEquals("currency", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun acceptsValidAutoBookPayload() {
        val result = LedgerV1Parser.parse(validPayload())
        assertTrue(result is ParseResult.Valid)
        assertEquals(1280L, (result as ParseResult.Valid).value.amountMinor)
    }

    @Test
    fun acceptsNullableOptionalFields() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"merchant\":\"Cafe\"", "\"merchant\":null")
                .replace("\"occurred_at\":\"2026-09-14T12:30:00+08:00\"", "\"occurred_at\":null")
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":null")
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"confidence\":0.96", "\"confidence\":0.2")
                .replace(",\"PAGE_EXACT_TIME\"", "")
                .replace("\"freshness\":\"VALID\"", "\"freshness\":\"UNKNOWN\"")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"MISSING_TIME\""),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun acceptsFreshWeChatSuccessWithEstimatedScreenshotTime() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                .replace("\"PAGE_EXACT_TIME\"", "\"MERCHANT_MARKER\",\"FRESH_TIME\""),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun acceptsFreshAlipaySuccessWithEstimatedScreenshotTime() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"platform\":\"WECHAT\"", "\"platform\":\"ALIPAY\"")
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                .replace("\"PAGE_EXACT_TIME\"", "\"MERCHANT_MARKER\",\"FRESH_TIME\""),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun rejectsEstimatedSuccessBasedOnStatusAndAmountWithoutPayeeStructure() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                .replace("\"PAGE_EXACT_TIME\"", "\"FRESH_TIME\""),
        )
        assertEquals("auto_book_invariants", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsEstimatedSuccessWhenPayeeMarkerHasNoExtractedPayee() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"merchant\":\"Cafe\"", "\"merchant\":null")
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                .replace("\"PAGE_EXACT_TIME\"", "\"MERCHANT_MARKER\",\"FRESH_TIME\""),
        )
        assertEquals("auto_book_invariants", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsEstimatedSuccessInNonNativeContexts() {
        listOf("CHAT_THREAD", "BILL_LIST", "HISTORY_DETAIL", "SHARE_POSTER", "IMAGE_PREVIEW")
            .forEach { negativeFeature ->
                val result = LedgerV1Parser.parse(
                    validPayload()
                        .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                        .replace("\"PAGE_EXACT_TIME\"", "\"MERCHANT_MARKER\",\"FRESH_TIME\"")
                        .replace(
                            "\"negative_features\":[]",
                            "\"negative_features\":[\"$negativeFeature\"]",
                        ),
                )
                assertEquals(
                    negativeFeature,
                    "auto_book_invariants",
                    (result as ParseResult.Invalid).reason,
                )
            }
    }

    @Test
    fun rejectsUnknownRootField() {
        val result = LedgerV1Parser.parse(validPayload().replace("\"confidence\":0.96", "\"confidence\":0.96,\"extra\":true"))
        assertTrue(result is ParseResult.Invalid)
    }

    @Test
    fun rejectsFloatingPointAmount() {
        val result = LedgerV1Parser.parse(validPayload().replace("\"amount_minor\":1280", "\"amount_minor\":12.8"))
        assertTrue(result is ParseResult.Invalid)
    }

    @Test
    fun rejectsDuplicateKeys() {
        val result = LedgerV1Parser.parse(
            validPayload().replace(
                "\"decision\":\"AUTO_BOOK\"",
                "\"decision\":\"NEEDS_CONFIRMATION\",\"decision\":\"AUTO_BOOK\"",
            ),
        )
        assertEquals("duplicate_keys", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsEscapedDuplicateKeysInsideEvidence() {
        val result = LedgerV1Parser.parse(
            validPayload().replace(
                "\"negative_features\":[]",
                "\"negative_features\":[],\"negative\\u005ffeatures\":[]",
            ),
        )
        assertEquals("duplicate_keys", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsServerOnlyTimeSources() {
        val result = LedgerV1Parser.parse(
            validPayload().replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"USER_CONFIRMED\""),
        )
        assertTrue(result is ParseResult.Invalid)
    }

    @Test
    fun rejectsStatementVerifiedTimeSourceFromVlm() {
        val result = LedgerV1Parser.parse(
            validPayload().replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"STATEMENT_VERIFIED\""),
        )
        assertEquals("vlm_time_source", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsInconsistentRejectSuggestion() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"REJECT\""),
        )
        assertTrue(result is ParseResult.Invalid)
    }

    @Test
    fun acceptsRejectForNonPaymentWithoutNegativeFeature() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"REJECT\"")
                .replace("\"is_payment_screenshot\":true", "\"is_payment_screenshot\":false"),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun acceptsRejectForPaymentWithStaleTimeNegativeFeature() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"REJECT\"")
                .replace("\"negative_features\":[]", "\"negative_features\":[\"STALE_TIME\"]")
                .replace("\"freshness\":\"VALID\"", "\"freshness\":\"STALE\"")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"STALE_TRANSACTION\""),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun acceptsConservativeEstimatedTimeForConfirmation() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                .replace("\"confidence\":0.96", "\"confidence\":0.6")
                .replace("\"freshness\":\"VALID\"", "\"freshness\":\"UNKNOWN\"")
                .replace(",\"PAGE_EXACT_TIME\"", "")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"MISSING_TIME\""),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun rejectsEstimatedTimeMarkedFresh() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                .replace(",\"PAGE_EXACT_TIME\"", "")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"MISSING_TIME\""),
        )
        assertEquals("estimated_time_invariants", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsEstimatedTimeWithoutMissingTimeReason() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":\"SCREENSHOT_ESTIMATED\"")
                .replace(",\"PAGE_EXACT_TIME\"", "")
                .replace("\"freshness\":\"VALID\"", "\"freshness\":\"UNKNOWN\""),
        )
        assertEquals("estimated_time_invariants", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsOccurredAtWithoutTimeSource() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"time_source\":\"PAGE_EXACT\"", "\"time_source\":null"),
        )
        assertEquals("time_source_consistency", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsTimeSourceWithoutOccurredAt() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"occurred_at\":\"2026-09-14T12:30:00+08:00\"", "\"occurred_at\":null"),
        )
        assertEquals("time_source_consistency", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsAutoBookWithAnyNegativeFeature() {
        val result = LedgerV1Parser.parse(
            validPayload().replace(
                "\"negative_features\":[]",
                "\"negative_features\":[\"CHAT_THREAD\"]",
            ),
        )
        assertEquals("auto_book_invariants", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun acceptsIncomeWithReceiptSuccessFeature() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"direction\":\"EXPENSE\"", "\"direction\":\"INCOME\"")
                .replace("\"PAYMENT_SUCCESS\"", "\"RECEIPT_SUCCESS\"")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"INCOME_PAGE_CONFIRMED\""),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun rejectsTimeEvidenceThatDoesNotMatchSource() {
        val result = LedgerV1Parser.parse(
            validPayload().replace("\"PAGE_EXACT_TIME\"", "\"NOTIFICATION_MATCH\""),
        )
        assertEquals("time_evidence_consistency", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsAutoBookWithLowConfidenceReason() {
        val result = LedgerV1Parser.parse(
            validPayload().replace(
                "\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"",
                "\"reason_code\":\"LOW_CONFIDENCE\"",
            ),
        )
        assertEquals("auto_book_invariants", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsAutoBookWithConflictingSuccessFeature() {
        val result = LedgerV1Parser.parse(
            validPayload().replace(
                "\"PAYMENT_SUCCESS\"",
                "\"PAYMENT_SUCCESS\",\"REFUND_SUCCESS\"",
            ),
        )
        assertEquals("auto_book_invariants", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsMissingAmountReasonWithExtractedAmount() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"MISSING_AMOUNT\""),
        )
        assertEquals("amount_evidence_consistency", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun rejectsMultipleAmountsWithUniqueExtractedAmount() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"negative_features\":[]", "\"negative_features\":[\"MULTIPLE_AMOUNTS\"]")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"MULTIPLE_AMOUNTS\""),
        )
        assertEquals("amount_evidence_consistency", (result as ParseResult.Invalid).reason)
    }

    @Test
    fun acceptsMultipleAmountsWithoutSelectedAmount() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"amount_minor\":1280", "\"amount_minor\":null")
                .replace(",\"UNIQUE_AMOUNT\"", "")
                .replace("\"negative_features\":[]", "\"negative_features\":[\"MULTIPLE_AMOUNTS\"]")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"MULTIPLE_AMOUNTS\""),
        )
        assertTrue(result is ParseResult.Valid)
    }

    @Test
    fun rejectsMissingTimeReasonWithExactPageTime() {
        val result = LedgerV1Parser.parse(
            validPayload()
                .replace("\"decision\":\"AUTO_BOOK\"", "\"decision\":\"NEEDS_CONFIRMATION\"")
                .replace("\"reason_code\":\"PAYMENT_PAGE_CONFIRMED\"", "\"reason_code\":\"MISSING_TIME\""),
        )
        assertEquals("reason_code_consistency", (result as ParseResult.Invalid).reason)
    }

    private fun validPayload(): String =
        """
        {
          "schema_version":"ledger.v1",
          "decision":"AUTO_BOOK",
          "is_payment_screenshot":true,
          "platform":"WECHAT",
          "direction":"EXPENSE",
          "amount_minor":1280,
          "currency":"CNY",
          "merchant":"Cafe",
          "counterparty":null,
          "occurred_at":"2026-09-14T12:30:00+08:00",
          "time_source":"PAGE_EXACT",
          "external_id":null,
          "suggested_tag":"餐饮",
          "confidence":0.96,
          "evidence":{
            "positive_features":["PAYMENT_SUCCESS","PLATFORM_MARKER","UNIQUE_AMOUNT","PAGE_EXACT_TIME"],
            "negative_features":[],
            "freshness":"VALID",
            "reason_code":"PAYMENT_PAGE_CONFIRMED"
          }
        }
        """.trimIndent()
}
