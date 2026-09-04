package com.classsentinel.core.evaluation

import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.PcmReplayResult
import com.classsentinel.core.speech.ReplayObservation
import com.classsentinel.core.speech.ReplayMode
import com.classsentinel.core.speech.ReplayPhase
import com.classsentinel.core.speech.StreamingAsrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnifiedAsrScorerTest {

    @Test
    fun `score preserves experiment identity and computes latency and steady state rtf`() {
        val result = replayResult(
            totalElapsedMs = 500L,
            inputDurationMs = 1_000L,
            recognizerInitMs = 100L,
            decodeElapsedMs = 400L,
            events = listOf(
                ReplayObservation(StreamingAsrEvent.Partial(1, "CAPM", 100L), 120L),
                ReplayObservation(StreamingAsrEvent.Final(1, "CAPM beta", 0L, 1_000L), 500L),
            ),
        )

        val score = UnifiedAsrScorer.score(
            result = result,
            referenceText = "CAPM beta",
            performance = DevicePerformanceMetrics(
                eventTriggerLatencyMs = 650L,
                aiStartLatencyMs = 900L,
                avgCpuPercent = 42.5,
                peakRamBytes = 123_456_789L,
            ),
        )

        assertEquals("sherpa-zh-14m", score.modelProfileId)
        assertEquals("abc1234", score.gitCommitSha)
        assertEquals("run-001", score.runId)
        assertEquals(ReplayPhase.WARM, score.phase)
        assertEquals(ReplayMode.FAST, score.replayMode)
        assertEquals(1, score.scorerVersion)
        assertEquals("mixed-zh-en-v1", score.normalizationProfile)
        assertEquals(0.0, score.cer, 0.0)
        assertEquals(0.0, score.wer, 0.0)
        assertEquals(0.0, score.codeSwitchErrorRate, 0.0)
        assertEquals(120L, score.firstPartialLatencyMs)
        assertEquals(500L, score.firstFinalLatencyMs)
        assertEquals(100L, score.recognizerInitMs)
        assertEquals(400L, score.decodeElapsedMs)
        assertEquals(500L, score.totalElapsedMs)
        assertEquals(0.4, score.steadyStateRtf!!, 0.0)
        assertEquals(650L, score.performance.eventTriggerLatencyMs)
        assertEquals(42.5, score.performance.avgCpuPercent!!, 0.0)
    }

    @Test
    fun `score reports professional and name recall plus false positives`() {
        val score = UnifiedAsrScorer.score(
            result = replayResult(
                events = listOf(
                    ReplayObservation(
                        StreamingAsrEvent.Final(1, "今天讲 CAPM 和 ROI，张伟负责回答", 0L, 1_000L),
                        100L,
                    ),
                ),
            ),
            referenceText = "今天讲 CAPM 和 beta，李华负责回答",
            professionalTerms = setOf("CAPM", "beta", "ROI"),
            names = setOf("李华", "张伟"),
        )

        assertEquals(2, score.professionalTerms.referenceHits)
        assertEquals(1, score.professionalTerms.matchedHits)
        assertEquals(2, score.professionalTerms.hypothesisHits)
        assertEquals(0.5, score.professionalTerms.recall!!, 0.0)
        assertEquals(0.5, score.professionalTerms.falseDiscoveryRate, 0.0)
        assertEquals(1, score.names.referenceHits)
        assertEquals(1, score.names.hypothesisHits)
        assertEquals(0, score.names.matchedHits)
        assertEquals(0.0, score.names.recall!!, 0.0)
        assertEquals(1.0, score.names.falseDiscoveryRate, 0.0)
    }

    @Test
    fun `score separates english and chinese script switch errors`() {
        val score = UnifiedAsrScorer.score(
            result = replayResult(
                events = listOf(
                    ReplayObservation(StreamingAsrEvent.Final(1, "CAPM risk", 0L, 1_000L), 100L),
                ),
            ),
            referenceText = "CAPM 风险",
        )

        assertEquals(0.5, score.codeSwitchErrorRate, 0.0)
        assertNull(score.names.recall)
        assertEquals(0.0, score.names.falseDiscoveryRate, 0.0)
    }

    @Test
    fun `digits do not create a script switch`() {
        val score = UnifiedAsrScorer.score(
            result = replayResult(
                events = listOf(
                    ReplayObservation(StreamingAsrEvent.Final(1, "第章", 0L, 1_000L), 100L),
                ),
            ),
            referenceText = "第3章",
        )

        assertEquals(0.0, score.codeSwitchErrorRate, 0.0)
    }

    @Test
    fun `ascii keyword matching does not count a substring inside another word`() {
        val score = UnifiedAsrScorer.score(
            result = replayResult(
                events = listOf(
                    ReplayObservation(StreamingAsrEvent.Final(1, "beta", 0L, 1_000L), 100L),
                ),
            ),
            referenceText = "betamax",
            professionalTerms = setOf("beta"),
        )

        assertEquals(0, score.professionalTerms.referenceHits)
        assertEquals(1, score.professionalTerms.hypothesisHits)
        assertEquals(1, score.professionalTerms.falsePositiveHits)
    }

    private fun replayResult(
        totalElapsedMs: Long = 100L,
        inputDurationMs: Long = 1_000L,
        recognizerInitMs: Long? = null,
        decodeElapsedMs: Long? = null,
        events: List<ReplayObservation> = emptyList(),
    ) = PcmReplayResult(
        modelProfileId = ModelProfiles.ZIPFORMER_ZH_14M.id,
        gitCommitSha = "abc1234",
        runId = "run-001",
        phase = ReplayPhase.WARM,
        engineName = "fake",
        inputSamples = 16_000L,
        inputDurationMs = inputDurationMs,
        totalElapsedMs = totalElapsedMs,
        observations = events,
        recognizerInitMs = recognizerInitMs,
        decodeElapsedMs = decodeElapsedMs,
    )
}
