package com.classsentinel.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话生命周期协调器（Task 9；纯 Kotlin/coroutines，无 Android/Room 依赖）。
 *
 * - [start] 幂等：在途 start 协程期间重复调用共享同一个 [Job]，只创建一次 handle、
 *   只调用一次 handle.start()；成功 stop 后丢弃 handle，下一次 start 重新创建。
 *   发布（发布 startJob 引用）后才启动协程，且不在持锁状态下执行任何用户 suspend 代码。
 * - [stop] 在 [scope] 中异步执行：优先取消并等待在途 start 协程，再停止当前 handle；
 *   不同 stop 调用用 mutex 串行化真正的 handle.stop()，每个调用仍独立执行 stopSelfResult。
 *   无论 stop 成功、抛异常还是被取消，都在 finally 中调用 [stopSelfResult]，异常原样传播。
 */
internal class ListenServiceSession(
    private val scope: CoroutineScope,
    private val createHandle: suspend () -> ListenSessionHandle,
    private val stopSelfResult: (Int) -> Boolean,
    private val onStartFailure: (Throwable) -> Unit = {},
) {

    private val lock = Any()

    /** 当前会话 handle；成功 stop 后清空（仅在锁内读写）。 */
    private var handle: ListenSessionHandle? = null

    /** 在途 start 协程 Job；完成时（身份校验后）清除（仅在锁内读写）。 */
    private var startJob: Job? = null

    /** 串行化真正的 handle.stop()，防止不同 stop 调用重复释放同一资源。 */
    private val handleStopMutex = Mutex()

    /** 幂等启动：取消但未完成的 start 仍视为在途；否则新建 Job。 */
    fun start(): Job {
        val job = synchronized(lock) {
            startJob?.takeIf { !it.isCompleted }?.let { return it }
            var self: Job? = null
            val newJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // 先发布引用（self/startJob）再执行；锁内不运行用户 suspend 代码。
                    val session = synchronized(lock) { handle } ?: createHandle().also { created ->
                        synchronized(lock) { if (handle == null) handle = created }
                    }
                    if (!session.start()) {
                        throw IllegalStateException("SESSION_START_REJECTED")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Service 启动失败必须收敛为可观察的安全回调；不能让
                    // SupervisorJob 子协程异常把“通知已显示、管线已死”留给用户。
                    runCatching { onStartFailure(e) }
                } finally {
                    synchronized(lock) { if (startJob === self) startJob = null }
                }
            }
            self = newJob
            startJob = newJob
            newJob
        }
        job.start()
        return job
    }

    /**
     * 异步停止：优先取消/等待在途 start，再重新读取 handle 并停止它。
     *
     * 重新读取是关键：底层 start 可能在取消请求后仍非协作式地进入 Running；
     * 只等待 start Job 会把已经打开的 AudioRecord/native 资源遗留在外层。
     */
    fun stop(startId: Int): Job {
        return scope.launch {
            try {
                val inFlight = synchronized(lock) { startJob?.takeIf { !it.isCompleted } }
                if (inFlight != null) {
                    inFlight.cancelAndJoin()
                }

                handleStopMutex.withLock {
                    val current = synchronized(lock) { handle }
                    if (current != null) {
                        // 不把“协程已取消”当作 native 资源已释放；必须进入下层 stop。
                        if (current.stop()) {
                            synchronized(lock) {
                                if (handle === current) handle = null
                            }
                        }
                    }
                }
            } finally {
                stopSelfResult(startId)
            }
        }
    }
}

/** 会话句柄契约：由 [ListenServiceSession] 协调的监听会话。 */
internal interface ListenSessionHandle {
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
}
