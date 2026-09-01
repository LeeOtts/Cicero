package com.leeotts.cicero.ai

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.leeotts.cicero.audio.MicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-word settings, through the same pure toConfig/write pair the rest of
 * the settings use.
 *
 * Two things matter here beyond a plain round trip: that an install upgrading
 * from before the wake word existed reads back as "off", needing no migration;
 * and that the Picovoice key does not land on disk in plaintext.
 */
class WakeSettingsTest {

    /** Reversible and obviously not plaintext, as in SettingsMigrationTest. */
    private object FakeSecrets : Secrets {
        override fun encrypt(plain: String) = if (plain.isEmpty()) "" else "enc:$plain"
        override fun decrypt(stored: String) = stored.removePrefix("enc:")
    }

    private fun roundTrip(config: BrainConfig): BrainConfig {
        val prefs = mutablePreferencesOf()
        prefs.write(config, FakeSecrets)
        return prefs.toConfig(FakeSecrets)
    }

    @Test
    fun `an install from before the wake word existed reads back as off`() {
        // The reason no DataStore migration is needed: absent keys are defaults.
        val config = mutablePreferencesOf().toConfig(FakeSecrets)

        assertFalse(config.wakeEnabled)
        assertEquals(MicSource.PHONE, config.wakeMic)
        assertEquals(0.5f, config.wakeSensitivity, 0.0001f)
        assertEquals("", config.wakeAccessKey)
        assertTrue(config.wakeArmOnlyWithGlasses)
        assertEquals(20, config.wakeBatteryFloor)
    }

    @Test
    fun `every wake field survives a round trip`() {
        val out = roundTrip(
            BrainConfig(
                wakeEnabled = true,
                wakeMic = MicSource.GLASSES,
                wakeSensitivity = 0.75f,
                wakeAccessKey = "pv-abc123",
                wakeArmOnlyWithGlasses = false,
                wakeBatteryFloor = 35,
                wakeUnprocessedAudio = true,
            ),
        )

        assertTrue(out.wakeEnabled)
        assertEquals(MicSource.GLASSES, out.wakeMic)
        assertEquals(0.75f, out.wakeSensitivity, 0.0001f)
        assertEquals("pv-abc123", out.wakeAccessKey)
        assertFalse(out.wakeArmOnlyWithGlasses)
        assertEquals(35, out.wakeBatteryFloor)
        assertTrue(out.wakeUnprocessedAudio)
    }

    @Test
    fun `the access key is encrypted on disk`() {
        val prefs = mutablePreferencesOf()
        prefs.write(BrainConfig(wakeAccessKey = "pv-secret"), FakeSecrets)

        val stored = prefs[stringPreferencesKey("wake_access_key")]
        assertEquals("enc:pv-secret", stored)
        assertFalse("the key must not be readable on disk", stored == "pv-secret")
    }

    @Test
    fun `clearing the access key removes it rather than blanking it`() {
        val prefs = mutablePreferencesOf()
        prefs.write(BrainConfig(wakeAccessKey = "pv-secret"), FakeSecrets)
        prefs.write(BrainConfig(wakeAccessKey = ""), FakeSecrets)

        assertEquals(null, prefs[stringPreferencesKey("wake_access_key")])
        assertEquals("", prefs.toConfig(FakeSecrets).wakeAccessKey)
    }

    @Test
    fun `an unreadable microphone value falls back rather than throwing`() {
        // A hand-edited or downgraded store must still open, same as themeMode.
        val prefs = mutablePreferencesOf(stringPreferencesKey("wake_mic") to "EARLOBE")
        assertEquals(MicSource.PHONE, prefs.toConfig(FakeSecrets).wakeMic)
    }

    @Test
    fun `arming rules are derived from the stored settings`() {
        val rules = BrainConfig(wakeArmOnlyWithGlasses = false, wakeBatteryFloor = 5).armingRules
        assertFalse(rules.armOnlyWithGlasses)
        assertEquals(5, rules.batteryFloor)
    }
}
