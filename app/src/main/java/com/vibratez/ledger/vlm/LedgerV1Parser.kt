package com.vibratez.ledger.vlm

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * Strictly validates the untrusted JSON returned by a VLM.
 * The local decision layer must not rely on the model's decision field alone.
 */
object LedgerV1Parser {
    private val rootKeys = setOf(
        "schema_version",
        "decision",
        "is_payment_screenshot",
        "platform",
        "direction",
        "amount_minor",
        "currency",
        "merchant",
        "counterparty",
        "occurred_at",
        "time_source",
        "external_id",
        "suggested_tag",
        "confidence",
        "evidence",
    )
    private val evidenceKeys = setOf("positive_features", "negative_features", "freshness", "reason_code")
    private val positiveFeatures = setOf(
        "PAYMENT_SUCCESS",
        "RECEIPT_SUCCESS",
        "REFUND_SUCCESS",
        "INCOME_RECEIVED",
        "PLATFORM_MARKER",
        "UNIQUE_AMOUNT",
        "MERCHANT_MARKER",
        "PAGE_EXACT_TIME",
        "NOTIFICATION_MATCH",
        "FRESH_TIME",
    )
    private val negativeFeatures = setOf(
        "CHAT_THREAD",
        "BILL_LIST",
        "HISTORY_DETAIL",
        "SHARE_POSTER",
        "IMAGE_PREVIEW",
        "SEARCH_RESULT",
        "MULTIPLE_TRANSACTIONS",
        "MULTIPLE_AMOUNTS",
        "PENDING_OR_FAILED",
        "NO_TRANSACTION_STATUS",
        "STALE_TIME",
        "CONFLICTING_FIELDS",
        "UNREADABLE",
        "NON_PAYMENT",
    )
    private val reasonCodes = setOf(
        "PAYMENT_PAGE_CONFIRMED",
        "REFUND_PAGE_CONFIRMED",
        "INCOME_PAGE_CONFIRMED",
        "NOT_PAYMENT_PAGE",
        "MULTIPLE_AMOUNTS",
        "MULTIPLE_TRANSACTIONS",
        "MISSING_AMOUNT",
        "MISSING_TIME",
        "STALE_TRANSACTION",
        "STRONG_NEGATIVE_FEATURE",
        "CONFLICTING_FIELDS",
        "LOW_CONFIDENCE",
        "UNSUPPORTED_PLATFORM",
        "PROCESSING_OR_FAILED",
        "UNREADABLE_IMAGE",
        "NOTIFICATION_MATCH",
        "INVALID_CONTEXT",
    )
    private val transactionSuccessFeatures = setOf(
        "PAYMENT_SUCCESS",
        "RECEIPT_SUCCESS",
        "REFUND_SUCCESS",
        "INCOME_RECEIVED",
    )
    private val strongNegativeFeatures = negativeFeatures

    fun parse(json: String): ParseResult {
        when (val preflightError = StrictJsonPreflight.validate(json)) {
            null -> Unit
            else -> return ParseResult.Invalid(preflightError)
        }
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return ParseResult.Invalid("invalid_json")
        }
        if (keysOf(root) != rootKeys) return ParseResult.Invalid("root_keys")
        if (requiredString(root, "schema_version") != "ledger.v1") {
            return ParseResult.Invalid("schema_version")
        }

        val decision = enumOrNull<Decision>(requiredString(root, "decision"))
            ?: return ParseResult.Invalid("decision")
        val isPayment = root.opt("is_payment_screenshot")
        if (isPayment !is Boolean) return ParseResult.Invalid("is_payment_screenshot")
        val platform = parsePlatform(requiredString(root, "platform"))
            ?: return ParseResult.Invalid("platform")
        val direction = parseDirection(requiredString(root, "direction"))
            ?: return ParseResult.Invalid("direction")

