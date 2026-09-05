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
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.llm.AnswerGenerationCoordinator
import com.classsentinel.core.llm.AnswerRequest
import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.llm.AnswerService
import com.classsentinel.core.llm.AnswerStyle
import com.classsentinel.core.llm.LlmError
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.core.llm.LlmException
import com.classsentinel.core.llm.answerFailureMessage
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.SettingsRepositoryHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 听讲前台服务：常驻通知「正在听讲」，麦克风采集 → 本地 sherpa-onnx 连续 ASR
 * → final 事件检测 → AlertCoordinator/答案通知。
 * 通过 ACTION_START / ACTION_STOP 控制，停止时取消全部协程。
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinator: AlertCoordinator? = null
    /** 同包 Robolectric 测试注入点：非空时 onCreate 将其作为本次会话句柄 */
    internal var sessionOverride: ListenServiceSession? = null
    /** 同包测试注入点：非空时跳过真实前台通知，仅执行该回调 */
    internal var foregroundOverride: (() -> Unit)? = null
    /** 当前会话句柄；注入或后续由 START 工厂装配 */
    private var serviceSession: ListenServiceSession? = null
    /** 前台通知状态收集协程；随服务销毁一并取消 */
    private var notificationJob: Job? = null
    /** Monotonic in-memory identity for answers whose event insert did not return a DB id. */
    private val transientAnswerSequence = AtomicLong(0L)
    private val answerResultHandler = AnswerResultHandler(
        persistAnswer = { eventId, answer ->
            withContext(Dispatchers.IO) {
                AppDatabase.get(applicationContext).eventDao().updateAnswer(eventId, answer)
            }
        },
        publish = { request, result ->
            LiveStreamBus.pushAnswer(
                eventId = request.eventId,
                question = request.question,
                context = request.context,
                timestampMs = System.currentTimeMillis(),
                result = result,
            )
            // Streaming 只更新进程内 UI，避免每个 delta 都刷新系统通知。
            if (request.eventId != null && result !is AnswerResult.Streaming) {
                publishAnswerNotification(request, result)
            }
        },
    )
    /** 每个 event ID 至多一个在途答案生成任务；重试复用原 event。 */
    private val answerCoordinator = AnswerGenerationCoordinator(
        scope = scope,
        generate = { request ->
            val cfg = request.llmConfig
                ?: throw LlmException(LlmError(LlmError.Kind.CONFIG))
            AnswerService().answer(
                question = request.question,
                context = request.context,
                style = request.style,
                cfg = cfg,
                answerLength = request.answerLength,
                streamOutput = request.streamOutput,
            )
        },
        onResult = { request, result -> handleAnswerResult(request, result) },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createAnswerNotificationChannel()
        serviceSession = sessionOverride ?: createDefaultSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RETRY -> {
                val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
                if (eventId > 0L) {
                    scope.launch {
                        val event = withContext(Dispatchers.IO) {
                            AppDatabase.get(applicationContext).eventDao().getQuestionById(eventId)
                        }
                        if (event != null) {
                            launchAnswer(
                                ClassEvent(
                                    type = EventType.QUESTION,
                                    triggerText = event.triggerText,
                                    context = event.contextText,
                                    ts = event.ts,
                                ),
                                event.id,
                            )
                        }
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                val session = serviceSession ?: sessionOverride ?: createDefaultSession()
                serviceSession = session
                session.stop(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 先把“启动中”发布给首页和前台通知；否则通知会在真正创建课程/管线前
                // 读取到初始 Idle，用户会误以为引擎根本没有开启。
                LiveStreamBus.pushState(PipelineState.Starting)
                startForegroundCompat()
                val session = serviceSession ?: sessionOverride ?: createDefaultSession()
                serviceSession = session
                session.start()
            }
            else -> return START_NOT_STICKY
        }
        // 录音是明确的用户动作，服务被系统回收后必须再次点击才能恢复；
        // 不让系统重放旧的 ACTION_START 而在后台自动打开麦克风。
        return START_NOT_STICKY
    }

    /** 默认会话工厂：装配真实监听句柄；测试通过 [sessionOverride] 注入替身绕过。 */
    private fun createDefaultSession(): ListenServiceSession = ListenServiceSession(
        scope = scope,
        createHandle = {
            ListenServiceHandleFactory(
                context = this@ListenService,
                scope = scope,
                onCoordinator = { coordinator = it },
                onQuestion = { event, eventId, _ -> launchAnswer(event, eventId) },
            ).create()
        },
        stopSelfResult = { id -> stopSelfResult(id) },
        onStartFailure = {
            LiveStreamBus.pushState(PipelineState.Error("监听启动失败"))
            stopSelf()
        },
    )

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        coordinator?.close()
        coordinator = null
        // onDestroy 不保证还能完成 Room 收尾；先释放进程内 UI 资格，数据库由启动时
        // 的 stale-course recovery 按超时规则兜底，避免首页永久显示“可停止”假会话。
        LiveStreamBus.activeCourseId.value?.let { LiveStreamBus.finishCourse(it) }
        LiveStreamBus.pushState(PipelineState.Idle)
        scope.cancel()
        super.onDestroy()
    }

    /** LLM 流式答题：coordinator 去重，结果更新原 event ID 与答案通知。 */
    private fun launchAnswer(event: ClassEvent, eventId: Long?) {
        val requestKey = eventId?.let { "event:$it" }
            ?: "transient:${transientAnswerSequence.incrementAndGet()}"
        scope.launch {
            val repo = SettingsRepositoryHolder.get(this@ListenService)
            val ai = try {
                repo.aiSettingsFlow.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (ai == null || ai.apiKey.isBlank()) {
                handleAnswerResult(
                    AnswerRequest(
                        eventId = eventId,
                        requestKey = requestKey,
                        question = event.triggerText,
                        context = event.context,
                    ),
                    AnswerResult.Failed("CONFIG"),
                )
                return@launch
            }
            val styleRaw = runCatching { repo.answerStyleFlow.first() }.getOrDefault("terseness")
            val style = if (styleRaw == "academic") AnswerStyle.ACADEMIC else AnswerStyle.TERSENESS
            val answerLength = runCatching { repo.answerLengthFlow.first() }.getOrDefault("mid")
            val streamOutput = runCatching { repo.streamOutputFlow.first() }.getOrDefault(true)
            answerCoordinator.submit(
                AnswerRequest(
                    eventId = eventId,
                    requestKey = requestKey,
                    question = event.triggerText,
                    context = event.context,
                    style = style,
                    llmConfig = LlmConfig(ai.baseUrl, ai.apiKey, ai.model),
                    answerLength = answerLength,
                    streamOutput = streamOutput,
                ),
            )
        }
    }

    private suspend fun handleAnswerResult(request: AnswerRequest, result: AnswerResult) =
        answerResultHandler.handle(request, result)

    private fun publishAnswerNotification(request: AnswerRequest, result: AnswerResult) {
        val eventId = request.eventId ?: return
        val answer = when (result) {
            AnswerResult.Generating -> "生成中…"
            is AnswerResult.Streaming -> result.text
            is AnswerResult.Succeeded -> result.answer
            is AnswerResult.Insufficient -> "依据不足，请检查课堂上下文后重试"
            is AnswerResult.Failed -> when (result.safeCode) {
                "ANSWER_SAVE" -> "答案已生成，但保存失败，请重试"
                else -> answerFailureMessage(result.safeCode)
            }
        }
        val notification = AnswerNotificationBuilder.build(
            context = this,
            eventId = eventId,
            answer = answer,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(AnswerNotificationBuilder.NOTIFICATION_ID, notification)
    }

    // ---- 前台通知 ----

    private fun startForegroundCompat() {
        foregroundOverride?.let {
            it()
            return
        }
        val notification = buildForegroundNotification(statusFor(LiveStreamBus.pipelineState.value))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (notificationJob?.isActive != true) {
            notificationJob = scope.launch {
                LiveStreamBus.pipelineState.collect { state ->
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIF_ID, buildForegroundNotification(statusFor(state)))
                }
            }
        }
    }

    private fun buildForegroundNotification(status: ListenNotificationStatus): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ListenService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return ListenNotificationBuilder.build(this, status, stopIntent)
    }

    /** 将管线状态映射为通知可安全展示的状态；绝不透传 Error/Recovering 的 message 文本。 */
    private fun statusFor(state: PipelineState): ListenNotificationStatus = when (state) {
        is PipelineState.Listening ->
            ListenNotificationStatus(state.elapsedMs, state.engine, state.pendingSegments)
        is PipelineState.Starting -> ListenNotificationStatus(0L, "准备中", 0)
        is PipelineState.Stopping -> ListenNotificationStatus(0L, "停止中", 0)
        is PipelineState.Recovering -> ListenNotificationStatus(0L, state.engine, 0)
        is PipelineState.Error -> ListenNotificationStatus(0L, "错误", 0)
        PipelineState.Idle -> ListenNotificationStatus(0L, "未开始", 0)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "听讲状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun createAnswerNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                AnswerNotificationBuilder.CHANNEL_ID,
                "课堂答案",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val ACTION_START = "com.classsentinel.action.START"
        const val ACTION_STOP = "com.classsentinel.action.STOP"
        const val ACTION_RETRY = "com.classsentinel.action.RETRY"
        const val EXTRA_EVENT_ID = AnswerNotificationBuilder.EXTRA_EVENT_ID
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

        fun retryAnswer(context: Context, eventId: Long) {
            if (eventId <= 0L) return
            context.startService(
                Intent(context, ListenService::class.java).apply {
                    action = ACTION_RETRY
                    putExtra(EXTRA_EVENT_ID, eventId)
                },
            )
        }
    }
}
