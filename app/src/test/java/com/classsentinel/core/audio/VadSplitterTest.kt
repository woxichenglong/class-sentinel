package com.classsentinel.core.audio

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSplitterTest {

    @Test
    fun `loud then silence flushes one wav segment`() = runTest {
        val loud = ShortArray(16000) { 8000 } // 1s 大声
        val quiet = ShortArray(16000) { 0 }   // 1s 静音
        val segs = VadSplitter(silenceDb = -35)
            .split(flowOf(loud, quiet, quiet)).toList()
        assertEquals(1, segs.size) // 800ms 静音即切分
        assertTrue(segs[0].size > 44) // 含 WAV 头
    }

    @Test
    fun `continuous speech stays one segment`() = runTest {
        val speech = ShortArray(16000 * 3) { 8000 } // 3s 无静音
        val segs = VadSplitter().split(flowOf(speech)).toList()
        assertEquals(1, segs.size)
    }

    @Test
    fun `two speeches split into two segments`() = runTest {
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }
        val segs = VadSplitter().split(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()
        assertEquals(2, segs.size)
    }

    @Test
    fun `max segment length forces flush`() = runTest {
        // 6s 连续说话，maxSegmentMs=2000 → 每 2s 切一段
        val speech = ShortArray(16000 * 6) { 8000 }
        val segs = VadSplitter(maxSegmentMs = 2000).split(flowOf(speech)).toList()
        assertEquals(3, segs.size)
    }

    @Test
    fun `silence only emits nothing`() = runTest {
        val quiet = ShortArray(16000) { 0 }
        val segs = VadSplitter().split(flowOf(quiet, quiet)).toList()
        assertEquals(0, segs.size)
    }

    @Test
    fun `wav header is valid`() = runTest {
        val loud = ShortArray(8000) { 8000 }
        val segs = VadSplitter().split(flowOf(loud)).toList()
        val wav = segs[0]
        assertEquals('R'.code, wav[0].toInt() and 0xFF)
        assertEquals('I'.code, wav[1].toInt() and 0xFF)
        assertEquals('W'.code, wav[8].toInt() and 0xFF)
        assertEquals('A'.code, wav[9].toInt() and 0xFF)
        // data 长度 = 总长 - 44
        val dataLen = (wav[40].toInt() and 0xFF) or
            ((wav[41].toInt() and 0xFF) shl 8) or
            ((wav[42].toInt() and 0xFF) shl 16) or
            ((wav[43].toInt() and 0xFF) shl 24)
        assertEquals(wav.size - 44, dataLen)
    }
}
