package com.classsentinel.core.audio

/**
 * v0.2 Task 7：AudioRecord.read() 返回值的纯分类契约。
 *
 * 生产 read-loop 依据本契约分流：正数采样/零重试/负数致命，
 * 不再把负/零结果静默吞掉，也不在 read-loop 里堆 if-else。
 */
sealed interface AudioReadResult {
    /** 读到 [count] 个采样（count > 0）。 */
    data class Data(val count: Int) : AudioReadResult

    /** 本次未读到数据（count == 0），调用方应短暂退避后重试，避免 CPU spin。 */
    data object RetryLater : AudioReadResult

    /** 采集出错（count < 0），携带平台错误码，调用方应终止。 */
    data class Fatal(val code: Int) : AudioReadResult
}

/**
 * 分类 AudioRecord.read() 返回值：n>0 → Data(n)；n==0 → RetryLater；n<0 → Fatal(n)。
 */
internal fun classifyAudioRead(n: Int): AudioReadResult = when {
    n > 0 -> AudioReadResult.Data(n)
    n == 0 -> AudioReadResult.RetryLater
    else -> AudioReadResult.Fatal(n)
}

/**
 * 音频采集 typed 失败：初始化（minBuf<=0 / 构造失败）或读取阶段（read<0）的可审计失败。
 *
 * 红线：message 只描述错误类别与平台错误码，绝不携带原始音频、课堂文本或答案。
 */
class AudioCaptureException(
    /** 平台错误码（AudioRecord 负值错误码；初始化失败时为 getMinBufferSize 返回值）。 */
    val code: Int,
    message: String,
) : Exception(message)
