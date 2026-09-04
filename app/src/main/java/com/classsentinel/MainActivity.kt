package com.classsentinel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.classsentinel.core.log.SafeLog
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.CourseRepository
import com.classsentinel.data.STALE_RUNNING_COURSE_TIMEOUT_MS
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.service.AnswerNotificationBuilder
import com.classsentinel.service.ListenService
import com.classsentinel.ui.ClassSentinelApp
import com.classsentinel.ui.theme.ClassSentinelTheme
import com.classsentinel.ui.theme.darkThemeForPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingEventId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEventId = eventIdFromIntent(intent)
        handleAnswerAction(intent)
        enableEdgeToEdge()
        // 回收进程异常遗留的 RUNNING 课程；只处理超过 5 分钟没有结束的记录，
        // 避免打开应用查看历史时误中止仍在前台监听的会话。
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                CourseRepository(AppDatabase.get(applicationContext)).abortStale(
                    System.currentTimeMillis() - STALE_RUNNING_COURSE_TIMEOUT_MS,
                )
            }.onFailure {
                SafeLog.w(
                    "stale_course_recovery_failed",
                    mapOf("module" to "MainActivity", "errorCode" to "STALE_COURSE_RECOVERY_FAILED"),
                )
            }
        }
        // 启动时把 DataStore 配置灌回 AppConfig（名字表/灵敏度/ASR三件套）
        lifecycleScope.launch {
            runCatching { SettingsRepositoryHolder.get(this@MainActivity).load() }
                .onFailure {
                    SafeLog.w(
                        "settings_load_failed",
                        mapOf("module" to "MainActivity", "errorCode" to "SETTINGS_LOAD_FAILED"),
                    )
                }
        }
        setContent {
            val settings = remember { SettingsRepositoryHolder.get(this@MainActivity) }
            val darkMode by settings.darkModeFlow.collectAsState(initial = "system")
            ClassSentinelTheme(
                darkTheme = darkThemeForPreference(darkMode, isSystemInDarkTheme()),
            ) {
                ClassSentinelApp(initialEventId = pendingEventId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEventId = eventIdFromIntent(intent)
        handleAnswerAction(intent)
    }

    private fun eventIdFromIntent(intent: Intent?): Long? =
        intent?.getLongExtra(ListenService.EXTRA_EVENT_ID, -1L)?.takeIf { it > 0L }

    private fun handleAnswerAction(intent: Intent?) {
        if (intent?.getStringExtra(AnswerNotificationBuilder.EXTRA_ACTION) ==
            AnswerNotificationBuilder.ACTION_RETRY
        ) {
            eventIdFromIntent(intent)?.let { ListenService.retryAnswer(this, it) }
        }
    }
}
