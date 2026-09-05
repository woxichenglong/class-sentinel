package com.classsentinel.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.classsentinel.MainActivity
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.core.speech.LocalListenStartPreflight
import com.classsentinel.core.speech.ModelProfile
import com.classsentinel.core.speech.ModelReadinessChecker
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveStreamBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class TilePresentation {
    ACTIVE,
    INACTIVE,
    UNAVAILABLE,
}

internal enum class ListenTileAction {
    START,
    STOP,
    OPEN_SETUP,
}

/** 监听中、恢复中或停止收尾时都视为同一活动会话，避免 Tile 重复启动。 */
internal fun isListeningState(state: PipelineState): Boolean = when (state) {
    PipelineState.Starting,
    is PipelineState.Listening,
    is PipelineState.Recovering,
    PipelineState.Stopping,
    -> true
    PipelineState.Idle,
    is PipelineState.Error,
    -> false
}

internal fun tilePresentationFor(state: PipelineState, ready: Boolean): TilePresentation = when {
    isListeningState(state) -> TilePresentation.ACTIVE
    !ready -> TilePresentation.UNAVAILABLE
    else -> TilePresentation.INACTIVE
}

internal fun tileActionFor(
    state: PipelineState,
    microphoneGranted: Boolean,
    modelReady: Boolean,
): ListenTileAction = when {
    isListeningState(state) -> ListenTileAction.STOP
    !microphoneGranted || !modelReady -> ListenTileAction.OPEN_SETUP
    else -> ListenTileAction.START
}

/**
 * Task 31：Quick Settings 入口。Tile 本身不保存 listening Boolean，展示和点击决策
 * 都直接读取 [LiveStreamBus.pipelineState]；启动/停止仍交给现有 ListenService。
 */
class ListenTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    @Volatile
    private var startReady = false

    private val localListenPreflight: LocalListenStartPreflight by lazy {
        LocalListenStartPreflight(
            readinessChecker = ModelReadinessChecker(applicationContext.filesDir),
            assetOpener = { path -> applicationContext.assets.open(path) },
        )
    }

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            startReady = hasStartPrerequisites()
            updateTile()
            LiveStreamBus.pipelineState.collect {
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val state = LiveStreamBus.pipelineState.value
            if (isListeningState(state)) {
                startReady = true
                sendStop()
            } else {
                val microphoneGranted = hasMicrophonePermission()
                val modelReady = ensureLocalModelReady()
                startReady = microphoneGranted && modelReady
                when (tileActionFor(state, microphoneGranted, modelReady)) {
                    ListenTileAction.STOP -> sendStop()
                    ListenTileAction.START -> sendStart()
                    ListenTileAction.OPEN_SETUP -> openSetup()
                }
            }
            updateTile()
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun sendStart() {
        val intent = Intent(this, ListenService::class.java).setAction(ListenService.ACTION_START)
        startForegroundService(intent)
    }

    private fun sendStop() {
        startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP))
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openSetup() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (tilePresentationFor(LiveStreamBus.pipelineState.value, startReady)) {
            TilePresentation.ACTIVE -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "课堂哨兵"
            }
            TilePresentation.INACTIVE -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "课堂哨兵"
            }
            TilePresentation.UNAVAILABLE -> {
                // STATE_UNAVAILABLE 在系统面板中不可点击；保持 inactive，点击才有机会
                // 打开 onboarding/self-test 修配置。内部枚举仍区分“需要配置”状态。
                tile.state = Tile.STATE_INACTIVE
                tile.label = "课堂哨兵（需配置）"
            }
        }
        tile.updateTile()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Resolve the same dedicated local profile that Home/ListenService use. */
    private suspend fun selectedLocalProfile(): ModelProfile? {
        return try {
            val settings = SettingsRepositoryHolder.get(applicationContext)
            settings.load()
            ModelProfiles.resolveDaily(settings.localAsrModelIdFlow.first())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun hasStartPrerequisites(): Boolean {
        val profile = selectedLocalProfile() ?: return false
        return hasMicrophonePermission() && localListenPreflight.isReady(profile)
    }

    private suspend fun ensureLocalModelReady(): Boolean {
        val profile = selectedLocalProfile() ?: return false
        return localListenPreflight.ensureReady(profile)
    }
}
