package com.vibratez.ledger.vlm

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class OpenAiVlmClientTest {
    private val client = OpenAiVlmClient(systemPrompt = "test prompt")

    @Test
    fun rejectsControlCharactersInApiKeyBeforeNetworkAccess() = runBlocking {
        val result = client.analyze(
            baseUrl = "https://api.example.com",
            apiKey = "secret\nheader",
            request = request("test-model"),
            timeoutSeconds = 60,
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_CONFIGURATION, result.category)
    }

    @Test
    fun rejectsInvalidModelBeforeNetworkAccess() = runBlocking {
        val result = client.analyze(
            baseUrl = "https://api.example.com",
            apiKey = "secret",
            request = request("invalid model"),
            timeoutSeconds = 60,
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_CONFIGURATION, result.category)
    }

    @Test
    fun streamingBodyProducesValidJsonAndDataUrl() {
        val request = request("test-model").copy(
            mimeType = "image/png",
            imageBytes = byteArrayOf(0, 1, 2, 3, 4),
        )
        val body = client.buildStreamingRequestBody(request, includeJsonSchema = true)
        val output = ByteArrayOutputStream()

        body.writeTo(output)

        assertEquals(body.contentLength, output.size().toLong())
        val root = JSONObject(output.toString(Charsets.UTF_8.name()))
        val url = root.getJSONArray("messages")
            .getJSONObject(1)
            .getJSONArray("content")
            .getJSONObject(1)
            .getJSONObject("image_url")
            .getString("url")
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(request.imageBytes),
            url,
        )
        assertTrue(root.has("response_format"))
    }

    @Test
    fun boundedReaderStopsWhenResponseExceedsByteLimit() {
        val result = client.readBounded(
            input = ByteArrayInputStream(ByteArray(9)),
            maxBytes = 8,
        )

        assertTrue(result is BoundedReadResult.TooLarge)
    }

    @Test
    fun oversizedResponseIsInvalidAndNotRetryable() {
        val oversizedRead = client.readBounded(
            input = ByteArrayInputStream(ByteArray(9)),
            maxBytes = 8,
        )
        val result = client.classifyAnalyzeResponse(
            response = client.httpResultFromRead(code = 200, read = oversizedRead),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("response_too_large", result.detail)
        assertFalse(result.retryable)
    }

    @Test
    fun exactContentByteLimitIsNotClassifiedAsTooLarge() {
        val result = client.classifyAnalyzeResponse(
            response = openAiResponse("x".repeat(64 * 1024)),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("invalid_json", result.detail)
    }

    @Test
    fun contentBeyondByteLimitIsClassifiedAsTooLarge() {
        val result = client.classifyAnalyzeResponse(
            response = openAiResponse("x".repeat(64 * 1024 + 1)),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("response_too_large", result.detail)
        assertFalse(result.retryable)
    }

    @Test
    fun contentLimitCountsUtf8BytesRatherThanCharacters() {
        val result = client.classifyAnalyzeResponse(
            response = openAiResponse("账".repeat(22_000)),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals("response_too_large", result.detail)
    }

    @Test
    fun outerMetadataMayExceedContentLimit() {
        val body = JSONObject()
            .put("padding", "x".repeat(70 * 1024))
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", validLedgerPayload()),
                    ),
                ),
            )
            .toString()
        val bounded = client.readBounded(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        assertTrue(bounded is BoundedReadResult.Success)

        val result = client.classifyAnalyzeResponse(
            response = client.httpResultFromRead(code = 200, read = bounded),
            requestId = "request-id",
            configuredModel = "test-model",
        )
        assertTrue(result is VlmAnalyzeResult.Success)
    }

    @Test
    fun oversizedAuthenticationErrorKeepsHttpClassification() {
        val oversizedRead = client.readBounded(
            input = ByteArrayInputStream(ByteArray(9)),
            maxBytes = 8,
        )
        val httpResult = client.httpResultFromRead(code = 401, read = oversizedRead)
        assertTrue(httpResult.bodyTruncated)
        assertTrue(httpResult.failure == null)

        val result = client.classifyAnalyzeResponse(
            response = httpResult,
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure
        assertEquals(FailureCategory.AUTHENTICATION, result.category)
        assertFalse(result.retryable)
    }

    @Test
    fun transportFailureIsNetworkAndRetryable() {
        val result = client.classifyAnalyzeResponse(
            response = HttpResult(
                code = -1,
                body = "",
                failure = HttpFailure.NETWORK,
            ),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.NETWORK, result.category)
        assertEquals("network_error", result.detail)
        assertTrue(result.retryable)
    }

    @Test
    fun requestTimeoutIsNetworkAndRetryable() {
        val result = client.classifyAnalyzeResponse(
            response = HttpResult(code = 408, body = ""),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.NETWORK, result.category)
        assertTrue(result.retryable)
    }

    @Test
    fun rateLimitIsRateLimitedAndRetryable() {
        val result = client.classifyAnalyzeResponse(
            response = HttpResult(code = 429, body = "", retryAfterMillis = 5_000L),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.RATE_LIMITED, result.category)
        assertTrue(result.retryable)
        assertEquals(5_000L, result.retryAfterMillis)
    }

    @Test
    fun parsesAndCapsRetryAfterHeader() {
        assertEquals(5_000L, client.parseRetryAfterMillis("5", nowMillis = 0L))
        assertEquals(30_000L, client.parseRetryAfterMillis("120", nowMillis = 0L))
        assertEquals(
            10_000L,
            client.parseRetryAfterMillis(
                "Thu, 01 Jan 1970 00:00:10 GMT",
                nowMillis = 0L,
            ),
        )
        assertEquals(null, client.parseRetryAfterMillis("invalid", nowMillis = 0L))
    }

    @Test
    fun responseWithRefusalIsInvalidEvenWhenContentIsValid() {
        val message = JSONObject()
            .put("refusal", "cannot comply")
            .put("content", validLedgerPayload())
        val result = classifyMessage(message)

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("invalid_outer_response", result.detail)
    }

    @Test
    fun responseWithToolCallsIsInvalidEvenWhenContentIsValid() {
        val message = JSONObject()
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1")))
            .put("content", validLedgerPayload())
        val result = classifyMessage(message)

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("invalid_outer_response", result.detail)
    }

    @Test
    fun contentArrayRequiresStringTextParts() {
        val message = JSONObject().put(
            "content",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put("text", JSONObject().put("unexpected", true)),
            ),
        )
        val result = classifyMessage(message)

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("invalid_outer_response", result.detail)
    }

    @Test
    fun requestSchemaRestrictsEvidenceEnumsAndDateTime() {
        val schema = client.ledgerSchema()
        val properties = schema.getJSONObject("properties")
        val occurredAt = properties.getJSONObject("occurred_at")
            .getJSONArray("anyOf")
            .getJSONObject(0)
        val amount = properties.getJSONObject("amount_minor")
            .getJSONArray("anyOf")
            .getJSONObject(0)
        val timeSources = properties.getJSONObject("time_source")
            .getJSONArray("anyOf")
            .getJSONObject(0)
            .getJSONArray("enum")
        val evidence = properties.getJSONObject("evidence").getJSONObject("properties")

        assertEquals("date-time", occurredAt.getString("format"))
        assertEquals(1, amount.getInt("minimum"))
        assertEquals(3, timeSources.length())
        assertFalse((0 until timeSources.length()).any { timeSources.getString(it) == "USER_CONFIRMED" })
        assertFalse((0 until timeSources.length()).any { timeSources.getString(it) == "STATEMENT_VERIFIED" })
        assertTrue(
            evidence.getJSONObject("positive_features")
                .getJSONObject("items")
                .has("enum"),
        )
        assertTrue(
            evidence.getJSONObject("negative_features")
                .getJSONObject("items")
                .has("enum"),
        )
        assertTrue(evidence.getJSONObject("reason_code").has("enum"))
    }

    @Test
    fun schemaFallbackRequiresExplicitUnsupportedError() {
        assertFalse(
            client.explicitlyRejectsStructuredOutput(
                "Invalid response_format: supplied schema has an error",
            ),
        )
        assertTrue(
            client.explicitlyRejectsStructuredOutput(
                "The response_format json_schema parameter is not supported by this model",
            ),
        )
    }

    private fun classifyMessage(message: JSONObject): VlmAnalyzeResult.Failure {
        val outer = JSONObject()
            .put(
                "choices",
                JSONArray().put(JSONObject().put("message", message)),
            )
            .toString()
        return client.classifyAnalyzeResponse(
            response = HttpResult(code = 200, body = outer),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure
    }

    private fun openAiResponse(content: String): HttpResult {
        val body = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", content),
                    ),
                ),
            )
            .toString()
        return HttpResult(code = 200, body = body)
    }

    private fun request(model: String) = VlmRequest(
        model = model,
        mimeType = "image/png",
        imageBytes = byteArrayOf(1),
        screenshotCapturedAt = null,
        deviceTimezone = "Asia/Shanghai",
    )

    private fun validLedgerPayload(): String =
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
