package com.classsentinel.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.audio.AudioRetentionPolicy
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.Sensitivity
import com.classsentinel.core.speech.ModelProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SettingsRepository 持久化测试（Robolectric）。
 * DataStore 为纯 JVM 文件存取，测试直接注入临时文件实例；syncEnabled=false 关闭
 * AppConfig 反向同步协程，仅测 保存 → 落盘 → 新实例 load 回读 与 编解码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File

    // AppConfig 全局状态快照（测试会回写它）
    private lateinit var savedNames: List<NameEntry>
    private lateinit var savedSensitivity: Sensitivity
    private lateinit var savedChannels: Set<String>
    private var savedLockscreenNotify: Boolean = true
    private var savedVibrationMode: String = "normal"
    private lateinit var secretStore: InMemorySecretStore

    @Before
    fun setUp() {
        savedNames = AppConfig.names.value
        savedSensitivity = AppConfig.sensitivity.value
        savedChannels = AppConfig.enabledChannels.value
        savedLockscreenNotify = AppConfig.lockscreenNotify.value
        savedVibrationMode = AppConfig.vibrationMode.value
        secretStore = InMemorySecretStore()

        file = File.createTempFile("settings-test", ".preferences_pb")
        file.deleteOnExit()
        dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { file },
        )
    }

    @After
    fun tearDown() {
        file.delete()
        AppConfig.names.value = savedNames
        AppConfig.sensitivity.value = savedSensitivity
        AppConfig.enabledChannels.value = savedChannels
        AppConfig.lockscreenNotify.value = savedLockscreenNotify
        AppConfig.vibrationMode.value = savedVibrationMode
        AppConfig.siliconApiKey = ""
        AppConfig.xunfeiAppId = ""
        AppConfig.xunfeiApiKey = ""
        AppConfig.xunfeiApiSecret = ""
    }

    private fun repo(sync: Boolean = false) =
        SettingsRepository(dataStore, secretStore = secretStore, syncEnabled = sync)

    @Test
    fun `保存后新实例 load 回读全部核心设置并写入 AppConfig`() = runBlocking {
        val names = listOf(
            NameEntry("张伟", listOf("zhang wei", "张微")),
            NameEntry("Alice", emptyList()),
        )
        repo().apply {
            saveNameList(names)
            saveSensitivityPreset("loose")
            saveVadDb(-42)
            saveRollcallSuppressMs(90_000)
            saveQuestionSuppressMs(150_000)
            saveQuestionWordLevel(1)
            saveAsrEngine("xunfei")
            saveSegmentMaxSec(6)
            saveAiSettings(AiSettings("https://example.test/v1", "sk-abc", "mini"))
            setChannelEnabled(Channels.FLASH, true)
            setChannelEnabled(Channels.RINGTONE, true)
            setChannelEnabled(Channels.VIBRATE, false)
            saveRetentionDays("90")
            saveDarkMode("on")
        }

        // 保存即时回写 AppConfig
        assertEquals(names, AppConfig.names.value)
        assertEquals(
            Sensitivity.LOOSE.copy(
                vadDb = -42,
                rollcallSuppressMs = 90_000,
                questionSuppressMs = 150_000,
                questionWordLevel = 1,
            ),
            AppConfig.sensitivity.value,
        )
        assertEquals(setOf("notify", "flash", "ringtone"), AppConfig.enabledChannels.value)
        // AI key 与 ASR key 分离（2026-08-16 修复混用缺陷）：saveAiSettings 不应污染 ASR key
        assertEquals("", AppConfig.siliconApiKey)
        repo().saveAsrSiliconKey("sk-asr-silicon")
        assertEquals("sk-asr-silicon", AppConfig.siliconApiKey)

        // 新实例从落盘数据 load
        val repo2 = repo()
        repo2.load()
        assertEquals(names, AppConfig.names.value)
        assertEquals(setOf("notify", "flash", "ringtone"), AppConfig.enabledChannels.value)
        assertEquals("sk-asr-silicon", AppConfig.siliconApiKey)
        assertEquals(
            Sensitivity.LOOSE.copy(
                vadDb = -42,
                rollcallSuppressMs = 90_000,
                questionSuppressMs = 150_000,
                questionWordLevel = 1,
            ),
            AppConfig.sensitivity.value,
        )
        assertEquals(names, repo2.nameListFlow.first())
        assertEquals("xunfei", repo2.asrEngineFlow.first())
        assertEquals(6, repo2.segmentMaxSecFlow.first())
        assertEquals(AiSettings("https://example.test/v1", "sk-abc", "mini"), repo2.aiSettingsFlow.first())
        assertEquals("90", repo2.retentionDaysFlow.first())
        assertEquals("on", repo2.darkModeFlow.first())
        assertEquals("sk-asr-silicon", AppConfig.siliconApiKey)
    }

    @Test
    fun `本地模型选择可持久化并在新实例回读`() = runBlocking {
        val r = repo()

        assertEquals(ModelProfiles.ZIPFORMER_ZH_14M.id, r.localAsrModelIdFlow.first())
        r.saveLocalAsrModel(ModelProfiles.X_ASR_960.id)

        assertEquals(ModelProfiles.X_ASR_960.id, r.localAsrModelIdFlow.first())
        assertEquals(ModelProfiles.X_ASR_960.id, repo().localAsrModelIdFlow.first())
    }

    @Test
    fun `名字表 JSON 往返保留变体与中文`() = runBlocking {
        val names = listOf(
            NameEntry("张三", listOf("zhang san", "三三")),
            NameEntry("Alice", emptyList()),
            NameEntry("欧阳锋", listOf("oy")),
        )
        repo().saveNameList(names)

        assertEquals(names, repo().nameListFlow.first())

        repo().saveNameList(emptyList())
        assertTrue(repo().nameListFlow.first().isEmpty())

        // 直接编解码
        assertEquals(names, decodeNameList(encodeNameList(names)))
        assertEquals(emptyList<NameEntry>(), decodeNameList(null))
        assertEquals(emptyList<NameEntry>(), decodeNameList("垃圾数据"))
        assertEquals(emptyList<NameEntry>(), decodeNameList(""))
        assertEquals(emptyList<NameEntry>(), encodeNameList(emptyList()).let { decodeNameList(it) })
    }

    @Test
    fun `灵敏度 JSON 编解码与档位自定义覆盖`() = runBlocking {
        assertEquals(Sensitivity.STANDARD, decodeSensitivity(encodeSensitivity(Sensitivity.STANDARD)))
        assertEquals(Sensitivity.STRICT, decodeSensitivity(encodeSensitivity(Sensitivity.STRICT)))
        assertEquals(null, decodeSensitivity(null))
        assertEquals(null, decodeSensitivity("not json"))

        val r = repo()
        r.saveSensitivityPreset("strict")
        assertEquals(Sensitivity.STRICT, AppConfig.sensitivity.value)

        r.saveVadDb(-50)
        r.saveQuestionWordLevel(1)
        assertEquals(Sensitivity.STRICT.copy(vadDb = -50, questionWordLevel = 1), AppConfig.sensitivity.value)

        val s = r.sensitivityFlow.first()
        assertEquals(-50, s.vadDb)
        assertEquals(0.92, s.nameScoreMin, 1e-9)
        assertEquals(1, s.questionWordLevel)
    }

    @Test
    fun `提醒通道开关驱动 enabledChannels 集合`() = runBlocking {
        val r = repo()
        r.load() // 补齐默认值：vibrate + notify 开启

        assertEquals(setOf("vibrate", "notify"), AppConfig.enabledChannels.value)

        r.setChannelEnabled(Channels.RINGTONE, true)
        assertEquals(setOf("vibrate", "notify", "ringtone"), AppConfig.enabledChannels.value)
        assertTrue(r.channelFlow(Channels.RINGTONE).first())

        r.setChannelEnabled(Channels.VIBRATE, false)
        assertEquals(setOf("notify", "ringtone"), AppConfig.enabledChannels.value)
        assertTrue(!r.channelFlow(Channels.VIBRATE).first())

        // 未知通道 key 静默忽略
        r.setChannelEnabled("unknown", true)
        assertEquals(setOf("notify", "ringtone"), AppConfig.enabledChannels.value)
    }

    @Test
    fun `提醒细节保存后回写 AppConfig 且新实例 load 可恢复`() = runBlocking {
        val r = repo()
        r.load()

        r.saveLockscreenNotify(false)
        r.saveVibrationMode("strong")

        assertFalse(AppConfig.lockscreenNotify.value)
        assertEquals("strong", AppConfig.vibrationMode.value)

        val repo2 = repo()
        repo2.load()
        assertFalse(AppConfig.lockscreenNotify.value)
        assertEquals("strong", AppConfig.vibrationMode.value)
        assertFalse(repo2.lockscreenNotifyFlow.first())
        assertEquals("strong", repo2.vibrationModeFlow.first())
    }

    @Test
    fun `音频保留策略默认最小留存并规范化保存值`() = runBlocking {
        val r = repo()
        r.load()

        assertEquals(AudioRetentionPolicy.DEFAULT.storedValue, r.audioRetentionPolicyFlow.first())
        r.saveAudioRetentionPolicy("FULL_SESSION")
        assertEquals(AudioRetentionPolicy.FULL_SESSION.storedValue, r.audioRetentionPolicyFlow.first())

        // 未知值不能让运行时进入未定义策略，安全回退到 FAILED_ONLY。
        r.saveAudioRetentionPolicy("future-policy")
        assertEquals(AudioRetentionPolicy.DEFAULT.storedValue, r.audioRetentionPolicyFlow.first())
    }
}