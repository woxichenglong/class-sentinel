package com.classsentinel.core.audio

/**
 * 用户可见的本地音频保留策略。
 *
 * 默认只保留转写失败的片段，避免把整堂课默默变成长期录音。除 [NONE] 外，
 * 失败段仍会暂存到应用私有目录，保证网络恢复时有机会离线补转；成功后的留存
 * 由策略和句子标记共同决定。
 */
enum class AudioRetentionPolicy(
    /** DataStore 中稳定保存的值；不要用本地化 label 作为持久化协议。 */
    val storedValue: String,
    val label: String,
    val description: String,
) {
    FAILED_ONLY(
        storedValue = "failed_only",
        label = "仅失败片段",
        description = "只保留未成功转写的片段，转写成功后自动删除。",
    ),
    MARKED_ONLY(
        storedValue = "marked_only",
        label = "仅标记片段",
        description = "失败片段用于恢复；成功后只保留被标记的片段。",
    ),
    FULL_SESSION(
        storedValue = "full_session",
        label = "完整课程（谨慎开启）",
        description = "保留课程中的全部音频，便于回放，但会明显增加存储和隐私风险。",
    ),
    NONE(
        storedValue = "none",
        label = "不保留音频",
        description = "不保存音频；关闭后不再保证失败片段的离线恢复。",
    ),
    ;

    /** 失败段是否应落盘，供实时 ASR 失败回调使用。 */
    fun shouldRetainFailedSegment(): Boolean = this != NONE

    /** 是否需要在成功转写时先捕获一份候选音频，供标记回放或完整课程回放使用。 */
    fun shouldCaptureSuccessfulSegments(): Boolean = when (this) {
        MARKED_ONLY, FULL_SESSION -> true
        FAILED_ONLY, NONE -> false
    }

    /** 成功转写后是否继续保留该段。 */
    fun shouldRetainAfterSuccessfulTranscription(marked: Boolean): Boolean = when (this) {
        FAILED_ONLY, NONE -> false
        MARKED_ONLY -> marked
        FULL_SESSION -> true
    }

    /** 只给 FULL_SESSION 显示明确的存储/隐私提醒。 */
    fun warningText(): String? = when (this) {
        FULL_SESSION -> "完整课程音频按约 1.92 MB/分钟增长；仅在明确需要回放时开启。"
        FAILED_ONLY, MARKED_ONLY, NONE -> null
    }

    companion object {
        val DEFAULT: AudioRetentionPolicy = FAILED_ONLY

        /** 非法、空值和历史未知值都安全回退到最小留存策略。 */
        fun fromStored(raw: String?): AudioRetentionPolicy =
            entries.firstOrNull { it.storedValue.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT

        /** 16kHz、单声道、16-bit PCM 的原始音频速率：32,000 bytes/s。 */
        fun estimateBytes(durationMs: Long): Long {
            val millis = durationMs.coerceAtLeast(0L)
            val bytesPerMillisecond = 32L
            return if (millis > Long.MAX_VALUE / bytesPerMillisecond) {
                Long.MAX_VALUE
            } else {
                millis * bytesPerMillisecond
            }
        }
    }
}
