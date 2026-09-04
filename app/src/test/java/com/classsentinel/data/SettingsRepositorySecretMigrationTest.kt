package com.classsentinel.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.classsentinel.core.config.AppConfig
import com.classsentinel.security.SecretStore
import com.classsentinel.security.SecretKeys
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositorySecretMigrationTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var secretStore: RecordingSecretStore
    private var previousAsrKey: String = ""

    private val legacyAiKey = stringPreferencesKey(SecretKeys.AI_API_KEY)
    private val legacyAsrKey = stringPreferencesKey(SecretKeys.ASR_SILICON_KEY)
    private val baseUrlKey = stringPreferencesKey("ai_base_url")
    private val modelKey = stringPreferencesKey("ai_model")
    private val migrationKey = booleanPreferencesKey(SECRET_STORE_MIGRATION_PREF)

    @Before
    fun setUp() {
        previousAsrKey = AppConfig.siliconApiKey
        dataStoreFile = File.createTempFile("secret-migration-test", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        dataStoreScope = CoroutineScope(Dispatchers.IO + Job())
        dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = dataStoreScope,
            produceFile = { dataStoreFile },
        )
        secretStore = RecordingSecretStore()
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        dataStoreFile.delete()
        AppConfig.siliconApiKey = previousAsrKey
    }

    @Test
    fun `load migrates legacy keys only after encrypted readback and removes plaintext values`() = runBlocking {
        dataStore.edit {
            it[legacyAiKey] = "legacy-ai-key"
            it[legacyAsrKey] = "legacy-asr-key"
            it[baseUrlKey] = "https://provider.test/v1"
            it[modelKey] = "test-model"
        }
        val repo = SettingsRepository(dataStore, syncEnabled = false, secretStore = secretStore)

        repo.load()

        val prefs = dataStore.data.first()
        assertNull(prefs[legacyAiKey])
        assertNull(prefs[legacyAsrKey])
        assertTrue(prefs[migrationKey] == true)
        assertEquals("legacy-ai-key", secretStore.values[SecretKeys.AI_API_KEY])
        assertEquals("legacy-asr-key", secretStore.values[SecretKeys.ASR_SILICON_KEY])
        assertEquals("legacy-ai-key", repo.aiSettingsFlow.first().apiKey)
        assertEquals("legacy-asr-key", AppConfig.siliconApiKey)

        val writeCount = secretStore.putCalls.size
        repo.load()
        assertEquals(writeCount, secretStore.putCalls.size)
    }

    @Test
    fun `saving provider keys writes to SecretStore and never to legacy DataStore keys`() = runBlocking {
        val repo = SettingsRepository(dataStore, syncEnabled = false, secretStore = secretStore)

        repo.saveAiSettings(
            com.classsentinel.data.AiSettings(
                baseUrl = " https://provider.test/v1/ ",
                apiKey = " ai-key ",
                model = " model ",
            ),
        )
        repo.saveAsrSiliconKey(" asr-key ")

        val prefs = dataStore.data.first()
        assertNull(prefs[legacyAiKey])
        assertNull(prefs[legacyAsrKey])
        assertEquals("ai-key", secretStore.values[SecretKeys.AI_API_KEY])
        assertEquals("asr-key", secretStore.values[SecretKeys.ASR_SILICON_KEY])
        assertEquals("ai-key", repo.aiSettingsFlow.first().apiKey)
        assertEquals("asr-key", AppConfig.siliconApiKey)
    }

    @Test
    fun `failed encrypted readback leaves legacy keys and migration marker untouched`() = runBlocking {
        dataStore.edit {
            it[legacyAiKey] = "legacy-ai-key"
            it[legacyAsrKey] = "legacy-asr-key"
        }
        secretStore.failReadback = true
        val repo = SettingsRepository(dataStore, syncEnabled = false, secretStore = secretStore)

        val failure = runCatching { repo.load() }.exceptionOrNull()

        assertNotNull(failure)
        val prefs = dataStore.data.first()
        assertEquals("legacy-ai-key", prefs[legacyAiKey])
        assertEquals("legacy-asr-key", prefs[legacyAsrKey])
        assertFalse(prefs[migrationKey] == true)
    }

    private class RecordingSecretStore : SecretStore {
        val values = linkedMapOf<String, String>()
        val putCalls = mutableListOf<String>()
        var failReadback = false

        override suspend fun get(key: String): String? =
            if (failReadback && key in values) null else values[key]

        override suspend fun put(key: String, value: String) {
            putCalls += key
            values[key] = value
        }

        override suspend fun delete(key: String) {
            values.remove(key)
        }
    }
}
