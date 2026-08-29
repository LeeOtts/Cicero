package com.leeotts.cicero

import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.home.NestController
import com.leeotts.cicero.tools.GetThermostatTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against the real Nest account, using whatever credentials are in Settings.
 *
 * Skips itself when nothing is configured, so it never breaks a run on a
 * machine - or a CI box - that has no Device Access project. Read-only on
 * purpose: a test that moved a live setpoint would change the temperature of an
 * actual house, and the command wire formats are already pinned by
 * SdmWireFormatTest against a mock server.
 */
class NestIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nest = NestController(context)

    private fun configured(): Boolean = runCatching {
        runBlocking { nest.gateway.isConfigured() }
    }.getOrDefault(false)

    @Test
    fun readsTheRealThermostat() = runBlocking {
        assumeTrue("no Nest credentials in settings", configured())

        val state = nest.gateway.thermostat()
        assertNotNull("no thermostat on the account", state)
        requireNotNull(state)

        // The fields every later decision is made from. A thermostat that
        // reports no mode would send set_thermostat down the wrong branch.
        assertTrue(state.deviceId.startsWith("enterprises/"))
        assertTrue(state.mode in setOf("HEAT", "COOL", "HEATCOOL", "OFF"))
        assertNotNull("no ambient temperature", state.ambientC)
    }

    @Test
    fun theToolProducesSomethingWorthSayingAloud() = runBlocking {
        assumeTrue("no Nest credentials in settings", configured())

        val outcome = GetThermostatTool(nest.gateway).run(JsonObject(emptyMap()))

        assertFalse(outcome.content, outcome.isError)
        assertTrue(outcome.content.contains("thermostat"))
    }

    @Test
    fun testConnectionReportsWhatItFound() = runBlocking {
        assumeTrue("no Nest credentials in settings", configured())

        val result = nest.test()
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
    }
}
