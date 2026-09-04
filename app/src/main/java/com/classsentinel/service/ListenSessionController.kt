package com.classsentinel.service

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 课程会话持久层抽象（M2b-1a 最小契约：仅创建课程；M2b-1b 增加 finalize）。 */
interface CourseSessionStore {
    suspend fun createCourse(): Long
    suspend fun finalizeCourse(courseId: Long, endTs: Long)
}

/** 听讲管线抽象（M2b-1a 最小契约：仅启动；M2b-1b 增加 stop）。 */
interface SessionPipeline {
    suspend fun start()
    suspend fun stop()
}

/** 会话生命周期状态。 */
sealed interface SessionState {
    data object Idle : SessionState
    data object Starting : SessionState
    data class Running(val courseId: Long) : SessionState
    data object Stopping : SessionState
    data class Error(val message: String) : SessionState
}

/**
 * M2b-1a：并发 start 幂等核心；M2b-1b：成功 stop/finalize。
 * start 仅保证一次通过（Idle/Error 允许进入，Starting 互斥），
 * 成功后先落 Running 再启动管线；stop 仅在 Running 时原子进入 Stopping，
 * 按 pipeline.stop() → finalizeCourse → 清 active id → Idle 顺序完成。
 * 异常路径（stop 抛错、Cancellation、Starting 竞态）由下一小批处理。
 */
