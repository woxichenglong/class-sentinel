package com.classsentinel.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * 麦克风 PCM 采集：16kHz 单声道 16bit。
 *
 * 音源用 VOICE_RECOGNITION（ASR 标准选择）：平坦频率响应、无 AGC 染色，
 * 相比默认 MIC 显著提升识别准确率；并尽力开启 AEC/NS 抑制本机外放声
 * （2026-08-16 真机验收：MIC 音源转写差 + 手机外放被录进）。
 */
open class AudioStreamer(
    private val sampleRate: Int = 16000,
    /** 软件增益倍数（VOICE_RECOGNITION 音源无 AGC，远场语音电平过低需放大；clip 保护） */
    private val gain: Double = 4.0,
) {

    open fun pcm(): Flow<ShortArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

        rec.startRecording()

        // 尽力开启回声消除/降噪（effect 须在 startRecording 后创建；不支持则跳过）
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.audioSessionId).apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.audioSessionId).apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        android.util.Log.d(
            "ClassSentinel",
            "AudioRecord 开始录音 minBuf=$minBuf src=VOICE_RECOGNITION aec=${aec != null} ns=${ns != null}",
        )

        try {
            val buf = ShortArray(minBuf)
            var readCount = 0
            var sumSq = 0.0
            while (currentCoroutineContext().isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    readCount++
                    for (i in 0 until n) {
                        val s = buf[i].toDouble()
                        sumSq += s * s
                    }
                    // 每 ~2 秒打一次 RMS 电平（诊断后台录音是否被静音）
                    if (readCount % 40 == 0) {
                        val rms = kotlin.math.sqrt(sumSq / (readCount * n))
                        val db = 20 * kotlin.math.log10(rms / 32768.0)
                        android.util.Log.d("ClassSentinel", "录音电平 ${db.toInt()}dB (读${readCount}次)")
                        sumSq = 0.0
                        readCount = 0
                    }
                    // 软件增益 + clip 保护
                    val amplified = if (gain == 1.0) {
                        buf.copyOf(n)
                    } else {
                        ShortArray(n) { i ->
                            (buf[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    emit(amplified)
                }
            }
        } finally {
            rec.stop()
            rec.release()
            aec?.release()
            ns?.release()
        }
    }.flowOn(Dispatchers.IO)
}
