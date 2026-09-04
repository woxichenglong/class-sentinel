package com.classsentinel.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.audio.AudioRetentionPolicy
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.Sensitivity
import com.classsentinel.core.llm.AiProviderPreset
import com.classsentinel.core.log.SafeLog
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.summary.SummaryTemplate
import com.classsentinel.core.summary.SummaryTemplateSettings
import com.classsentinel.core.summary.SummaryTemplates
import com.classsentinel.security.KeystoreSecretStore
import com.classsentinel.security.SecretKeys
import com.classsentinel.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** AI 服务配置（OpenAI 兼容） */
data class AiSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/** 应用级 DataStore 委托（进程内单例，文件 app_settings.preferences_pb） */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Phase 6：全部设置的 DataStore 持久化仓库。
 *
 * 同步模型（双向）：
 * - 启动时调用 [load]：DataStore → AppConfig（names/sensitivity/enabledChannels/硅基密钥）
 * - UI 每次保存：写 DataStore **并且** 立即回写 AppConfig（保证 ListenService 热读最新值）
 * - 持续同步：syncEnabled 时在内部 scope 里订阅 AppConfig 的三个 StateFlow，外部组件
 *   直接改 AppConfig 也会被落盘（幂等回写）
 *
 * 设置项清单见 [Constants]。核心成对接口：nameListFlow/saveNameList、
 * sensitivityFlow/saveSensitivityPreset、rollcallSuppressMsFlow/saveRollcallSuppressMs、
 * questionSuppressMsFlow/saveQuestionSuppressMs、vadDbFlow/saveVadDb、
 * asrEngineFlow/saveAsrEngine、localAsrModelIdFlow/saveLocalAsrModel、
 * channelFlow/setChannelEnabled、aiSettingsFlow/saveAiSettings。
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    private val syncEnabled: Boolean = true,
) {

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncStarted = false

    init {
        // sync 不能在构造时启动：AppConfig 初始为空，会在 load() 之前把空值写回 DataStore
        // 覆盖用户已保存的名字表（2026-08-16 真机实测 bug）。延迟到 load() 末尾启动。
    }

    /** 停止内部同步协程 */
    fun close() {
        syncScope.cancel()
    }

    // ------------------------------------------------------------------
    // 启动加载：DataStore → AppConfig，并补齐缺失键的默认值
    // ------------------------------------------------------------------

    suspend fun load() {
        val initialPreferences = dataStore.data.first()
        val secrets = migrateLegacySecrets(initialPreferences)
        val p = dataStore.data.first()
        val fallback = AppConfigSink( // 以 AppConfig 当前值兜底（冷启动时即默认值）
            names = AppConfig.names.value,
            sensitivity = AppConfig.sensitivity.value,
            channels = AppConfig.enabledChannels.value,
        )

        val names = p[Keys.NAMES]?.let { decodeNameList(it) } ?: fallback.names
        val presetName = p[Keys.SENSITIVITY_PRESET] ?: "standard"
        val vadDb = p[Keys.VAD_DB] ?: fallback.sensitivity.vadDb
        val rollcallMs = p[Keys.ROLLCALL_SUPPRESS_MS] ?: fallback.sensitivity.rollcallSuppressMs
        val questionMs = p[Keys.QUESTION_SUPPRESS_MS] ?: fallback.sensitivity.questionSuppressMs
        val qLevel = p[Keys.QUESTION_WORD_LEVEL] ?: fallback.sensitivity.questionWordLevel
        val asrApiKey = secrets.asrSiliconKey

        AppConfig.names.value = names
        AppConfig.sensitivity.value = composeSensitivity(presetName, vadDb, rollcallMs, questionMs, qLevel)
        AppConfig.enabledChannels.value = Channels.ALL.filter { key ->
            p[Channels.prefKey(key)] ?: (key in fallback.channels)
        }.toSet()
        AppConfig.lockscreenNotify.value = p[Keys.LOCKSCREEN_NOTIFY] ?: true
        AppConfig.vibrationMode.value = normalizeVibrationMode(p[Keys.VIBRATE_MODE] ?: "normal")
        AppConfig.siliconApiKey = asrApiKey

        // 缺失键落盘默认值，保证 DataStore 自洽
        dataStore.edit {
            if (it[Keys.NAMES] == null) it[Keys.NAMES] = encodeNameList(names)
            if (it[Keys.SENSITIVITY_PRESET] == null) it[Keys.SENSITIVITY_PRESET] = presetName
            if (it[Keys.VAD_DB] == null) it[Keys.VAD_DB] = vadDb
            if (it[Keys.ROLLCALL_SUPPRESS_MS] == null) it[Keys.ROLLCALL_SUPPRESS_MS] = rollcallMs
            if (it[Keys.QUESTION_SUPPRESS_MS] == null) it[Keys.QUESTION_SUPPRESS_MS] = questionMs
            if (it[Keys.QUESTION_WORD_LEVEL] == null) it[Keys.QUESTION_WORD_LEVEL] = qLevel
            setDefaultsIfMissing(it)
        }
        // load 完成后才开启双向同步（此时 AppConfig 已是 DataStore 的真实值）
        startSyncIfNeeded()
    }

    /**
     * Move legacy plaintext values only after SecretStore confirms an exact read-back.
     * Any exception leaves both legacy values and the migration marker untouched.
     */
    private suspend fun migrateLegacySecrets(preferences: Preferences): StoredSecrets {
        if (preferences[Keys.SECRETS_MIGRATED] != true) {
            migrateLegacySecret(preferences[Keys.LEGACY_AI_API_KEY], SecretKeys.AI_API_KEY)
            migrateLegacySecret(preferences[Keys.LEGACY_ASR_SILICON_KEY], SecretKeys.ASR_SILICON_KEY)
            dataStore.edit {
                it.remove(Keys.LEGACY_AI_API_KEY)
                it.remove(Keys.LEGACY_ASR_SILICON_KEY)
                it[Keys.SECRETS_MIGRATED] = true
            }
        }
        return StoredSecrets(
            aiApiKey = secretStore.get(SecretKeys.AI_API_KEY).orEmpty(),
            asrSiliconKey = secretStore.get(SecretKeys.ASR_SILICON_KEY).orEmpty(),
        )
    }

    private suspend fun migrateLegacySecret(value: String?, secretKey: String) {
        if (value == null) return
        secretStore.put(secretKey, value)
        check(secretStore.get(secretKey) == value) { "Secret migration readback failed" }
    }

    @Synchronized
    private fun startSyncIfNeeded() {
        if (syncStarted || !syncEnabled) return
        syncStarted = true
        startSync()
    }

    // ------------------------------------------------------------------
    // 持续双向同步：AppConfig StateFlow（外部组件改动）→ DataStore
    // ------------------------------------------------------------------

    private fun startSync() {
        syncScope.launch {
            AppConfig.names
                .collect { list ->
                    runCatching { dataStore.edit { it[Keys.NAMES] = encodeNameList(list) } }
                        .onFailure {
                            SafeLog.w("settings_sync_failed", mapOf("module" to "SettingsRepository", "errorCode" to "NAMES_SYNC_FAILED"))
                        }
                }
        }
        syncScope.launch {
            AppConfig.sensitivity
                .collect { sens ->
                    runCatching { dataStore.edit { it[Keys.SENSITIVITY_JSON] = encodeSensitivity(sens) } }
                        .onFailure {
                            SafeLog.w("settings_sync_failed", mapOf("module" to "SettingsRepository", "errorCode" to "SENSITIVITY_SYNC_FAILED"))
                        }
                }
        }
        syncScope.launch {
            AppConfig.enabledChannels
                .collect { set ->
                    runCatching {
                        dataStore.edit { p ->
                            Channels.ALL.forEach { key -> p[Channels.prefKey(key)] = key in set }
                        }
                    }.onFailure {
                        SafeLog.w("settings_sync_failed", mapOf("module" to "SettingsRepository", "errorCode" to "CHANNEL_SYNC_FAILED"))
                    }
                }
        }
    }

    // ------------------------------------------------------------------
    // 字段流（UI 收集）
    // ------------------------------------------------------------------

    val nameListFlow: Flow<List<NameEntry>> = dataStore.data
        .map { decodeNameList(it[Keys.NAMES]) }
        .ioCatch { emptyList() }

    val sensitivityPresetFlow: Flow<String> = dataStore.data
        .map { it[Keys.SENSITIVITY_PRESET] ?: "standard" }
        .ioCatch { "standard" }

    val sensitivityFlow: Flow<Sensitivity> = dataStore.data
        .map { p ->
            p[Keys.SENSITIVITY_JSON]?.let { decodeSensitivity(it) }
                ?: composeSensitivity(
                    presetName = p[Keys.SENSITIVITY_PRESET] ?: "standard",
                    vadDb = p[Keys.VAD_DB] ?: -35,
                    rollcallMs = p[Keys.ROLLCALL_SUPPRESS_MS] ?: 60_000L,
                    questionMs = p[Keys.QUESTION_SUPPRESS_MS] ?: 120_000L,
                    qLevel = p[Keys.QUESTION_WORD_LEVEL] ?: 2,
                )
        }
        .ioCatch { Sensitivity.STANDARD }

    val rollcallSuppressMsFlow: Flow<Long> = dataStore.data
        .map { it[Keys.ROLLCALL_SUPPRESS_MS] ?: 60_000L }
        .ioCatch { 60_000L }

    val questionSuppressMsFlow: Flow<Long> = dataStore.data
        .map { it[Keys.QUESTION_SUPPRESS_MS] ?: 120_000L }
        .ioCatch { 120_000L }

    val vadDbFlow: Flow<Int> = dataStore.data
        .map { it[Keys.VAD_DB] ?: -35 }
        .ioCatch { -35 }

    val questionWordLevelFlow: Flow<Int> = dataStore.data
        .map { it[Keys.QUESTION_WORD_LEVEL] ?: 2 }
        .ioCatch { 2 }

    val segmentMaxSecFlow: Flow<Int> = dataStore.data
        .map { it[Keys.SEGMENT_MAX_SEC] ?: Constants.SEGMENT_MAX_SEC_DEFAULT }
        .ioCatch { Constants.SEGMENT_MAX_SEC_DEFAULT }

    val asrEngineFlow: Flow<String> = dataStore.data
        .map { it[Keys.ASR_ENGINE] ?: Constants.ASR_ENGINE_DEFAULT }
        .ioCatch { Constants.ASR_ENGINE_DEFAULT }

    /** Ordinary local streaming model selection; legacy [asrEngineFlow] remains separate. */
    val localAsrModelIdFlow: Flow<String> = dataStore.data
        .map { ModelProfiles.resolveDaily(it[Keys.LOCAL_ASR_MODEL_ID]).id }
        .ioCatch { ModelProfiles.ZIPFORMER_ZH_14M.id }

    /** 单通道开关流（key ∈ vibrate/ringtone/notify/flash/ear） */
    fun channelFlow(key: String): Flow<Boolean> = dataStore.data
        .map { it[Channels.prefKey(key)] ?: Channels.DEFAULT.contains(key) }
        .ioCatch { Channels.DEFAULT.contains(key) }

    val lockscreenNotifyFlow: Flow<Boolean> = dataStore.data
        .map { it[Keys.LOCKSCREEN_NOTIFY] ?: true }
        .ioCatch { true }

    val vibrationModeFlow: Flow<String> = dataStore.data
        .map { it[Keys.VIBRATE_MODE] ?: "normal" }
        .ioCatch { "normal" }

    val aiSettingsFlow: Flow<AiSettings> = dataStore.data
        .map { p ->
            AiSettings(
                baseUrl = p[Keys.AI_BASE_URL] ?: DEFAULT_AI_SETTINGS.baseUrl,
                apiKey = secretStore.get(SecretKeys.AI_API_KEY).orEmpty(),
                model = p[Keys.AI_MODEL] ?: DEFAULT_AI_SETTINGS.model,
            )
        }
        .ioCatch { DEFAULT_AI_SETTINGS }

    val answerLengthFlow: Flow<String> = dataStore.data
        .map { it[Keys.ANSWER_LENGTH] ?: "mid" }
        .ioCatch { "mid" }

    val answerStyleFlow: Flow<String> = dataStore.data
        .map { it[Keys.ANSWER_STYLE] ?: "terseness" }
        .ioCatch { "terseness" }

    val streamOutputFlow: Flow<Boolean> = dataStore.data
        .map { it[Keys.STREAM_OUTPUT] ?: true }
        .ioCatch { true }

    val autoSummaryFlow: Flow<Boolean> = dataStore.data
        .map { it[Keys.AUTO_SUMMARY] ?: false }
        .ioCatch { false }

    val summaryTemplateIdFlow: Flow<String> = dataStore.data
        .map { it[Keys.SUMMARY_TEMPLATE_ID] ?: SummaryTemplates.DEFAULT_ID }
        .ioCatch { SummaryTemplates.DEFAULT_ID }

    val summaryCustomPromptFlow: Flow<String> = dataStore.data
        .map { it[Keys.SUMMARY_CUSTOM_PROMPT] ?: "" }
        .ioCatch { "" }

    /** 当前可执行模板；损坏的自定义值由目录安全回退到默认模板。 */
    val summaryTemplateFlow: Flow<SummaryTemplate> = dataStore.data
        .map { p ->
            SummaryTemplates.resolve(
                id = p[Keys.SUMMARY_TEMPLATE_ID] ?: SummaryTemplates.DEFAULT_ID,
                customPrompt = p[Keys.SUMMARY_CUSTOM_PROMPT] ?: "",
            )
        }
        .ioCatch { SummaryTemplates.DEFAULT }

    /** 设置页一次性读取的模板快照，避免编辑草稿被初始默认值覆盖。 */
    val summaryTemplateSettingsFlow: Flow<SummaryTemplateSettings> = dataStore.data
        .map { p ->
            SummaryTemplateSettings(
                templateId = p[Keys.SUMMARY_TEMPLATE_ID] ?: SummaryTemplates.DEFAULT_ID,
                customPrompt = p[Keys.SUMMARY_CUSTOM_PROMPT] ?: "",
            )
        }
        .ioCatch { SummaryTemplateSettings(SummaryTemplates.DEFAULT_ID, "") }

    val retentionDaysFlow: Flow<String> = dataStore.data
        .map { it[Keys.RETENTION_DAYS] ?: "30" }
        .ioCatch { "30" }

    /** 本地音频保留策略；未知值安全回退到 FAILED_ONLY。 */
    val audioRetentionPolicyFlow: Flow<String> = dataStore.data
        .map { AudioRetentionPolicy.fromStored(it[Keys.AUDIO_RETENTION_POLICY]).storedValue }
        .ioCatch { AudioRetentionPolicy.DEFAULT.storedValue }

    val darkModeFlow: Flow<String> = dataStore.data
        .map { it[Keys.DARK_MODE] ?: "system" }
        .ioCatch { "system" }

    /** 首启引导完成标记；缺失即视为未完成，避免新安装直接跳过必要设置。 */
    val onboardingCompletedFlow: Flow<Boolean> = dataStore.data
        .map { it[Keys.ONBOARDING_COMPLETED] ?: false }
        .ioCatch { false }

    // ------------------------------------------------------------------
    // 保存接口（写 DataStore + 同步回写 AppConfig）
    // ------------------------------------------------------------------

    suspend fun saveNameList(names: List<NameEntry>) {
        dataStore.edit { it[Keys.NAMES] = encodeNameList(names) }
        AppConfig.names.value = names
    }

    /** 切换灵敏度档位：同时把自定义项重置为档位默认值 */
    suspend fun saveSensitivityPreset(presetName: String) {
        val preset = Sensitivity.preset(presetName)
        dataStore.edit {
            it[Keys.SENSITIVITY_PRESET] = presetName
            it[Keys.VAD_DB] = preset.vadDb
            it[Keys.ROLLCALL_SUPPRESS_MS] = preset.rollcallSuppressMs
            it[Keys.QUESTION_SUPPRESS_MS] = preset.questionSuppressMs
            it[Keys.QUESTION_WORD_LEVEL] = preset.questionWordLevel
        }
        applySensitivityToAppConfig()
    }

    suspend fun saveVadDb(db: Int) {
        dataStore.edit { it[Keys.VAD_DB] = db }
        applySensitivityToAppConfig()
    }

    suspend fun saveRollcallSuppressMs(ms: Long) {
        dataStore.edit { it[Keys.ROLLCALL_SUPPRESS_MS] = ms }
        applySensitivityToAppConfig()
    }

    suspend fun saveQuestionSuppressMs(ms: Long) {
        dataStore.edit { it[Keys.QUESTION_SUPPRESS_MS] = ms }
        applySensitivityToAppConfig()
    }

    suspend fun saveQuestionWordLevel(level: Int) {
        dataStore.edit { it[Keys.QUESTION_WORD_LEVEL] = level }
        applySensitivityToAppConfig()
    }

    suspend fun saveSegmentMaxSec(sec: Int) {
        dataStore.edit { it[Keys.SEGMENT_MAX_SEC] = sec }
    }

    suspend fun saveAsrEngine(engine: String) {
        dataStore.edit { it[Keys.ASR_ENGINE] = engine }
        SafeLog.d("settings_saved", mapOf("module" to "SettingsRepository", "engine" to engine))
    }

    suspend fun saveLocalAsrModel(profileId: String) {
        val profile = ModelProfiles.DAILY_SELECTABLE.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("UNKNOWN_LOCAL_ASR_MODEL")
        dataStore.edit { it[Keys.LOCAL_ASR_MODEL_ID] = profile.id }
        SafeLog.d("settings_saved", mapOf("module" to "SettingsRepository", "localModel" to profile.id))
    }

    suspend fun setChannelEnabled(key: String, enabled: Boolean) {
        if (!Channels.isKnown(key)) return
        val prefKey = Channels.prefKey(key)
        dataStore.edit { it[prefKey] = enabled }
        val p = dataStore.data.first()
        AppConfig.enabledChannels.value =
            Channels.ALL.filter { ck -> p[Channels.prefKey(ck)] ?: Channels.DEFAULT.contains(ck) }.toSet()
    }

    suspend fun saveLockscreenNotify(enabled: Boolean) {
        dataStore.edit { it[Keys.LOCKSCREEN_NOTIFY] = enabled }
        AppConfig.lockscreenNotify.value = enabled
    }

    suspend fun saveVibrationMode(mode: String) {
        val normalized = normalizeVibrationMode(mode)
        dataStore.edit { it[Keys.VIBRATE_MODE] = normalized }
        AppConfig.vibrationMode.value = normalized
    }

    suspend fun saveAiSettings(ai: AiSettings) {
        val normalized = AiProviderPreset.normalizeSettings(ai)
        writeSecret(SecretKeys.AI_API_KEY, normalized.apiKey)
        dataStore.edit {
            it[Keys.AI_BASE_URL] = normalized.baseUrl
            it.remove(Keys.LEGACY_AI_API_KEY)
            it[Keys.AI_MODEL] = normalized.model
        }
    }

    suspend fun saveAiBaseUrl(url: String) {
        dataStore.edit { it[Keys.AI_BASE_URL] = AiProviderPreset.normalizeBaseUrl(url) }
    }

    suspend fun saveAiApiKey(key: String) {
        writeSecret(SecretKeys.AI_API_KEY, key.trim())
        dataStore.edit { it.remove(Keys.LEGACY_AI_API_KEY) }
    }

    suspend fun saveAsrSiliconKey(key: String) {
        val normalized = key.trim()
        writeSecret(SecretKeys.ASR_SILICON_KEY, normalized)
        dataStore.edit { it.remove(Keys.LEGACY_ASR_SILICON_KEY) }
        AppConfig.siliconApiKey = normalized
    }

    suspend fun saveAiModel(model: String) {
        require(model.trim().isNotBlank()) { "AI model must not be blank" }
        dataStore.edit { it[Keys.AI_MODEL] = model.trim() }
    }

    suspend fun saveAnswerLength(length: String) {
        dataStore.edit { it[Keys.ANSWER_LENGTH] = length }
    }

    suspend fun saveAnswerStyle(style: String) {
        dataStore.edit { it[Keys.ANSWER_STYLE] = style }
    }

    suspend fun saveStreamOutput(enabled: Boolean) {
        dataStore.edit { it[Keys.STREAM_OUTPUT] = enabled }
    }

    suspend fun saveAutoSummary(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_SUMMARY] = enabled }
    }

    /** 保存模板选择和可选自定义要求；只落盘 ID 与原始编辑文本，不落盘渲染结果。 */
    suspend fun saveSummaryTemplate(templateId: String, customPrompt: String = "") {
        val normalizedId = SummaryTemplates.requireKnownId(templateId)
        val normalizedPrompt = customPrompt.trim()
        val validation = SummaryTemplates.validateCustomPrompt(
            normalizedPrompt,
            required = normalizedId == SummaryTemplates.CUSTOM_ID,
        )
        require(validation == null) { validation ?: "INVALID_CUSTOM_PROMPT" }
        dataStore.edit {
            it[Keys.SUMMARY_TEMPLATE_ID] = normalizedId
            it[Keys.SUMMARY_CUSTOM_PROMPT] = normalizedPrompt
        }
    }

    suspend fun saveRetentionDays(days: String) {
        dataStore.edit { it[Keys.RETENTION_DAYS] = days }
    }

    /** 只保存稳定协议值，不把本地化 label 写入 DataStore。 */
    suspend fun saveAudioRetentionPolicy(policy: String) {
        val normalized = AudioRetentionPolicy.fromStored(policy).storedValue
        dataStore.edit { it[Keys.AUDIO_RETENTION_POLICY] = normalized }
    }

    suspend fun saveDarkMode(mode: String) {
        dataStore.edit { it[Keys.DARK_MODE] = mode }
    }

    suspend fun saveOnboardingCompleted(completed: Boolean = true) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private suspend fun applySensitivityToAppConfig() {
        val p = dataStore.data.first()
        AppConfig.sensitivity.value = composeSensitivity(
            presetName = p[Keys.SENSITIVITY_PRESET] ?: "standard",
            vadDb = p[Keys.VAD_DB] ?: -35,
            rollcallMs = p[Keys.ROLLCALL_SUPPRESS_MS] ?: 60_000L,
            questionMs = p[Keys.QUESTION_SUPPRESS_MS] ?: 120_000L,
            qLevel = p[Keys.QUESTION_WORD_LEVEL] ?: 2,
        )
    }

    private suspend fun writeSecret(key: String, value: String) {
        secretStore.put(key, value)
        check(secretStore.get(key) == value) { "Secret write readback failed" }
    }

    private fun composeSensitivity(
        presetName: String,
        vadDb: Int,
        rollcallMs: Long,
        questionMs: Long,
        qLevel: Int,
    ): Sensitivity = Sensitivity.preset(presetName).copy(
        vadDb = vadDb,
        rollcallSuppressMs = rollcallMs,
        questionSuppressMs = questionMs,
        questionWordLevel = qLevel,
    )

    private fun normalizeVibrationMode(mode: String): String =
        mode.trim().lowercase().takeIf { it in setOf("gentle", "normal", "strong") } ?: "normal"

    private fun <T> Flow<T>.ioCatch(fallback: () -> T): Flow<T> =
        catch { e -> if (e is IOException) emit(fallback()) else throw e }

    private fun setDefaultsIfMissing(p: androidx.datastore.preferences.core.MutablePreferences) {
        if (p[Keys.SEGMENT_MAX_SEC] == null) p[Keys.SEGMENT_MAX_SEC] = Constants.SEGMENT_MAX_SEC_DEFAULT
        if (p[Keys.ASR_ENGINE] == null) p[Keys.ASR_ENGINE] = Constants.ASR_ENGINE_DEFAULT
        if (p[Keys.LOCAL_ASR_MODEL_ID] == null) p[Keys.LOCAL_ASR_MODEL_ID] = ModelProfiles.ZIPFORMER_ZH_14M.id
        if (p[Keys.LOCKSCREEN_NOTIFY] == null) p[Keys.LOCKSCREEN_NOTIFY] = true
        if (p[Keys.VIBRATE_MODE] == null) p[Keys.VIBRATE_MODE] = "normal"
        if (p[Keys.AI_BASE_URL] == null) p[Keys.AI_BASE_URL] = Constants.AI_BASE_URL_DEFAULT
        if (p[Keys.AI_MODEL] == null) p[Keys.AI_MODEL] = Constants.AI_MODEL_DEFAULT
        if (p[Keys.ANSWER_LENGTH] == null) p[Keys.ANSWER_LENGTH] = "mid"
        if (p[Keys.ANSWER_STYLE] == null) p[Keys.ANSWER_STYLE] = "terseness"
        if (p[Keys.STREAM_OUTPUT] == null) p[Keys.STREAM_OUTPUT] = true
        if (p[Keys.AUTO_SUMMARY] == null) p[Keys.AUTO_SUMMARY] = false
        if (p[Keys.SUMMARY_TEMPLATE_ID] == null) p[Keys.SUMMARY_TEMPLATE_ID] = SummaryTemplates.DEFAULT_ID
        if (p[Keys.SUMMARY_CUSTOM_PROMPT] == null) p[Keys.SUMMARY_CUSTOM_PROMPT] = ""
        if (p[Keys.RETENTION_DAYS] == null) p[Keys.RETENTION_DAYS] = "30"
        if (p[Keys.AUDIO_RETENTION_POLICY] == null) {
            p[Keys.AUDIO_RETENTION_POLICY] = AudioRetentionPolicy.DEFAULT.storedValue
        }
        if (p[Keys.DARK_MODE] == null) p[Keys.DARK_MODE] = "system"
        if (p[Keys.ONBOARDING_COMPLETED] == null) p[Keys.ONBOARDING_COMPLETED] = false
        Channels.ALL.forEach { key ->
            if (p[Channels.prefKey(key)] == null) p[Channels.prefKey(key)] = key in Channels.DEFAULT
        }
    }

    companion object {
        /** The only AI fallback used by the UI; its key is intentionally empty. */
        val DEFAULT_AI_SETTINGS: AiSettings = AiSettings(
            baseUrl = Constants.AI_BASE_URL_DEFAULT,
            apiKey = "",
            model = Constants.AI_MODEL_DEFAULT,
        )

        /** 应用内共享单例（DataStore 同文件只允许一个实例） */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(
                dataStore = context.applicationContext.settingsDataStore,
                secretStore = KeystoreSecretStore(context.applicationContext),
            )

        /** Test-only constructor seam; production callers must use [create]. */
        internal fun createForTests(context: Context, secretStore: SecretStore): SettingsRepository =
            SettingsRepository(
                dataStore = context.applicationContext.settingsDataStore,
                secretStore = secretStore,
                syncEnabled = false,
            )
    }
}

