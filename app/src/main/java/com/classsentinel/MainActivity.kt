package com.classsentinel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.ui.ClassSentinelApp
import com.classsentinel.ui.theme.ClassSentinelTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 启动时把 DataStore 配置灌回 AppConfig（名字表/灵敏度/ASR密钥/AI三件套）
        lifecycleScope.launch {
            runCatching { SettingsRepositoryHolder.get(this@MainActivity).load() }
                .onFailure { e -> println("[Main] settings load failed: ${e.message}") }
        }
        setContent {
            ClassSentinelTheme {
                ClassSentinelApp()
            }
        }
    }
}
