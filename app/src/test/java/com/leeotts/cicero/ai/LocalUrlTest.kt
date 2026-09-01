package com.leeotts.cicero.ai

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the two self-hosted addresses gets used.
 *
 * The rules live in [BrainConfig.localUrl] as a pure function precisely so they
 * can be pinned here without a network: the probe's answer is a parameter, not
 * something this test has to arrange.
 */
class LocalUrlTest {

    private val lan = "http://192.168.1.50:1234"
    private val tailnet = "http://basement:1234"

    private fun config(
        mode: LocalUrlMode = LocalUrlMode.AUTO,
        lanUrl: String = lan,
        tailscaleUrl: String = tailnet,
    ) = BrainConfig(
        localBaseUrl = lanUrl,
        localTailscaleUrl = tailscaleUrl,
        localUrlMode = mode,
    )

    @Test
    fun `auto uses the lan address when it answers`() {
        assertEquals(lan, config().localUrl(lanReachable = true))
    }

    @Test
    fun `auto falls back to tailscale when the lan address does not answer`() {
        assertEquals(tailnet, config().localUrl(lanReachable = false))
    }

    /**
     * The unprobed case, which is what every synchronous reader of
     * [BrainConfig.activeLocalUrl] sees. Favouring the LAN address keeps the
     * at-home path unchanged for anyone who never sets a Tailscale address.
     */
    @Test
    fun `auto favours the lan address before anything has probed`() {
        assertEquals(lan, config().localUrl(lanReachable = null))
        assertEquals(lan, config().activeLocalUrl)
    }

    @Test
    fun `a fixed mode ignores the probe entirely`() {
        val lanMode = config(mode = LocalUrlMode.LAN)
        assertEquals(lan, lanMode.localUrl(lanReachable = false))

        val tailscaleMode = config(mode = LocalUrlMode.TAILSCALE)
        assertEquals(tailnet, tailscaleMode.localUrl(lanReachable = true))
    }

    /**
     * The common case: nobody has set up Tailscale. There is nothing to fall
     * back to, so an unreachable LAN address is still the address - and
     * [LocalEndpoint] skips the probe rather than paying for it every turn.
     */
    @Test
    fun `a blank tailscale address always means the lan address`() {
        val config = config(tailscaleUrl = "")
        assertEquals(lan, config.localUrl(lanReachable = false))
        assertEquals(lan, config.localUrl(lanReachable = null))
    }

    @Test
    fun `a blank lan address always means the tailscale address`() {
        val config = config(lanUrl = "")
        assertEquals(tailnet, config.localUrl(lanReachable = false))
        assertEquals(tailnet, config.localUrl(lanReachable = null))
    }

    /** Whisper has to follow the model to the same machine, not stay behind. */
    @Test
    fun `speech follows the resolved address when it shares the server`() {
        val config = BrainConfig(
            providerId = Providers.LOCAL.id,
            localBaseUrl = lan,
            localTailscaleUrl = tailnet,
            localUrlMode = LocalUrlMode.TAILSCALE,
            whisperSameServer = true,
        )
        assertEquals(tailnet, config.speechUrl)
    }

    @Test
    fun `the mode round trips through datastore`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("local_url_mode") to LocalUrlMode.TAILSCALE.name,
        )
        assertEquals(LocalUrlMode.TAILSCALE, prefs.toConfig(NoSecrets).localUrlMode)
    }

    /**
     * A store written by a newer build, or edited by hand, must not crash the
     * one reading it - the same rule themeMode follows.
     */
    @Test
    fun `an unreadable mode falls back to auto`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("local_url_mode") to "SOMETHING_ELSE",
        )
        assertEquals(LocalUrlMode.AUTO, prefs.toConfig(NoSecrets).localUrlMode)
        assertEquals(LocalUrlMode.AUTO, mutablePreferencesOf().toConfig(NoSecrets).localUrlMode)
    }

    private object NoSecrets : Secrets {
        override fun encrypt(plain: String) = plain
        override fun decrypt(stored: String) = stored
    }
}
