package com.classsentinel.security

import android.security.keystore.KeyGenParameterSpec
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: Android 16 rejects caller-provided AES/GCM IVs for the default
 * The encrypt path therefore lets the provider generate
 * the IV, while the production spec requires randomized encryption so a future
 * caller-supplied IV cannot violate the storage contract.
 *
 * [androidKeyGenParameterSpec] is an internal production seam so this test pins the
 * exact key-generation contract without requiring a hardware Keystore in Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidKeyGenSpecRegressionTest {

    @Test
    fun `android keystore aes-gcm key spec requires provider-generated IV`() {
        val spec: KeyGenParameterSpec =
            androidKeyGenParameterSpec("com.classsentinel.secret-store.v1")

        assertTrue(
            "KeystoreSecretStore lets AndroidKeyStore generate the encryption IV; " +
                "caller-provided IVs must remain forbidden",
            spec.isRandomizedEncryptionRequired,
        )
    }
}
