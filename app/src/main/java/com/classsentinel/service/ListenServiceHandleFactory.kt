package com.classsentinel.service

import android.content.Context
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.alert.NotifyChannel
import com.classsentinel.core.alert.VibratorChannel
import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.context.TranscriptContextBuffer
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventEngine
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.detect.FinalTranscript
import com.classsentinel.core.detect.NameMatcher
import com.classsentinel.core.pipeline.StreamingListenPipeline
import com.classsentinel.core.speech.SherpaModelInstaller
import com.classsentinel.core.speech.SherpaOnnxRecognizerFactory
import com.classsentinel.core.speech.SherpaOnnxStreamingEngine
import com.classsentinel.core.speech.ModelProfile
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.ProfileBoundStreamingSpeechEngine
import com.classsentinel.core.speech.StreamingAsrEvent
import com.classsentinel.core.speech.StreamingSpeechEngine
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.CourseRepository
import com.classsentinel.data.STALE_RUNNING_COURSE_TIMEOUT_MS
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import com.classsentinel.worker.SummaryWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真实会话句柄工厂：按 ListenService 现有装配方式（配置 → 本地连续 ASR → 管线 →
 * 事件检测 → 提醒协调器）构建真实监听会话，交给 [ListenSessionController] 管理生命周期。
 * 纯适配层，不包含任何 Service / 通知 / manifest 生命周期代码。
 */
internal class ListenServiceHandleFactory(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onCoordinator: (AlertCoordinator) -> Unit,
    private val onQuestion: (ClassEvent, Long?, AppDatabase) -> Unit,
) {

    suspend fun create(): ListenSessionHandle {
        // 配置加载失败：固定安全文案，绝不把异常原文（可能含凭证/课堂文本）带入状态或日志。
        val settings = SettingsRepositoryHolder.get(context)
        try {
            settings.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("配置加载失败")
        }
        val selectedProfile = ModelProfiles.resolveDaily(settings.localAsrModelIdFlow.first())

        val modelDirectory = withContext(Dispatchers.IO) {
            SherpaModelInstaller(
                filesDir = context.applicationContext.filesDir,
                profile = selectedProfile,
                assetOpener = context.applicationContext.assets::open,
            ).install()
        }
        val db = AppDatabase.get(context)
        // 新会话开始前先收敛旧 RUNNING 课程；pending 音频不依赖前台进程存活。
        val repository = CourseRepository(db)
        withContext(Dispatchers.IO) {
            repository.abortStale(System.currentTimeMillis() - STALE_RUNNING_COURSE_TIMEOUT_MS)
        }
        val store = CourseSessionStoreAdapter(repository)
        val speech = createLiveStreamingSpeechEngine(modelDirectory, selectedProfile)
        val pipeline = StreamingListenPipeline(
            streamer = AudioStreamer(context = context),
            speech = speech,
            onStateChanged = LiveStreamBus::pushState,
        )
        val eventEngine = EventEngine(NameMatcher(AppConfig.names), AppConfig.sensitivity)
        val alert = AlertCoordinator(
            channels = listOf(
                VibratorChannel(),
                NotifyChannel(),
            ),
            enabledFlow = AppConfig.enabledChannels,
        )
        onCoordinator(alert)

        // 滚动课堂上下文：每个会话句柄一个缓冲，上限 60 秒 / 2000 字符。
        val contextBuffer = TranscriptContextBuffer(windowMs = 60_000L, maxChars = 2_000)
        val sessionPipeline = SessionPipelineAdapter(
            context = context,
            scope = scope,
            pipeline = pipeline,
            eventEngine = eventEngine,
            alert = alert,
            currentCourseId = store::currentCourseId,
            nextChunkSeq = store::nextChunkSeq,
            contextBuffer = contextBuffer,
            onQuestion = { event, eventId -> onQuestion(event, eventId, db) },
            insertTranscript = { chunk ->
                withContext(Dispatchers.IO) { db.transcriptDao().insert(chunk) }
            },
            insertEvent = { event ->
                withContext(Dispatchers.IO) { db.eventDao().insert(event) }
            },
        )
        return createControllerHandle(
            store = store,
            pipeline = sessionPipeline,
            context = context,
            db = db,
        )
    }
}

/**
 * 生产 ControllerHandle 装配边界：供 factory wiring 测试验证 STOP → finalize → hook。
 * hook 的具体业务资格由调用方传入，避免在生命周期层复制 SummaryWorker 规则。
 */
