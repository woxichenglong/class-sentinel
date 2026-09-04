package com.classsentinel.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * 会话生命周期协调器（Task 9；纯 Kotlin/coroutines，无 Android/Room 依赖）。
 *
 * - [start] 幂等：在途 start 协程期间重复调用共享同一个 [Job]，只创建一次 handle、
 *   只调用一次 handle.start()；已创建的 handle 在本次 start 完成后保留，供后续 start 复用。
 *   发布（发布 startJob 引用）后才启动协程，且不在持锁状态下执行任何用户 suspend 代码。
 * - [stop] 在 [scope] 中异步执行：优先取消并等待在途 start 协程，否则停止当前 handle；
 *   无论 stop 成功、抛异常还是被取消，都在 finally 中调用 [stopSelfResult]，异常原样传播。
 */
internal class ListenServiceSession(
    private val scope: CoroutineScope,
    private val createHandle: suspend () -> ListenSessionHandle,
    private val stopSelfResult: (Int) -> Boolean,
    private val onStartFailure: (Throwable) -> Unit = {},
) {

    private val lock = Any()

    /** 最近一次创建的 handle；start 完成后保留，供后续 start 复用（仅在锁内读写）。 */
    private var handle: ListenSessionHandle? = null

    /** 在途 start 协程 Job；完成时（身份校验后）清除（仅在锁内读写）。 */
    private var startJob: Job? = null

    /** 幂等启动：在途 start 协程活跃时共享返回同一 Job；否则新建 Job 并复用已建 handle。 */
    fun start(): Job {
        val job = synchronized(lock) {
            startJob?.takeIf { it.isActive }?.let { return it }
            var self: Job? = null
            val newJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // 先发布引用（self/startJob）再执行；锁内不运行用户 suspend 代码。
                    val session = synchronized(lock) { handle } ?: createHandle().also { created ->
                        synchronized(lock) { if (handle == null) handle = created }
                    }
                    session.start()
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

    /** 异步停止：优先取消/等待在途 start，否则停止 handle；随后总是调用 stopSelfResult。 */
    fun stop(startId: Int): Job = scope.launch {
        val inFlight = synchronized(lock) { startJob?.takeIf { !it.isCompleted } }
        try {
            if (inFlight != null) {
                inFlight.cancelAndJoin()
            } else {
                synchronized(lock) { handle }?.stop()
            }
        } finally {
            stopSelfResult(startId)
        }
    }
}

/** 会话句柄契约：由 [ListenServiceSession] 协调的监听会话。 */
internal interface ListenSessionHandle {
    suspend fun start()
    suspend fun stop(): Boolean
}
