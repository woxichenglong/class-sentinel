package com.classsentinel.core.speech

import com.classsentinel.core.detect.NameEntry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameVoiceCalibratorTest {

    @Test
    fun `X480 capture returns one final and stops the audio source`() = runTest {
        val audio = FakeAudioCapture(flowOf(shortArrayOf(1)))
        val calibrator = calibrator(
            audio = audio,
            engine = FakeEngine(
                flowOf(StreamingAsrEvent.Final(1, "梁津干", 0L, 500L)),
            ),
        )

        val result = calibrator.captureOnce("梁津淦")

        assertEquals(NameCalibrationAttempt.Success("梁津干"), result)
        assertEquals(1, audio.prepareCalls)
        assertEquals(1, audio.stopCalls)
    }

    @Test
    fun `one attempt timeout fails and releases audio`() = runTest {
        val audio = FakeAudioCapture(flowOf(shortArrayOf(1)))
        val calibrator = calibrator(
            audio = audio,
            engine = FakeEngine(
                flow {
                    delay(20_000)
                    emit(StreamingAsrEvent.Final(1, "永远不会到达", 0L, 0L))
                },
            ),
        )

        val result = calibrator.captureOnce("梁津淦")

        assertEquals(NameCalibrationAttempt.Failure(NameCalibrationFailure.TIMEOUT), result)
        assertEquals(1, audio.stopCalls)
    }

    @Test
    fun `X480 recognizer initialization failure is unavailable and releases audio`() = runTest {
        val audio = FakeAudioCapture(flowOf(shortArrayOf(1)))
        val calibrator = X480NameVoiceCalibrator(
            ensureReady = { true },
            audioFactory = { audio },
            modelDirectory = { File("build/name-calibration") },
            engineFactory = { throw IllegalStateException("synthetic X480 init failure") },
        )

        val result = calibrator.captureOnce("梁津淦")

        assertEquals(
            NameCalibrationAttempt.Failure(NameCalibrationFailure.MODEL_UNAVAILABLE),
            result,
        )
        assertEquals(1, audio.stopCalls)
    }

    @Test
    fun `not ready skips microphone and recognizer creation`() = runTest {
        var audioCreated = false
        var engineCreated = false
        val calibrator = X480NameVoiceCalibrator(
            ensureReady = { false },
            audioFactory = {
                audioCreated = true
                FakeAudioCapture(flowOf(shortArrayOf(1)))
            },
            modelDirectory = { File("build/name-calibration") },
            engineFactory = {
                engineCreated = true
                FakeEngine(flowOf(StreamingAsrEvent.Final(1, "不会调用", 0L, 0L)))
            },
        )

        val result = calibrator.captureOnce("梁津淦")

        assertEquals(
            NameCalibrationAttempt.Failure(NameCalibrationFailure.MODEL_UNAVAILABLE),
            result,
        )
        assertTrue(!audioCreated)
        assertTrue(!engineCreated)
    }

    @Test
    fun `cancellation propagates and stops active audio`() = runTest {
        val started = CompletableDeferred<Unit>()
        val audio = FakeAudioCapture(flowOf(shortArrayOf(1)))
        val calibrator = calibrator(
            audio = audio,
            engine = FakeEngine(
                flow {
                    started.complete(Unit)
                    awaitCancellation()
                },
            ),
        )
        val request = launch {
            calibrator.captureOnce("梁津淦")
        }

        started.await()
        request.cancel()
        request.join()

        assertTrue(request.isCancelled)
        assertEquals(1, audio.stopCalls)
    }

    @Test
    fun `three successful slots preserve order and merge into one NameEntry`() = runTest {
        val controller = NameCalibrationController(
            displayName = "梁津淦",
            aiSeedVariants = listOf("良津干"),
            calibrator = FakeCalibrator(
                listOf(
                    NameCalibrationAttempt.Success("梁津干"),
                    NameCalibrationAttempt.Success("梁金干"),
                    NameCalibrationAttempt.Success("梁津淦"),
                ),
            ),
        )

        controller.captureNext()
        controller.captureNext()
        val finalState = controller.captureNext()

        assertEquals(3, finalState.completedSlots)
        assertTrue(finalState.finished)
        assertEquals(listOf("梁津干", "梁金干", "梁津淦"), finalState.measuredTranscripts)
        assertEquals(
            NameEntry("梁津淦", emptyList(), listOf("梁津干", "梁金干", "良津干")),
            controller.mergeEntry(NameEntry("梁津淦", emptyList(), emptyList())),
        )
    }

    @Test
    fun `timeout can retry the same slot without losing prior success`() = runTest {
        val fake = FakeCalibrator(
            listOf(
                NameCalibrationAttempt.Success("第一个"),
                NameCalibrationAttempt.Failure(NameCalibrationFailure.TIMEOUT),
                NameCalibrationAttempt.Success("第二个"),
            ),
        )
        val controller = NameCalibrationController("梁津淦", emptyList(), fake)

        controller.captureNext()
        val failed = controller.captureNext()
        assertEquals(1, failed.completedSlots)
        assertEquals(listOf("第一个"), failed.measuredTranscripts)
        assertEquals(NameCalibrationFailure.TIMEOUT, failed.lastFailure)

        val retried = controller.captureNext()
        assertEquals(2, retried.completedSlots)
        assertEquals(listOf("第一个", "第二个"), retried.measuredTranscripts)
    }

    @Test
    fun `skip finishes without calling calibrator and preserves AI seed`() = runTest {
        val fake = FakeCalibrator(emptyList())
        val controller = NameCalibrationController(
            displayName = "梁津淦",
            aiSeedVariants = listOf("梁津干"),
            calibrator = fake,
        )

        controller.skipAll()
        val stateAfterIgnoredCapture = controller.captureNext()

        assertEquals(0, fake.calls)
        assertTrue(stateAfterIgnoredCapture.finished)
        assertEquals(listOf("梁津干"), controller.mergedVariants())
    }

    private fun calibrator(
        audio: FakeAudioCapture,
        engine: ProfileBoundStreamingSpeechEngine,
    ): X480NameVoiceCalibrator = X480NameVoiceCalibrator(
        ensureReady = { true },
        audioFactory = { audio },
        modelDirectory = { File("build/name-calibration") },
        engineFactory = { engine },
    )

    private class FakeAudioCapture(
        private val source: Flow<ShortArray>,
    ) : NameCalibrationAudioCapture {
        var prepareCalls = 0
        var stopCalls = 0

        override fun prepareForCapture() {
            prepareCalls++
        }

        override fun pcm(): Flow<ShortArray> = source

        override fun stop() {
            stopCalls++
        }
    }

    private class FakeEngine(
        private val events: Flow<StreamingAsrEvent>,
    ) : ProfileBoundStreamingSpeechEngine {
        override val name: String = "fake-x480"
        override val modelProfileId: String = ModelProfiles.PRODUCTION.id
        override val sampleRate: Int = ModelProfiles.PRODUCTION.recognizer.sampleRate

        override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = events
    }

    private class FakeCalibrator(
        private val results: List<NameCalibrationAttempt>,
    ) : NameVoiceCalibrator {
        var calls = 0

        override suspend fun captureOnce(expectedDisplayName: String): NameCalibrationAttempt =
            results.getOrElse(calls++) { NameCalibrationAttempt.Failure(NameCalibrationFailure.UNKNOWN) }
    }
}
