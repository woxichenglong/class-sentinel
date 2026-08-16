package com.classsentinel.core.alert

import android.content.Context
import com.classsentinel.core.detect.ClassEvent

/**
 * 提醒通道统一接口：一种提醒方式一个实现。
 * 由 AlertCoordinator 按 AppConfig.enabledChannels 过滤后分发。
 */
interface AlertChannel {
    /** 通道唯一标识（与 AppConfig.enabledChannels 中的 key 对应） */
    val key: String

    /** 触发提醒 */
    fun fire(event: ClassEvent, context: Context)
}
