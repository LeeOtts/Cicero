package com.leeotts.cicero

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leeotts.cicero.ai.KeystoreSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because the Android Keystore does not exist in a JVM unit test -
 * there is no way to exercise the real thing off-device.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {

    @Test
    fun encryptDecryptRoundTrips() {
        val secret = "sk-or-v1-0123456789abcdef"
        assertEquals(secret, KeystoreSecrets.decrypt(KeystoreSecrets.encrypt(secret)))
    }

    @Test
    fun cipherTextIsNotThePlaintext() {
        val secret = "sk-test-value"
        val stored = KeystoreSecrets.encrypt(secret)
        assertNotEquals(secret, stored)
        assertTrue(stored, !stored.contains(secret))
    }

    /**
     * GCM must never reuse an IV. Two encryptions of the same value differing is
     * the observable consequence, and the reason the IV is read back off the
     * cipher rather than supplied.
     */
    @Test
    fun theSameInputEncryptsDifferentlyEachTime() {
        val secret = "sk-test-value"
        assertNotEquals(KeystoreSecrets.encrypt(secret), KeystoreSecrets.encrypt(secret))
    }

    @Test
    fun blankRoundTripsAsBlank() {
        assertEquals("", KeystoreSecrets.encrypt(""))
        assertEquals("", KeystoreSecrets.decrypt(""))
    }

    /**
     * The Keystore key can genuinely vanish - a device restore, a keystore reset.
     * The user must then see an empty field and re-paste, not a crash on a
     * background turn with the phone in a pocket.
     */
    @Test
    fun garbageDecryptsToEmptyRatherThanThrowing() {
        assertEquals("", KeystoreSecrets.decrypt("v1:not-actually-base64-!!!"))
        assertEquals("", KeystoreSecrets.decrypt("v1:AAAA"))
    }

    /** A value written before encryption existed still reads back as itself. */
    @Test
    fun unprefixedValuesPassThroughUnchanged() {
        assertEquals("legacy-plaintext-key", KeystoreSecrets.decrypt("legacy-plaintext-key"))
    }

    @Test
    fun unicodeSurvivesTheRoundTrip() {
        val secret = "clé-secrète-日本語-🔑"
        assertEquals(secret, KeystoreSecrets.decrypt(KeystoreSecrets.encrypt(secret)))
    }
}
