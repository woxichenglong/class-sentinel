package com.classsentinel.core.speech

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackSpeechEngineTest {

    private class ScriptedEngine(
        override val name: String,
        private val script: (Int) -> Flow<String>,
    ) : SpeechEngine {
        var calls: Int = 0
            private set

        override fun transcribe(pcm: Flow<ShortArray>): Flow<String> {
            calls++
            return script(calls)
        }
    }

    @Test
    fun `fallback returns to primary after three successful fallback sentences`() = runTest {
        val primary = ScriptedEngine("primary") { attempt ->
            if (attempt == 1) {
                flow { throw IOException("primary unavailable") }
            } else {
                flowOf("primary recovered")
            }
        }
        val fallback = ScriptedEngine("fallback") {
            flowOf("fallback one", "fallback two", "fallback three")
        }
        val engine = FallbackSpeechEngine(listOf(primary, fallback))

        val output = engine.transcribe(flowOf(shortArrayOf(1))).toList()

        assertEquals(
            listOf("fallback one", "fallback two", "fallback three", "primary recovered"),
            output,
        )
        assertEquals(2, primary.calls)
        assertEquals(1, fallback.calls)
        assertEquals("primary", engine.activeEngine.value)
    }
}
