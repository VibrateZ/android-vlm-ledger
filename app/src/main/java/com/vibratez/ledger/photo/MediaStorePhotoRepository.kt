package com.vibratez.ledger.photo

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.Closeable

data class ScreenshotCandidate(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val capturedAtMillis: Long?,
    val width: Int?,
    val height: Int?,
)

sealed interface PhotoReadResult {
    data class Success(val bytes: ByteArray) : PhotoReadResult
    data class Failure(val reason: String) : PhotoReadResult
}

/**
 * Queries only recent, completed images and reads them directly from their MediaStore URI.
 * No application-owned image copy is created.
 */
class MediaStorePhotoRepository(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun observeChanges(onChange: () -> Unit): Closeable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange()
            }
        }
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        return Closeable { resolver.unregisterContentObserver(observer) }
    }

    fun recentScreenshots(
        nowMillis: Long = System.currentTimeMillis(),
        windowMinutes: Long = 30,
        limit: Int = 20,
    ): List<ScreenshotCandidate> {
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
            }
        }.toTypedArray()
        val minimumAddedSeconds =
            (nowMillis - windowMinutes * 60_000L).coerceAtLeast(0L) / 1_000L
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.IS_PENDING} = 0 AND " +
                "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        } else {
            "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        }
        val result = mutableListOf<ScreenshotCandidate>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(minimumAddedSeconds.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            while (cursor.moveToNext() && result.size < limit.coerceAtLeast(1)) {
                val name = cursor.getString(nameColumn).orEmpty()
                val relativePath = if (relativePathColumn >= 0) {
                    cursor.getString(relativePathColumn).orEmpty()
                } else {
                    ""
                }
                if (!isScreenshotName(name, relativePath)) continue
                val mime = cursor.getString(mimeColumn).orEmpty()
                if (!isSupportedMimeType(mime)) continue
                val taken = cursor.getLong(takenColumn).takeIf { it > 0L }
                val added = cursor.getLong(addedColumn).takeIf { it > 0L }?.times(1_000L)
                val candidate = ScreenshotCandidate(
                    uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn).toString(),
                    ),
                    displayName = name,
                    mimeType = mime,
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    capturedAtMillis = taken ?: added,
                    width = cursor.getInt(widthColumn).takeIf { it > 0 },
                    height = cursor.getInt(heightColumn).takeIf { it > 0 },
                )
                if (validateMetadata(candidate) == null) result += candidate
            }
        }
        return result
    }

    fun read(candidate: ScreenshotCandidate, maxBytes: Int = MAX_IMAGE_BYTES): PhotoReadResult {
        validateMetadata(candidate, maxBytes)?.let { return PhotoReadResult.Failure(it) }
        return try {
            resolver.openInputStream(candidate.uri)?.use { input ->
                val output = ByteArrayOutputStream(
                    candidate.sizeBytes.coerceIn(0L, 256 * 1024L).toInt(),
                )
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) return PhotoReadResult.Failure("image_over_limit")
                    output.write(buffer, 0, count)
                }
                PhotoReadResult.Success(output.toByteArray())
            } ?: PhotoReadResult.Failure("uri_not_readable")
        } catch (_: SecurityException) {
            PhotoReadResult.Failure("permission_denied")
        } catch (_: Exception) {
            PhotoReadResult.Failure("read_failed")
        }
    }

    fun candidateFromUri(uri: Uri): ScreenshotCandidate? {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        return try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString).orEmpty()
                val mime = mimeIndex.takeIf { it >= 0 }?.let(cursor::getString)
                    ?: resolver.getType(uri)
                    ?: return@use null
                val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val takenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val addedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val taken = takenIndex.takeIf { it >= 0 }?.let(cursor::getLong)?.takeIf { it > 0L }
                val added = addedIndex.takeIf { it >= 0 }?.let(cursor::getLong)
                    ?.takeIf { it > 0L }?.times(1_000L)
                ScreenshotCandidate(
                    uri = uri,
                    displayName = name.ifBlank { uri.lastPathSegment.orEmpty() },
                    mimeType = mime,
                    sizeBytes = sizeIndex.takeIf { it >= 0 }?.let(cursor::getLong)
                        ?.coerceAtLeast(0L) ?: 0L,
                    capturedAtMillis = taken ?: added,
                    width = widthIndex.takeIf { it >= 0 }?.let(cursor::getInt)?.takeIf { it > 0 },
                    height = heightIndex.takeIf { it >= 0 }?.let(cursor::getInt)?.takeIf { it > 0 },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isScreenshotName(name: String, relativePath: String): Boolean {
        val value = "$name/$relativePath".lowercase()
        return SCREENSHOT_MARKERS.any { value.contains(it) }
    }

    private fun validateMetadata(
        candidate: ScreenshotCandidate,
        maxBytes: Int = MAX_IMAGE_BYTES,
    ): String? {
        if (!isSupportedMimeType(candidate.mimeType)) return "unsupported_image_type"
        if (candidate.sizeBytes > maxBytes) return "image_over_limit"
        val width = candidate.width
        val height = candidate.height
        if (width != null && height != null &&
            (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION ||
                width.toLong() * height.toLong() > MAX_IMAGE_PIXELS)
        ) {
            return "image_dimensions_over_limit"
        }
        return null
    }

    private fun isSupportedMimeType(mimeType: String): Boolean =
        mimeType.lowercase() in SUPPORTED_MIME_TYPES

    private companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_IMAGE_DIMENSION = 16_384
        const val MAX_IMAGE_PIXELS = 64_000_000L
        val SUPPORTED_MIME_TYPES = setOf(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/heic",
            "image/heif",
        )
        val SCREENSHOT_MARKERS = listOf(
            "screenshot",
            "screenshots",
            "截屏",
            "截图",
        )
    }
}
