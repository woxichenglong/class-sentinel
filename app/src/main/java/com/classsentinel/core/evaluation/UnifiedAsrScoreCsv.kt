package com.classsentinel.core.evaluation

/** Stable CSV serialization for one score row; transcript text is intentionally excluded. */
object UnifiedAsrScoreCsv {
    private val columns = listOf(
        "model_profile_id",
        "git_commit_sha",
        "run_id",
        "phase",
        "cer",
        "wer",
        "code_switch_error_rate",
        "professional_reference_hits",
        "professional_matched_hits",
        "professional_hypothesis_hits",
        "professional_false_positive_hits",
        "professional_recall",
        "professional_false_positive_rate",
        "name_reference_hits",
        "name_matched_hits",
        "name_hypothesis_hits",
        "name_false_positive_hits",
        "name_recall",
        "name_false_positive_rate",
        "first_partial_latency_ms",
        "first_final_latency_ms",
        "rtf",
        "input_duration_ms",
        "elapsed_ms",
        "event_trigger_latency_ms",
        "ai_start_latency_ms",
        "avg_cpu_percent",
        "peak_ram_bytes",
        "duration_minutes",
        "battery_start_percent",
        "battery_end_percent",
        "battery_temp_start_c",
        "battery_temp_end_c",
        "thermal_throttle_observed",
    )

    val header: String = columns.joinToString(",")

    fun row(score: UnifiedAsrScore): String = listOf(
        score.modelProfileId,
        score.gitCommitSha,
        score.runId,
        score.phase.name,
        score.cer,
        score.wer,
        score.codeSwitchErrorRate,
        score.professionalTerms.referenceHits,
        score.professionalTerms.matchedHits,
        score.professionalTerms.hypothesisHits,
        score.professionalTerms.falsePositiveHits,
        score.professionalTerms.recall,
        score.professionalTerms.falsePositiveRate,
        score.names.referenceHits,
        score.names.matchedHits,
        score.names.hypothesisHits,
        score.names.falsePositiveHits,
        score.names.recall,
        score.names.falsePositiveRate,
        score.firstPartialLatencyMs,
        score.firstFinalLatencyMs,
        score.rtf,
        score.inputDurationMs,
        score.elapsedMs,
        score.performance.eventTriggerLatencyMs,
        score.performance.aiStartLatencyMs,
        score.performance.avgCpuPercent,
        score.performance.peakRamBytes,
        score.performance.durationMinutes,
        score.performance.batteryStartPercent,
        score.performance.batteryEndPercent,
        score.performance.batteryTempStartC,
        score.performance.batteryTempEndC,
        score.performance.thermalThrottleObserved,
    ).joinToString(",") { value -> csvCell(value?.toString().orEmpty()) }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
