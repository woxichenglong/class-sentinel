package com.classsentinel.core.speech

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Performs expensive model readiness checks away from the UI thread and shares their result.
 * The stat signature avoids re-hashing unchanged model files; installers still invalidate it after
 * a successful replacement, and a changed file size/mtime/marker causes a fresh probe.
 */
internal class ModelReadinessChecker(
    private val filesDir: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val probe: (File, ModelProfile) -> Boolean = { root, profile ->
        SherpaModelInstaller.isInstalled(root, profile)
    },
) {

    suspend fun isReady(profile: ModelProfile): Boolean = withContext(dispatcher) {
        isReadyOnIo(profile)
    }

    /** Prepare an APK-backed model before issuing a live START command. */
    suspend fun ensureReady(
        profile: ModelProfile,
        assetOpener: (String) -> InputStream,
    ): Boolean = withContext(dispatcher) {
        if (isReadyOnIo(profile)) return@withContext true
        try {
            SherpaModelInstaller(
                filesDir = filesDir,
                profile = profile,
                assetOpener = assetOpener,
            ).install()
            invalidate(profile)
            isReadyOnIo(profile)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    fun invalidate(profile: ModelProfile) {
        invalidate(filesDir, profile)
    }

    companion object {
        internal fun invalidate(filesDir: File, profile: ModelProfile) {
            val key = buildCacheKey(filesDir, profile)
            synchronized(CACHE_LOCK) {
                CACHE.remove(key)
            }
        }

        private fun buildCacheKey(filesDir: File, profile: ModelProfile): CacheKey = CacheKey(
            filesRoot = filesDir.absoluteFile.path,
            profileId = profile.id,
            profileVersion = profile.version,
            artifactFingerprint = profile.artifact.files.joinToString("|") {
                "${it.name}:${it.expectedSize}:${it.sha256}"
            },
        )

        private val CACHE_LOCK = Any()
        private val CACHE = mutableMapOf<CacheKey, CacheEntry>()
    }

    private fun cacheKey(profile: ModelProfile): CacheKey = cacheKey(filesDir, profile)

    private fun cacheKey(filesDir: File, profile: ModelProfile): CacheKey =
        Companion.buildCacheKey(filesDir, profile)

    private fun isReadyOnIo(profile: ModelProfile): Boolean {
        val key = cacheKey(profile)
        val signature = fileSignature(profile)
        synchronized(CACHE_LOCK) {
            CACHE[key]?.takeIf { it.signature == signature }?.let { return it.ready }
        }

        val ready = probe(filesDir, profile)
        synchronized(CACHE_LOCK) {
            CACHE[key] = CacheEntry(signature, ready)
        }
        return ready
    }
    /** Only metadata/stat calls happen here; the full SHA-256 probe remains behind dispatcher. */
    private fun fileSignature(profile: ModelProfile): String {
        val targetDir = File(File(filesDir, "asr"), profile.artifact.directory)
        val files = listOf(File(targetDir, ".model-profile")) +
            profile.artifact.files.map { File(targetDir, it.name) }
        return files.joinToString("|") { file ->
            "${file.name}:${file.exists()}:${file.length()}:${file.lastModified()}"
        }
    }

    private data class CacheKey(
        val filesRoot: String,
        val profileId: String,
        val profileVersion: String,
        val artifactFingerprint: String,
    )

    private data class CacheEntry(
        val signature: String,
        val ready: Boolean,
    )

}
