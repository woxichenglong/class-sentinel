package com.classsentinel.core.speech

import java.io.Closeable
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class RemoteDownloadDisposition {
    APPEND,
    REPLACE,
}

internal class RemoteDownloadChunk(
    val disposition: RemoteDownloadDisposition,
    val startOffset: Long,
    val totalBytes: Long?,
    val body: java.io.InputStream,
) : Closeable {
    override fun close() {
        body.close()
    }
}

internal fun interface ResumableRemoteModelSource {
    fun open(location: String, offset: Long, expectedSize: Long): RemoteDownloadChunk
}

/** HTTP adapter that normalizes 200/206 responses before the installer sees them. */
internal class HttpRangeModelSource(
    private val client: OkHttpClient = OkHttpClient(),
) : ResumableRemoteModelSource {

    override fun open(location: String, offset: Long, expectedSize: Long): RemoteDownloadChunk {
        if (offset < 0L || offset > expectedSize) {
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.RANGE_INVALID,
                retryable = false,
                message = "ASR_MODEL_RANGE_INVALID",
            )
        }
        val url = location.toHttpUrlOrNull()
            ?: throw ModelDownloadException(
                reason = ModelDownloadFailureReason.HTTP,
                retryable = false,
                message = "ASR_MODEL_REMOTE_URL_INVALID",
            )
        val request = Request.Builder()
            .url(url)
            .apply {
                if (offset > 0L) header("Range", "bytes=$offset-")
            }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.NETWORK,
                retryable = true,
                message = "ASR_MODEL_REMOTE_SOURCE_FAILED",
                cause = error,
            )
        }

        val body = response.body
        if (body == null) {
            response.close()
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.HTTP,
                retryable = false,
                message = "ASR_MODEL_REMOTE_BODY_MISSING",
            )
        }

        try {
            return when (response.code) {
                200 -> {
                    val contentLength = body.contentLength()
                    if (contentLength >= 0L && contentLength != expectedSize) {
                        throw ModelDownloadException(
                            reason = ModelDownloadFailureReason.RANGE_INVALID,
                            retryable = false,
                            message = "ASR_MODEL_RANGE_INVALID",
                        )
                    }
                    RemoteDownloadChunk(
                        disposition = RemoteDownloadDisposition.REPLACE,
                        startOffset = 0L,
                        totalBytes = contentLength.takeIf { it >= 0L },
                        body = body.byteStream(),
                    )
                }

                206 -> {
                    val contentRange = parseContentRange(response.header("Content-Range"))
                    if (contentRange.start != offset || contentRange.end < contentRange.start) {
                        throw ModelDownloadException(
                            reason = ModelDownloadFailureReason.RANGE_INVALID,
                            retryable = false,
                            message = "ASR_MODEL_RANGE_INVALID",
                        )
                    }
                    if (contentRange.total != null && contentRange.total != expectedSize) {
                        throw ModelDownloadException(
                            reason = ModelDownloadFailureReason.RANGE_INVALID,
                            retryable = false,
                            message = "ASR_MODEL_RANGE_INVALID",
                        )
                    }
                    val expectedChunkLength = contentRange.end - contentRange.start + 1L
                    val contentLength = body.contentLength()
                    if (contentLength >= 0L && contentLength != expectedChunkLength) {
                        throw ModelDownloadException(
                            reason = ModelDownloadFailureReason.RANGE_INVALID,
                            retryable = false,
                            message = "ASR_MODEL_RANGE_INVALID",
                        )
                    }
                    if (contentRange.end >= expectedSize) {
                        throw ModelDownloadException(
                            reason = ModelDownloadFailureReason.RANGE_INVALID,
                            retryable = false,
                            message = "ASR_MODEL_RANGE_INVALID",
                        )
                    }
                    RemoteDownloadChunk(
                        disposition = RemoteDownloadDisposition.APPEND,
                        startOffset = contentRange.start,
                        totalBytes = contentRange.total,
                        body = body.byteStream(),
                    )
                }

                else -> {
                    val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                    throw ModelDownloadException(
                        reason = if (retryable) {
                            ModelDownloadFailureReason.NETWORK
                        } else {
                            ModelDownloadFailureReason.HTTP
                        },
                        retryable = retryable,
                        message = "ASR_MODEL_REMOTE_HTTP",
                    )
                }
            }
        } catch (error: ModelDownloadException) {
            response.close()
            throw error
        } catch (error: IOException) {
            response.close()
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.NETWORK,
                retryable = true,
                message = "ASR_MODEL_REMOTE_SOURCE_FAILED",
                cause = error,
            )
        }
    }

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
    )

    private fun parseContentRange(value: String?): ContentRange {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty())
            ?: throw ModelDownloadException(
                reason = ModelDownloadFailureReason.RANGE_INVALID,
                retryable = false,
                message = "ASR_MODEL_RANGE_INVALID",
            )
        return ContentRange(
            start = match.groupValues[1].toLong(),
            end = match.groupValues[2].toLong(),
            total = match.groupValues[3].takeUnless { it == "*" }?.toLong(),
        )
    }

    private companion object {
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    }
}
