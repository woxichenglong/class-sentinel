package com.classsentinel.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.core.study.StudyArtifactGenerator
import com.classsentinel.core.study.BilingualOutput
import com.classsentinel.core.study.StudyGenerationResult
import com.classsentinel.data.entities.StudyArtifactEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyArtifactWorkerTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val config = LlmConfig(
        baseUrl = "https://llm.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
    )

    @Test
    fun `successful flashcard generation persists RUNNING then SUCCEEDED`() = runBlocking {
        val deps = FakeDependencies(
            transcript = "课堂内容",
            generator = StudyArtifactGenerator(
                streamChat = { _, _ -> flowOf("[{\"question\":\"q\",\"answer\":\"a\"}]") },
            ),
        )

        val result = worker(deps).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf("RUNNING", "SUCCEEDED"),
            deps.saved.map { it.status },
        )
        assertEquals("FLASHCARDS", deps.saved.last().type)
        assertTrue(deps.saved.last().contentJson.orEmpty().contains("question"))
    }

    @Test
    fun `llm failure persists safe FAILED state and exposes retryable UI status`() = runBlocking {
        val rawProviderFailure = "provider body with classroom answer"
        val deps = FakeDependencies(
            transcript = "课堂内容",
            generator = StudyArtifactGenerator(
                streamChat = { _, _ ->
                    kotlinx.coroutines.flow.flow { throw IllegalStateException(rawProviderFailure) }
                },
            ),
        )

        val result = worker(deps).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(listOf("RUNNING", "FAILED"), deps.saved.map { it.status })
        assertEquals(StudyArtifactWorker.ERROR_CODE_GENERATION, deps.saved.last().error)
        assertFalse(result.toString().contains(rawProviderFailure))
    }

    @Test
    fun `bilingual failure preserves original content while exposing FAILED status`() = runBlocking {
        val original = "The original marked sentence must remain unchanged."
        val deps = FakeDependencies(
            transcript = original,
            generator = StudyArtifactGenerator(
                streamChat = { _, _ ->
                    kotlinx.coroutines.flow.flow { throw IllegalStateException("provider body") }
                },
            ),
        )

        val result = worker(
            deps,
            type = StudyArtifactEntity.TYPE_BILINGUAL_SUMMARY,
            mode = StudyArtifactWorker.MODE_MARKED_TEXT,
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(listOf("RUNNING", "FAILED"), deps.saved.map { it.status })
        val preserved = StudyArtifactGenerator.parseBilingual(deps.saved.last().contentJson.orEmpty())
        assertEquals(BilingualOutput(original, null), preserved)
    }

    @Test
    fun `work request carries no transcript or provider secret and requires connected network`() {
        val request = StudyArtifactWorker.buildRequest(
            courseId = 42L,
            type = StudyArtifactEntity.TYPE_QUIZ,
            mode = StudyArtifactWorker.MODE_FULL_TRANSCRIPT,
        )

        assertEquals(
            setOf(
                StudyArtifactWorker.KEY_COURSE_ID,
                StudyArtifactWorker.KEY_ARTIFACT_TYPE,
                StudyArtifactWorker.KEY_MODE,
            ),
            request.workSpec.input.keyValueMap.keys,
        )
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    private fun worker(
        deps: StudyArtifactWorkerDependencies,
        type: String = StudyArtifactEntity.TYPE_FLASHCARDS,
        mode: String = StudyArtifactWorker.MODE_FULL_TRANSCRIPT,
    ): StudyArtifactWorker =
        TestListenableWorkerBuilder.from(context, StudyArtifactWorker::class.java)
            .setInputData(
                Data.Builder()
                    .putLong(StudyArtifactWorker.KEY_COURSE_ID, 7L)
                    .putString(StudyArtifactWorker.KEY_ARTIFACT_TYPE, type)
                    .putString(StudyArtifactWorker.KEY_MODE, mode)
                    .build(),
            )
            .build()
            .apply { dependencies = deps }

    private class FakeDependencies(
        private val transcript: String,
        override val generator: StudyArtifactGenerator,
    ) : StudyArtifactWorkerDependencies {
        val saved = mutableListOf<StudyArtifactEntity>()

        override suspend fun transcriptForCourse(courseId: Long, type: String, mode: String): String = transcript

        override suspend fun aiConfig(): LlmConfig? = LlmConfig(
            baseUrl = "https://llm.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
        )

        override suspend fun artifactForCourse(courseId: Long, type: String): StudyArtifactEntity? =
            saved.lastOrNull()

        override suspend fun saveArtifact(artifact: StudyArtifactEntity): Long {
            val id = if (artifact.id == 0L) 1L else artifact.id
            saved += artifact.copy(id = id)
            return id
        }
    }
}
