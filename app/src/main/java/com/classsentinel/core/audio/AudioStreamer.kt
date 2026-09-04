package com.classsentinel.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.classsentinel.core.log.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** read()==0 时的有界退避（协程 delay，可取消，不用 Thread.sleep），避免 CPU spin。 */
private const val ZERO_READ_RETRY_MS = 20L

/**
 * v0.2 Task 7（返工）：AudioRecord 生命周期资源的最小可测通用包装。
 *
 * JVM 单测没有 android.media 真实现，因此把"释放"提升为接口契约：
 * 生产用 [RealAudioRecordResource] 包住 AudioRecord，测试用 fake 记录 close()。
 * 只有两条契约，都不触碰平台细节：
 *  - [close] 可被安全调用（幂等收敛 stop/release/AEC/NS）；
 *  - 一旦 open 成功，无论 block 走完、抛 typed 失败还是协程取消，[close] 都必须执行。
 */
internal fun interface AudioRecordResource {
    fun close()
}

/**
 * 通用资源包装：open 创建资源，block 使用资源，close 在 finally 中保证执行。
 *
 * 注意：必须是普通（非 inline）suspend 函数——若做成 inline，调用方 lambda 里的
 * 非局部 return 会跳过本函数的 finally，导致 open 成功后资源不释放（正是本返工要
 * 防的泄漏）。生产代码用它覆盖 AudioRecord 的整个生命周期（含 state 校验），使
 * 初始化状态失败、startRecording 失败、read fatal、collector cancellation
 * 全部走 close() 释放。CancellationException 不在此吞掉，随原始异常栈向上传播。
 */
internal suspend fun <T : Any, R> useAudioRecordResource(
    open: () -> T,
    close: (T) -> Unit,
    block: suspend (T) -> R,
): R {
    var resource: T? = null
    try {
        resource = open()
        return block(resource)
    } finally {
        if (resource != null) {
            close(resource)
        }
    }
}

/**
 * 生产实现：把 android.media.AudioRecord 的 stop/release 与 AEC/NS release
 * 收敛为单个幂等 close()，供 pcm() 的 finally 统一调用。
 * 未进入 RECORDING 时 stop() 会抛，吞掉以免掩盖原始异常，release 必须执行。
 */
internal class RealAudioRecordResource(
    private val rec: AudioRecord,
) : AudioRecordResource {
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    val audioRecord: AudioRecord get() = rec

    /** startRecording 之后创建的 AEC/NS 挂到资源上，随 close() 一起释放。 */
    fun attachEffects(aec: AcousticEchoCanceler?, ns: NoiseSuppressor?) {
        this.aec = aec
        this.ns = ns
    }

    override fun close() {
        runCatching { rec.stop() }
        rec.release()
        aec?.release()
        ns?.release()
    }
}

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
    /** 生产调用方传入应用 Context，用于在创建 AudioRecord 前检查录音权限。 */
    private val context: Context? = null,
) {

    open fun pcm(): Flow<ShortArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // v0.2 Task 7：minBuf<=0 在构造 AudioRecord 前就以 typed 失败拒绝，绝不分配无效缓冲区
        if (minBuf <= 0) {
            throw AudioCaptureException(
                code = minBuf,
                message = "音频采集初始化失败: getMinBufferSize() 返回无效值 $minBuf",
            )
        }
        // v0.2 Task 7 返工：整个 AudioRecord 生命周期（含 state 校验）都在
        // useAudioRecordResource 的 finally 覆盖下——state 失败/startRecording 失败/
        // read fatal/协程取消一律 release，不再有 try 之前的裸 throw 泄漏路径。
        useAudioRecordResource(
            open = {
                val appContext = context ?: throw AudioCaptureException(
                    code = PackageManager.PERMISSION_DENIED,
                    message = "录音权限检查失败",
                )
                check(
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED,
                ) {
                    "RECORD_AUDIO permission denied"
                }
                RealAudioRecordResource(
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2,
                    ),
                )
            },
            close = { it.close() },
        ) { res ->
            val rec = res.audioRecord
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw AudioCaptureException(
                    code = rec.state,
                    message = "音频采集初始化失败: AudioRecord 状态 ${rec.state}（预期 INITIALIZED）",
                )
            }

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
            res.attachEffects(aec, ns)
            SafeLog.d(
                "audio_record_start",
                mapOf("module" to "AudioStreamer"),
            )

            val buf = ShortArray(minBuf)
            var readCount = 0
            var sumSq = 0.0
            while (currentCoroutineContext().isActive) {
                when (val result = classifyAudioRead(rec.read(buf, 0, buf.size))) {
                    is AudioReadResult.Data -> {
                        val n = result.count
                        readCount++
                        for (i in 0 until n) {
                            val s = buf[i].toDouble()
                            sumSq += s * s
                        }
                        // 每 ~2 秒打一次 RMS 电平（诊断后台录音是否被静音）
                        if (readCount % 40 == 0) {
                            val rms = kotlin.math.sqrt(sumSq / (readCount * n))
                            val db = 20 * kotlin.math.log10(rms / 32768.0)
                            SafeLog.d(
                                "audio_level",
                                mapOf("module" to "AudioStreamer", "levelDb" to db.toInt()),
                            )
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

                    // v0.2 Task 7：0 结果不忙等，有界退避后重读（delay 可取消）
                    AudioReadResult.RetryLater -> delay(ZERO_READ_RETRY_MS)

                    // v0.2 Task 7：负结果不 emit、不循环吞掉，typed 失败进入上层 pipeline
                    is AudioReadResult.Fatal -> throw AudioCaptureException(
                        code = result.code,
                        message = "音频读取失败: AudioRecord.read() 返回错误码 ${result.code}",
                    )
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
