package com.classsentinel.security

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretStoreContractTest {

    private lateinit var rootDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rootDir = File.createTempFile("classsentinel-secrets", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun `encrypted store round trips values and does not write plaintext`() = runBlocking {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = KeystoreSecretStore(
            context = context,
            storageDir = rootDir,
            keyProvider = { key },
        )

        store.put(SecretKeys.AI_API_KEY, "ai-secret-value")

        assertEquals("ai-secret-value", store.get(SecretKeys.AI_API_KEY))
        val persisted = rootDir.listFiles()!!.single { !it.name.endsWith(".tmp") }.readBytes()
        assertFalse(String(persisted, StandardCharsets.UTF_8).contains("ai-secret-value"))

        store.put(SecretKeys.AI_API_KEY, "updated-ai-secret-value")
        assertEquals("updated-ai-secret-value", store.get(SecretKeys.AI_API_KEY))

        store.delete(SecretKeys.AI_API_KEY)
        assertNull(store.get(SecretKeys.AI_API_KEY))
    }

    @Test
    fun `different secret keys do not overwrite one another`() = runBlocking {
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val store = KeystoreSecretStore(
            context = context,
            storageDir = rootDir,
            keyProvider = { key },
        )

        store.put(SecretKeys.AI_API_KEY, "ai-value")
        store.put(SecretKeys.ASR_SILICON_KEY, "asr-value")

        assertEquals("ai-value", store.get(SecretKeys.AI_API_KEY))
        assertEquals("asr-value", store.get(SecretKeys.ASR_SILICON_KEY))
    }
}
