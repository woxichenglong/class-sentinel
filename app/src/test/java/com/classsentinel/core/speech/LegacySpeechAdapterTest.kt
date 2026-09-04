package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Legacy 适配路径：`Flow<ShortArray> -> Flow<String>` 仍可用，
 * 且复用 [VadSplitter.segments] + 新单段接口（不复制 VAD、不静默吞错）。
 */
class LegacySpeechAdapterTest {

    private class FakeSegmentEngine(
        override val name: String = "fake",
        private val onSegment: (String) -> String,
    ) : SegmentSpeechEngine {
        override suspend fun transcribeSegment(segment: com.classsentinel.core.audio.WavSegment): Result<String> =
            runCatching { onSegment(segment.id) }
    }

    @Test
    fun `legacy transcribe splits via vad segments and emits once per segment`() = runTest {
        val seen = mutableListOf<String>()
        val engine = FakeSegmentEngine { id ->
            seen += id
            "text-$id"
        }
        val adapter = LegacySpeechAdapter(engine)
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }

        val texts = adapter.transcribe(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()

        assertEquals(listOf("text-s1", "text-s2"), texts)
        // 每段只转写一次，成功段只 emit 一次（无重复文本）
        assertEquals(listOf("s1", "s2"), seen)
    }

    @Test
    fun `legacy transcribe propagates typed failure instead of swallowing`() = runTest {
        val engine = FakeSegmentEngine { id ->
            if (id == "s1") throw AsrException(AsrError.fromHttp(500)) else "text-$id"
        }
        val adapter = LegacySpeechAdapter(engine)
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }

        val thrown = runCatching {
            adapter.transcribe(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()
        }.exceptionOrNull()

        // 失败段不再静默跳过：typed failure 上抛给 FallbackSpeechEngine / 上层
        assertEquals(true, thrown is AsrException)
        thrown as AsrException
        assertEquals(AsrError.Kind.SERVER, thrown.error.kind)
        assertEquals(true, thrown.error.retriable)
    }

    @Test
    fun `adapter reuses vad segments rather than reimplementing vad`() = runTest {
        val vad = VadSplitter()
        val engine = FakeSegmentEngine { it }
        val adapter = LegacySpeechAdapter(engine, vad)
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }

        val expected = vad.segments(flowOf(loud, quiet, quiet, loud, quiet, quiet))
            .toList().map { it.id }
        val got = adapter.transcribe(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()

        assertEquals(expected, got)
    }

    @Test
    fun `segment callback receives successful segment metadata exactly once`() = runTest {
        val captured = mutableListOf<Pair<String, String>>()
        val engine = FakeSegmentEngine { id -> "text-$id" }
        val adapter = LegacySpeechAdapter(
            engine = engine,
            onSegmentTranscribed = { segment, text -> captured += segment.id to text },
        )
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }

        adapter.transcribe(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()

        assertEquals(listOf("s1" to "text-s1", "s2" to "text-s2"), captured)
    }
}
