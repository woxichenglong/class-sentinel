package com.classsentinel.core.alert

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.classsentinel.core.detect.EventType

/**
 * 全屏闪屏页：传统 View 实现（非 Compose），亮屏 + 大字提示，15 秒后自动关闭。
 * Manifest 中单独注册（exported=false，无边框黑色全屏主题）。
 */
class FlashScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏/息屏也能亮屏显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val eventTypeName = intent.getStringExtra(EXTRA_EVENT_TYPE)
        val isRollcall = eventTypeName?.let { name ->
            runCatching { EventType.valueOf(name) }.getOrNull()
        } != EventType.QUESTION
        val trigger = intent.getStringExtra(EXTRA_TRIGGER_TEXT).orEmpty()

        val title = if (isRollcall) "老师点名了！" else "老师提问了！"
        val subtitle = trigger.ifBlank { "快看看是不是你！" }

        val titleView = TextView(this).apply {
            text = title
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(48, 16, 48, 0)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#C62828"))
            addView(titleView)
            addView(subtitleView)
        }
        setContentView(root)

        root.postDelayed({ finish() }, AUTO_DISMISS_MS)
    }

    companion object {
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_TRIGGER_TEXT = "trigger_text"
        private const val AUTO_DISMISS_MS = 15_000L
    }
}
