package com.vibratez.ledger.photo

import com.vibratez.ledger.ledger.LedgerDecision
import com.vibratez.ledger.ledger.LedgerDecisionEngine
import com.vibratez.ledger.ledger.LedgerStore
import com.vibratez.ledger.ledger.PhotoEvidence
import com.vibratez.ledger.security.AppSettings
import com.vibratez.ledger.security.SecureSettings
import com.vibratez.ledger.vlm.VlmAnalyzeResult
import com.vibratez.ledger.vlm.VlmRequest
import com.vibratez.ledger.vlm.OpenAiVlmClient
import com.vibratez.ledger.vlm.Decision
import com.vibratez.ledger.vlm.Direction
import com.vibratez.ledger.vlm.Evidence
import com.vibratez.ledger.vlm.FailureCategory
import com.vibratez.ledger.vlm.Freshness
import com.vibratez.ledger.vlm.LedgerV1
import com.vibratez.ledger.vlm.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

sealed interface PhotoProcessingResult {
    val candidate: ScreenshotCandidate

    data class Completed(
        override val candidate: ScreenshotCandidate,
        val sha256: String,
        val decision: LedgerDecision,
        val vlmRequestId: String? = null,
        val vlmModel: String? = null,
        val autoBookSaveState: AutoBookSaveState = AutoBookSaveState.NOT_APPLICABLE,
    ) : PhotoProcessingResult

    data class Failed(
        override val candidate: ScreenshotCandidate,
        val reason: String,
        val retryable: Boolean,
    ) : PhotoProcessingResult
}

enum class AutoBookSaveState {
    NOT_APPLICABLE,
    STORED,
    ALREADY_STORED,
    FAILED,
}

/**
 * Processes each candidate independently. The image byte array is cleared/released
 * as soon as the request finishes and is never persisted.
 */
