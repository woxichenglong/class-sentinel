package com.classsentinel.security

/** Stable names for secrets kept outside Preferences DataStore. */
object SecretKeys {
    const val AI_API_KEY = "ai_api_key"
    const val ASR_SILICON_KEY = "asr_silicon_key"
}

/** Small suspendable secret-storage boundary used by SettingsRepository. */
interface SecretStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun delete(key: String)
}
