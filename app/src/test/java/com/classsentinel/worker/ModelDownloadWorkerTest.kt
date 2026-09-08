package com.classsentinel.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.classsentinel.core.speech.ModelDownloadFailureReason
import com.classsentinel.core.speech.ModelDownloadState
import com.classsentinel.core.speech.ModelDownloadStateStore
import com.classsentinel.core.speech.ModelDistribution
import com.classsentinel.core.speech.ModelFileSpec
import com.classsentinel.core.speech.ModelIntegrityVerifier
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.ModelProfile
import com.classsentinel.core.speech.ModelDownloadException
import com.classsentinel.core.speech.RemoteDownloadChunk
import com.classsentinel.core.speech.RemoteDownloadDisposition
import com.classsentinel.core.speech.RemoteModelInstaller
import com.classsentinel.core.speech.RemoteModelSource
import com.classsentinel.core.speech.ResumableRemoteModelSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadWorkerTest {

    private val modelFiles = linkedMapOf(
        "encoder.onnx" to byteArrayOf(1, 2, 3, 4),
        "decoder.onnx" to byteArrayOf(5, 6, 7),
        "joiner.onnx" to byteArrayOf(8, 9, 10),
        "tokens.txt" to "tokens".toByteArray(),
    )

    @Test
    fun `remote profile enters worker and ends ready`() = runBlocking {
        val root = Files.createTempDirectory("worker-remote-").toFile()
        try {
            val profile = remoteProfile()
            val states = RecordingStateStore()
            val worker = worker(
                profile = profile,
                dependencies = dependencies(root, profile, states) { p -> legacyInstaller(root, p) },
            )

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(ModelDownloadState.Ready, states.current(profile.id))
            assertTrue(states.history.any { it is ModelDownloadState.NotInstalled })
            assertTrue(states.history.any { it is ModelDownloadState.Downloading })
            assertTrue(states.history.any { it is ModelDownloadState.Verifying })
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `bundled profile is rejected before installer creation`() = runBlocking {
        val root = Files.createTempDirectory("worker-bundled-").toFile()
        try {
            val profile = bundledProfile()
            val states = RecordingStateStore()
            var installerCalls = 0
            val worker = worker(
                profile = profile,
                dependencies = dependencies(root, profile, states) {
                    installerCalls++
                    error("bundled profile must be rejected")
                },
            )

            assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
            assertEquals(0, installerCalls)
            assertEquals(
                ModelDownloadState.Failed(ModelDownloadFailureReason.BUNDLED_PROFILE),
                states.current(profile.id),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unknown profile is rejected`() = runBlocking {
        val root = Files.createTempDirectory("worker-unknown-").toFile()
        try {
            val states = RecordingStateStore()
            val worker = worker(
                profileId = "not-in-catalog",
                dependencies = ModelDownloadWorkerDependencies(
                    filesDir = root,
                    profileResolver = { null },
                    installerFactory = { error("unknown profile must not create installer") },
                    stateStore = states,
                ),
            )

            assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
            assertEquals(
                ModelDownloadState.Failed(ModelDownloadFailureReason.INVALID_PROFILE),
                states.current("not-in-catalog"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `already ready succeeds without opening source again`() = runBlocking {
        val root = Files.createTempDirectory("worker-ready-").toFile()
        try {
            val profile = remoteProfile()
            legacyInstaller(root, profile).install()
            val states = RecordingStateStore()
            var installerCalls = 0
            val worker = worker(
                profile = profile,
                dependencies = dependencies(root, profile, states) {
                    installerCalls++
                    error("ready model must not create installer")
                },
            )

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, installerCalls)
            assertEquals(ModelDownloadState.Ready, states.current(profile.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `partial formal files and part resume from disk and become ready`() = runBlocking {
        val root = Files.createTempDirectory("worker-resume-").toFile()
        try {
            val profile = remoteProfile()
            val target = targetDirectory(root, profile)
            File(target, "encoder.onnx").writeBytes(modelFiles.getValue("encoder.onnx"))
            File(target, "decoder.onnx.part").writeBytes(byteArrayOf(5))
            val offsets = mutableListOf<Pair<String, Long>>()
            val states = RecordingStateStore()
            val worker = worker(
                profile = profile,
                dependencies = dependencies(root, profile, states) { p ->
                    resumableInstaller(root, p, offsets)
                },
            )

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(1L, offsets.first { it.first == "decoder.onnx" }.second)
            assertEquals(ModelDownloadState.Ready, states.current(profile.id))
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `network interruption keeps part and next worker resumes it`() = runBlocking {
        val root = Files.createTempDirectory("worker-interrupt-").toFile()
        try {
            val profile = remoteProfile()
            val firstRun = AtomicInteger(0)
            val states = RecordingStateStore()
            val first = worker(
                profile = profile,
                dependencies = dependencies(root, profile, states) { p ->
                    interruptingInstaller(root, p, firstRun)
                },
            )

            assertEquals(ListenableWorker.Result.retry(), first.doWork())
            val target = targetDirectory(root, profile)
            assertTrue(target.listFiles()?.any { it.name.endsWith(".part") } == true)
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))

            val second = worker(
                profile = profile,
                dependencies = dependencies(root, profile, states) { p ->
                    resumableInstaller(root, p, mutableListOf())
                },
            )
            assertEquals(ListenableWorker.Result.success(), second.doWork())
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `complete formal files without marker are adopted without source reads`() = runBlocking {
        val root = Files.createTempDirectory("worker-marker-repair-").toFile()
        try {
            val profile = remoteProfile()
            val target = targetDirectory(root, profile)
            writeFormalFiles(profile, target)
            val states = RecordingStateStore()
            var sourceReads = 0
            val worker = worker(
                profile = profile,
                dependencies = dependencies(root, profile, states) { p ->
                    RemoteModelInstaller(root, p, RemoteModelSource {
                        sourceReads++
                        error("correct formal files must be reused")
                    })
                },
            )

            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, sourceReads)
            assertTrue(File(target, ".model-profile").isFile)
            assertTrue(ModelIntegrityVerifier.verify(profile, target))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `request carries only profile id and uses connected unique work`() {
        val profileId = "x-asr-480"
        val request = ModelDownloadWorker.buildRequest(profileId)

        assertEquals(profileId, request.workSpec.input.getString(ModelDownloadWorker.KEY_PROFILE_ID))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(
            "model-download:$profileId",
            ModelDownloadWorker.uniqueWorkName(profileId),
        )
        assertEquals(setOf(ModelDownloadWorker.KEY_PROFILE_ID), request.workSpec.input.keyValueMap.keys)
    }

    @Test
    fun `work manager actions keep one unique request and cancel without deleting files`() {
        val context = RuntimeEnvironment.getApplication() as Context
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(context) }
        val workManager = WorkManager.getInstance(context)
        val profileId = "ui-action-${System.nanoTime()}"
        val actions = WorkManagerModelDownloadActions(workManager)

        actions.enqueue(profileId)
        actions.enqueue(profileId)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(ModelDownloadWorker.uniqueWorkName(profileId)).get().size)

        actions.cancel(profileId)
        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(ModelDownloadWorker.uniqueWorkName(profileId)).get().single().state,
        )
    }

    private fun worker(
        profile: ModelProfile? = null,
        profileId: String = profile!!.id,
        dependencies: ModelDownloadWorkerDependencies,
    ): ModelDownloadWorker = TestListenableWorkerBuilder.from(
        RuntimeEnvironment.getApplication() as Context,
        ModelDownloadWorker::class.java,
    )
        .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_PROFILE_ID, profileId).build())
        .build()
        .apply {
            this.dependencies = dependencies
        }

    private fun dependencies(
        root: File,
        profile: ModelProfile,
        states: RecordingStateStore,
        installerFactory: (ModelProfile) -> RemoteModelInstaller,
    ): ModelDownloadWorkerDependencies = ModelDownloadWorkerDependencies(
        filesDir = root,
        profileResolver = { id -> if (id == profile.id) profile else null },
        installerFactory = installerFactory,
        stateStore = states,
    )

    private fun legacyInstaller(root: File, profile: ModelProfile): RemoteModelInstaller =
        RemoteModelInstaller(root, profile, RemoteModelSource { location ->
            ByteArrayInputStream(modelFiles.getValue(location))
        })

    private fun resumableInstaller(
        root: File,
        profile: ModelProfile,
        offsets: MutableList<Pair<String, Long>>,
    ): RemoteModelInstaller = RemoteModelInstaller(
        root,
        profile,
        ResumableRemoteModelSource { location, offset, expectedSize ->
            offsets += location to offset
            val bytes = modelFiles.getValue(location)
            RemoteDownloadChunk(
                disposition = RemoteDownloadDisposition.APPEND,
                startOffset = offset,
                totalBytes = expectedSize,
                body = ByteArrayInputStream(bytes.copyOfRange(offset.toInt(), bytes.size)),
            )
        },
    )

    private fun interruptingInstaller(
        root: File,
        profile: ModelProfile,
        run: AtomicInteger,
    ): RemoteModelInstaller = RemoteModelInstaller(
        root,
        profile,
        ResumableRemoteModelSource { location, offset, expectedSize ->
            if (run.getAndIncrement() == 0) {
                val bytes = modelFiles.getValue(location)
                RemoteDownloadChunk(
                    disposition = RemoteDownloadDisposition.APPEND,
                    startOffset = offset,
                    totalBytes = expectedSize,
                    body = FailingInputStream(bytes.copyOfRange(offset.toInt(), bytes.size), 1),
                )
            } else {
                val bytes = modelFiles.getValue(location)
                RemoteDownloadChunk(
                    disposition = RemoteDownloadDisposition.APPEND,
                    startOffset = offset,
                    totalBytes = expectedSize,
                    body = ByteArrayInputStream(bytes.copyOfRange(offset.toInt(), bytes.size)),
                )
            }
        },
    )

    private fun remoteProfile(): ModelProfile {
        val base = ModelProfiles.X_ASR_480
        return base.copy(
            id = "worker-remote",
            artifact = base.artifact.copy(
                directory = "worker-model",
                encoder = spec("encoder.onnx"),
                decoder = spec("decoder.onnx"),
                joiner = spec("joiner.onnx"),
                tokens = spec("tokens.txt"),
            ),
            distribution = ModelDistribution.Remote(
                files = modelFiles.keys.associateWith { it },
            ),
        )
    }

    private fun bundledProfile(): ModelProfile =
        remoteProfile().copy(
            id = "worker-bundled",
            distribution = ModelDistribution.Bundled,
        )

    private fun spec(name: String): ModelFileSpec {
        val bytes = modelFiles.getValue(name)
        return ModelFileSpec(name, bytes.size.toLong(), sha256(bytes))
    }

    private fun targetDirectory(root: File, profile: ModelProfile): File =
        File(root, "asr/${profile.artifact.directory}").apply { mkdirs() }

    private fun writeFormalFiles(profile: ModelProfile, target: File) {
        profile.artifact.files.forEach { spec ->
            File(target, spec.name).writeBytes(modelFiles.getValue(spec.name))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class RecordingStateStore : ModelDownloadStateStore {
        private val values = linkedMapOf<String, ModelDownloadState>()
        val history = mutableListOf<ModelDownloadState>()

        override fun publish(profileId: String, state: ModelDownloadState) {
            values[profileId] = state
            history += state
        }

        override fun current(profileId: String): ModelDownloadState? = values[profileId]
    }

    private class FailingInputStream(
        private val bytes: ByteArray,
        private val successfulBytes: Int,
    ) : java.io.InputStream() {
        private var index = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= successfulBytes) throw IOException("synthetic disconnect")
            val count = minOf(length, successfulBytes - index, bytes.size - index)
            bytes.copyInto(buffer, offset, index, index + count)
            index += count
            return count
        }

        override fun read(): Int =
            if (index >= successfulBytes) throw IOException("synthetic disconnect") else bytes[index++].toInt()
    }
}