class PhotoProcessor(
    private val repository: MediaStorePhotoRepository,
    private val settings: SecureSettings,
    private val client: OpenAiVlmClient = OpenAiVlmClient(),
    private val decisionEngine: LedgerDecisionEngine = LedgerDecisionEngine(),
    private val ledgerStore: LedgerStore,
) {
    suspend fun processRecent(): List<PhotoProcessingResult> = withContext(Dispatchers.IO) {
        val candidates = repository.recentScreenshots()
        processCandidatesInternal(candidates)
    }

    suspend fun processCandidates(candidates: List<ScreenshotCandidate>): List<PhotoProcessingResult> =
        withContext(Dispatchers.IO) {
            processCandidatesInternal(candidates)
        }

    private suspend fun processCandidatesInternal(
        candidates: List<ScreenshotCandidate>,
    ): List<PhotoProcessingResult> {
        return candidates.map { candidate ->
            processOne(candidate)
        }
    }

    private suspend fun processOne(
        candidate: ScreenshotCandidate,
    ): PhotoProcessingResult {
        if (!settings.load().cloudEnabled) {
            return PhotoProcessingResult.Failed(candidate, "cloud_disabled", retryable = false)
        }
        var bytes: ByteArray? = null
        return try {
            when (val readResult = repository.read(candidate)) {
                is PhotoReadResult.Failure -> {
                    PhotoProcessingResult.Failed(candidate, readResult.reason, retryable = false)
                }
                is PhotoReadResult.Success -> {
                    bytes = readResult.bytes
                    val hash = LedgerDecisionEngine.sha256(bytes!!)
                    if (ledgerStore.containsHash(hash)) {
                        PhotoProcessingResult.Completed(
                            candidate,
                            hash,
                            LedgerDecision.Duplicate,
                        )
                    } else {
                        // Re-read consent and credentials immediately before each upload so
                        // disabling cloud recognition stops the remainder of a batch.
                        val appSettings = settings.load()
                        val apiKey = settings.readApiKey()
                        if (!appSettings.cloudEnabled) {
                            return PhotoProcessingResult.Failed(
                                candidate,
                                "cloud_disabled",
                                retryable = false,
                            )
                        }
                        if (apiKey.isNullOrBlank() ||
                            appSettings.baseUrl.isBlank() ||
                            appSettings.model.isBlank()
                        ) {
                            return PhotoProcessingResult.Failed(
                                candidate,
                                "missing_configuration",
                                retryable = false,
                            )
                        }
                        var request = VlmRequest(
                            model = appSettings.model,
                            mimeType = candidate.mimeType,
                            imageBytes = bytes!!,
                            screenshotCapturedAt = candidate.capturedAtMillis?.let(::toRfc3339),
                            deviceTimezone = ZoneId.systemDefault().id,
                        )
                        var attempt = 0
                        var response = client.analyze(
                            baseUrl = appSettings.baseUrl,
                            apiKey = apiKey,
                            request = request,
                            timeoutSeconds = appSettings.timeoutSeconds,
                            allowAdditionalUpload = additionalUploadAllowed(
                                expectedSettings = appSettings,
                                expectedApiKey = apiKey,
                            ),
                        )
                        while (response is VlmAnalyzeResult.Failure &&
                            response.retryable &&
                            attempt == 0
                        ) {
                            attempt++
                            delay(response.retryAfterMillis ?: 1_000L)
                            val retrySettings = settings.load()
                            val retryApiKey = settings.readApiKey()
                            if (!retrySettings.cloudEnabled) {
                                return PhotoProcessingResult.Failed(
                                    candidate,
                                    "cloud_disabled",
                                    retryable = false,
                                )
                            }
                            if (retryApiKey.isNullOrBlank() ||
                                retrySettings.baseUrl.isBlank() ||
                                retrySettings.model.isBlank()
                            ) {
                                return PhotoProcessingResult.Failed(
                                    candidate,
                                    "missing_configuration",
                                    retryable = false,
                                )
                            }
                            request = request.copy(model = retrySettings.model)
                            response = client.analyze(
                                baseUrl = retrySettings.baseUrl,
                                apiKey = retryApiKey,
                                request = request,
                                timeoutSeconds = retrySettings.timeoutSeconds,
                                allowAdditionalUpload = additionalUploadAllowed(
                                    expectedSettings = retrySettings,
                                    expectedApiKey = retryApiKey,
                                ),
                            )
                        }
                        when (response) {
                            is VlmAnalyzeResult.Success -> {
                                val decision = decisionEngine.decide(
                                    response.response,
                                    PhotoEvidence(
                                        uri = candidate.uri.toString(),
                                        sha256 = hash,
                                        screenshotCapturedAtMillis = candidate.capturedAtMillis,
                                    ),
                                )
                                val saveState = if (decision is LedgerDecision.AutoBook) {
                                    persistAutoBook(
                                        decision = decision,
                                        candidate = candidate,
                                        sha256 = hash,
                                        vlmRequestId = response.response.requestId,
                                        vlmModel = response.response.model,
                                    )
                                } else {
                                    AutoBookSaveState.NOT_APPLICABLE
                                }
                                PhotoProcessingResult.Completed(
                                    candidate = candidate,
                                    sha256 = hash,
                                    decision = decision,
                                    vlmRequestId = response.response.requestId,
                                    vlmModel = response.response.model,
                                    autoBookSaveState = saveState,
                                )
                            }
                            is VlmAnalyzeResult.Failure -> {
                                if (response.category == FailureCategory.INVALID_RESPONSE) {
                                    PhotoProcessingResult.Completed(
                                        candidate = candidate,
                                        sha256 = hash,
                                        decision = LedgerDecision.NeedsConfirmation(
                                            ledger = emptyManualReviewLedger(),
                                            reason = "INVALID_RESPONSE",
                                        ),
                                        vlmModel = request.model,
                                    )
                                } else {
                                    PhotoProcessingResult.Failed(
                                        candidate,
                                        response.category.name.lowercase(),
                                        response.retryable,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            bytes?.fill(0)
        }
    }

    private fun toRfc3339(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    private fun additionalUploadAllowed(
        expectedSettings: AppSettings,
        expectedApiKey: String,
    ): () -> Boolean = {
        val current = settings.load()
        current.cloudEnabled &&
            current.baseUrl == expectedSettings.baseUrl &&
            current.model == expectedSettings.model &&
            current.timeoutSeconds == expectedSettings.timeoutSeconds &&
            settings.readApiKey() == expectedApiKey
    }

    private suspend fun persistAutoBook(
        decision: LedgerDecision.AutoBook,
        candidate: ScreenshotCandidate,
        sha256: String,
        vlmRequestId: String?,
        vlmModel: String?,
    ): AutoBookSaveState = try {
        val record = ledgerStore.addIfAbsent(
            ledger = decision.ledger,
            sha256 = sha256,
            sourceUri = candidate.uri.toString(),
            vlmModel = vlmModel,
            vlmRequestId = vlmRequestId,
            screenshotCapturedAtMillis = candidate.capturedAtMillis,
            localDecision = "AUTO_BOOK",
        )
        if (record == null) AutoBookSaveState.ALREADY_STORED else AutoBookSaveState.STORED
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        AutoBookSaveState.FAILED
    }

    private fun emptyManualReviewLedger(): LedgerV1 = LedgerV1(
        decision = Decision.NEEDS_CONFIRMATION,
        isPaymentScreenshot = false,
        platform = Platform.UNKNOWN,
        direction = Direction.UNKNOWN,
        amountMinor = null,
        currency = null,
        merchant = null,
        counterparty = null,
        occurredAt = null,
        timeSource = null,
        externalId = null,
        suggestedTag = null,
        confidence = 0.0,
        evidence = Evidence(
            positiveFeatures = emptyList(),
            negativeFeatures = emptyList(),
            freshness = Freshness.UNKNOWN,
            reasonCode = "INVALID_CONTEXT",
        ),
    )
}
