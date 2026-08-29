package com.leeotts.cicero.ai

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade path off the three named key fields.
 *
 * Driven through the pure [migrateLegacy] with a fake [Secrets], because the
 * Android Keystore does not exist off-device and losing somebody's API keys on
 * upgrade is not a thing to discover by hand.
 */
class SettingsMigrationTest {

    /** Reversible and obviously not plaintext, which is all the test needs. */
    private object FakeSecrets : Secrets {
        override fun encrypt(plain: String) = if (plain.isEmpty()) "" else "enc:$plain"
        override fun decrypt(stored: String) = stored.removePrefix("enc:")
    }

    private fun legacy(vararg pairs: Pair<String, String>) =
        mutablePreferencesOf(*pairs.map { stringPreferencesKey(it.first) to it.second }
            .toTypedArray())

    @Test
    fun `a legacy key survives as an encrypted per-provider key`() {
        val out = migrateLegacy(legacy("gemini_key" to "AIza-secret"), FakeSecrets)

        val stored = out[keyPref(Providers.GEMINI.id)]
        assertEquals("enc:AIza-secret", stored)
        assertEquals("AIza-secret", FakeSecrets.decrypt(stored!!))
        // The point of the exercise: what lands on disk is not the key.
        assertFalse(stored == "AIza-secret")
    }

    @Test
    fun `all three legacy keys move`() {
        val out = migrateLegacy(
            legacy(
                "gemini_key" to "g",
                "claude_key" to "c",
                "local_key" to "l",
            ),
            FakeSecrets,
        )
        assertEquals("enc:g", out[keyPref(Providers.GEMINI.id)])
        assertEquals("enc:c", out[keyPref(Providers.CLAUDE.id)])
        assertEquals("enc:l", out[keyPref(Providers.LOCAL.id)])
    }

    @Test
    fun `the plaintext originals are removed`() {
        val out = migrateLegacy(legacy("gemini_key" to "g", "claude_key" to "c"), FakeSecrets)
        assertNull(out[stringPreferencesKey("gemini_key")])
        assertNull(out[stringPreferencesKey("claude_key")])
        assertNull(out[stringPreferencesKey("local_key")])
    }

    @Test
    fun `models move across without being encrypted`() {
        val out = migrateLegacy(legacy("claude_model" to "claude-opus-5"), FakeSecrets)
        assertEquals("claude-opus-5", out[modelPref(Providers.CLAUDE.id)])
        assertNull(out[stringPreferencesKey("claude_model")])
    }

    @Test
    fun `the old choice enum maps onto a provider id`() {
        assertEquals(
            Providers.CLAUDE.id,
            migrateLegacy(legacy("choice" to "CLAUDE"), FakeSecrets)[PROVIDER_ID],
        )
        assertEquals(
            Providers.LOCAL.id,
            migrateLegacy(legacy("choice" to "LOCAL"), FakeSecrets)[PROVIDER_ID],
        )
        assertEquals(
            Providers.GEMINI.id,
            migrateLegacy(legacy("choice" to "GEMINI"), FakeSecrets)[PROVIDER_ID],
        )
    }

    /** A value written by some future build must not become a crash here. */
    @Test
    fun `an unrecognised choice falls back to the default`() {
        val out = migrateLegacy(legacy("choice" to "SOMETHING_ELSE"), FakeSecrets)
        assertEquals(Providers.GEMINI.id, out[PROVIDER_ID])
    }

    @Test
    fun `a blank legacy key does not create an entry`() {
        val out = migrateLegacy(legacy("claude_key" to ""), FakeSecrets)
        assertNull(out[keyPref(Providers.CLAUDE.id)])
    }

    /**
     * DataStore will not re-run this, but the flag is what makes that true - and
     * a second pass must never double-encrypt a key into unreadability.
     */
    @Test
    fun `migration is idempotent`() {
        val once = migrateLegacy(legacy("gemini_key" to "g", "choice" to "CLAUDE"), FakeSecrets)
        val twice = migrateLegacy(once, FakeSecrets)
        assertEquals("enc:g", twice[keyPref(Providers.GEMINI.id)])
        assertEquals(Providers.CLAUDE.id, twice[PROVIDER_ID])
        assertTrue(once[MIGRATED] == true)
    }

    @Test
    fun `a fresh install migrates to the default provider without inventing keys`() {
        val out = migrateLegacy(mutablePreferencesOf(), FakeSecrets)
        assertEquals(Providers.GEMINI.id, out[PROVIDER_ID])
        Providers.all.forEach { assertNull(out[keyPref(it.id)]) }
    }
}
