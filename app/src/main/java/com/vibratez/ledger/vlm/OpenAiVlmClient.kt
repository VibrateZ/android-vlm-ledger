package com.vibratez.ledger.vlm

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

internal enum class HttpFailure {
    NETWORK,
    RESPONSE_TOO_LARGE,
}

internal data class HttpResult(
    val code: Int,
    val body: String,
    val failure: HttpFailure? = null,
    val bodyTruncated: Boolean = false,
    val retryAfterMillis: Long? = null,
)

internal sealed interface BoundedReadResult {
    data class Success(val body: String) : BoundedReadResult

    data class TooLarge(val prefix: String) : BoundedReadResult
}

internal data class StreamingRequestBody(
    val prefix: ByteArray,
    val imageBytes: ByteArray,
    val suffix: ByteArray,
) {
    val contentLength: Long = prefix.size.toLong() + suffix.size.toLong() +
        ((imageBytes.size.toLong() + 2L) / 3L) * 4L

    fun writeTo(output: OutputStream) {
        output.write(prefix)
        val nonClosingOutput = object : OutputStream() {
            override fun write(value: Int) = output.write(value)

            override fun write(buffer: ByteArray, offset: Int, length: Int) =
                output.write(buffer, offset, length)

            override fun flush() = output.flush()

            override fun close() = output.flush()
        }
        Base64.getEncoder().wrap(nonClosingOutput).use { encoded ->
            encoded.write(imageBytes)
        }
        output.write(suffix)
    }
}

sealed interface VlmAnalyzeResult {
    data class Success(val response: VlmResponse) : VlmAnalyzeResult
    data class Failure(
        val category: FailureCategory,
        val retryable: Boolean,
        val detail: String,
        val retryAfterMillis: Long? = null,
    ) : VlmAnalyzeResult
}

enum class FailureCategory {
    INVALID_CONFIGURATION,
    AUTHENTICATION,
    RATE_LIMITED,
    NETWORK,
    SERVER,
    UNSUPPORTED_IMAGE,
    INVALID_RESPONSE,
    TOO_LARGE,
}

sealed interface ConnectionResult {
    data class Success(val modelAvailable: Boolean) : ConnectionResult
    data class Failure(val category: FailureCategory, val detail: String) : ConnectionResult
}

enum class VlmApiProtocol {
    RESPONSES,
    CHAT_COMPLETIONS,
}

/**
 * Dependency-free OpenAI-compatible client. The API key never appears in URLs,
 * request bodies, exceptions, or returned diagnostics.
 */