class ListenSessionController(
    private val store: CourseSessionStore,
    private val pipeline: SessionPipeline,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onCourseFinalized: suspend (Long) -> Unit = {},
) {
    internal var onStartingPublished: suspend () -> Unit = {}

    private val lifecycleLock = Any()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** 当前激活会话 id；未激活或失败时为 null。 */
    @Volatile
    private var activeCourseId: Long? = null

    /** 当前激活会话的 stop 阶段标志：pipeline.stop 是否已成功。失败后保留以便重试时跳过。 */
    @Volatile
    private var pipelineStopped = false

    /** 当前激活会话的 stop 阶段标志：finalizeCourse 是否已成功。成功后清 active + 复位。 */
    @Volatile
    private var finalized = false

    /** start 在途（Starting，或 Running 且引用未清）的 start 协程 Job。stop 用它取消在途 start。 */
    @Volatile
    private var startJobRef: Job? = null

    /** 课程已 finalize 后的课后任务回调是否已成功执行；失败时保留活动会话以便重试。 */
    @Volatile
    private var courseFinalizationHookDone = false

    suspend fun start(): Boolean {
        val startJob = coroutineContext[Job]
        synchronized(lifecycleLock) {
            if (activeCourseId != null) return false
            when (_state.value) {
                SessionState.Idle, is SessionState.Error -> {
                    _state.value = SessionState.Starting
                    startJobRef = startJob
                }
                else -> return false
            }
        }
        try {
            onStartingPublished()
            val id = store.createCourse()
            if (activeCourseId != null) {
                // 极端竞态兜底：已有激活会话，放弃本次（不覆盖 Running 状态）。
                // 但本次已创建的课程不得遗留：NonCancellable 中收尾，父协程取消也不能中断补偿。
                withContext(NonCancellable) {
                    try {
                        store.finalizeCourse(id, nowMillis())
                    } catch (e2: Exception) {
                        // 补偿失败不吞取消：异常原文绝不进入状态（仅供上层日志）。
                    }
                }
                return false
            }
            activeCourseId = id
            pipelineStopped = false
            finalized = false
            courseFinalizationHookDone = false
            _state.value = SessionState.Running(id)
            pipeline.start()
            // 完成后立即在锁内清引用（身份校验）：堵住 post-start/pre-finally 小窗口内
            // stop 把已完成的 start 当作可取消在途任务的竞态；finally 的身份校验兜底保持无害。
            synchronized(lifecycleLock) {
                if (startJobRef === startJob) startJobRef = null
            }
            return true
        } catch (e: CancellationException) {
            // 取消：课程已创建则先尽力收尾（NonCancellable 中 finalize 不被本次取消中断），
            // 清理 active id/标志，状态回到 Idle，再原样传播取消（不改成 Error/成功）。
            val created = activeCourseId
            if (created != null) {
                withContext(NonCancellable) {
                    try {
                        store.finalizeCourse(created, nowMillis())
                    } catch (e2: Exception) {
                        // 补偿失败不吞取消：异常原文绝不进入状态（仅供上层日志）。
                    }
                }
            }
            activeCourseId = null
            pipelineStopped = false
            finalized = false
            courseFinalizationHookDone = false
            _state.value = SessionState.Idle
            throw e
        } catch (e: Exception) {
            // 课程已创建则收尾恰好一次，防止遗留孤儿课程。
            val created = activeCourseId
            if (created != null) {
                try {
                    store.finalizeCourse(created, nowMillis())
                } catch (e2: Exception) {
                    // 补偿失败：状态仍为固定安全文案，异常原文绝不进入状态（仅供上层日志）。
                }
            }
            activeCourseId = null
            courseFinalizationHookDone = false
            // 异常 message 可能携带 provider 原文/凭证/用户内容，绝不写入状态。
            _state.value = SessionState.Error("课程启动失败")
            return false
        } finally {
            synchronized(lifecycleLock) {
                if (startJobRef === startJob) startJobRef = null
            }
        }
    }

    /**
     * M2b-1b：成功路径 stop；M2b-1b-3：普通异常分阶段恢复。
     * 仅当 Running 时原子取出 active course 并进入 Stopping（CAS 保证并发只有一个赢家）；
     * Running 或 Error（上次 stop 失败留下的活动会话）允许进入重试。
     * 按 pipeline.stop() → store.finalizeCourse(id, nowMillis()) → 清 active id → Idle 顺序执行，
     * 每阶段成功后记录标志，重试时跳过已成功阶段；失败保留 active course 与标志，
     * 状态固定为安全 Error（异常原文绝不写入状态），返回 false 供上层重试。
     * 无活动会话 / 重复 stop / Starting / Stopping 一律不调用任何依赖、直接返回 false、不抛错。
     */
    suspend fun stop(): Boolean {
        // start 竞态：start 仍在途（Starting，或 Running 但 pipeline.start/清引用未完成）。
        // 取消独立 start Job 并等待其完成清理（start 的 CancellationException catch 会
        // finalize/回 Idle），从而消除孤儿课程。
        // 若 start 与 stop 在同一协程（direct 同步调用，无独立 start Job），不能取消 stop 所在
        // Job——安全返回 false，绝不破坏成功 stop/重试/取消行为。
        val myJob = coroutineContext[Job]
        val inFlight = synchronized(lifecycleLock) {
            val candidate = startJobRef
            // 取消在锁内与 Starting/Running 观察线性化；等待放在锁外，供 start 的 finally 清理引用。
            if ((_state.value is SessionState.Starting || _state.value is SessionState.Running) &&
                myJob != null && candidate != null && candidate !== myJob
            ) {
                candidate.cancel()
                candidate
            } else {
                null
            }
        }
        if (inFlight != null) {
            inFlight.join()
            // 取消请求可能发生在非协作式 pipeline.start() 已完成、但 start() 尚未
            // 清除 startJobRef 的窗口。此时 join 后仍是 Running，必须继续走正常
            // pipeline.stop → finalize 收尾，不能把“已处理”当成“已停止”。
            if (_state.value !is SessionState.Running) return true
        }
        val id = activeCourseId ?: return false
        while (true) {
            when (val s = _state.value) {
                is SessionState.Running -> {
                    if (s.courseId == id) {
                        if (_state.compareAndSet(s, SessionState.Stopping)) break
                    } else {
                        return false
                    }
                }
                is SessionState.Error -> {
                    // 上次 stop 失败的恢复：active course 仍在，允许进入 Stopping 重试。
                    if (_state.compareAndSet(s, SessionState.Stopping)) break
                }
                else -> return false
            }
        }
        try {
            if (!pipelineStopped) {
                pipeline.stop()
                pipelineStopped = true
            }
            if (!finalized) {
                store.finalizeCourse(id, nowMillis())
                finalized = true
            }
            if (!courseFinalizationHookDone) {
                onCourseFinalized(id)
                courseFinalizationHookDone = true
            }
        } catch (e: CancellationException) {
            // 取消：结构性中断，绝不停留在 Stopping。先在 NonCancellable 中把剩余 stop 阶段
            // （pipeline.stop / finalizeCourse，成功标志防重复）执行完，清理 active id/标志，
            // 状态回到 Idle，再原样传播取消（不改成 Error/成功）。
            try {
                withContext(NonCancellable) {
                    if (!pipelineStopped) {
                        pipeline.stop()
                        pipelineStopped = true
                    }
                    if (!finalized) {
                        store.finalizeCourse(id, nowMillis())
                        finalized = true
                    }
                    if (!courseFinalizationHookDone) {
                        try {
                            onCourseFinalized(id)
                            courseFinalizationHookDone = true
                        } catch (_: Exception) {
                            // 取消路径仍须原样传播；课后任务由后续显式重试/恢复处理。
                        }
                    }
                }
            } catch (e2: Exception) {
                // 清理阶段失败不吞取消：异常原文绝不进入状态，状态/active 仍有明确安全结果。
            }
            activeCourseId = null
            pipelineStopped = false
            finalized = false
            courseFinalizationHookDone = false
            _state.value = SessionState.Idle
            throw e
        } catch (e: Exception) {
            // 普通异常：保留 active course 与阶段标志以便重试；异常原文绝不进入状态。
            _state.value = SessionState.Error("课程停止失败")
            return false
        }
        activeCourseId = null
        pipelineStopped = false
        finalized = false
        courseFinalizationHookDone = false
        _state.value = SessionState.Idle
        return true
    }
}