// ----------------------------------------------------------------------
// 键/常量/编解码
// ----------------------------------------------------------------------

internal const val SECRET_STORE_MIGRATION_PREF = "secret_store_migration_v1"

private object Keys {
    val NAMES = stringPreferencesKey("names_json")
    val SENSITIVITY_PRESET = stringPreferencesKey("sensitivity_preset")
    val SENSITIVITY_JSON = stringPreferencesKey("sensitivity_json")
    val VAD_DB = intPreferencesKey("vad_db")
    val ROLLCALL_SUPPRESS_MS = longPreferencesKey("rollcall_suppress_ms")
    val QUESTION_SUPPRESS_MS = longPreferencesKey("question_suppress_ms")
    val QUESTION_WORD_LEVEL = intPreferencesKey("question_word_level")
    val SEGMENT_MAX_SEC = intPreferencesKey("segment_max_sec")
    val ASR_ENGINE = stringPreferencesKey("asr_engine")
    val LOCAL_ASR_MODEL_ID = stringPreferencesKey("local_asr_model_id")
    val CH_VIBRATE = booleanPreferencesKey("ch_vibrate")
    val CH_RINGTONE = booleanPreferencesKey("ch_ringtone")
    val CH_NOTIFY = booleanPreferencesKey("ch_notify")
    val CH_FLASH = booleanPreferencesKey("ch_flash")
    val CH_EAR = booleanPreferencesKey("ch_ear")
    val LOCKSCREEN_NOTIFY = booleanPreferencesKey("lockscreen_notify")
    val VIBRATE_MODE = stringPreferencesKey("vibrate_mode")
    val AI_BASE_URL = stringPreferencesKey("ai_base_url")
    val LEGACY_AI_API_KEY = stringPreferencesKey(SecretKeys.AI_API_KEY)
    val LEGACY_ASR_SILICON_KEY = stringPreferencesKey(SecretKeys.ASR_SILICON_KEY)
    val AI_MODEL = stringPreferencesKey("ai_model")
    val ANSWER_LENGTH = stringPreferencesKey("answer_length")
    val ANSWER_STYLE = stringPreferencesKey("answer_style")
    val STREAM_OUTPUT = booleanPreferencesKey("stream_output")
    val AUTO_SUMMARY = booleanPreferencesKey("auto_summary")
    val SUMMARY_TEMPLATE_ID = stringPreferencesKey("summary_template_id")
    val SUMMARY_CUSTOM_PROMPT = stringPreferencesKey("summary_custom_prompt")
    val RETENTION_DAYS = stringPreferencesKey("retention_days")
    val AUDIO_RETENTION_POLICY = stringPreferencesKey("audio_retention_policy")
    val DARK_MODE = stringPreferencesKey("dark_mode")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val SECRETS_MIGRATED = booleanPreferencesKey(SECRET_STORE_MIGRATION_PREF)
}

