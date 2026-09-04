package com.classsentinel.core.evaluation

import com.classsentinel.core.speech.PcmReplayResult
import com.classsentinel.core.speech.ReplayPhase
import com.classsentinel.core.speech.ReplayObservation
import com.classsentinel.core.speech.StreamingAsrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedAsrScoreCsvTest {

    @Test
    fun `csv contains trace identity quality and performance columns`() {
        val score = UnifiedAsrScorer.score(
            result = PcmReplayResult(
                modelProfileId = "x-asr-480",
                gitCommitSha = "sha,with-comma",
                runId = "run-001",
                phase = ReplayPhase.STEADY,
                engineName = "x-asr",
                inputSamples = 16_000L,
                inputDurationMs = 1_000L,
                totalElapsedMs = 1_200L,
                observations = listOf(
                    ReplayObservation(StreamingAsrEvent.Final(1, "结果", 0L, 1_000L), 1_200L),
                ),
            ),
            referenceText = "结果",
            performance = DevicePerformanceMetrics(avgCpuPercent = 42.5),
        )

        val header = UnifiedAsrScoreCsv.header
        val row = UnifiedAsrScoreCsv.row(score)

        assertTrue(header.contains("model_profile_id"))
        assertTrue(header.contains("git_commit_sha"))
        assertTrue(header.contains("run_id"))
        assertTrue(header.contains("cer"))
        assertTrue(header.contains("wer"))
        assertTrue(header.contains("code_switch_error_rate"))
        assertTrue(header.contains("name_false_positive_rate"))
        assertTrue(header.contains("replay_mode"))
        assertTrue(header.contains("input_packet_ms"))
        assertTrue(header.contains("recognizer_init_ms"))
        assertTrue(header.contains("decode_elapsed_ms"))
        assertTrue(header.contains("total_elapsed_ms"))
        assertTrue(header.contains("steady_state_rtf"))
        assertTrue(header.contains("avg_cpu_percent"))
        assertTrue(row.contains("x-asr-480"))
        assertTrue(row.contains("\"sha,with-comma\""))
        assertTrue(row.contains("STEADY"))
        assertTrue(row.contains("42.5"))
        assertEquals(header.split(',').size, csvFields(row).size)
    }

    private fun csvFields(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < row.length) {
            val char = row[index]
            if (char == '"' && quoted && index + 1 < row.length && row[index + 1] == '"') {
                current.append('"')
                index++
            } else if (char == '"') {
                quoted = !quoted
            } else if (char == ',' && !quoted) {
                fields += current.toString()
                current.clear()
            } else {
                current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }
}
