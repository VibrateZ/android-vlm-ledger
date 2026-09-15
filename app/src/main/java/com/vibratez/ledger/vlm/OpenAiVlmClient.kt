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
        allowAdditionalUpload: () -> Boolean = { true },
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
        val endpoint = runCatching { normalizeEndpoint(baseUrl) }.getOrElse {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_CONFIGURATION,
                retryable = false,
                detail = "invalid_base_url",
            )
        }
        val requestId = UUID.randomUUID().toString()
        val first = execute(
            endpoint = endpoint,
            method = "POST",
            apiKey = apiKey,
            body = null,
            timeoutSeconds = timeoutSeconds,
            streamingBody = buildStreamingRequestBody(request, includeJsonSchema = true),
        )
        val result = classifyAnalyzeResponse(first, requestId, request.model)
        if (result is VlmAnalyzeResult.Failure &&
            first.failure == null &&
            !first.bodyTruncated &&
            first.code == HTTP_BAD_REQUEST &&
            explicitlyRejectsStructuredOutput(first.body) &&
            runCatching(allowAdditionalUpload).getOrDefault(false)
        ) {
            val fallback = execute(
                endpoint = endpoint,
                method = "POST",
                apiKey = apiKey,
                body = null,
                timeoutSeconds = timeoutSeconds,
                streamingBody = buildStreamingRequestBody(request, includeJsonSchema = false),
            )
            return classifyAnalyzeResponse(fallback, requestId, request.model)
        }
        return result
    }

    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutSeconds: Int,
    ): ConnectionResult {
        if (!isValidApiKey(apiKey) || !isValidModel(model)) {
            return ConnectionResult.Failure(FailureCategory.INVALID_CONFIGURATION, "missing_configuration")
        }
        val endpoint = runCatching { normalizeEndpoint(baseUrl) }.getOrElse {
            return ConnectionResult.Failure(FailureCategory.INVALID_CONFIGURATION, "invalid_base_url")
        }
        val modelsEndpoint = endpoint.removeSuffix("/chat/completions") + "/models"
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
            return ConnectionResult.Success(modelAvailable = available)
        }
        if (response.code == 404 || response.code == 405) {
            val probe = execute(
                endpoint = endpoint,
                method = "POST",
                apiKey = apiKey,
                body = JSONObject()
                    .put("model", model)
                    .put("temperature", 0)
                    .put("max_tokens", 8)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", "只返回 OK"),
                        ),
                    )
                    .toString(),
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

    internal fun buildStreamingRequestBody(
        request: VlmRequest,
        includeJsonSchema: Boolean,
    ): StreamingRequestBody {
        val json = buildRequestBody(
            request = request,
            includeJsonSchema = includeJsonSchema,
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

    private fun buildRequestBody(
        request: VlmRequest,
        includeJsonSchema: Boolean,
        imageUrl: String,
    ): JSONObject {
        val userContext = buildString {
            append("请仅分析下面这一张图片，并严格遵守系统 Prompt。\n")
            append("截图元数据（只能用于判断新鲜度）：\n")
            append("- screenshot_captured_at: ")
            append(request.screenshotCapturedAt ?: "null")
            append("\n- device_timezone: ")
            append(request.deviceTimezone)
            append("\n- freshness_window_minutes: 30\n")
            append("- notification_match: ")
            append(request.notificationMatchJson ?: "null")
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", userContext))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", imageUrl)
                            .put("detail", "high"),
                    ),
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", request.model)
            .put("temperature", 0)
            .put("top_p", 1)
            .put("max_tokens", 900)
            .put("messages", messages)
        if (includeJsonSchema) {
            body.put(
                "response_format",
                JSONObject()
                    .put("type", "json_schema")
                    .put(
                        "json_schema",
                        JSONObject()
                            .put("name", "ledger_v1")
                            .put("strict", true)
                            .put("schema", ledgerSchema()),
                    ),
            )
        } else {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return body
    }

    internal fun classifyAnalyzeResponse(
        response: HttpResult,
        requestId: String,
        configuredModel: String,
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
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
                ?: error("missing_choice")
            val message = choice.optJSONObject("message") ?: error("missing_message")
            if (message.has("refusal") && !message.isNull("refusal")) error("refusal")
            if (message.has("tool_calls") && !message.isNull("tool_calls")) error("tool_call")
            val raw = message.opt("content")
            when (raw) {
                is String -> raw
                is JSONArray -> buildString {
                    if (raw.length() == 0) error("empty_content")
                    for (i in 0 until raw.length()) {
                        val part = raw.optJSONObject(i) ?: error("non_text_content")
                        if (part.opt("type") != "text") error("non_text_content")
                        val text = part.opt("text")
                        if (text !is String) error("non_text_content")
                        append(text)
                    }
                }
                else -> error("invalid_content")
            }
        }.getOrElse {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = "invalid_outer_response",
            )
        }
        if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = "response_too_large",
            )
        }
        if (content.startsWith("\u0060\u0060\u0060") ||
            content.trim() != content
        ) {
            return VlmAnalyzeResult.Failure(
                FailureCategory.INVALID_RESPONSE,
                retryable = false,
                detail = "response_not_strict_json",
            )
        }
        return when (val parsed = LedgerV1Parser.parse(content)) {
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
                detail = parsed.reason,
            )
        }
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

    private fun normalizeEndpoint(baseUrl: String): String {
        val input = baseUrl.trim().trimEnd('/')
        require(input.length <= MAX_BASE_URL_CHARS)
        val uri = URI(input)
        require(uri.scheme.equals("https", ignoreCase = true))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        val path = uri.path.orEmpty().trimEnd('/')
        require(path.isEmpty() || path == "/v1" || path.endsWith("/v1"))
        val apiPath = if (path.isEmpty()) "/v1" else path
        return URI("https", null, uri.host, uri.port, "$apiPath/chat/completions", null, null).toString()
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

    internal fun explicitlyRejectsStructuredOutput(body: String): Boolean {
        val normalized = body.lowercase()
        val namesStructuredOutput = "response_format" in normalized || "json_schema" in normalized
        val explicitlyUnsupported = listOf(
            "not supported",
            "does not support",
            "unsupported",
            "unknown parameter",
            "unrecognized parameter",
            "not implemented",
        ).any { it in normalized }
        return namesStructuredOutput && explicitlyUnsupported
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

    internal fun ledgerSchema(): JSONObject = JSONObject(
        """
        {
          "type":"object","additionalProperties":false,
          "required":["schema_version","decision","is_payment_screenshot","platform","direction","amount_minor","currency","merchant","counterparty","occurred_at","time_source","external_id","suggested_tag","confidence","evidence"],
          "properties":{
            "schema_version":{"const":"ledger.v1"},
            "decision":{"enum":["AUTO_BOOK","NEEDS_CONFIRMATION","REJECT"]},
            "is_payment_screenshot":{"type":"boolean"},
            "platform":{"enum":["WECHAT","ALIPAY","OTHER","UNKNOWN"]},
            "direction":{"enum":["EXPENSE","INCOME","REFUND","UNKNOWN"]},
            "amount_minor":{"anyOf":[{"type":"integer","minimum":1},{"type":"null"}]},
            "currency":{"anyOf":[{"type":"string","pattern":"^[A-Z]{3}$"},{"type":"null"}]},
            "merchant":{"anyOf":[{"type":"string","maxLength":120},{"type":"null"}]},
            "counterparty":{"anyOf":[{"type":"string","maxLength":120},{"type":"null"}]},
            "occurred_at":{"anyOf":[{"type":"string","format":"date-time","maxLength":40},{"type":"null"}]},
            "time_source":{"anyOf":[{"enum":["PAGE_EXACT","NOTIFICATION_MATCHED","SCREENSHOT_ESTIMATED"]},{"type":"null"}]},
            "external_id":{"anyOf":[{"type":"string","maxLength":128},{"type":"null"}]},
            "suggested_tag":{"anyOf":[{"type":"string","maxLength":40},{"type":"null"}]},
            "confidence":{"type":"number","minimum":0,"maximum":1},
            "evidence":{
              "type":"object","additionalProperties":false,
              "required":["positive_features","negative_features","freshness","reason_code"],
              "properties":{
                "positive_features":{"type":"array","uniqueItems":true,"maxItems":12,"items":{"enum":["PAYMENT_SUCCESS","RECEIPT_SUCCESS","REFUND_SUCCESS","INCOME_RECEIVED","PLATFORM_MARKER","UNIQUE_AMOUNT","MERCHANT_MARKER","PAGE_EXACT_TIME","NOTIFICATION_MATCH","FRESH_TIME"]}},
                "negative_features":{"type":"array","uniqueItems":true,"maxItems":12,"items":{"enum":["CHAT_THREAD","BILL_LIST","HISTORY_DETAIL","SHARE_POSTER","IMAGE_PREVIEW","SEARCH_RESULT","MULTIPLE_TRANSACTIONS","MULTIPLE_AMOUNTS","PENDING_OR_FAILED","NO_TRANSACTION_STATUS","STALE_TIME","CONFLICTING_FIELDS","UNREADABLE","NON_PAYMENT"]}},
                "freshness":{"enum":["VALID","STALE","UNKNOWN"]},
                "reason_code":{"enum":["PAYMENT_PAGE_CONFIRMED","REFUND_PAGE_CONFIRMED","INCOME_PAGE_CONFIRMED","NOT_PAYMENT_PAGE","MULTIPLE_AMOUNTS","MULTIPLE_TRANSACTIONS","MISSING_AMOUNT","MISSING_TIME","STALE_TRANSACTION","STRONG_NEGATIVE_FEATURE","CONFLICTING_FIELDS","LOW_CONFIDENCE","UNSUPPORTED_PLATFORM","PROCESSING_OR_FAILED","UNREADABLE_IMAGE","NOTIFICATION_MATCH","INVALID_CONTEXT"]}
              }
            }
          }
        }
        """.trimIndent(),
    )

    private companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_CONTENT_BYTES = 64 * 1024
        const val MAX_HTTP_RESPONSE_BYTES = 256 * 1024
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NETWORK_ERROR = -1
        const val MAX_BASE_URL_CHARS = 2_048
        const val MAX_RETRY_AFTER_MILLIS = 30_000L
        const val IMAGE_DATA_PLACEHOLDER = "LEDGER_IMAGE_DATA_7F3A4B2D9C8E"
        const val DEFAULT_SYSTEM_PROMPT =
            "你是 Ledger VLM。严格按照 ledger.v1 只输出一个 JSON 对象；" +
                "未知字段使用 null 或 UNKNOWN，禁止 Markdown、解释、完整 OCR 文本和猜测。" +
                "必须判断支付成功/退款/收入页面，排除聊天、账单列表、历史详情、分享海报和图片预览，" +
                "并返回 decision、is_payment_screenshot、platform、direction、amount_minor、currency、" +
                "merchant、counterparty、occurred_at、time_source、external_id、suggested_tag、confidence、evidence。" +
                "只有成功状态、金额唯一、时间新鲜、无负特征且 confidence>=0.90 才可建议 AUTO_BOOK。"
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
