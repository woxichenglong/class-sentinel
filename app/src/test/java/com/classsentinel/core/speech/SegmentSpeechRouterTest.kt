package com.classsentinel.core.speech

import com.classsentinel.core.audio.WavSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2 Task 5：单段路由（主引擎有限重试 + 同段 fallback）契约。
 *
 * 只针对 [SegmentSpeechRouter] 的可观测行为，不触碰真实 ASR / 网络 / 凭证：
 * - 主引擎成功：只调用一次，result 标记 primary；
 * - 主引擎 transient 失败（NETWORK / RATE_LIMIT / SERVER）：对同一段有限重试，
 *   之后依次尝试 fallback，fallback 必须收到原段（同一 id / 同一 bytes 对象）；
 * - EMPTY 允许尝试 fallback（尽管 AsrError.retriable=false）；AUTH / CONFIG / UNKNOWN
 *   不重试、不锤 fallback，直接 typed failure；
 * - 每段从 primary 开始；s1 成功后 s2 失败不得重放 s1；
 * - 引擎切换/恢复通过 [SegmentSpeechRouter.events] StateFlow 可观测，事件只含
 *   engine / segmentId / 状态，不含文本、key、body。
 */
class SegmentSpeechRouterTest {

    private fun segment(id: String, filler: Int = 7): WavSegment {
        val bytes = ByteArray(44 + 16000 * 2) { (filler * 13 + it).toByte() }
        return WavSegment(id = id, startOffsetMs = 0, endOffsetMs = 1000, bytes = bytes)
    }

    /** 记录每次调用；无调用次数上限（由测试自行决定何时抛错/成功）。 */
    private class RecordingEngine(
        override val name: String,
        private val handler: (attempt: Int, segment: WavSegment) -> Result<String>,
    ) : SegmentSpeechEngine {
        val seen = mutableListOf<Pair<Int, WavSegment>>() // (attempt, segment)
        override suspend fun transcribeSegment(segment: WavSegment): Result<String> {
            val attempt = seen.size + 1
            seen += attempt to segment
            return handler(attempt, segment)
        }
    }

