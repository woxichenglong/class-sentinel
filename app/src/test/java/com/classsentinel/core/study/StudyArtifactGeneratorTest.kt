package com.classsentinel.core.study

import com.classsentinel.core.llm.LlmConfig
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StudyArtifactGeneratorTest {
    private val config = LlmConfig(
        baseUrl = "https://llm.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
    )

    @Test
    fun `parses fenced and unfenced flashcard JSON`() {
        val json = "[{\"question\":\"什么是傅里叶变换？\",\"answer\":\"把信号分解到频域。\"}]"

        assertEquals(
            listOf(Flashcard("什么是傅里叶变换？", "把信号分解到频域。")),
            StudyArtifactGenerator.parseFlashcards(json),
        )
        assertEquals(
            listOf(Flashcard("什么是傅里叶变换？", "把信号分解到频域。")),
            StudyArtifactGenerator.parseFlashcards("```json\n$json\n```"),
        )
    }

    @Test
    fun `missing fields and hallucinated prose are rejected`() {
        assertParseFailure("[{\"question\":\"缺答案\"}]")
        assertParseFailure("这里是一些解释\n[{\"question\":\"q\",\"answer\":\"a\"}]")
        assertParseFailure("[{\"question\":\"q\",\"answer\":\"a\"}]\n以上就是结果")
    }

    @Test
    fun `quiz correct index must be inside options`() {
        val invalid = "[{\"question\":\"q\",\"options\":[\"a\",\"b\"],\"correctIndex\":2,\"explanation\":\"因为 a。\"}]"
        assertParseFailure(invalid)

        val valid = "[{\"question\":\"q\",\"options\":[\"a\",\"b\"],\"correctIndex\":1,\"explanation\":\"因为 b。\"}]"
        assertEquals(
            listOf(QuizQuestion("q", listOf("a", "b"), 1, "因为 b。")),
            StudyArtifactGenerator.parseQuiz(valid),
        )
    }

    @Test
    fun `long transcript is compressed before final flashcard request`() = runBlocking {
        val requests = mutableListOf<List<Map<String, String>>>()
        val generator = StudyArtifactGenerator(
            streamChat = { messages, _ ->
                requests += messages
                if (messages.first()["content"].orEmpty().contains("压缩")) {
                    flowOf("本段要点")
                } else {
                    flowOf("[{\"question\":\"q\",\"answer\":\"a\"}]")
                }
            },
        )

        val result = generator.generateFlashcards("课堂内容".repeat(1_100), config)

        assertTrue(result is StudyGenerationResult.Success<*>)
        assertEquals(3, requests.size)
        assertTrue(requests.first().first()["content"].orEmpty().contains("压缩"))
        assertTrue(requests.last().last()["content"].orEmpty().contains("本段要点"))
    }

    private fun assertParseFailure(raw: String) {
        try {
            StudyArtifactGenerator.parseFlashcards(raw)
            fail("invalid study JSON was accepted")
        } catch (_: StudyJsonParseException) {
            // expected strict parser rejection
        }
    }
}
