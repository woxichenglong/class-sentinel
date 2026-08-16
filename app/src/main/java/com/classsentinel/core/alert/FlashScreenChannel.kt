package com.classsentinel.core.alert

import android.content.Context
import android.content.Intent
import com.classsentinel.core.detect.ClassEvent

/**
 * 全屏闪屏通道：亮屏 + 大字提醒，适合手机放桌上/锁屏场景。
 * 后台启动受限（目标 35）时静默降级，不抛异常。
 */
class FlashScreenChannel : AlertChannel {

    override val key = "flash"

    override fun fire(event: ClassEvent, context: Context) {
        val intent = Intent(context, FlashScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FlashScreenActivity.EXTRA_EVENT_TYPE, event.type.name)
            putExtra(FlashScreenActivity.EXTRA_TRIGGER_TEXT, event.triggerText)
        }
        runCatching { context.startActivity(intent) }
    }
}
