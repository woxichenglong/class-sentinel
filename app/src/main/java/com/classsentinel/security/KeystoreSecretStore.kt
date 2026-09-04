package com.classsentinel.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores each secret as AES/GCM ciphertext in app-private storage.
 * The AES key is non-exportable when the default AndroidKeyStore provider is used.
 * [keyProvider] is injectable solely so JVM tests can exercise the file/crypto
 * contract without pretending that Robolectric is a hardware-backed device.
 */
class KeystoreSecretStore(
    context: Context,
    private val storageDir: File = File(context.noBackupFilesDir, "secrets"),
    private val keyProvider: () -> SecretKey = { androidKey() },
) : SecretStore {

    private val lock = Any()

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = secretFile(key)
            if (!file.isFile) return@synchronized null
            decrypt(file.readBytes())
        }
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureDirectory()
            val target = secretFile(key)
            val temporary = File(storageDir, "${target.name}.tmp")
            try {
                temporary.writeBytes(encrypt(value))
                atomicReplace(temporary, target)
            } finally {
                temporary.delete()
            }
        }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = secretFile(key)
            if (file.exists() && !file.delete()) {
                throw IOException("Unable to delete stored secret")
            }
        }
    }

    private fun encrypt(value: String): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            val iv = cipher.iv
            if (iv == null || iv.size != IV_SIZE) {
                throw GeneralSecurityException(
                    "AndroidKeyStore produced unexpected AES/GCM IV length: ${iv?.size}",
                )
            }
            val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            ByteArray(FORMAT_HEADER_SIZE + iv.size + ciphertext.size).also { output ->
                output[0] = FORMAT_VERSION
                System.arraycopy(iv, 0, output, FORMAT_HEADER_SIZE, iv.size)
                System.arraycopy(ciphertext, 0, output, FORMAT_HEADER_SIZE + iv.size, ciphertext.size)
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("Unable to encrypt stored secret", e)
        }
    }

    private fun decrypt(blob: ByteArray): String {
        if (blob.size <= FORMAT_HEADER_SIZE + IV_SIZE + TAG_BYTES || blob[0] != FORMAT_VERSION) {
            throw IOException("Stored secret is invalid")
        }
        return try {
            val iv = blob.copyOfRange(FORMAT_HEADER_SIZE, FORMAT_HEADER_SIZE + IV_SIZE)
            val ciphertext = blob.copyOfRange(FORMAT_HEADER_SIZE + IV_SIZE, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw IOException("Unable to decrypt stored secret", e)
        }
    }

    private fun secretFile(key: String): File {
        require(key.isNotBlank()) { "Secret key must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(storageDir, "$digest.bin")
    }

    private fun ensureDirectory() {
        if (!storageDir.isDirectory && !storageDir.mkdirs() && !storageDir.isDirectory) {
            throw IOException("Unable to create secret storage")
        }
    }

    private fun atomicReplace(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.classsentinel.secret-store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val IV_SIZE = 12
        const val FORMAT_VERSION: Byte = 1
        const val FORMAT_HEADER_SIZE = 1

        fun androidKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(androidKeyGenParameterSpec(KEY_ALIAS))
            return generator.generateKey()
        }
    }
}

internal fun androidKeyGenParameterSpec(alias: String): KeyGenParameterSpec =
    KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        // encrypt() intentionally omits GCMParameterSpec so AndroidKeyStore creates
        // a fresh IV. Requiring randomized encryption keeps this contract aligned
        // with the provider and rejects accidental caller-supplied IVs.
        .setRandomizedEncryptionRequired(true)
        .build()
