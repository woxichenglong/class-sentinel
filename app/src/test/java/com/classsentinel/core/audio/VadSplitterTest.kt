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

    // ---- v0.2 Task 4: WavSegment 元数据（稳定字符串 id + 单调 offset + WAV bytes）----

    @Test
    fun `segments emit stable monotonic string ids s1 s2`() = runTest {
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }
        val segs = VadSplitter().segments(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()
        assertEquals(2, segs.size)
        assertEquals(listOf("s1", "s2"), segs.map { it.id })
        // id 不重复
        assertEquals(segs.size, segs.map { it.id }.toSet().size)
    }

    @Test
    fun `segment offsets are deterministic monotonic and end gt start`() = runTest {
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }
        val segs = VadSplitter().segments(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()
        assertEquals(2, segs.size)
        // 16k/16-bit/mono：1s 大声 + 1.6s 静音 → 段1 在 30 帧静音处 flush，
        // 段1 [0, 2200ms)；再 1s 大声（起点 3000ms）→ 段2 [3000, 5200ms)
        assertEquals(0L, segs[0].startOffsetMs)
        assertEquals(2200L, segs[0].endOffsetMs)
        assertEquals(3000L, segs[1].startOffsetMs)
        assertEquals(5200L, segs[1].endOffsetMs)
        assertTrue(segs[0].endOffsetMs > segs[0].startOffsetMs)
        assertTrue(segs[1].endOffsetMs > segs[1].startOffsetMs)
        // 顺序不倒退：下一段起点不早于上一段终点
        assertTrue(segs[1].startOffsetMs >= segs[0].endOffsetMs)
    }

    @Test
    fun `segment ids are deterministic across re-collection`() = runTest {
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }
        val src = flowOf(loud, quiet, quiet, loud, quiet, quiet)
        val splitter = VadSplitter()
        val first = splitter.segments(src).toList()
        val second = splitter.segments(src).toList()
        // 计数是每次收集（session）内的状态：重新收集仍从 s1 开始，不漂移
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.map { it.startOffsetMs }, second.map { it.startOffsetMs })
        assertEquals(first.map { it.endOffsetMs }, second.map { it.endOffsetMs })
    }

    @Test
    fun `segments bytes equal legacy split output`() = runTest {
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }
        val src = flowOf(loud, quiet, quiet, loud, quiet, quiet)
        val splitter = VadSplitter()
        val segs = splitter.segments(src).toList()
        val legacy = splitter.split(src).toList()
        assertEquals(legacy.size, segs.size)
        for (i in legacy.indices) {
            assertTrue("segment $i bytes mismatch", legacy[i].contentEquals(segs[i].bytes))
        }
    }

    @Test
    fun `segments bytes carry valid wav header`() = runTest {
        val loud = ShortArray(8000) { 8000 }
        val segs = VadSplitter().segments(flowOf(loud)).toList()
        val wav = segs[0].bytes
        assertEquals('R'.code, wav[0].toInt() and 0xFF)
        assertEquals('I'.code, wav[1].toInt() and 0xFF)
        assertEquals('W'.code, wav[8].toInt() and 0xFF)
        assertEquals('A'.code, wav[9].toInt() and 0xFF)
        val dataLen = (wav[40].toInt() and 0xFF) or
            ((wav[41].toInt() and 0xFF) shl 8) or
            ((wav[42].toInt() and 0xFF) shl 16) or
            ((wav[43].toInt() and 0xFF) shl 24)
        assertEquals(wav.size - 44, dataLen)
    }

    @Test
    fun `max segment length forces flush with correct ids and offsets`() = runTest {
        // 6s 连续说话，maxSegmentMs=2000 → 每 2s 切一段：s1/s2/s3
        val speech = ShortArray(16000 * 6) { 8000 }
        val segs = VadSplitter(maxSegmentMs = 2000).segments(flowOf(speech)).toList()
        assertEquals(3, segs.size)
        assertEquals(listOf("s1", "s2", "s3"), segs.map { it.id })
        assertEquals(listOf(0L, 2000L, 4000L), segs.map { it.startOffsetMs })
        assertEquals(listOf(2000L, 4000L, 6000L), segs.map { it.endOffsetMs })
    }
}
