package com.leeotts.cicero.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.leeotts.cicero.TAG
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Two-way protection for the API keys held in settings.
 *
 * An interface, not just the object, because the settings migration has to be
 * testable off-device and the Android Keystore does not exist in a JVM unit test.
 */
interface Secrets {
    fun encrypt(plain: String): String
    fun decrypt(stored: String): String
}

/**
 * AES-256-GCM under a key the Android Keystore holds and never hands back.
 *
 * `androidx.security`'s EncryptedSharedPreferences is deprecated and is
 * deliberately not used.
 *
 * Honest about what this is worth: the DataStore file already sits in private
 * app storage, and anything that can read it can usually also drive the app into
 * decrypting it. The two real wins are that keys no longer travel to cloud
 * backup as cleartext, and that a file-sync or coding tool pointed at the device
 * cannot simply read them out.
 *
 * Stored form is "v1:" + base64(iv || ciphertext||tag). The prefix means a value
 * written before this existed still reads back as itself instead of as garbage,
 * and it makes "is this actually encrypted" assertable from a test.
 */
object KeystoreSecrets : Secrets {

    private const val ALIAS = "cicero.settings.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "v1:"

    override fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            // setRandomizedEncryptionRequired defaults to true, so the IV comes
            // FROM the cipher and must be read back - supplying one is rejected.
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(iv + body)
        } catch (e: GeneralSecurityException) {
            // Storing cleartext instead would be worse than storing nothing.
            Log.w(TAG, "could not encrypt a secret; it will not be saved", e)
            ""
        }
    }

    override fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        // Written before this class existed, or by a build without the prefix.
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (raw.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // The Keystore key can genuinely vanish - a device restore, a
            // keystore reset. The user must see an empty field and re-paste, not
            // a crash on a background turn with the phone in a pocket.
            Log.w(TAG, "could not decrypt a stored secret; treating it as unset", e)
            ""
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Deliberately NOT setUserAuthenticationRequired, and NOT
                    // setUnlockedDeviceRequired: a turn can start from the
                    // long-press-power assistant gesture on a locked phone, and a
                    // key the app cannot read then is a key that breaks the app.
                    .build()
            )
            generateKey()
        }
    }
}