private object Constants {
    const val SEGMENT_MAX_SEC_DEFAULT = 4
    const val ASR_ENGINE_DEFAULT = "telespeech"
    const val AI_BASE_URL_DEFAULT = "https://api.siliconflow.cn/v1"
    const val AI_MODEL_DEFAULT = "Qwen/Qwen2.5-7B-Instruct"
}

/** 提醒通道 key ↔ 偏好键映射。key 与 AlertChannel.key 一一对应。 */
object Channels {
    const val VIBRATE = "vibrate"
    const val RINGTONE = "ringtone"
    const val NOTIFY = "notify"
    const val FLASH = "flash"
    const val EAR = "ear"

    val ALL = listOf(VIBRATE, RINGTONE, NOTIFY, FLASH, EAR)

    /** 与 AppConfig.enabledChannels 初始默认一致 */
    val DEFAULT = setOf(VIBRATE, NOTIFY)

    fun isKnown(key: String): Boolean = key in ALL

    fun prefKey(key: String): androidx.datastore.preferences.core.Preferences.Key<Boolean> = when (key) {
        VIBRATE -> Keys.CH_VIBRATE
        RINGTONE -> Keys.CH_RINGTONE
        NOTIFY -> Keys.CH_NOTIFY
        FLASH -> Keys.CH_FLASH
        EAR -> Keys.CH_EAR
        else -> throw IllegalArgumentException("unknown channel key: $key")
    }
}

