package com.classsentinel.core.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v0.2 Task 7：AudioRecord 初始化与 read-loop 错误处理的纯逻辑测试。
 *
 * 只测 [classifyAudioRead] 分类契约与 [AudioCaptureException] typed 失败类型，
 * 不触碰 android.media（JVM 单测 stub），不连接真机麦克风。
 */
class AudioStreamerLogicTest {

    // AudioRecord 错误码字面量（android.media 在 JVM 单测是 stub，不直接引用）
    private val errorInvalidOperation = -3 // AudioRecord.ERROR_INVALID_OPERATION
    private val errorBadValue = -2         // AudioRecord.ERROR_BAD_VALUE
    private val errorDeadObject = -6       // AudioRecord.ERROR_DEAD_OBJECT

    @Test
    fun `positive read count classifies as Data with count`() {
        assertEquals(AudioReadResult.Data(1), classifyAudioRead(1))
        assertEquals(AudioReadResult.Data(1024), classifyAudioRead(1024))
        assertEquals(AudioReadResult.Data(16000), classifyAudioRead(16000))
    }

    @Test
    fun `zero read classifies as RetryLater`() {
        assertEquals(AudioReadResult.RetryLater, classifyAudioRead(0))
    }

    @Test
    fun `ERROR_INVALID_OPERATION classifies as Fatal`() {
        assertEquals(AudioReadResult.Fatal(errorInvalidOperation), classifyAudioRead(errorInvalidOperation))
    }

    @Test
    fun `fatal read after an explicit stop is a graceful completion decision`() {
        assertTrue(
            shouldGracefullyCompleteAfterStop(
                result = AudioReadResult.Fatal(errorInvalidOperation),
                stopRequested = true,
            ),
        )
        assertFalse(
            shouldGracefullyCompleteAfterStop(
                result = AudioReadResult.Fatal(errorInvalidOperation),
                stopRequested = false,
            ),
        )
        assertFalse(
            shouldGracefullyCompleteAfterStop(
                result = AudioReadResult.Data(1),
                stopRequested = true,
            ),
        )
    }

    @Test
    fun `ERROR_BAD_VALUE classifies as Fatal`() {
        assertEquals(AudioReadResult.Fatal(errorBadValue), classifyAudioRead(errorBadValue))
    }

    @Test
    fun `ERROR_DEAD_OBJECT classifies as Fatal`() {
        assertEquals(AudioReadResult.Fatal(errorDeadObject), classifyAudioRead(errorDeadObject))
    }

    @Test
    fun `any other negative read classifies as Fatal preserving code`() {
        assertEquals(AudioReadResult.Fatal(-1), classifyAudioRead(-1))
        assertEquals(AudioReadResult.Fatal(-4), classifyAudioRead(-4))
        assertEquals(AudioReadResult.Fatal(-100), classifyAudioRead(-100))
    }

    @Test
    fun `classifier never returns Data for non positive reads`() {
        for (n in -10..0) {
            assertTrue("n=$n 不应分类为 Data", classifyAudioRead(n) !is AudioReadResult.Data)
        }
    }

    @Test
    fun `AudioCaptureException carries typed code and non empty message`() {
        val ex = AudioCaptureException(
            code = errorDeadObject,
            message = "音频读取失败: 采集设备错误 (code=-6)",
        )
        assertEquals(errorDeadObject, ex.code)
        assertTrue(ex.message.orEmpty().contains("code=-6"))
        assertTrue(ex.message.orEmpty().isNotBlank())
        // 红线：消息不得包含原始音频、课堂文本或答案（本批次约定）
        assertTrue(ex.message.orEmpty().length < 200)
    }

    /**
     * 回归红线（Task 7 返工）：初始化状态失败也必须走 release/finally。
     *
     * 原实现里 `rec.state != INITIALIZED` 的 throw 在 try/finally 之前，
     * AudioRecord 永不 release —— 这是资源泄漏，纯分类测试证明不了。
     * 本测试建模生产路径：open 成功构造资源（AudioRecord 已分配），
     * block 里的状态校验抛 typed 失败 → 资源必须被 close() 释放。
     */
    @Test
    fun `initialization state failure still closes the audio resource`() = runBlocking {
        var closed = 0
        try {
            useAudioRecordResource(
                open = { "rec" },
                close = { closed++ },
            ) { throw AudioCaptureException(code = errorBadValue, message = "AudioRecord 状态失败") }
            fail("状态校验抛异常时 use 必须向上传播")
        } catch (e: AudioCaptureException) {
            assertEquals(errorBadValue, e.code)
        }
        assertEquals("状态失败后资源必须被 close() 释放", 1, closed)
    }

    @Test
    fun `open failure propagates without closing unallocated resource`() = runBlocking {
        var closed = 0
        try {
            useAudioRecordResource(
                open = { throw AudioCaptureException(code = errorBadValue, message = "构造失败") },
                close = { closed++ },
            ) { fail("open 抛异常时 use 不得进入 body") }
            fail("open 抛异常时 use 必须向上传播")
        } catch (e: AudioCaptureException) {
            assertEquals(errorBadValue, e.code)
        }
        // open 失败 = 资源从未分配（AudioRecord 构造抛异常），无可释放，close 不应被调用
        assertEquals("open 失败时无资源可释放", 0, closed)
    }

    @Test
    fun `use closes resource when body throws`() = runBlocking {
        var closed = 0
        try {
            useAudioRecordResource(
                open = { },
                close = { closed++ },
            ) { throw IllegalStateException("body boom") }
            fail("body 抛异常时 use 必须向上传播")
        } catch (e: IllegalStateException) {
            assertEquals("body boom", e.message)
        }
        assertEquals("body 抛异常后资源必须被 close() 释放", 1, closed)
    }

    @Test
    fun `use closes resource on normal completion`() = runBlocking {
        var closed = 0
        val result = useAudioRecordResource(
            open = { "rec" },
            close = { closed++ },
        ) { it.length }
        assertEquals(3, result)
        assertEquals("正常完成后资源必须被 close() 释放", 1, closed)
    }
}
