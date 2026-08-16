package com.classsentinel.core.alert

import android.content.Context
import android.content.ContextWrapper
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AlertCoordinatorTest {

    // fake 通道不会触碰 context；ContextWrapper 是 mockable jar 中唯一可凭空实例化的 Context
    private val ctx: Context = ContextWrapper(null)
    private val event = ClassEvent(EventType.ROLLCALL, "张三，来回答一下", "张三，来回答一下", 1234L)

    private class FakeChannel(override val key: String) : AlertChannel {
        var fired = 0
        var lastEvent: ClassEvent? = null
        var lastContext: Context? = null

        override fun fire(event: ClassEvent, context: Context) {
            fired++
            lastEvent = event
            lastContext = context
        }
    }

    @Test
    fun `全开时 fire 调用全部通道并透传事件与上下文`() = runTest {
        val a = FakeChannel("a")
        val b = FakeChannel("b")
        val enabled = MutableStateFlow(setOf("a", "b"))
        val coordinator = AlertCoordinator(listOf(a, b), enabled, this)
        advanceUntilIdle()

        coordinator.fire(event, ctx)

        assertEquals(1, a.fired)
        assertEquals(1, b.fired)
        assertSame(event, a.lastEvent)
        assertSame(ctx, a.lastContext)
        coordinator.close()
    }

    @Test
    fun `禁用的通道不被调用`() = runTest {
        val a = FakeChannel("a")
        val b = FakeChannel("b")
        val enabled = MutableStateFlow(setOf("a"))
        val coordinator = AlertCoordinator(listOf(a, b), enabled, this)
        advanceUntilIdle()

        coordinator.fire(event, ctx)

        assertEquals(1, a.fired)
        assertEquals(0, b.fired)
        assertEquals(null, b.lastEvent)
        coordinator.close()
    }

    @Test
    fun `动态切换 enabledFlow 立即生效`() = runTest {
        val a = FakeChannel("a")
        val b = FakeChannel("b")
        val enabled = MutableStateFlow(setOf("a", "b"))
        val coordinator = AlertCoordinator(listOf(a, b), enabled, this)
        advanceUntilIdle()

        coordinator.fire(event, ctx)          // 第一轮：都开
        enabled.value = setOf("b")            // 运行时关掉 a
        advanceUntilIdle()
        coordinator.fire(event, ctx)          // 第二轮：只剩 b

        assertEquals(1, a.fired)              // 第二轮 a 未被调用
        assertEquals(2, b.fired)
        coordinator.close()
    }

    @Test
    fun `无通道时 fire 不抛异常`() = runTest {
        val coordinator = AlertCoordinator(emptyList(), MutableStateFlow(setOf("a")), this)
        advanceUntilIdle()
        coordinator.fire(event, ctx)
        coordinator.close()
    }
}