        val amountField = parseAmount(root.opt("amount_minor"))
        if (!amountField.valid) return ParseResult.Invalid("amount_minor")
        val amount = amountField.value
        val currencyField = nullableString(root, "currency", 3, 3, uppercase = true)
        if (!currencyField.valid) return ParseResult.Invalid("currency")
        val currency = currencyField.value
        if (currency != null && !isSupportedCurrency(currency)) {
            return ParseResult.Invalid("currency")
        }
        val merchantField = nullableString(root, "merchant", 0, 120)
        if (!merchantField.valid) return ParseResult.Invalid("merchant")
        val merchant = merchantField.value
        val counterpartyField = nullableString(root, "counterparty", 0, 120)
        if (!counterpartyField.valid) return ParseResult.Invalid("counterparty")
        val counterparty = counterpartyField.value
        val occurredAtField = nullableString(root, "occurred_at", 0, 40)
        if (!occurredAtField.valid) return ParseResult.Invalid("occurred_at")
        val occurredAtText = occurredAtField.value
        val occurredAt = occurredAtText?.let {
            try {
                OffsetDateTime.parse(it)
            } catch (_: DateTimeParseException) {
                return ParseResult.Invalid("occurred_at_format")
            }
        }
        val timeSourceField = parseNullableTimeSource(root.opt("time_source"))
        if (!timeSourceField.valid) return ParseResult.Invalid("time_source")
        val timeSource = timeSourceField.value
        val externalIdField = nullableString(root, "external_id", 0, 128)
        if (!externalIdField.valid) return ParseResult.Invalid("external_id")
        val externalId = externalIdField.value
        val suggestedTagField = nullableString(root, "suggested_tag", 0, 40)
        if (!suggestedTagField.valid) return ParseResult.Invalid("suggested_tag")
        val suggestedTag = suggestedTagField.value

        val confidence = root.opt("confidence")
        if (confidence !is Number || !confidence.toDouble().isFinite()) {
            return ParseResult.Invalid("confidence_type")
        }
        val confidenceValue = confidence.toDouble()
        if (confidenceValue !in 0.0..1.0) return ParseResult.Invalid("confidence_range")

        val evidenceObject = root.opt("evidence")
        if (evidenceObject !is JSONObject || keysOf(evidenceObject) != evidenceKeys) {
            return ParseResult.Invalid("evidence_keys")
        }
        val positive = parseFeatureArray(evidenceObject.opt("positive_features"), positiveFeatures)
            ?: return ParseResult.Invalid("positive_features")
        val negative = parseFeatureArray(evidenceObject.opt("negative_features"), negativeFeatures)
            ?: return ParseResult.Invalid("negative_features")
        val freshness = enumOrNull<Freshness>(requiredString(evidenceObject, "freshness"))
            ?: return ParseResult.Invalid("freshness")
        val reasonCode = requiredString(evidenceObject, "reason_code")
            ?: return ParseResult.Invalid("reason_code")
        if (reasonCode !in reasonCodes) return ParseResult.Invalid("reason_code")

