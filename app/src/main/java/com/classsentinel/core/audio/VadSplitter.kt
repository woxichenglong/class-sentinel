package com.classsentinel.core.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * VAD 分段器：PCM 流 → 有声片段 WAV 字节流。
 * 静音 1200ms 或段长达到 maxSegmentMs 即切分输出。
 *
 * 2026-08-16 真机调优：
 * ① 800ms 切段太激进，一句话被切碎喂给 ASR 导致转写差 → 放宽 1200ms + 最长 8s
 * ② 固定阈值在远场场景失效（VOICE_RECOGNITION 音源电平比 MIC 低 ~8dB，
 *    老师声音 -40dB 以下过不了 -35 阈值）→ 改自适应：噪声基线 + 12dB
 */
class VadSplitter(
    private val silenceDb: Int = -35,
    private val maxSegmentMs: Int = 8000,
    private val sampleRate: Int = 16000,
    private val minSilenceMs: Int = 1200,
    /** 自适应阈值相对噪声基线的增益(dB)：帧电平 > 噪声基线 + 此值 判为有声 */
    private val voiceBoostDb: Double = 15.0,
) {

    private fun rmsDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return -100.0
        val rms = sqrt(samples.map { (it * it).toDouble() }.average())
        if (rms == 0.0) return -100.0 // 全零帧防 -Infinity 污染噪声基线
        return 20 * log10(rms / 32768.0)
    }

    fun split(pcm: Flow<ShortArray>): Flow<ByteArray> = flow {
        segments(pcm).collect { emit(it.bytes) }
    }

    /**
     * 带元数据的分段流：稳定单调 id（s1/s2/…）、基于采样率换算的
     * [WavSegment.startOffsetMs]/[endOffsetMs]、44 字节 WAV 头 + PCM16。
     *
     * 计数生命周期：segmentCounter / sessionStartMs 都是本函数每次收集时
     * 新建的局部状态 —— 同一 splitter 实例的多次收集（如测试中重复 toList()）
     * 各自从 s1/0ms 开始，不跨调用漂移；同一收集内单调递增。
     * 阈值为 40ms 帧：静音 ≥ minSilenceMs 或累计 ≥ maxSegmentMs 即 flush。
     */
    fun segments(pcm: Flow<ShortArray>): Flow<WavSegment> = flow {
        val acc = mutableListOf<Short>()
        var silenceMs = 0
        var accMs = 0
        val frameSize = sampleRate * 40 / 1000 // 40ms 帧
        var noiseFloor = -50.0 // 噪声基线（静音帧指数滑动平均）
        var adaptCount = 0
        // session 局部计数：每次收集重置（s1 起），不跨调用漂移
        var segmentCounter = 0
        var sessionStartMs = 0L
        var segmentStartMs = 0L
        pcm.collect { chunk ->
            var offset = 0
            while (offset < chunk.size) {
                val end = minOf(offset + frameSize, chunk.size)
                val frame = chunk.copyOfRange(offset, end)
                offset = end
                // 先推进会话时钟，flush 时 endOffsetMs 已含本帧
                sessionStartMs += frame.size * 1000 / sampleRate
                val db = rmsDb(frame)
                // 自适应阈值：噪声基线+12dB 与固定阈值取更敏感者。课堂远场语音也能过。
                val threshold = minOf(noiseFloor + voiceBoostDb, silenceDb.toDouble())
                val voiced = db >= threshold
                if (voiced) {
                    if (acc.isEmpty()) segmentStartMs = sessionStartMs - frame.size * 1000L / sampleRate // 本段第一个有声帧的位置
                    acc += frame.toList()
                    accMs += frame.size * 1000 / sampleRate
                    silenceMs = 0
                } else {
                    // 静音帧更新噪声基线（指数滑动）
                    if (adaptCount < 25) adaptCount++
                    noiseFloor = if (adaptCount < 25) {
                        (noiseFloor * (adaptCount - 1) + db) / adaptCount // 初始算术平均
                    } else {
                        noiseFloor * 0.95 + db * 0.05 // 之后指数滑动
                    }
                    if (acc.isNotEmpty()) {
                        silenceMs += frame.size * 1000 / sampleRate
                    }
                }
                if (acc.isNotEmpty() && (silenceMs >= minSilenceMs || accMs >= maxSegmentMs)) {
                    emit(segment(acc, segmentCounter++, segmentStartMs, sessionStartMs))
                    acc.clear()
                    silenceMs = 0
                    accMs = 0
                }
            }
        }
        if (acc.isNotEmpty()) emit(segment(acc, segmentCounter++, segmentStartMs, sessionStartMs))
    }

    /** 把累积样本打包成 WavSegment：id 单调、offset 按采样率换算、WAV 头由 [wavBytes] 生成。 */
    private fun segment(
        samples: List<Short>,
        index: Int,
        startOffsetMs: Long,
        endOffsetMs: Long,
    ): WavSegment {
        return WavSegment(
            id = "s${index + 1}",
            startOffsetMs = startOffsetMs,
            endOffsetMs = endOffsetMs,
            bytes = wavBytes(samples),
        )
    }

    /** PCM16 → 44 字节头 WAV */
    private fun wavBytes(samples: List<Short>): ByteArray {
        val data = ByteArray(samples.size * 2)
        for ((i, s) in samples.withIndex()) {
            data[i * 2] = (s.toInt() and 0xFF).toByte()
            data[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        val out = ByteArray(44 + data.size)
        fun w(i: Int, v: Int) {
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = (v shr 8 and 0xFF).toByte()
            out[i + 2] = (v shr 16 and 0xFF).toByte()
            out[i + 3] = (v shr 24 and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(out, 0)
        w(4, 36 + data.size)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        w(16, 16) // fmt chunk size
        w(20, 1)  // PCM
        w(22, 1)  // mono
        w(24, sampleRate)
        w(28, sampleRate * 2) // byte rate
        w(32, 2)  // block align
        w(34, 16) // bits per sample
        "data".toByteArray().copyInto(out, 36)
        w(40, data.size)
        data.copyInto(out, 44)
        return out
    }
}