    private fun netFail(): Result<String> = Result.failure(AsrException(AsrError.network("down")))
    private fun rateFail(): Result<String> = Result.failure(AsrException(AsrError.fromHttp(429)))
    private fun serverFail(): Result<String> = Result.failure(AsrException(AsrError.fromHttp(503)))
    private fun authFail(): Result<String> = Result.failure(AsrException(AsrError.fromHttp(401)))
    private fun configFail(): Result<String> = Result.failure(AsrException(AsrError(AsrError.Kind.CONFIG, retriable = false)))
    private fun unknownFail(): Result<String> = Result.failure(AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)))
    private fun emptyFail(): Result<String> = Result.failure(AsrException(AsrError.emptyText()))
    private fun ok(text: String): Result<String> = Result.success(text)

    private fun router(
        primary: SegmentSpeechEngine,
        fallbacks: List<SegmentSpeechEngine>,
        maxPrimaryRetries: Int = 2,
        retryDelayMillis: Long = 0L,
    ) = SegmentSpeechRouter(
        primary = primary,
        fallbacks = fallbacks,
        maxPrimaryRetries = maxPrimaryRetries,
        retryDelayMillis = retryDelayMillis,
    )

    // ---- 最小 RED 场景：transient primary → fallback 接住同一段 ---- //

    @Test
    fun `transient primary then fallback success returns fallback result for same segment`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { attempt, _ ->
            if (attempt == 1) netFail() else ok("should-not-reach")
        }
        val fallback = RecordingEngine("fallback") { _, seg ->
            assertEquals("s1", seg.id)
            ok("fallback-${seg.id}")
        }

        val result = router(primary, listOf(fallback), maxPrimaryRetries = 0).transcribeSegment(s1)

        assertTrue("expected success, was ${result.exceptionOrNull()}", result.isSuccess)
        val success = result.getOrThrow()
        assertEquals("s1", success.segmentId)
        assertEquals("fallback-s1", success.text)
        assertEquals("fallback", success.engine)
        // maxPrimaryRetries=0：primary 只尝试一次（失败即降级），不进入重试
        assertEquals(listOf(1 to s1), primary.seen)
        assertEquals(listOf(1 to s1), fallback.seen)
    }

    // ---- 主引擎成功：一次、不降级 ---- //

    @Test
    fun `primary success calls primary once and no fallback`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> ok("主") }
        val fallback = RecordingEngine("fallback") { _, _ -> ok("备") }

        val result = router(primary, listOf(fallback)).transcribeSegment(s1)

        val success = result.getOrThrow()
        assertEquals("s1", success.segmentId)
        assertEquals("主", success.text)
        assertEquals("primary", success.engine)
        assertEquals(listOf(1 to s1), primary.seen)
        assertTrue("fallback must not be called", fallback.seen.isEmpty())
    }

    // ---- transient 重试：有界、同段、注入延迟 ---- //

    @Test
    fun `transient primary retries bounded times then fallback receives same segment`() = runTest {
        val s1 = segment("s1")
        var primaryCalls = 0
        val primary = RecordingEngine("primary") { attempt, seg ->
            primaryCalls++
            assertSame("same segment object each retry", s1, seg)
            when (attempt) {
                1 -> netFail()
                2 -> rateFail()
                else -> serverFail()
            }
        }
        val fallback = RecordingEngine("fallback") { _, seg ->
            assertSame(s1, seg)
            ok("saved")
        }

        // maxPrimaryRetries=2 → 总共最多 3 次 primary 尝试（attempt 1,2,3）
        val result = router(primary, listOf(fallback), maxPrimaryRetries = 2).transcribeSegment(s1)

        assertEquals("saved", result.getOrThrow().text)
        assertEquals(3, primaryCalls)
        assertEquals(3, primary.seen.size)
        assertEquals(listOf(1, 2, 3), primary.seen.map { it.first })
        // 重试失败后 fallback 收到同一段
        assertEquals(listOf(1 to s1), fallback.seen)
    }

    @Test
    fun `retry delay is injected not slept in test clock`() = runTest {
        val s1 = segment("s1")
        val delays = mutableListOf<Long>()
        val primary = RecordingEngine("primary") { attempt, _ ->
            if (attempt <= 2) netFail() else ok("finally")
        }
        val router = SegmentSpeechRouter(
            primary = primary,
            fallbacks = listOf(RecordingEngine("fb") { _, _ -> ok("x") }),
            maxPrimaryRetries = 2,
            retryDelayMillis = 60_000L, // 若走真实 delay 测试将等待 60s（不可接受）
            onDelay = { delays += it },
        )

        val result = router.transcribeSegment(s1)

        assertEquals("finally", result.getOrThrow().text)
        assertEquals(3, primary.seen.size)
        assertEquals("delay must be injected, not slept through", 2, delays.size)
    }

    @Test
    fun `retry backoff doubles from base`() = runTest {
        val s1 = segment("s1")
        val delays = mutableListOf<Long>()
        val primary = RecordingEngine("primary") { attempt, _ ->
            if (attempt <= 2) netFail() else ok("第三次成功")
        }
        val router = SegmentSpeechRouter(
            primary = primary,
            fallbacks = emptyList(),
            maxPrimaryRetries = 2,
            retryDelayMillis = 100L,
            onDelay = { delays += it },
        )

        val result = router.transcribeSegment(s1)

        assertEquals("第三次成功", result.getOrThrow().text)
        assertEquals("第 1 次重试用 base，第 2 次翻倍", listOf(100L, 200L), delays)
    }

    // ---- 非 transient：AUTH / CONFIG / UNKNOWN 不重试、不降级 ---- //

    @Test
    fun `auth failure is not retried and no fallback`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> authFail() }
        val fallback = RecordingEngine("fallback") { _, _ -> ok("nope") }

        val failure = router(primary, listOf(fallback), maxPrimaryRetries = 3).transcribeSegment(s1)
            .exceptionOrNull()

        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.AUTH, failure.error.kind)
        assertFalse(failure.error.retriable)
        assertEquals("auth must not be retried", listOf(1 to s1), primary.seen)
        assertTrue("auth must not trigger fallback", fallback.seen.isEmpty())
    }

    @Test
    fun `config failure is not retried and no fallback`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> configFail() }
        val fallback = RecordingEngine("fallback") { _, _ -> ok("nope") }

        val failure = router(primary, listOf(fallback), maxPrimaryRetries = 3).transcribeSegment(s1)
            .exceptionOrNull()

        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.CONFIG, failure.error.kind)
        assertEquals(listOf(1 to s1), primary.seen)
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `unknown failure is not retried and no fallback`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> unknownFail() }
        val fallback = RecordingEngine("fallback") { _, _ -> ok("nope") }

        val failure = router(primary, listOf(fallback), maxPrimaryRetries = 3).transcribeSegment(s1)
            .exceptionOrNull()

        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.UNKNOWN, failure.error.kind)
        assertEquals(listOf(1 to s1), primary.seen)
        assertTrue(fallback.seen.isEmpty())
    }

    // ---- EMPTY：允许尝试 fallback（即使 retriable=false）---- //

    @Test
    fun `empty result allows fallback without retry`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> emptyFail() }
        val fallback = RecordingEngine("fallback") { _, seg ->
            assertEquals("s1", seg.id)
            ok("备用文本")
        }

        val result = router(primary, listOf(fallback), maxPrimaryRetries = 3).transcribeSegment(s1)

        val success = result.getOrThrow()
        assertEquals("s1", success.segmentId)
        assertEquals("备用文本", success.text)
        assertEquals("fallback", success.engine)
        // EMPTY 不重试（primary 只调用一次），但允许尝试 fallback
        assertEquals(listOf(1 to s1), primary.seen)
        assertEquals(listOf(1 to s1), fallback.seen)
    }

    // ---- 全部失败：typed failure ---- //

    @Test
    fun `all engines fail returns typed failure with last useful error`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { attempt, _ ->
            when (attempt) {
                1 -> netFail()
                2 -> serverFail()
                3 -> rateFail()
                else -> serverFail()
            }
        }
        val fallback1 = RecordingEngine("fb1") { _, _ -> netFail() }
        val fallback2 = RecordingEngine("fb2") { _, _ -> emptyFail() }

        val failure = router(
            primary,
            listOf(fallback1, fallback2),
            maxPrimaryRetries = 2,
        ).transcribeSegment(s1).exceptionOrNull()

        assertTrue("must be typed AsrException, was ${failure?.javaClass?.name}", failure is AsrException)
        failure as AsrException
        // 全部失败 → 携带最后一个尝试引擎（最后一个 fallback）的 typed error
        assertEquals(AsrError.Kind.EMPTY, failure.error.kind)
        assertFalse(failure.error.retriable)
        assertEquals(3, primary.seen.size)
        assertEquals(listOf(1 to s1), fallback1.seen)
        assertEquals(listOf(1 to s1), fallback2.seen)
    }

    // ---- 跨段：s1 成功，s2 失败，不重放 s1 ---- //

    @Test
    fun `successful s1 is not replayed when s2 fails`() = runTest {
        val s1 = segment("s1", filler = 1)
        val s2 = segment("s2", filler = 2)
        val primary = RecordingEngine("primary") { _, seg ->
            if (seg.id == "s1") ok("第一段") else netFail()
        }
        val fallback = RecordingEngine("fallback") { _, seg ->
            if (seg.id == "s2") netFail() else ok("重放!")
        }
        val r = router(primary, listOf(fallback), maxPrimaryRetries = 0)

        val first = r.transcribeSegment(s1).getOrThrow()
        val second = r.transcribeSegment(s2).exceptionOrNull()

        assertEquals("第一段", first.text)
        assertEquals("primary", first.engine)
        assertTrue(second is AsrException)
        assertEquals(AsrError.Kind.NETWORK, (second as AsrException).error.kind)
        // 所有引擎对 s2 失败 → 只涉及 s1、s2；没有任何引擎对 s1 的第二次调用
        assertEquals(listOf("s1", "s2"), primary.seen.map { it.second.id })
        assertEquals(listOf("s2"), fallback.seen.map { it.second.id })
    }

    // ---- 恢复：每段都从 primary 开始 ---- //

    @Test
    fun `primary recovers on next segment after a fallback`() = runTest {
        val s1 = segment("s1")
        val s2 = segment("s2")
        val primary = RecordingEngine("primary") { _, seg ->
            if (seg.id == "s1") netFail() else ok("第二段")
        }
        val fallback = RecordingEngine("fallback") { _, seg ->
            if (seg.id == "s1") ok("第一段-备用") else ok("不应被调用")
        }
        val r = router(primary, listOf(fallback), maxPrimaryRetries = 0)

        val first = r.transcribeSegment(s1).getOrThrow()
        val second = r.transcribeSegment(s2).getOrThrow()

        assertEquals("第一段-备用", first.text)
        assertEquals("fallback", first.engine)
        assertEquals("第二段", second.text)
        assertEquals("primary", second.engine) // s2 重新从 primary 开始
        assertEquals(listOf("s1", "s2"), primary.seen.map { it.second.id })
        assertEquals(listOf("s1"), fallback.seen.map { it.second.id })
    }

    // ---- 可观测性：events 状态流 ---- //

    @Test
    fun `events observe retry fallback and recovery without leaking text`() = runTest {
        val s1 = segment("s1")
        val s2 = segment("s2")
        val primary = RecordingEngine("primary") { attempt, seg ->
            if (seg.id == "s1" && attempt == 1) netFail() else if (seg.id == "s1") netFail() else ok("第二段")
        }
        val fallback = RecordingEngine("fallback") { _, seg ->
            if (seg.id == "s1") ok("备用") else ok("x")
        }
        val r = router(primary, listOf(fallback), maxPrimaryRetries = 1)

        r.transcribeSegment(s1).getOrThrow()
        r.transcribeSegment(s2).getOrThrow()

        val events = r.events.value
        // 事件只含 engine / segmentId / 状态，不含文本、key、body
        assertTrue("expected events, got $events", events.isNotEmpty())
        events.forEach { e ->
            assertTrue("event text must not leak, got $e", e.engine.isNotBlank())
        }
        // s1 的最终状态是 fallback；s2 的最终状态回到 primary
        assertEquals("fallback", events.last { it.segmentId == "s1" }.engine)
        assertEquals("primary", events.last { it.segmentId == "s2" }.engine)
        assertTrue(events.filter { it.segmentId == "s1" }.size >= 2) // primary 尝试 + fallback
    }

    // ---- 取消语义：CancellationException 必须上抛，不能吞成 failure ---- //

    @Test
    fun `cancellation from primary is rethrown not treated as failure`() = runTest {
        val s1 = segment("s1")
        var cancelled = 0
        val primary = RecordingEngine("primary") { _, _ ->
            cancelled++
            throw kotlinx.coroutines.CancellationException("cancelled")
        }
        val fallback = RecordingEngine("fallback") { _, _ -> ok("不应") }
        val r = router(primary, listOf(fallback), maxPrimaryRetries = 3)

        val thrown = try {
            r.transcribeSegment(s1)
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue(
            "CancellationException must propagate, was ${thrown?.javaClass?.name}",
            thrown is kotlinx.coroutines.CancellationException,
        )
        assertEquals(1, cancelled) // 取消立即上抛，不进入重试
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `plain ioexception is normalized to network and retried`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { attempt, _ ->
            if (attempt == 1) Result.failure(java.io.IOException("boom"))
            else ok("恢复了")
        }
        val fallback = RecordingEngine("fallback") { _, _ -> ok("x") }

        val result = router(primary, listOf(fallback), maxPrimaryRetries = 1).transcribeSegment(s1)

        assertEquals("恢复了", result.getOrThrow().text)
        assertEquals(2, primary.seen.size)
        assertTrue(fallback.seen.isEmpty())
    }

    @Test
    fun `all engines ioexception failure carries network typed error`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ ->
            Result.failure(java.io.IOException("boom"))
        }
        val fallback = RecordingEngine("fallback") { _, _ ->
            Result.failure(java.io.IOException("also down"))
        }
        val r = router(primary, listOf(fallback), maxPrimaryRetries = 1)

        val failure = r.transcribeSegment(s1).exceptionOrNull()

        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.NETWORK, failure.error.kind)
        assertTrue(failure.error.retriable)
        assertEquals(2, primary.seen.size)
        assertEquals(1, fallback.seen.size)
    }

    @Test
    fun `fallbacks tried in order`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> netFail() }
        val fb1 = RecordingEngine("fb1") { _, _ -> netFail() }
        val fb2 = RecordingEngine("fb2") { _, _ -> ok("老二接住") }
        val fb3 = RecordingEngine("fb3") { _, _ -> ok("老三") }
        val r = router(primary, listOf(fb1, fb2, fb3), maxPrimaryRetries = 1)

        val result = r.transcribeSegment(s1).getOrThrow()

        assertEquals("老二接住", result.text)
        assertEquals("fb2", result.engine)
        assertEquals(2, primary.seen.size)
        assertEquals(listOf(1 to s1), fb1.seen)
        assertEquals(listOf(1 to s1), fb2.seen)
        assertTrue("fb3 must not be reached", fb3.seen.isEmpty())
    }

    @Test
    fun `multiple fallbacks all fail returns last fallback error`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> netFail() }
        val fb1 = RecordingEngine("fb1") { _, _ -> serverFail() }
        val fb2 = RecordingEngine("fb2") { _, _ -> rateFail() }
        val r = router(primary, listOf(fb1, fb2), maxPrimaryRetries = 0)

        val failure = r.transcribeSegment(s1).exceptionOrNull()

        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.RATE_LIMIT, failure.error.kind)
        assertEquals(listOf(1 to s1), primary.seen)
        assertEquals(listOf(1 to s1), fb1.seen)
        assertEquals(listOf(1 to s1), fb2.seen)
    }

    // ---- cooldown：primary 连续 transient 失败 → 有限段数跳过 primary ---- //

    @Test
    fun `two consecutive primary transient failures trigger one segment cooldown then primary recovers`() = runTest {
        val s1 = segment("s1")
        val s2 = segment("s2")
        val s3 = segment("s3")
        val s4 = segment("s4")
        val primary = RecordingEngine("primary") { _, seg ->
            when (seg.id) {
                "s1", "s2" -> netFail() // 连续两段 transient 失败 → 计数到阈值
                "s3" -> ok("cooldown 段不应到达 primary") // canary：若被调用即为 bug
                else -> ok("第四段-primary-恢复")
            }
        }
        val fallback = RecordingEngine("fallback") { _, seg ->
            if (seg.id == "s3") assertSame("fallback 必须收到同一段对象", s3, seg)
            ok("fb-${seg.id}")
        }
        val r = SegmentSpeechRouter(
            primary = primary,
            fallbacks = listOf(fallback),
            maxPrimaryRetries = 0, // 每段 primary 只尝试一次，耗尽即计一次失败
            cooldownAfterPrimaryFailures = 2,
            primaryCooldownSegments = 1,
        )

        val results = listOf(s1, s2, s3, s4).map { r.transcribeSegment(it) }

        assertTrue(
            "all must succeed, got ${results.map { it.exceptionOrNull() }}",
            results.all { it.isSuccess },
        )
        // s1/s2 各耗尽 primary 配额（各 1 次）→ 计数到 2 → s3 进入 1 段 cooldown
        assertEquals(listOf("s1", "s2", "s4"), primary.seen.map { it.second.id })
        assertEquals(listOf("s1", "s2", "s3"), fallback.seen.map { it.second.id })
        assertEquals("fallback", results[2].getOrThrow().engine) // s3 由 fallback 接住同一段
        assertEquals("fb-s3", results[2].getOrThrow().text)
        assertEquals("第四段-primary-恢复", results[3].getOrThrow().text) // s4 恢复 primary 且成功
    }

    @Test
    fun `non-retriable server error is not retried but fallback receives same segment`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ ->
            Result.failure(AsrException(AsrError(AsrError.Kind.SERVER, retriable = false, message = "gone")))
        }
        val fallback = RecordingEngine("fallback") { _, seg ->
            assertSame(s1, seg)
            ok("备用")
        }
        val r = SegmentSpeechRouter(
            primary = primary,
            fallbacks = listOf(fallback),
            maxPrimaryRetries = 3, // 若 isTransientRetriable 只看 kind，这里会重试 3 次
        )

        val result = r.transcribeSegment(s1)

        val success = result.getOrThrow()
        assertEquals("备用", success.text)
        assertEquals("fallback", success.engine)
        assertEquals("SERVER(retriable=false) 不得触发 primary retry", listOf(1 to s1), primary.seen)
        assertEquals(listOf(1 to s1), fallback.seen)
        assertTrue(
            "no RETRY event expected, got ${r.events.value}",
            r.events.value.none { it.status == SegmentSpeechRouter.RouterEvent.Status.RETRY },
        )
    }

    @Test
    fun `cooldown without fallback never drops a segment and still attempts primary`() = runTest {
        val s1 = segment("s1")
        val s2 = segment("s2")
        val s3 = segment("s3")
        val primary = RecordingEngine("primary") { _, seg ->
            if (seg.id == "s3") ok("第三段终于成功") else netFail()
        }
        val r = SegmentSpeechRouter(
            primary = primary,
            fallbacks = emptyList(),
            maxPrimaryRetries = 0,
            cooldownAfterPrimaryFailures = 2,
            primaryCooldownSegments = 1,
        )

        val r1 = r.transcribeSegment(s1).exceptionOrNull()
        val r2 = r.transcribeSegment(s2).exceptionOrNull()
        val r3 = r.transcribeSegment(s3).getOrThrow()

        assertTrue(r1 is AsrException)
        assertTrue(r2 is AsrException)
        assertEquals("第三段终于成功", r3.text)
        // 无 fallback：cooldown 不丢段，每个 segment 都仍尝试 primary
        assertEquals(listOf("s1", "s2", "s3"), primary.seen.map { it.second.id })
    }

    // ---- RED: 失败段持久化回调 seam（onSegmentFailed）----
    // 目标契约：primary + fallback 全部失败时，在返回 typed failure 的同时恰好回调一次，
    // 回调收到与输入同一 WavSegment 对象和 NETWORK typed error；不得重复回调。

    @Test
    fun `onSegmentFailed invoked once with same segment object and network error when all engines fail`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> netFail() }
        val fallback = RecordingEngine("fallback") { _, _ -> netFail() }
        val failed = mutableListOf<Pair<WavSegment, AsrException>>()

        val r = SegmentSpeechRouter(
            primary = primary,
            fallbacks = listOf(fallback),
            maxPrimaryRetries = 0,
            onSegmentFailed = { seg, error ->
                failed += seg to error
            },
        )

        val result = r.transcribeSegment(s1)

        // 结果仍为 typed failure
        assertTrue("expected typed failure, was ${result.exceptionOrNull()}", result.isFailure)
        val failure = result.exceptionOrNull()
        assertTrue("must be AsrException, was ${failure?.javaClass?.name}", failure is AsrException)
        assertEquals(AsrError.Kind.NETWORK, (failure as AsrException).error.kind)
        // 回调恰好一次：收到同一 WavSegment 对象 + NETWORK error
        assertEquals("callback must fire exactly once, got $failed", 1, failed.size)
        assertSame("callback must receive the same WavSegment object", s1, failed[0].first)
        assertEquals(AsrError.Kind.NETWORK, failed[0].second.error.kind)
        // primary/fallback 各尝试一次，无重试
        assertEquals(listOf(1 to s1), primary.seen)
        assertEquals(listOf(1 to s1), fallback.seen)
    }

    // ---- RED (Task 16): Router 不得吞失败段持久化回调异常 ----
    // 若 onSegmentFailed 里的持久化（如 PendingAudioRecovery.persist）因磁盘/DAO 失败抛异常，
    // 该异常必须可被调用方捕获/观察到，绝不能得到正常 Result.failure 后静默丢段。

    @Test
    fun `persistence callback exception propagates instead of being swallowed`() = runTest {
        val s1 = segment("s1")
        val primary = RecordingEngine("primary") { _, _ -> netFail() }
        val persistFailure = IllegalStateException("disk full")

        val r = SegmentSpeechRouter(
            primary = primary,
            fallbacks = emptyList(),
            maxPrimaryRetries = 0,
            onSegmentFailed = { _, _ -> throw persistFailure },
        )

        val thrown = try {
            r.transcribeSegment(s1)
            null
        } catch (t: Throwable) {
            t
        }

        assertSame(
            "persistence failure must propagate, was ${thrown?.javaClass?.name}",
            persistFailure,
            thrown,
        )
        // primary 只尝试一次（失败即收口）；无 fallback
        assertEquals(listOf(1 to s1), primary.seen)
    }
}