        val value = LedgerV1(
            decision = decision,
            isPaymentScreenshot = isPayment,
            platform = platform,
            direction = direction,
            amountMinor = amount,
            currency = currency,
            merchant = merchant,
            counterparty = counterparty,
            occurredAt = occurredAt,
            timeSource = timeSource,
            externalId = externalId,
            suggestedTag = suggestedTag,
            confidence = confidenceValue,
            evidence = Evidence(positive, negative, freshness, reasonCode),
        )
        return validateInvariants(value)
    }

    private fun validateInvariants(value: LedgerV1): ParseResult {
        if (value.amountMinor != null && value.amountMinor <= 0L) {
            return ParseResult.Invalid("amount_not_positive")
        }
        if ((value.occurredAt == null) != (value.timeSource == null)) {
            return ParseResult.Invalid("time_source_consistency")
        }
        if (value.timeSource == TimeSource.STATEMENT_VERIFIED ||
            value.timeSource == TimeSource.USER_CONFIRMED
        ) {
            return ParseResult.Invalid("vlm_time_source")
        }
        val positive = value.evidence.positiveFeatures
        val negative = value.evidence.negativeFeatures
        val hasPageTime = "PAGE_EXACT_TIME" in positive
        val hasNotificationTime = "NOTIFICATION_MATCH" in positive
        when (value.timeSource) {
            TimeSource.PAGE_EXACT -> if (!hasPageTime) {
                return ParseResult.Invalid("time_evidence_consistency")
            }
            TimeSource.NOTIFICATION_MATCHED -> if (!hasNotificationTime) {
                return ParseResult.Invalid("time_evidence_consistency")
            }
            TimeSource.SCREENSHOT_ESTIMATED -> if (hasPageTime || hasNotificationTime) {
                return ParseResult.Invalid("time_evidence_consistency")
            }
            null -> if (hasPageTime || hasNotificationTime ||
                value.evidence.freshness == Freshness.VALID
            ) {
                return ParseResult.Invalid("time_evidence_consistency")
            }
            TimeSource.STATEMENT_VERIFIED,
            TimeSource.USER_CONFIRMED,
            -> return ParseResult.Invalid("vlm_time_source")
        }
        if ("STALE_TIME" in negative && value.evidence.freshness != Freshness.STALE) {
            return ParseResult.Invalid("freshness_evidence_consistency")
        }
        if (value.evidence.freshness == Freshness.STALE && "STALE_TIME" !in negative) {
            return ParseResult.Invalid("freshness_evidence_consistency")
        }
        if (value.amountMinor == null && "UNIQUE_AMOUNT" in positive) {
            return ParseResult.Invalid("amount_evidence_consistency")
        }
        if (("MULTIPLE_AMOUNTS" in negative || value.evidence.reasonCode == "MULTIPLE_AMOUNTS") &&
            (value.amountMinor != null || "UNIQUE_AMOUNT" in positive)
        ) {
            return ParseResult.Invalid("amount_evidence_consistency")
        }
        if (value.evidence.reasonCode == "MISSING_AMOUNT" &&
            (value.amountMinor != null || "UNIQUE_AMOUNT" in positive)
        ) {
            return ParseResult.Invalid("amount_evidence_consistency")
        }
        if (value.decision == Decision.REJECT &&
            value.isPaymentScreenshot &&
            negative.none { it in strongNegativeFeatures }
        ) {
            return ParseResult.Invalid("reject_invariants")
        }
        if (value.decision == Decision.AUTO_BOOK) {
            val successFeatures = when (value.direction) {
                Direction.EXPENSE -> setOf("PAYMENT_SUCCESS")
                Direction.INCOME -> setOf("RECEIPT_SUCCESS", "INCOME_RECEIVED")
                Direction.REFUND -> setOf("REFUND_SUCCESS")
                Direction.UNKNOWN -> emptySet()
            }
            val timeEvidenceMatchesSource = when (value.timeSource) {
                TimeSource.PAGE_EXACT -> "PAGE_EXACT_TIME" in value.evidence.positiveFeatures
                TimeSource.NOTIFICATION_MATCHED -> "NOTIFICATION_MATCH" in value.evidence.positiveFeatures
                TimeSource.SCREENSHOT_ESTIMATED -> isImmediateNativePaymentSuccess(value, positive)
                else -> false
            }
            val presentSuccessFeatures = positive.filter { it in transactionSuccessFeatures }
            val reasonMatchesDirection = when (value.direction) {
                Direction.EXPENSE -> value.evidence.reasonCode == "PAYMENT_PAGE_CONFIRMED" ||
                    (value.evidence.reasonCode == "NOTIFICATION_MATCH" &&
                        value.timeSource == TimeSource.NOTIFICATION_MATCHED)
                Direction.INCOME -> value.evidence.reasonCode == "INCOME_PAGE_CONFIRMED" ||
                    (value.evidence.reasonCode == "NOTIFICATION_MATCH" &&
                        value.timeSource == TimeSource.NOTIFICATION_MATCHED)
                Direction.REFUND -> value.evidence.reasonCode == "REFUND_PAGE_CONFIRMED" ||
                    (value.evidence.reasonCode == "NOTIFICATION_MATCH" &&
                        value.timeSource == TimeSource.NOTIFICATION_MATCHED)
                Direction.UNKNOWN -> false
            }
            val required = successFeatures.isNotEmpty() &&
                value.isPaymentScreenshot &&
                value.platform != Platform.UNKNOWN &&
                value.direction != Direction.UNKNOWN &&
                value.amountMinor != null &&
                value.currency != null &&
                value.occurredAt != null &&
                value.timeSource != null &&
                value.confidence >= 0.90 &&
                value.evidence.freshness == Freshness.VALID &&
                negative.isEmpty() &&
                "PLATFORM_MARKER" in positive &&
                "UNIQUE_AMOUNT" in positive &&
                presentSuccessFeatures.isNotEmpty() &&
                presentSuccessFeatures.all { it in successFeatures } &&
                timeEvidenceMatchesSource &&
                reasonMatchesDirection
            if (!required) return ParseResult.Invalid("auto_book_invariants")
        }
        if (value.timeSource == TimeSource.SCREENSHOT_ESTIMATED) {
            val immediateNativePaymentSuccess = value.decision == Decision.AUTO_BOOK &&
                isImmediateNativePaymentSuccess(value, positive)
            val conservativeConfirmation = value.decision == Decision.NEEDS_CONFIRMATION &&
                value.evidence.freshness != Freshness.VALID &&
                value.evidence.reasonCode == "MISSING_TIME"
            if (!immediateNativePaymentSuccess && !conservativeConfirmation) {
                return ParseResult.Invalid("estimated_time_invariants")
            }
        }
        when (value.evidence.reasonCode) {
            "MISSING_TIME" -> if (
                value.decision != Decision.NEEDS_CONFIRMATION ||
                value.timeSource == TimeSource.PAGE_EXACT ||
                value.timeSource == TimeSource.NOTIFICATION_MATCHED ||
                hasPageTime ||
                hasNotificationTime
            ) {
                return ParseResult.Invalid("reason_code_consistency")
            }
            "STALE_TRANSACTION" -> if (
                value.decision == Decision.AUTO_BOOK ||
                value.evidence.freshness != Freshness.STALE ||
                "STALE_TIME" !in negative
            ) {
                return ParseResult.Invalid("reason_code_consistency")
            }
            "LOW_CONFIDENCE" -> if (
                value.decision == Decision.AUTO_BOOK || value.confidence >= 0.90
            ) {
                return ParseResult.Invalid("reason_code_consistency")
            }
        }
        return ParseResult.Valid(value)
    }

    private fun isImmediateNativePaymentSuccess(value: LedgerV1, positive: List<String>): Boolean =
        value.platform != Platform.UNKNOWN &&
            value.direction in setOf(Direction.EXPENSE, Direction.INCOME, Direction.REFUND) &&
            (value.merchant != null || value.counterparty != null) &&
            value.evidence.freshness == Freshness.VALID &&
            value.evidence.reasonCode in setOf(
                "PAYMENT_PAGE_CONFIRMED",
                "INCOME_PAGE_CONFIRMED",
                "REFUND_PAGE_CONFIRMED",
            ) &&
            setOf(
                "PLATFORM_MARKER",
                "UNIQUE_AMOUNT",
                "MERCHANT_MARKER",
                "FRESH_TIME",
            ).all { it in positive } &&
            positive.any { it in transactionSuccessFeatures }

    private data class NullableField<T>(val valid: Boolean, val value: T?)

    private fun parseAmount(raw: Any?): NullableField<Long?> {
        if (raw === JSONObject.NULL) return NullableField(true, null)
        if (raw !is Number) return NullableField(false, null)
        val text = raw.toString()
        if (!Regex("0|[1-9][0-9]*").matches(text)) return NullableField(false, null)
        val value = text.toLongOrNull()?.takeIf { it >= 0L }
        return NullableField(value != null, value)
    }

    private fun nullableString(
        objectValue: JSONObject,
        key: String,
        minLength: Int,
        maxLength: Int,
        uppercase: Boolean = false,
    ): NullableField<String?> {
        val raw = objectValue.opt(key)
        if (raw === JSONObject.NULL) return NullableField(true, null)
        if (raw !is String) return NullableField(false, null)
        if (raw != raw.trim() || raw.length !in minLength..maxLength) {
            return NullableField(false, null)
        }
        if (raw.any { it.isISOControl() || it == '\n' || it == '\r' }) {
            return NullableField(false, null)
        }
        if (uppercase && !Regex("[A-Z]{3}").matches(raw)) {
            return NullableField(false, null)
        }
        return NullableField(true, raw)
    }

    private fun parseFeatureArray(raw: Any?, allowed: Set<String>): List<String>? {
        if (raw !is JSONArray || raw.length() > 12) return null
        val result = mutableListOf<String>()
        for (index in 0 until raw.length()) {
            val item = raw.opt(index)
            if (item !is String || item !in allowed || item in result) return null
            result += item
        }
        return result
    }

    private fun isSupportedCurrency(currency: String): Boolean = runCatching {
        Currency.getInstance(currency).defaultFractionDigits in 0..6
    }.getOrDefault(false)

    private fun parsePlatform(value: String?): Platform? = when (value) {
        "WECHAT" -> Platform.WECHAT
        "ALIPAY" -> Platform.ALIPAY
        "OTHER" -> Platform.OTHER
        "UNKNOWN" -> Platform.UNKNOWN
        else -> null
    }

    private fun parseDirection(value: String?): Direction? = when (value) {
        "EXPENSE" -> Direction.EXPENSE
        "INCOME" -> Direction.INCOME
        "REFUND" -> Direction.REFUND
        "UNKNOWN" -> Direction.UNKNOWN
        else -> null
    }

    private fun parseNullableTimeSource(raw: Any?): NullableField<TimeSource?> {
        if (raw === JSONObject.NULL) return NullableField(true, null)
        if (raw !is String) return NullableField(false, null)
        val value = when (raw) {
            "PAGE_EXACT" -> TimeSource.PAGE_EXACT
            "NOTIFICATION_MATCHED" -> TimeSource.NOTIFICATION_MATCHED
            "SCREENSHOT_ESTIMATED" -> TimeSource.SCREENSHOT_ESTIMATED
            "STATEMENT_VERIFIED" -> TimeSource.STATEMENT_VERIFIED
            "USER_CONFIRMED" -> TimeSource.USER_CONFIRMED
            else -> null
        }
        return NullableField(value != null, value)
    }

    private fun requiredString(objectValue: JSONObject, key: String): String? =
        objectValue.opt(key).let { value -> if (value is String) value else null }

    private fun keysOf(objectValue: JSONObject): Set<String> = buildSet {
        val iterator = objectValue.keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
        value?.let { name -> enumValues<T>().firstOrNull { it.name == name } }

    /** JSONObject keeps the last duplicate key, so reject duplicates before parsing. */
    private object StrictJsonPreflight {
        fun validate(json: String): String? = try {
            Scanner(json).validate()
            null
        } catch (_: DuplicateKeyException) {
            "duplicate_keys"
        } catch (_: IllegalArgumentException) {
            "invalid_json"
        }

        private class DuplicateKeyException : IllegalArgumentException()

        private class Scanner(private val source: String) {
            private var index = 0

            fun validate() {
                skipWhitespace()
                parseValue(depth = 0)
                skipWhitespace()
                require(index == source.length)
            }

            private fun parseValue(depth: Int) {
                require(depth <= MAX_DEPTH)
                skipWhitespace()
                require(index < source.length)
                when (source[index]) {
                    '{' -> parseObject(depth + 1)
                    '[' -> parseArray(depth + 1)
                    '"' -> parseString()
                    else -> parsePrimitive()
                }
            }

            private fun parseObject(depth: Int) {
                index++
                skipWhitespace()
                if (consume('}')) return
                val keys = mutableSetOf<String>()
                while (true) {
                    skipWhitespace()
                    require(index < source.length && source[index] == '"')
                    if (!keys.add(parseString())) throw DuplicateKeyException()
                    skipWhitespace()
                    require(consume(':'))
                    parseValue(depth)
                    skipWhitespace()
                    if (consume('}')) return
                    require(consume(','))
                }
            }

            private fun parseArray(depth: Int) {
                index++
                skipWhitespace()
                if (consume(']')) return
                while (true) {
                    parseValue(depth)
                    skipWhitespace()
                    if (consume(']')) return
                    require(consume(','))
                }
            }

            private fun parseString(): String {
                require(consume('"'))
                val result = StringBuilder()
                while (index < source.length) {
                    val character = source[index++]
                    when {
                        character == '"' -> return result.toString()
                        character == '\\' -> result.append(parseEscape())
                        character.code < 0x20 -> throw IllegalArgumentException()
                        else -> result.append(character)
                    }
                }
                throw IllegalArgumentException()
            }

            private fun parseEscape(): Char {
                require(index < source.length)
                return when (val escaped = source[index++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(index + 4 <= source.length)
                        val value = source.substring(index, index + 4).toIntOrNull(16)
                            ?: throw IllegalArgumentException()
                        index += 4
                        value.toChar()
                    }
                    else -> throw IllegalArgumentException()
                }
            }

            private fun parsePrimitive() {
                when (source.getOrNull(index)) {
                    't' -> consumeLiteral("true")
                    'f' -> consumeLiteral("false")
                    'n' -> consumeLiteral("null")
                    else -> parseNumber()
                }
            }

            private fun consumeLiteral(literal: String) {
                require(source.regionMatches(index, literal, 0, literal.length))
                index += literal.length
            }

            private fun parseNumber() {
                consume('-')
                when (source.getOrNull(index)) {
                    '0' -> {
                        index++
                        require(source.getOrNull(index)?.isDigit() != true)
                    }
                    else -> {
                        require(source.getOrNull(index)?.let { it in '1'..'9' } == true)
                        index++
                        while (source.getOrNull(index)?.isDigit() == true) index++
                    }
                }
                if (consume('.')) {
                    require(source.getOrNull(index)?.isDigit() == true)
                    while (source.getOrNull(index)?.isDigit() == true) index++
                }
                if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
                    index++
                    if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
                    require(source.getOrNull(index)?.isDigit() == true)
                    while (source.getOrNull(index)?.isDigit() == true) index++
                }
            }

            private fun consume(expected: Char): Boolean {
                if (index >= source.length || source[index] != expected) return false
                index++
                return true
            }

            private fun skipWhitespace() {
                while (source.getOrNull(index) in JSON_WHITESPACE) index++
            }

            private companion object {
                const val MAX_DEPTH = 64
                val JSON_WHITESPACE = setOf(' ', '\t', '\n', '\r')
            }
        }
    }
}
