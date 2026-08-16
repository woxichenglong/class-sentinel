package com.classsentinel.core.alert

import android.content.Context
import com.classsentinel.core.detect.ClassEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 提醒协调器：订阅 enabledFlow（如 AppConfig.enabledChannels），
 * fire 时只把事件分发给当前启用的通道。启用状态可运行时热切换。
 *
 * 启用集合读取策略：
 * - enabledFlow 是 StateFlow 时，fire 直接读其最新值（零订阅延迟，运行时切换立即生效）；
 * - 其他 Flow 时，用 [scope] 内协程缓存到 [enabled]。
 *
 * @param channels 全部可用通道（内部按 key 过滤）
 * @param enabledFlow 当前启用的通道 key 集合
 * @param scope 订阅 enabledFlow 的协程作用域（测试注入 TestScope）
 */
class AlertCoordinator(
    private val channels: List<AlertChannel>,
    private val enabledFlow: Flow<Set<String>>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val enabled = MutableStateFlow<Set<String>>(emptySet())

    private val collectJob: Job = scope.launch {
        enabledFlow.collect { enabled.value = it }
    }

    init {
        // StateFlow 直接取当前值，避免订阅异步到达前首条事件被吞
        if (enabledFlow is StateFlow) enabled.value = enabledFlow.value
    }

    /** 读取当前启用的通道 key 集合 */
    private fun enabledKeys(): Set<String> =
        if (enabledFlow is StateFlow) enabledFlow.value else enabled.value

    /** 触发提醒：只调用当前启用的通道 */
    fun fire(event: ClassEvent, context: Context) {
        val keys = enabledKeys()
        for (channel in channels) {
            if (channel.key in keys) {
                channel.fire(event, context)
            }
        }
    }

    /** 停止订阅启用状态（Service 销毁时调用） */
    fun close() {
        collectJob.cancel()
    }
}