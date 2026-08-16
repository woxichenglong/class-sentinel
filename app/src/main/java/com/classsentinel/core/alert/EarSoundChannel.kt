package com.classsentinel.core.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.classsentinel.core.detect.ClassEvent

/**
 * 耳侧提示音通道：ToneGenerator 短促哔声，适合戴耳机/蓝牙耳机场景。
 */
class EarSoundChannel : AlertChannel {

    override val key = "ear"

    override fun fire(event: ClassEvent, context: Context) {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        generator.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
        // 播完再释放，避免 startTone 即被 release 截断
        Handler(Looper.getMainLooper()).postDelayed({ generator.release() }, 500)
    }
}
