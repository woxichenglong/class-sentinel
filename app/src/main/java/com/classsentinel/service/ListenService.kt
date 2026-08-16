package com.classsentinel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.classsentinel.MainActivity
import com.classsentinel.R
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.alert.EarSoundChannel
import com.classsentinel.core.alert.FlashScreenChannel
import com.classsentinel.core.alert.NotifyChannel
import com.classsentinel.core.alert.RingtoneChannel
import com.classsentinel.core.alert.VibratorChannel
import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.audio.VadSplitter
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventEngine
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.detect.NameMatcher
import com.classsentinel.core.llm.AnswerService
import com.classsentinel.core.llm.AnswerStyle
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.core.pipeline.ListenPipeline
import com.classsentinel.core.speech.FallbackSpeechEngine
import com.classsentinel.core.speech.SenseVoiceEngine
import com.classsentinel.core.speech.TeleSpeechEngine
import com.classsentinel.core.speech.XunfeiRtasrEngine
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import com.classsentinel.service.LiveStreamBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 听讲前台服务：常驻通知「正在听讲」，麦克风采集 → ASR（TeleSpeech 主 + SenseVoice 兜底）
 * → 事件检测 → AlertCoordinator 分发提醒。
 * 通过 ACTION_START / ACTION_STOP 控制，停止时取消全部协程。
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinator: AlertCoordinator? = null
    private var listenJob: Job? = null
    /** 当前课程 id（START 建课，STOP 时写 endTs） */
    private var currentCourseId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                if (listenJob == null) beginListening()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // STOP / 系统销毁：补写课程结束时间（fire-and-forget，不阻塞主线程）
        currentCourseId?.let { cid ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    AppDatabase.get(this@ListenService).courseDao().updateEndTs(cid, System.currentTimeMillis())
                }
            }
        }
        currentCourseId = null
        coordinator?.close()
        coordinator = null
        listenJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** 组装并启动监听管线：pipeline → segments → EventEngine → AlertCoordinator */
    private fun beginListening() {
        scope.launch {
            // 先确保配置已加载（key/名字热读；2026-08-16 真机 bug：引擎构造快照了空 key → 401）
            val loadOk = runCatching {
                SettingsRepositoryHolder.get(this@ListenService).load()
            }.onFailure { e -> Log.e(TAG, "配置加载失败，中止启动: ${e.message}") }.isSuccess
            if (!loadOk) {
                stopSelf()
                return@launch
            }
            Log.d(TAG, "load 完成, asrKey=${if (AppConfig.siliconApiKey.isNotEmpty()) "有值" else "空!"}, names=${AppConfig.names.value.map { it.display }}")

            // P1 修复：VAD 阈值/分段时长/引擎选择真正生效（此前设置页可调但管线不用）
            val repo = SettingsRepositoryHolder.get(this@ListenService)
            val vadDb = runCatching { repo.vadDbFlow.first() }.getOrDefault(-35)
            val segSec = runCatching { repo.segmentMaxSecFlow.first() }.getOrDefault(8)
            val asrChoice = runCatching { repo.asrEngineFlow.first() }.getOrDefault("telespeech")
            Log.d(TAG, "引擎配置: vadDb=$vadDb segSec=${segSec}s asr=$asrChoice")
            val vad = VadSplitter(silenceDb = vadDb, maxSegmentMs = segSec * 1000)
            val engines = when (asrChoice) {
                "xunfei" -> listOf(
                    XunfeiRtasrEngine(AppConfig.xunfeiAppId, AppConfig.xunfeiApiKey),
                    TeleSpeechEngine(AppConfig.siliconApiKey, vad),
                )
                "sensevoice" -> listOf(
                    SenseVoiceEngine(AppConfig.siliconApiKey, vad),
                    TeleSpeechEngine(AppConfig.siliconApiKey, vad),
                )
                else -> listOf(
                    TeleSpeechEngine(AppConfig.siliconApiKey, vad),
                    SenseVoiceEngine(AppConfig.siliconApiKey, vad),
                )
            }
            val speech = FallbackSpeechEngine(engines)
            val pipeline = ListenPipeline(AudioStreamer(), speech)
            val engine = EventEngine(NameMatcher(AppConfig.names), AppConfig.sensitivity)
            val alert = AlertCoordinator(
                channels = listOf(
                    VibratorChannel(),
                    RingtoneChannel(),
                    NotifyChannel(),
                    FlashScreenChannel(),
                    EarSoundChannel(),
                ),
                enabledFlow = AppConfig.enabledChannels,
            )
            coordinator = alert

            // START 落库：建课并拿到 id
            val courseId = withContext(Dispatchers.IO) {
                AppDatabase.get(this@ListenService).courseDao().insert(
                    CourseEntity(
                        title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                        startTs = System.currentTimeMillis(),
                    ),
                )
            }
            currentCourseId = courseId
            val db = AppDatabase.get(this@ListenService)
            var chunkSeq = 0
            Log.d(TAG, "建课完成 courseId=$courseId")

            // 先注册收集再启动管道（避免漏前几句）
            listenJob = scope.launch {
                pipeline.segments.collect { segment ->
                    // P1 修复：真实管线接入 Live 总线（此前仅自检模拟推送）
                    LiveStreamBus.pushSegment(segment)
                    LiveStreamBus.pushState(pipeline.state.value)
                    withContext(Dispatchers.IO) {
                        db.transcriptDao().insert(
                            TranscriptChunkEntity(
                                courseId = courseId,
                                seq = chunkSeq++,
                                text = segment,
                                ts = System.currentTimeMillis(),
                            ),
                        )
                    }
                    engine.process(segment)?.let { event ->
                        LiveStreamBus.pushEvent(event)
                        var eventId: Long? = null
                        withContext(Dispatchers.IO) {
                            eventId = db.eventDao().insert(
                                EventEntity(
                                    courseId = courseId,
                                    type = event.type.name,
                                    triggerText = event.triggerText,
                                    contextText = event.context,
                                    answerText = null,
                                    notifiedAt = event.ts,
                                    ts = event.ts,
                                ),
                            )
                        }
                        alert.fire(event, this@ListenService)
                        // P1 修复：LLM 答题与悬浮窗接入主流程
                        when (event.type) {
                            EventType.ROLLCALL ->
                                FloatAnswerWindow.showRollcall(this@ListenService, event.triggerText)
                            EventType.QUESTION ->
                                launchAnswer(event, eventId, db)
                        }
                    }
                }
            }
            pipeline.start(scope)
            Log.d(TAG, "pipeline 已启动")
        }
    }

    /** LLM 流式答题：悬浮窗展示 + 答案回填 DB（P1 修复：主流程接线） */
    private fun launchAnswer(event: ClassEvent, eventId: Long?, db: AppDatabase) {
        scope.launch {
            val repo = SettingsRepositoryHolder.get(this@ListenService)
            val ai = runCatching { repo.aiSettingsFlow.first() }.getOrNull()
            if (ai == null || ai.apiKey.isBlank()) {
                Log.w(TAG, "AI 未配置，跳过答题")
                return@launch
            }
            val styleRaw = runCatching { repo.answerStyleFlow.first() }.getOrDefault("terseness")
            val style = if (styleRaw == "academic") AnswerStyle.ACADEMIC else AnswerStyle.TERSENESS
            val sb = StringBuilder()
            try {
                withContext(Dispatchers.Main) {
                    FloatAnswerWindow.show(this@ListenService, event.triggerText)
                }
                AnswerService().answer(
                    question = event.triggerText,
                    context = event.context,
                    style = style,
                    cfg = LlmConfig(ai.baseUrl, ai.apiKey, ai.model),
                ).collect { delta ->
                    sb.append(delta)
                    withContext(Dispatchers.Main) {
                        FloatAnswerWindow.appendAnswer(delta)
                    }
                }
                val answer = sb.toString().trim()
                if (answer.isNotBlank() && eventId != null) {
                    withContext(Dispatchers.IO) {
                        db.eventDao().updateAnswer(eventId, answer)
                    }
                }
                Log.d(TAG, "答题完成: ${answer.take(60)}")
            } catch (e: Exception) {
                Log.w(TAG, "答题失败: ${e.message}")
            }
        }
    }

    // ---- 前台通知 ----

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在听讲…")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "听讲状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "ClassSentinel"
        const val ACTION_START = "com.classsentinel.action.START"
        const val ACTION_STOP = "com.classsentinel.action.STOP"
        const val CHANNEL_ID = "listen_service"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ListenService::class.java).apply { action = ACTION_START }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ListenService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