internal fun createControllerHandle(
    store: CourseSessionStore,
    pipeline: SessionPipeline,
    onCourseFinalized: suspend (Long) -> Unit,
): ListenSessionHandle = ControllerHandle(
    ListenSessionController(
        store = store,
        pipeline = pipeline,
        onCourseFinalized = onCourseFinalized,
    ),
)

/** 生产默认 hook：复用 SummaryWorker 的资格判断与唯一队列。 */
internal fun createControllerHandle(
    store: CourseSessionStore,
    pipeline: SessionPipeline,
    context: Context,
    db: AppDatabase,
): ListenSessionHandle = createControllerHandle(store, pipeline) { courseId ->
    SummaryWorker.enqueueIfEligible(context, db, courseId)
}

/** Live production seam: local sherpa streaming only; no VAD/HTTP fallback. */
internal fun createLiveStreamingSpeechEngine(
    modelDirectory: File,
    profile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
): ProfileBoundStreamingSpeechEngine =
    SherpaOnnxStreamingEngine(
        profile = profile,
        recognizerFactory = { SherpaOnnxRecognizerFactory.create(modelDirectory, profile) },
    )

/** 课程会话持久层适配：课程创建与收尾统一经 CourseRepository，不新增任何 Room 字段/迁移。 */
private class CourseSessionStoreAdapter(
    private val repository: CourseRepository,
) : CourseSessionStore {

    /** 本地课程 id：建课写入，供管线侧读取。 */
    private var courseId: Long? = null

    /** 本地块序号：每次建课归零，随转写按序递增。 */
    private var chunkSeq = 0

    override suspend fun createCourse(): Long {
        val id = withContext(Dispatchers.IO) {
            repository.createRunningCourse(
                title = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                startTs = System.currentTimeMillis(),
            )
        }
        courseId = id
        chunkSeq = 0
        LiveStreamBus.startCourse(id)
        return id
    }

    override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
        withContext(Dispatchers.IO) {
            repository.finalizeCourse(courseId, endTs)
        }
        LiveStreamBus.finishCourse(courseId)
    }

    fun currentCourseId(): Long? = courseId

    fun nextChunkSeq(): Int = chunkSeq++
}

/**
 * 听讲管线适配：包住 [StreamingListenPipeline]，保持 final 的顺序处理。
 * 独立的 service-scope 写 Job 串行化分段处理；收集器取消不影响在途写 Job。
 */
