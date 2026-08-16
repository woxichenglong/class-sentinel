package com.classsentinel.core.alert

import android.content.Context
import android.media.RingtoneManager
import com.classsentinel.core.detect.ClassEvent

/**
 * 铃声通道：播放系统默认通知音。
 */
class RingtoneChannel : AlertChannel {

    override val key = "ringtone"

    override fun fire(event: ClassEvent, context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        val tone = RingtoneManager.getRingtone(context, uri) ?: return
        if (tone.isPlaying) tone.stop()
        tone.play()
    }
}
