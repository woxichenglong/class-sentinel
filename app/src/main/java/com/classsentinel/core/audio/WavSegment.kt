package com.classsentinel.core.audio

/**
 * v0.2 Task 4：带稳定元数据的语音分段。
 *
 * - [id] 同一 splitter/session（一次 Flow 收集）内稳定单调：s1、s2、s3…，
 *   与 M1a `SpeechEvent.segmentId: String` 契约一致；计数在每次收集内重置，
 *   不依赖 wall clock / UUID / 跨收集全局状态。
 * - [startOffsetMs]/[endOffsetMs] 基于 16k、16-bit、mono 采样率由帧数换算，
 *   确定性、单调、end > start。
 * - [bytes] 为 44-byte WAV 头 + PCM16 内容，与旧 `split()` 输出逐字节一致。
 */
data class WavSegment(
    val id: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is WavSegment &&
            id == other.id &&
            startOffsetMs == other.startOffsetMs &&
            endOffsetMs == other.endOffsetMs &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + startOffsetMs.hashCode()
        result = 31 * result + endOffsetMs.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