internal class SessionPipelineAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pipeline: StreamingListenPipeline,
    private val eventEngine: EventEngine,
    private val alert: AlertCoordinator,
    private val currentCourseId: () -> Long?,
    private val nextChunkSeq: () -> Int,
    private val contextBuffer: TranscriptContextBuffer,
    private val onQuestion: (ClassEvent, Long?) -> Unit,
    private val insertTranscript: suspend (TranscriptChunkEntity) -> Long,
    private val insertEvent: suspend (EventEntity) -> Long,
) : SessionPipeline {

    @Volatile
    private var collector: Job? = null

    @Volatile
    private var writeJob: Job? = null

    private val earlyRollcallAlertGate = EarlyRollcallAlertGate()

    override suspend fun start() {
        if (collector?.isActive == true) return
        val courseId = currentCourseId() ?: return
        eventEngine.resetSession()
        earlyRollcallAlertGate.clear()
        // 新课程/新会话开始时清空滚动上下文缓冲，避免跨课程残留。
        contextBuffer.clear()
        // 先注册唯一源收集器（UNDISPATCHED 立即订阅），再启动管线，避免漏句。
        val src = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { event ->
                when (event) {
                    is StreamingAsrEvent.Partial -> {
                        LiveStreamBus.pushPartial(
                            utteranceId = event.utteranceId,
                            text = event.text,
                            offsetMs = event.audioOffsetMs,
                        )
                        val provisional = eventEngine.processPartialRollcall(
                            utteranceId = event.utteranceId,
                            text = event.text,
                        )
                        if (provisional != null && earlyRollcallAlertGate.record(event.utteranceId)) {
                            // Provisional alert only: no LiveStreamBus event, Room row, or LLM.
                            alert.fire(provisional, context)
                        }
                    }

                    is StreamingAsrEvent.Final -> {
                        val earlyAlerted = earlyRollcallAlertGate.consume(event.utteranceId)
                        LiveStreamBus.pushFinal(
                            utteranceId = event.utteranceId,
                            text = event.text,
                            startOffsetMs = event.startOffsetMs,
                            endOffsetMs = event.endOffsetMs,
                        )
                        dispatchWrite(courseId, event, earlyAlerted)
                    }

                    is StreamingAsrEvent.UtteranceEnded -> {
                        LiveStreamBus.clearPartial(event.utteranceId)
                    }

                    else -> Unit
                }
            }
        }
        collector = src
        pipeline.start(scope)
    }

    /** 独立写 Job：join 前一个写 Job 实现串行；写 Job 是 scope 的孩子，收集器取消不会波及。 */
    private fun dispatchWrite(
        courseId: Long,
        final: StreamingAsrEvent.Final,
        earlyAlerted: Boolean,
    ) {
        val previous = writeJob
        val job = scope.launch {
            previous?.join()
            processSegment(courseId, final, earlyAlerted)
        }
        writeJob = job
    }

    /** Final-only 顺序：持久化 best-effort；事件提醒独立于任一 Room 写入。 */
    internal suspend fun processSegment(
        courseId: Long,
        final: StreamingAsrEvent.Final,
        earlyAlerted: Boolean,
    ) {
        val segment = final.text
        val seq = nextChunkSeq()
        val chunkTs = System.currentTimeMillis()
        val chunkId = bestEffortWrite {
            insertTranscript(
                TranscriptChunkEntity(
                    courseId = courseId,
                    seq = seq,
                    text = segment,
                    ts = chunkTs,
                    segmentId = "",
                    startOffsetMs = final.startOffsetMs,
                    endOffsetMs = final.endOffsetMs,
                ),
            )
        }
        // 只有 transcript 真正落库后才发布“最近一句”资格；final 文本已由 event collector
        // 推入 LiveBus，这里不能再次 pushSegment，否则每句 final 会在实时页面重复一次。
        chunkId?.let { LiveStreamBus.pushLatestChunk(courseId, it) }
        LiveStreamBus.pushState(pipeline.state.value)
        // 事件时间与 final 一致；事件上下文为当前段之前的滚动课堂上下文
        // 加上当前 final，供详情与 AnswerService 使用。
        val contextBeforeCurrent = contextBuffer.contextAt(chunkTs)
        contextBuffer.addFinal(
            FinalTranscript(
                utteranceId = final.utteranceId,
                text = segment,
                startOffsetMs = final.startOffsetMs,
                endOffsetMs = final.endOffsetMs,
            ),
            timestampMs = chunkTs,
        )
        eventEngine.processFinal(
            FinalTranscript(
                utteranceId = final.utteranceId,
                text = segment,
                startOffsetMs = final.startOffsetMs,
                endOffsetMs = final.endOffsetMs,
            ),
            ts = chunkTs,
        )?.let { event ->
            LiveStreamBus.pushEvent(event)
            val contextEvent = event.copy(
                context = (contextBeforeCurrent.takeIf { it.isNotBlank() }
                    ?.plus("\n")
                    .orEmpty() + segment).trim(),
            )
            val eventId = bestEffortWrite {
                insertEvent(
                    EventEntity(
                        courseId = courseId,
                        type = contextEvent.type.name,
                        triggerText = contextEvent.triggerText,
                        contextText = contextEvent.context,
                        answerText = null,
                        notifiedAt = contextEvent.ts,
                        ts = contextEvent.ts,
                    ),
                )
            }
            if (!(contextEvent.type == EventType.ROLLCALL && earlyAlerted)) {
                alert.fire(contextEvent, context)
            }
            when (contextEvent.type) {
                EventType.ROLLCALL -> Unit
                EventType.QUESTION -> if (eventId != null) onQuestion(contextEvent, eventId)
            }
        }
    }

    private suspend fun <T> bestEffortWrite(write: suspend () -> T): T? = try {
        withContext(Dispatchers.IO) { write() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    override suspend fun stop() {
        pipeline.stop()
        val src = collector
        if (src != null) {
            src.cancelAndJoin()
            collector = null
        }
        val w = writeJob
        if (w != null) {
            w.join()
            writeJob = null
        }
        earlyRollcallAlertGate.clear()
    }
}

/** 会话句柄：把 [ListenSessionController] 适配成 [ListenSessionHandle]。 */
private class ControllerHandle(
    private val controller: ListenSessionController,
) : ListenSessionHandle {
    override suspend fun start(): Boolean = controller.start()

    override suspend fun stop(): Boolean = controller.stop()
}
