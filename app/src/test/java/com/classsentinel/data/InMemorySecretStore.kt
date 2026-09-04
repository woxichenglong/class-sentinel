package com.classsentinel.data

import com.classsentinel.security.SecretStore

/** Test-only store used to keep repository unit tests independent of AndroidKeyStore. */
internal class InMemorySecretStore : SecretStore {
    val values = linkedMapOf<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }
}