/** 名字表 JSON 编解码（internal 便于单测直测） */
internal fun encodeNameList(names: List<NameEntry>): String {
    val arr = JSONArray()
    names.forEach { n ->
        arr.put(JSONObject().put("display", n.display).put("variants", JSONArray(n.variants)))
    }
    return arr.toString()
}

internal fun decodeNameList(json: String?): List<NameEntry> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val variants = o.optJSONArray("variants") ?: JSONArray()
                add(
                    NameEntry(
                        display = o.optString("display"),
                        variants = List(variants.length()) { j -> variants.optString(j) },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

internal fun encodeSensitivity(s: Sensitivity): String = JSONObject()
    .put("nameScoreMin", s.nameScoreMin)
    .put("contextRequired", s.contextRequired)
    .put("questionWordLevel", s.questionWordLevel)
    .put("vadDb", s.vadDb)
    .put("rollcallSuppressMs", s.rollcallSuppressMs)
    .put("questionSuppressMs", s.questionSuppressMs)
    .toString()

internal fun decodeSensitivity(json: String?): Sensitivity? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        Sensitivity(
            nameScoreMin = o.optDouble("nameScoreMin", 0.8),
            contextRequired = o.optBoolean("contextRequired", true),
            questionWordLevel = o.optInt("questionWordLevel", 2),
            vadDb = o.optInt("vadDb", -35),
            rollcallSuppressMs = o.optLong("rollcallSuppressMs", 60_000),
            questionSuppressMs = o.optLong("questionSuppressMs", 120_000),
        )
    }.getOrNull()
}

/** 共享仓库单例持有者（各 Compose 屏共用同一 DataStore 实例） */
object SettingsRepositoryHolder {
    @Volatile
    private var instance: SettingsRepository? = null

    fun get(context: Context): SettingsRepository {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: SettingsRepository.create(context).also { instance = it }
        }
    }

    /** Test-only replacement; always clear it in the test teardown. */
    internal fun installForTests(repository: SettingsRepository?) {
        synchronized(this) {
            instance = repository
        }
    }
}

private data class StoredSecrets(
    val aiApiKey: String,
    val asrSiliconKey: String,
)

/** load() 时 AppConfig 当前值的兜底快照 */
private data class AppConfigSink(
    val names: List<NameEntry>,
    val sensitivity: Sensitivity,
    val channels: Set<String>,
)