class OpenAiVlmClient(
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    suspend fun analyze(
        baseUrl: String,
        apiKey: String,
        request: VlmRequest,
        timeoutSeconds: Int,
        protocol: VlmApiProtocol = VlmApiProtocol.RESPONSES,
    ): VlmAnalyzeResult {
        if (!isValidApiKey(apiKey) || !isValidModel(request.model)) {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_CONFIGURATION,
                retryable = false,
                detail = "missing_configuration",
            )
        }
        if (request.imageBytes.size > MAX_IMAGE_BYTES) {
            return VlmAnalyzeResult.Failure(
                FailureCategory.TOO_LARGE,
                retryable = false,
                detail = "image_over_12_mib",
            )
        }
        val endpoint = runCatching { normalizeEndpoint(baseUrl, protocol) }.getOrElse {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_CONFIGURATION,
                retryable = false,
                detail = "invalid_base_url",
            )
        }
        val requestId = UUID.randomUUID().toString()
        if (protocol == VlmApiProtocol.CHAT_COMPLETIONS) {
            val response = execute(
                endpoint = endpoint,
                method = "POST",
                apiKey = apiKey,
                body = null,
                timeoutSeconds = timeoutSeconds,
                streamingBody = buildChatCompletionsRequestBody(request),
            )
            return classifyChatCompletionsResponse(response, requestId, request.model, request)
        }
        val response = execute(
            endpoint = endpoint,
            method = "POST",
            apiKey = apiKey,
            body = null,
            timeoutSeconds = timeoutSeconds,
            streamingBody = buildStreamingRequestBody(request),
        )
        return classifyAnalyzeResponse(response, requestId, request.model, request)
    }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutSeconds: Int,
        protocol: VlmApiProtocol = VlmApiProtocol.RESPONSES,
    ): ConnectionResult {
        if (!isValidApiKey(apiKey) || !isValidModel(model)) {
            return ConnectionResult.Failure(FailureCategory.INVALID_CONFIGURATION, "missing_configuration")
        }
        val endpoint = runCatching { normalizeEndpoint(baseUrl, protocol) }.getOrElse {
            return ConnectionResult.Failure(FailureCategory.INVALID_CONFIGURATION, "invalid_base_url")
        }
        val modelsEndpoint = when (protocol) {
            VlmApiProtocol.RESPONSES -> endpoint.removeSuffix("/responses") + "/models"
            VlmApiProtocol.CHAT_COMPLETIONS -> endpoint.removeSuffix("/chat/completions") + "/models"
        }
        val response = execute(
            endpoint = modelsEndpoint,
            method = "GET",
            apiKey = apiKey,
            body = null,
            timeoutSeconds = timeoutSeconds,
        )
        response.failure?.let { failure ->
            return ConnectionResult.Failure(
                category = failure.category,
                detail = failure.detail,
            )
        }
        if (response.code in 200..299) {
            val available = runCatching {
                val data = JSONObject(response.body).optJSONArray("data") ?: return@runCatching false
                (0 until data.length()).any { data.optJSONObject(it)?.optString("id") == model }
            }.getOrDefault(false)
            if (protocol == VlmApiProtocol.CHAT_COMPLETIONS) {
                return testChatContentCapability(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    timeoutSeconds = timeoutSeconds,
                    modelAvailable = available,
                )
            }
            return ConnectionResult.Success(modelAvailable = available)
        }
        if (response.code == 404 || response.code == 405) {
            if (protocol == VlmApiProtocol.CHAT_COMPLETIONS) {
                return testChatContentCapability(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    timeoutSeconds = timeoutSeconds,
                    modelAvailable = false,
                )
            }
            val probeBody = when (protocol) {
                VlmApiProtocol.RESPONSES -> JSONObject()
                    .put("model", model)
                    .put("input", "只返回 OK")
                    .put("max_output_tokens", 8)
                VlmApiProtocol.CHAT_COMPLETIONS -> error("handled above")
            }
            val probe = execute(
                endpoint = endpoint,
                method = "POST",
                apiKey = apiKey,
                body = probeBody.toString(),
                timeoutSeconds = timeoutSeconds,
            )
            probe.failure?.let { failure ->
                return ConnectionResult.Failure(
                    category = failure.category,
                    detail = failure.detail,
                )
            }
            return if (probe.code in 200..299) {
                ConnectionResult.Success(modelAvailable = true)
            } else {
                ConnectionResult.Failure(categoryFor(probe.code), "probe_http_" + probe.code)
            }
        }
        return ConnectionResult.Failure(categoryFor(response.code), "models_http_" + response.code)
    }

    private fun testChatContentCapability(
        endpoint: String,
        apiKey: String,
        model: String,
        timeoutSeconds: Int,
        modelAvailable: Boolean,
    ): ConnectionResult = classifyChatContentProbe(
        response = execute(
            endpoint = endpoint,
            method = "POST",
            apiKey = apiKey,
            body = buildChatContentProbeBody(model).toString(),
            timeoutSeconds = timeoutSeconds,
        ),
        modelAvailable = modelAvailable,
    )

    internal fun buildChatContentProbeBody(model: String): JSONObject {
        return JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "只返回 OK，不要解释。\n/no_think"),
                ),
            )
            .put("temperature", 0)
            .put("max_tokens", 64)
            .apply {
                if (usesQwenThinkingControl(model)) put("enable_thinking", false)
            }
    }

    internal fun classifyChatContentProbe(
        response: HttpResult,
        modelAvailable: Boolean,
    ): ConnectionResult {
        response.failure?.let { failure ->
            return ConnectionResult.Failure(failure.category, failure.detail)
        }
        if (response.code !in 200..299) {
            return ConnectionResult.Failure(categoryFor(response.code), "probe_http_" + response.code)
        }
        val supported = runCatching {
            val choices = JSONObject(response.body).getJSONArray("choices")
            if (choices.length() != 1) return@runCatching false
            val message = choices.getJSONObject(0).getJSONObject("message")
            if (message.has("tool_calls") || message.has("function_call")) return@runCatching false
            val content = message.opt("content")
            content is String && content.trim() == "OK"
        }.getOrDefault(false)
        return if (supported) {
            ConnectionResult.Success(modelAvailable = modelAvailable)
        } else {
            ConnectionResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                "chat_text_response_unsupported",
            )
        }
    }

    internal fun buildStreamingRequestBody(
        request: VlmRequest,
    ): StreamingRequestBody {
        val json = buildRequestBody(
            request = request,
            imageUrl = "data:${request.mimeType};base64,$IMAGE_DATA_PLACEHOLDER",
        ).toString()
        val placeholderIndex = json.indexOf(IMAGE_DATA_PLACEHOLDER)
        check(placeholderIndex >= 0 && placeholderIndex == json.lastIndexOf(IMAGE_DATA_PLACEHOLDER))
        return StreamingRequestBody(
            prefix = json.substring(0, placeholderIndex).toByteArray(Charsets.UTF_8),
            imageBytes = request.imageBytes,
            suffix = json.substring(placeholderIndex + IMAGE_DATA_PLACEHOLDER.length)
                .toByteArray(Charsets.UTF_8),
        )
    }

    internal fun buildChatCompletionsRequestBody(request: VlmRequest): StreamingRequestBody {
        val imageUrl = "data:${request.mimeType};base64,$IMAGE_DATA_PLACEHOLDER"
        val prompt = systemPrompt.trimEnd() + "\n\n" + buildUserContext(request)
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject()
                                        .put("url", imageUrl)
                                        .put("detail", "high"),
                                ),
                        ),
                ),
        )
        val body = JSONObject()
            .put("model", request.model)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", 256)
            .apply {
                if (usesQwenThinkingControl(request.model)) put("enable_thinking", false)
            }
            .toString()
        val placeholderIndex = body.indexOf(IMAGE_DATA_PLACEHOLDER)
        check(placeholderIndex >= 0 && placeholderIndex == body.lastIndexOf(IMAGE_DATA_PLACEHOLDER))
        return StreamingRequestBody(
            prefix = body.substring(0, placeholderIndex).toByteArray(Charsets.UTF_8),
            imageBytes = request.imageBytes,
            suffix = body.substring(placeholderIndex + IMAGE_DATA_PLACEHOLDER.length)
                .toByteArray(Charsets.UTF_8),
        )
    }

    private fun buildRequestBody(
        request: VlmRequest,
        imageUrl: String,
    ): JSONObject {
        val userContext = buildUserContext(request)
        val content = JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", userContext))
            .put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", imageUrl)
                    .put("detail", "high"),
            )
        val input = JSONArray()
            .put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", request.model)
            .put("instructions", systemPrompt)
            .put("temperature", 0)
            .put("max_output_tokens", 256)
            .put("input", input)
        body.put(
            "text",
            JSONObject()
                .put(
                    "format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put("name", "ledger_capture_v1")
                        .put("strict", true)
                        .put("schema", captureSchema()),
                ),
        )
        return body
    }

    private fun buildUserContext(@Suppress("UNUSED_PARAMETER") request: VlmRequest): String =
        "只提取 is_history、amount_minor 和 expense_target。不要读取时间。只返回 ledger.capture.v1 JSON。\n/no_think"

    internal fun classifyChatCompletionsResponse(
        response: HttpResult,
        requestId: String,
        configuredModel: String,
        request: VlmRequest? = null,
    ): VlmAnalyzeResult {
        response.failure?.let { failure ->
            return VlmAnalyzeResult.Failure(
                category = failure.category,
                retryable = failure == HttpFailure.NETWORK,
                detail = failure.detail,
            )
        }
        if (response.code !in 200..299) {
            return VlmAnalyzeResult.Failure(
                category = categoryFor(response.code),
                retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                detail = "http_" + response.code,
                retryAfterMillis = response.retryAfterMillis,
            )
        }
        val content = runCatching {
            val choices = JSONObject(response.body).optJSONArray("choices")
                ?: error("missing_output")
            if (choices.length() != 1) {
                error(if (choices.length() > 1) "multiple_choices" else "missing_output")
            }
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: error("missing_output")
            val refusal = message.opt("refusal")
            if (refusal is String && refusal.isNotBlank()) error("refusal")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null) {
                error(if (toolCalls.length() > 1) "multiple_tool_calls" else "unexpected_tool_call")
            }
            if (message.has("function_call")) error("unexpected_tool_call")
            val content = message.opt("content")
            if (content !is String || content.isBlank()) error("missing_output")
            content
        }.getOrElse { error ->
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = outerResponseFailureDetail(error.message),
            )
        }
        return parseLedgerContent(
            content = content,
            requestId = requestId,
            configuredModel = configuredModel,
            request = request,
            allowOuterWhitespace = true,
        )
    }

    internal fun classifyAnalyzeResponse(
        response: HttpResult,
        requestId: String,
        configuredModel: String,
        request: VlmRequest? = null,
    ): VlmAnalyzeResult {
        response.failure?.let { failure ->
            return VlmAnalyzeResult.Failure(
                category = failure.category,
                retryable = failure == HttpFailure.NETWORK,
                detail = failure.detail,
            )
        }
        if (response.code !in 200..299) {
            return VlmAnalyzeResult.Failure(
                category = categoryFor(response.code),
                retryable = response.code == 408 || response.code == 429 || response.code >= 500,
                detail = "http_" + response.code,
                retryAfterMillis = response.retryAfterMillis,
            )
        }
        val content = runCatching {
            val root = JSONObject(response.body)
            val status = root.opt("status")
            if (status is String && status != "completed") error("response_not_completed")
            val output = root.optJSONArray("output")
            if (output == null) {
                val direct = root.opt("output_text")
                if (direct !is String || direct.isEmpty()) error("missing_output")
                direct
            } else {
                buildString {
                    if (output.length() == 0) error("empty_output")
                    for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i) ?: error("invalid_output_item")
                        when (item.opt("type")) {
                            "message" -> {
                                val parts = item.optJSONArray("content") ?: error("missing_content")
                                if (parts.length() == 0) error("empty_content")
                                for (j in 0 until parts.length()) {
                                    val part = parts.optJSONObject(j) ?: error("invalid_content_part")
                                    when (part.opt("type")) {
                                        "output_text" -> {
                                            val text = part.opt("text")
                                            if (text !is String || text.isEmpty()) error("invalid_output_text")
                                            append(text)
                                        }
                                        "refusal" -> error("refusal")
                                        else -> error("non_text_content")
                                    }
                                }
                            }
                            "function_call" -> error("unexpected_tool_call")
                            "refusal" -> error("refusal")
                            else -> error("non_message_output")
                        }
                    }
                }
            }
        }.getOrElse { error ->
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = outerResponseFailureDetail(error.message),
            )
        }
        return parseLedgerContent(
            content = content,
            requestId = requestId,
            configuredModel = configuredModel,
            request = request,
            allowOuterWhitespace = true,
        )
    }

    private fun parseLedgerContent(
        content: String,
        requestId: String,
        configuredModel: String,
        request: VlmRequest?,
        allowOuterWhitespace: Boolean,
    ): VlmAnalyzeResult {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = "response_too_large",
            )
        }
        val normalizedContent = if (allowOuterWhitespace) content.trim() else content
        if (normalizedContent.startsWith("\u0060\u0060\u0060") ||
            (!allowOuterWhitespace && normalizedContent.trim() != normalizedContent)
        ) {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = "response_not_strict_json",
            )
        }
        val parsed = if (runCatching {
                JSONObject(normalizedContent).optString("schema_version") == CAPTURE_SCHEMA_VERSION
            }.getOrDefault(false)
        ) {
            parseCapture(normalizedContent, request)
        } else {
            LedgerV1Parser.parse(normalizedContent)
        }
        return when (parsed) {
            is ParseResult.Valid -> VlmAnalyzeResult.Success(
                VlmResponse(
                    ledger = parsed.value,
                    requestId = requestId,
                    model = configuredModel,
                ),
            )
            is ParseResult.Invalid -> VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = if (parsed.reason == "invalid_json") {
                    classifyInvalidJsonShape(normalizedContent)
                } else {
                    parsed.reason
                },
            )
        }
    }

    private fun parseCapture(content: String, request: VlmRequest?): ParseResult {
        val context = request ?: return ParseResult.Invalid("missing_capture_context")
        val root = runCatching { JSONObject(content) }.getOrElse {
            return ParseResult.Invalid("invalid_json")
        }
        val keys = buildSet {
            val iterator = root.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (keys != CAPTURE_KEYS) return ParseResult.Invalid("root_keys")
        if (root.optString("schema_version") != CAPTURE_SCHEMA_VERSION) {
            return ParseResult.Invalid("schema_version")
        }
        val isHistory = root.opt("is_history") as? Boolean
            ?: return ParseResult.Invalid("is_history")
        val amount = when (val raw = root.opt("amount_minor")) {
            JSONObject.NULL -> null
            is Number -> raw.toString().takeIf { Regex("[1-9][0-9]*").matches(it) }
                ?.toLongOrNull()
                ?: return ParseResult.Invalid("amount_minor")
            else -> return ParseResult.Invalid("amount_minor")
        }
        val expenseTarget = when (val raw = root.opt("expense_target")) {
            JSONObject.NULL -> null
            is String -> raw.takeIf {
                it == it.trim() && it.length <= 120 && it.none(Char::isISOControl)
            } ?: return ParseResult.Invalid("expense_target")
            else -> return ParseResult.Invalid("expense_target")
        }
        val capturedAt = context.screenshotCapturedAt?.let {
            runCatching { OffsetDateTime.parse(it) }.getOrNull()
        }
        val platform = platformForPackage(context.sourcePackage)
        val canBook = !isHistory && amount != null && capturedAt != null && platform != Platform.UNKNOWN
        val reasonCode = when {
            isHistory -> "STALE_TRANSACTION"
            amount == null -> "MISSING_AMOUNT"
            capturedAt == null -> "MISSING_TIME"
            platform == Platform.UNKNOWN -> "UNSUPPORTED_PLATFORM"
            else -> "PAYMENT_PAGE_CONFIRMED"
        }
        val positive = if (canBook) {
            buildList {
                add("PAYMENT_SUCCESS")
                add("PLATFORM_MARKER")
                add("UNIQUE_AMOUNT")
                if (expenseTarget != null) add("MERCHANT_MARKER")
                add("FRESH_TIME")
            }
        } else {
            emptyList()
        }
        return ParseResult.Valid(
            LedgerV1(
                decision = when {
                    isHistory -> Decision.REJECT
                    canBook -> Decision.AUTO_BOOK
                    else -> Decision.NEEDS_CONFIRMATION
                },
                isPaymentScreenshot = !isHistory,
                platform = platform,
                direction = Direction.EXPENSE,
                amountMinor = amount,
                currency = amount?.let { "CNY" },
                merchant = expenseTarget,
                counterparty = null,
                occurredAt = capturedAt,
                timeSource = capturedAt?.let { TimeSource.SCREENSHOT_ESTIMATED },
                externalId = null,
                suggestedTag = null,
                confidence = if (canBook) 0.99 else 0.0,
                evidence = Evidence(
                    positiveFeatures = positive,
                    negativeFeatures = if (isHistory) listOf("HISTORY_DETAIL") else emptyList(),
                    freshness = when {
                        isHistory -> Freshness.STALE
                        capturedAt != null -> Freshness.VALID
                        else -> Freshness.UNKNOWN
                    },
                    reasonCode = reasonCode,
                ),
            ),
        )
    }

    private fun platformForPackage(sourcePackage: String?): Platform = when (sourcePackage?.lowercase()) {
        "com.tencent.mm" -> Platform.WECHAT
        "com.eg.android.alipaygphone" -> Platform.ALIPAY
        null -> Platform.UNKNOWN
        else -> Platform.OTHER
    }

    internal fun classifyInvalidJsonShape(content: String): String = when {
        content.startsWith('"') && content.endsWith('"') -> "invalid_json_quoted"
        !content.startsWith('{') -> "invalid_json_non_object"
        !content.endsWith('}') -> "invalid_json_truncated"
        else -> "invalid_json_syntax"
    }

    private fun outerResponseFailureDetail(reason: String?): String = when (reason) {
        "refusal" -> "response_refusal"
        "unexpected_tool_call" -> "unexpected_tool_call"
        "multiple_tool_calls", "multiple_choices" -> "multiple_tool_calls"
        "non_message_output" -> "unsupported_output_item"
        "non_text_content" -> "unsupported_content_part"
        "response_not_completed" -> "response_not_completed"
        "empty_output", "empty_content", "missing_output", "missing_content" -> "empty_response_output"
        else -> "invalid_outer_response"
    }

    private fun execute(
        endpoint: String,
        method: String,
        apiKey: String,
        body: String?,
        timeoutSeconds: Int,
        streamingBody: StreamingRequestBody? = null,
    ): HttpResult {
        require(body == null || streamingBody == null)
        val hasRequestBody = body != null || streamingBody != null
        val timeoutMillis = Duration.ofSeconds(timeoutSeconds.coerceIn(10, 120).toLong())
            .toMillis()
            .toInt()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            useCaches = false
            doInput = true
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer " + apiKey)
            setRequestProperty("Accept", "application/json")
            if (hasRequestBody) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                streamingBody?.let { setFixedLengthStreamingMode(it.contentLength) }
            }
        }
        return try {
            if (hasRequestBody) {
                connection.outputStream.use { output ->
                    if (streamingBody != null) {
                        streamingBody.writeTo(output)
                    } else {
                        output.write(checkNotNull(body).toByteArray(Charsets.UTF_8))
                    }
                }
            }
            val code = connection.responseCode
            val retryAfterMillis = if (code == 429) {
                parseRetryAfterMillis(connection.getHeaderField("Retry-After"))
            } else {
                null
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val maxBytes = if (code in 200..299) MAX_HTTP_RESPONSE_BYTES else MAX_ERROR_BODY_BYTES
            val read = stream?.let { readBounded(it, maxBytes) } ?: BoundedReadResult.Success("")
            httpResultFromRead(code, read, retryAfterMillis)
        } catch (_: Exception) {
            HttpResult(
                code = HTTP_NETWORK_ERROR,
                body = "",
                failure = HttpFailure.NETWORK,
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun normalizeEndpoint(
        baseUrl: String,
        protocol: VlmApiProtocol = VlmApiProtocol.RESPONSES,
    ): String {
        val input = baseUrl.trim().trimEnd('/')
        require(input.length <= MAX_BASE_URL_CHARS)
        val uri = URI(input)
        require(uri.scheme.equals("https", ignoreCase = true))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        val path = uri.path.orEmpty().trimEnd('/')
        require(path.isEmpty() || path == "/v1" || path.endsWith("/v1"))
        val apiPath = if (path.isEmpty()) "/v1" else path
        val resource = when (protocol) {
            VlmApiProtocol.RESPONSES -> "responses"
            VlmApiProtocol.CHAT_COMPLETIONS -> "chat/completions"
        }
        return URI("https", null, uri.host, uri.port, "$apiPath/$resource", null, null).toString()
    }

    internal fun readBounded(
        input: java.io.InputStream,
        maxBytes: Int = MAX_HTTP_RESPONSE_BYTES,
    ): BoundedReadResult {
        require(maxBytes >= 0)
        BufferedInputStream(input).use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val remaining = maxBytes - total
                if (count > remaining) {
                    if (remaining > 0) output.write(buffer, 0, remaining)
                    return BoundedReadResult.TooLarge(output.toString(Charsets.UTF_8.name()))
                }
                total += count
                output.write(buffer, 0, count)
            }
            return BoundedReadResult.Success(output.toString(Charsets.UTF_8.name()))
        }
    }

    internal fun httpResultFromRead(
        code: Int,
        read: BoundedReadResult,
        retryAfterMillis: Long? = null,
    ): HttpResult = when (read) {
        is BoundedReadResult.Success -> HttpResult(
            code = code,
            body = read.body,
            retryAfterMillis = retryAfterMillis,
        )
        is BoundedReadResult.TooLarge -> if (code in 200..299) {
            HttpResult(
                code = code,
                body = "",
                failure = HttpFailure.RESPONSE_TOO_LARGE,
            )
        } else {
            HttpResult(
                code = code,
                body = read.prefix,
                bodyTruncated = true,
                retryAfterMillis = retryAfterMillis,
            )
        }
    }

    internal fun parseRetryAfterMillis(
        value: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        normalized.toLongOrNull()?.takeIf { it >= 0L }?.let { seconds ->
            return (seconds.coerceAtMost(MAX_RETRY_AFTER_MILLIS / 1_000L) * 1_000L)
        }
        return runCatching {
            val retryAt = ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (retryAt - nowMillis).coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
        }.getOrNull()
    }

    private fun categoryFor(code: Int): FailureCategory = when {
        code == 401 || code == 403 -> FailureCategory.AUTHENTICATION
        code == 413 || code == 415 -> FailureCategory.UNSUPPORTED_IMAGE
        code == 408 -> FailureCategory.NETWORK
        code == 429 -> FailureCategory.RATE_LIMITED
        code >= 500 -> FailureCategory.SERVER
        code == HTTP_NETWORK_ERROR -> FailureCategory.NETWORK
        else -> FailureCategory.INVALID_RESPONSE
    }

    internal fun captureSchema(): JSONObject = JSONObject(
        """
        {
          "type":"object","additionalProperties":false,
          "required":["schema_version","is_history","amount_minor","expense_target"],
          "properties":{
            "schema_version":{"const":"ledger.capture.v1"},
            "is_history":{"type":"boolean"},
            "amount_minor":{"anyOf":[{"type":"integer","minimum":1},{"type":"null"}]},
            "expense_target":{"anyOf":[{"type":"string","maxLength":120},{"type":"null"}]}
          }
        }
        """.trimIndent(),
    )

    private companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_CONTENT_BYTES = 64 * 1024
        const val MAX_HTTP_RESPONSE_BYTES = 256 * 1024
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val HTTP_NETWORK_ERROR = -1
        const val MAX_BASE_URL_CHARS = 2_048
        const val MAX_RETRY_AFTER_MILLIS = 30_000L
        const val CAPTURE_SCHEMA_VERSION = "ledger.capture.v1"
        val CAPTURE_KEYS = setOf("schema_version", "is_history", "amount_minor", "expense_target")

        fun usesQwenThinkingControl(model: String): Boolean =
            model.substringAfterLast('/').startsWith("Qwen3", ignoreCase = true)
        const val IMAGE_DATA_PLACEHOLDER = "LEDGER_IMAGE_DATA_7F3A4B2D9C8E"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是截图记账字段提取器。只判断页面是否明确含历史记录字样，并提取唯一金额和支出对象；" +
                "不要读取或判断时间。只输出 ledger.capture.v1 JSON，不得输出解释、推理或 Markdown。"
    }
}

private fun isValidApiKey(apiKey: String): Boolean =
    apiKey.isNotBlank() && apiKey.length <= 4_096 && apiKey.none(Char::isISOControl)

private fun isValidModel(model: String): Boolean =
    Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$").matches(model)

private val HttpFailure.category: FailureCategory
    get() = when (this) {
        HttpFailure.NETWORK -> FailureCategory.NETWORK
        HttpFailure.RESPONSE_TOO_LARGE -> FailureCategory.INVALID_RESPONSE
    }

private val HttpFailure.detail: String
    get() = when (this) {
        HttpFailure.NETWORK -> "network_error"
        HttpFailure.RESPONSE_TOO_LARGE -> "response_too_large"
    }
