package com.classsentinel.core.speech

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductionAsrEngineStarterTest {

    @Test
    fun `X ASR preparation failure propagates without creating an engine`() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("synthetic init failure")
        val starter = starter(calls) {
            calls += "verify"
            throw failure
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { starter.create() }
        }

        assertSame(failure, thrown)
        assertEquals(listOf("prepare", "verify"), calls)
    }

    @Test
    fun `cancellation propagates unchanged without trying another engine`() {
        val cancellation = CancellationException("synthetic cancellation")
        val calls = mutableListOf<String>()
        val starter = starter(calls) {
            calls += "verify"
            throw cancellation
        }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { starter.create() }
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf("prepare", "verify"), calls)
    }

    private fun starter(
        calls: MutableList<String>,
        prepare: suspend () -> Unit,
    ): ProductionAsrEngineStarter = ProductionAsrEngineStarter(
        prepareDirectory = {
            calls += "prepare"
            prepare()
            File("build/production-asr-starter")
        },
        createEngine = {
            calls += "create"
            object : ProfileBoundStreamingSpeechEngine {
                override val name = "fake"
                override val modelProfileId = ModelProfiles.PRODUCTION.id
                override val sampleRate = ModelProfiles.PRODUCTION.recognizer.sampleRate
                override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = emptyFlow()
            }
        },
    )
}
