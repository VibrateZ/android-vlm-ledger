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
        val body = client.buildStreamingRequestBody(request)
        val output = ByteArrayOutputStream()

        body.writeTo(output)

        assertEquals(body.contentLength, output.size().toLong())
        val root = JSONObject(output.toString(Charsets.UTF_8.name()))
        val url = root.getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
            .getJSONObject(1)
            .getString("image_url")
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(request.imageBytes),
            url,
        )
        assertEquals("test prompt", root.getString("instructions"))
        assertEquals("input_text", root.getJSONArray("input")
            .getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
        val inputText = root.getJSONArray("input")
            .getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(inputText.contains("ledger.capture.v1"))
        assertTrue(inputText.contains("不要读取时间"))
        assertFalse(inputText.contains("screenshot_estimated_at"))
        assertTrue(inputText.contains("/no_think"))
        assertEquals(
            "json_schema",
            root.getJSONObject("text").getJSONObject("format").getString("type"),
        )
        assertFalse(root.has("messages"))
        assertFalse(root.has("response_format"))
        assertFalse(root.has("tools"))
        assertFalse(root.has("tool_choice"))
    }

    @Test
    fun responsesRequestAlwaysUsesJsonSchemaWithoutTools() {
        val body = client.buildStreamingRequestBody(request("test-model"))
        val output = ByteArrayOutputStream()

        body.writeTo(output)

        val root = JSONObject(output.toString(Charsets.UTF_8.name()))
        assertEquals(
            "json_schema",
            root.getJSONObject("text").getJSONObject("format").getString("type"),
        )
        assertFalse(root.has("tools"))
        assertFalse(root.has("tool_choice"))
        assertFalse(root.has("parallel_tool_calls"))
    }

    @Test
    fun chatCompletionsBodySubmitsPromptAndImageInOneUserMessage() {
        val request = request("Qwen/Qwen3.8-27B").copy(
            imageBytes = byteArrayOf(5, 6, 7),
            screenshotCapturedAt = "2026-09-17T12:34:56.789+08:00",
            sourcePackage = "com.tencent.mm",
        )
        val body = client.buildChatCompletionsRequestBody(request)
        val output = ByteArrayOutputStream()

        body.writeTo(output)

        assertEquals(body.contentLength, output.size().toLong())
        val root = JSONObject(output.toString(Charsets.UTF_8.name()))
        val messages = root.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val content = messages.getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        val prompt = content.getJSONObject(0).getString("text")
        assertTrue(prompt.startsWith("test prompt\n\n"))
        assertTrue(prompt.contains("ledger.capture.v1"))
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(request.imageBytes),
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
        assertEquals(
            "high",
            content.getJSONObject(1).getJSONObject("image_url").getString("detail"),
        )
        assertTrue(prompt.contains("不要读取时间"))
        assertFalse(prompt.contains("2026-09-17T12:34"))
        assertFalse(prompt.contains("com.tencent.mm"))
        assertTrue(prompt.endsWith("/no_think"))
        assertFalse(root.getBoolean("enable_thinking"))
        assertFalse(root.has("response_format"))
        assertFalse(root.has("tools"))
        assertFalse(root.has("tool_choice"))
        assertFalse(root.has("parallel_tool_calls"))
        assertFalse(root.has("input"))
        assertFalse(root.has("text"))
    }

    @Test
    fun chatConnectionProbeRequestsPlainTextWithoutImage() {
        val root = client.buildChatContentProbeBody("gemini-3.8-flash")

        assertEquals("gemini-3.8-flash", root.getString("model"))
        assertEquals(
            "只返回 OK，不要解释。\n/no_think",
            root.getJSONArray("messages").getJSONObject(0).getString("content"),
        )
        assertEquals(64, root.getInt("max_tokens"))
        assertFalse(root.has("enable_thinking"))
        assertFalse(root.has("response_format"))
        assertFalse(root.has("tools"))
        assertFalse(root.has("tool_choice"))
        assertFalse(root.toString().contains("image_url"))
    }

    @Test
    fun chatConnectionProbeRequiresPlainOkResult() {
        val valid = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", "OK"),
                    ),
                ),
            )
            .toString()
        val invalid = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", "not OK")),
                ),
            )
            .toString()

        assertTrue(
            client.classifyChatContentProbe(HttpResult(200, valid), modelAvailable = true) is
                ConnectionResult.Success,
        )
        val failure = client.classifyChatContentProbe(
            HttpResult(200, invalid),
            modelAvailable = true,
        ) as ConnectionResult.Failure
        assertEquals("chat_text_response_unsupported", failure.detail)
    }

    @Test
    fun normalizesBaseUrlToResponsesEndpoint() {
        assertEquals("https://api.example.com/v1/responses", client.normalizeEndpoint("https://api.example.com"))
        assertEquals("https://api.example.com/v1/responses", client.normalizeEndpoint("https://api.example.com/v1/"))
    }

    @Test
    fun normalizesBaseUrlToChatCompletionsEndpoint() {
        assertEquals(
            "https://api.siliconflow.cn/v1/chat/completions",
            client.normalizeEndpoint(
                "https://api.siliconflow.cn/v1/",
                VlmApiProtocol.CHAT_COMPLETIONS,
            ),
        )
    }

    @Test
    fun chatCompletionsParsesLedgerJsonContent() {
        val response = JSONObject()
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

        val result = client.classifyChatCompletionsResponse(
            HttpResult(code = 200, body = response),
            requestId = "request-id",
            configuredModel = "Qwen/Qwen3.8-27B",
        )

        assertTrue(result is VlmAnalyzeResult.Success)
    }

    @Test
    fun captureResponseUsesScreenshotTimeAndRejectsHistoryLocally() {
        val response = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put(
                "content",
                JSONObject()
                    .put("schema_version", "ledger.capture.v1")
                    .put("is_history", false)
                    .put("has_purchase_actions", false)
                    .put("amount_minor", 1280)
                    .put("expense_target", "Cafe")
                    .toString(),
            )))).toString()
        val result = client.classifyChatCompletionsResponse(
            HttpResult(200, response), "request-id", "Qwen/Qwen3.5-35B-A3B",
            request("Qwen/Qwen3.5-35B-A3B").copy(
                screenshotCapturedAt = "2026-09-18T12:30:00+08:00",
                sourcePackage = "com.tencent.mm",
            ),
        ) as VlmAnalyzeResult.Success
        assertEquals(1280L, result.response.ledger.amountMinor)
        assertEquals("2026-09-18T12:30+08:00", result.response.ledger.occurredAt.toString())
        assertEquals(Platform.WECHAT, result.response.ledger.platform)
    }

    @Test
    fun captureResponseRejectsProductPageWithPurchaseButtons() {
        val response = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put(
                "content",
                JSONObject()
                    .put("schema_version", "ledger.capture.v1")
                    .put("is_history", false)
                    .put("has_purchase_actions", true)
                    .put("amount_minor", 1280)
                    .put("expense_target", "Example shop")
                    .toString(),
            )))).toString()
        val result = client.classifyChatCompletionsResponse(
            HttpResult(200, response), "request-id", "Qwen/Qwen3.5-35B-A3B",
            request("Qwen/Qwen3.5-35B-A3B").copy(
                screenshotCapturedAt = "2026-09-18T12:30:00+08:00",
                sourcePackage = "com.xunmeng.pinduoduo",
            ),
        ) as VlmAnalyzeResult.Success

        assertEquals(Decision.REJECT, result.response.ledger.decision)
        assertEquals("NOT_PAYMENT_PAGE", result.response.ledger.evidence.reasonCode)
        assertEquals(listOf("NON_PAYMENT"), result.response.ledger.evidence.negativeFeatures)
        assertFalse("PAYMENT_SUCCESS" in result.response.ledger.evidence.positiveFeatures)
    }

    @Test
    fun chatCompletionsAcceptsJsonContent() {
        val response = JSONObject()
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

        val result = client.classifyChatCompletionsResponse(
            HttpResult(code = 200, body = response),
            requestId = "request-id",
            configuredModel = "Qwen/Qwen3.8-27B",
        )

        assertTrue(result is VlmAnalyzeResult.Success)
    }

    @Test
    fun chatCompletionsRejectsMultipleToolCalls() {
        val call = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "submit_ledger_v1")
                    .put("arguments", validLedgerPayload()),
            )
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("tool_calls", JSONArray().put(call).put(call)),
                    ),
                ),
            )
            .toString()

        val result = client.classifyChatCompletionsResponse(
            HttpResult(code = 200, body = response),
            requestId = "request-id",
            configuredModel = "Qwen/Qwen3.8-27B",
        ) as VlmAnalyzeResult.Failure

        assertEquals("multiple_tool_calls", result.detail)
    }

    @Test
    fun chatCompletionsRejectsSingleToolCall() {
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put(
                            "tool_calls",
                            JSONArray().put(JSONObject().put("type", "function")),
                        ),
                    ),
                ),
            )
            .toString()

        val result = client.classifyChatCompletionsResponse(
            HttpResult(code = 200, body = response),
            requestId = "request-id",
            configuredModel = "Qwen/Qwen3.8-27B",
        ) as VlmAnalyzeResult.Failure

        assertEquals("unexpected_tool_call", result.detail)
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
        assertEquals("invalid_json_non_object", result.detail)
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
    fun invalidJsonDiagnosticsRevealOnlyTheStructuralShape() {
        assertEquals("invalid_json_quoted", client.classifyInvalidJsonShape("\"not an object\""))
        assertEquals("invalid_json_non_object", client.classifyInvalidJsonShape("not an object"))
        assertEquals("invalid_json_truncated", client.classifyInvalidJsonShape("{\"schema_version\":"))
        assertEquals("invalid_json_syntax", client.classifyInvalidJsonShape("{invalid}"))
    }

    @Test
    fun outerMetadataMayExceedContentLimit() {
        val body = JSONObject()
            .put("padding", "x".repeat(70 * 1024))
            .put("status", "completed")
            .put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("type", "message")
                        .put("role", "assistant")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "output_text")
                                    .put("text", validLedgerPayload()),
                            ),
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
    fun responseWithRefusalIsInvalid() {
        val result = classifyContent(
            JSONArray().put(
                JSONObject()
                    .put("type", "refusal")
                    .put("refusal", "cannot comply"),
            ),
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("response_refusal", result.detail)
    }

    @Test
    fun responseWithToolCallIsInvalid() {
        val outer = JSONObject()
            .put("status", "completed")
            .put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("type", "function_call")
                        .put("name", "unexpected"),
                ),
            )
            .toString()
        val result = client.classifyAnalyzeResponse(
            response = HttpResult(code = 200, body = outer),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("unexpected_tool_call", result.detail)
    }

    @Test
    fun standardResponseRejectsReservedLedgerFunctionCall() {
        val outer = JSONObject()
            .put("status", "completed")
            .put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("type", "function_call")
                        .put("name", "submit_ledger_v1")
                        .put("arguments", validLedgerPayload()),
                ),
            )
            .toString()

        val result = client.classifyAnalyzeResponse(
            response = HttpResult(code = 200, body = outer),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals("unexpected_tool_call", result.detail)
    }

    @Test
    fun contentArrayRequiresStringTextParts() {
        val result = classifyContent(
            JSONArray().put(
                JSONObject()
                    .put("type", "output_text")
                    .put("text", JSONObject().put("unexpected", true)),
            ),
        ) as VlmAnalyzeResult.Failure

        assertEquals(FailureCategory.INVALID_RESPONSE, result.category)
        assertEquals("invalid_outer_response", result.detail)
    }

    @Test
    fun responseTextFragmentsAreConcatenatedInOrder() {
        val payload = validLedgerPayload()
        val split = payload.length / 2
        val result = classifyContent(
            JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", payload.substring(0, split)))
                .put(JSONObject().put("type", "output_text").put("text", payload.substring(split))),
        )

        assertTrue(result is VlmAnalyzeResult.Success)
    }

    @Test
    fun responsesAcceptsInsignificantWhitespaceAroundCompleteJson() {
        val result = client.classifyAnalyzeResponse(
            response = openAiResponse(" \n" + validLedgerPayload() + "\n "),
            requestId = "request-id",
            configuredModel = "test-model",
        )

        assertTrue(result is VlmAnalyzeResult.Success)
    }

    @Test
    fun responsesStillRejectsMarkdownWrappedJson() {
        val result = client.classifyAnalyzeResponse(
            response = openAiResponse("\n```json\n" + validLedgerPayload() + "\n```\n"),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals("response_not_strict_json", result.detail)
    }

    @Test
    fun chatCompletionsAcceptsInsignificantWhitespaceAroundCompleteJson() {
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", "\n" + validLedgerPayload()),
                    ),
                ),
            )
            .toString()

        val result = client.classifyChatCompletionsResponse(
            response = HttpResult(code = 200, body = response),
            requestId = "request-id",
            configuredModel = "test-model",
        )

        assertTrue(result is VlmAnalyzeResult.Success)
    }

    @Test
    fun incompleteResponseIsRejected() {
        val body = JSONObject()
            .put("status", "incomplete")
            .put("output", JSONArray())
            .toString()
        val result = client.classifyAnalyzeResponse(
            response = HttpResult(code = 200, body = body),
            requestId = "request-id",
            configuredModel = "test-model",
        ) as VlmAnalyzeResult.Failure

        assertEquals("response_not_completed", result.detail)
    }

    @Test
    fun requestSchemaAsksForHistoryPurchaseActionsAmountAndExpenseTarget() {
        val schema = client.captureSchema()
        val properties = schema.getJSONObject("properties")
        val amount = properties.getJSONObject("amount_minor")
            .getJSONArray("anyOf")
            .getJSONObject(0)

        assertEquals(
            setOf("schema_version", "is_history", "has_purchase_actions", "amount_minor", "expense_target"),
            properties.keys().asSequence().toSet())
        assertEquals(1, amount.getInt("minimum"))
        assertFalse(properties.has("occurred_at"))
        assertFalse(properties.has("platform"))
        assertFalse(properties.has("direction"))
    }

    private fun classifyContent(content: JSONArray): VlmAnalyzeResult {
        val outer = JSONObject()
            .put("status", "completed")
            .put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("type", "message")
                        .put("role", "assistant")
                        .put("content", content),
                ),
            )
            .toString()
        return client.classifyAnalyzeResponse(
            response = HttpResult(code = 200, body = outer),
            requestId = "request-id",
            configuredModel = "test-model",
        )
    }

    private fun openAiResponse(content: String): HttpResult {
        val body = JSONObject()
            .put("status", "completed")
            .put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("type", "message")
                        .put("role", "assistant")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "output_text")
                                    .put("text", content),
                            ),
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
