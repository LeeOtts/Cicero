package com.leeotts.cicero.home

import com.leeotts.cicero.ai.Http
import com.leeotts.cicero.tools.GetThermostatTool
import com.leeotts.cicero.tools.SetThermostatTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the whole Nest path against a mock server: token refresh, device read,
 * trait commands and the mode guards. No network, no Android.
 *
 * The tools are exercised through their real [NestGateway] rather than a stub,
 * because the guards that matter - which setpoint command a mode allows - live
 * in the tool and the parsing lives in the client, and a fake between them would
 * test neither.
 */
class SdmWireFormatTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/").toString().trimEnd('/')

    private val configured = NestConfig(
        projectId = "proj-1",
        clientId = "client-1",
        clientSecret = "secret-1",
        refreshToken = "refresh-1",
    )

    /** A gateway wired to the mock server, with a fresh token cache each time. */
    private fun gateway(config: NestConfig = configured) = NestGateway(
        client = SdmClient(url()),
        auth = NestAuth("${url()}/token"),
        config = { config },
    )

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun token() = enqueue("""{"access_token":"tok-1","expires_in":3599}""")

    private fun next(): RecordedRequest = server.takeRequest()

    private fun bodyJson(request: RecordedRequest): JsonObject =
        Http.json.parseToJsonElement(request.body.readUtf8()) as JsonObject

    private fun devices(
        mode: String = "HEAT",
        eco: String = "OFF",
        hvac: String = "HEATING",
        ambientC: Double = 21.5,
        heatC: Double? = 21.0,
        coolC: Double? = null,
        scale: String = "FAHRENHEIT",
        available: String = """["HEAT","COOL","HEATCOOL","OFF"]""",
    ): String {
        val setpoint = listOfNotNull(
            heatC?.let { """"heatCelsius":$it""" },
            coolC?.let { """"coolCelsius":$it""" },
        ).joinToString(",")
        return """
            {"devices":[{
              "name":"enterprises/proj-1/devices/dev-1",
              "type":"sdm.devices.types.THERMOSTAT",
              "traits":{
                "sdm.devices.traits.Temperature":{"ambientTemperatureCelsius":$ambientC},
                "sdm.devices.traits.Humidity":{"ambientHumidityPercent":38.0},
                "sdm.devices.traits.ThermostatMode":{"availableModes":$available,"mode":"$mode"},
                "sdm.devices.traits.ThermostatEco":{"mode":"$eco","heatCelsius":15.0},
                "sdm.devices.traits.ThermostatHvac":{"status":"$hvac"},
                "sdm.devices.traits.ThermostatTemperatureSetpoint":{$setpoint},
                "sdm.devices.traits.Settings":{"temperatureScale":"$scale"}
              },
              "parentRelations":[{"parent":"enterprises/proj-1/structures/s/rooms/r",
                                  "displayName":"Hallway"}]
            }]}
        """.trimIndent()
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
    }

    // ---------- auth ----------

    @Test
    fun `exchanges the refresh token and sends the result as a bearer`() = runBlocking {
        token()
        enqueue(devices())

        GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        val tokenRequest = next()
        assertEquals("/token", tokenRequest.path)
        val form = tokenRequest.body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-1"))
        assertTrue(form.contains("client_secret=secret-1"))

        val listRequest = next()
        assertEquals("/enterprises/proj-1/devices", listRequest.path)
        assertEquals("Bearer tok-1", listRequest.getHeader("Authorization"))
    }

    @Test
    fun `a rejected token is refreshed once, then reported as expired`() = runBlocking {
        token()
        enqueue("""{"error":"unauthenticated"}""", code = 401)
        token()
        enqueue("""{"error":"unauthenticated"}""", code = 401)

        val outcome = GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("expired"))
        // Two token calls and two device calls: one retry, not a loop.
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a dead refresh token names the consent screen`() = runBlocking {
        enqueue("""{"error":"invalid_grant"}""", code = 400)

        val outcome = GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("testing"))
    }

    @Test
    fun `an unconfigured account says so without calling out`() = runBlocking {
        val outcome = GetThermostatTool(gateway(NestConfig())).run(JsonObject(emptyMap()))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("settings"))
        assertEquals(0, server.requestCount)
    }

    // ---------- reading ----------

    @Test
    fun `reads a Fahrenheit thermostat in the unit it displays`() = runBlocking {
        token()
        enqueue(devices())

        val outcome = GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertFalse(outcome.isError)
        assertEquals(
            "Hallway thermostat: 71 degrees inside, 38 percent humidity. " +
                "Mode heat, set to 70. Heating right now.",
            outcome.content,
        )
    }

    @Test
    fun `reads a Celsius thermostat without converting`() = runBlocking {
        token()
        enqueue(devices(scale = "CELSIUS"))

        val outcome = GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertTrue(outcome.content.contains("22 degrees Celsius inside"))
        assertTrue(outcome.content.contains("set to 21"))
    }

    @Test
    fun `a heatcool thermostat reads as a range`() = runBlocking {
        token()
        enqueue(devices(mode = "HEATCOOL", heatC = 20.0, coolC = 24.0))

        val outcome = GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertTrue(outcome.content.contains("between 68 and 75"))
    }

    @Test
    fun `the device read is cached, because SDM allows five a minute`() = runBlocking {
        token()
        enqueue(devices())

        val nest = gateway()
        GetThermostatTool(nest).run(JsonObject(emptyMap()))
        GetThermostatTool(nest).run(JsonObject(emptyMap()))

        // One token, one device list - the second read came from the cache.
        assertEquals(2, server.requestCount)
    }

    // ---------- writing ----------

    @Test
    fun `a target in heat mode sends SetHeat in Celsius`() = runBlocking {
        token()
        enqueue(devices(mode = "HEAT"))
        enqueue("")

        val outcome = SetThermostatTool(gateway()).run(args("temperature" to 72))

        assertFalse(outcome.isError)
        next(); next()
        val command = next()
        assertEquals("/enterprises/proj-1/devices/dev-1:executeCommand", command.path)
        val body = bodyJson(command)
        assertEquals(SdmClient.SET_HEAT, body["command"]?.jsonPrimitive?.content)
        assertEquals(
            22.22,
            body["params"]!!.jsonObject["heatCelsius"]!!.jsonPrimitive.double,
            0.01,
        )
    }

    @Test
    fun `a target in cool mode sends SetCool`() = runBlocking {
        token()
        enqueue(devices(mode = "COOL", heatC = null, coolC = 22.0, hvac = "COOLING"))
        enqueue("")

        SetThermostatTool(gateway()).run(args("temperature" to 68))

        next(); next()
        val body = bodyJson(next())
        assertEquals(SdmClient.SET_COOL, body["command"]?.jsonPrimitive?.content)
        assertEquals(
            20.0,
            body["params"]!!.jsonObject["coolCelsius"]!!.jsonPrimitive.double,
            0.01,
        )
    }

    @Test
    fun `a target in heatcool moves the nearer setpoint and keeps the other`() = runBlocking {
        token()
        enqueue(devices(mode = "HEATCOOL", heatC = 20.0, coolC = 22.0))
        enqueue("")

        // 66F is 18.9C - nearer the heat setpoint at 20 than the cool one at 22.
        val outcome = SetThermostatTool(gateway()).run(args("temperature" to 66))

        assertFalse(outcome.isError)
        assertTrue(outcome.content.contains("on the heat setting"))
        next(); next()
        val params = bodyJson(next())["params"]!!.jsonObject
        assertEquals(18.89, params["heatCelsius"]!!.jsonPrimitive.double, 0.01)
        assertEquals(22.0, params["coolCelsius"]!!.jsonPrimitive.double, 0.01)
    }

    @Test
    fun `a heatcool target that would collapse the range is refused`() = runBlocking {
        token()
        enqueue(devices(mode = "HEATCOOL", heatC = 20.0, coolC = 22.0))

        // 70F is 21.1C - nearer the cool setpoint, and moving it there leaves
        // barely a degree above the heat setpoint.
        val outcome = SetThermostatTool(gateway()).run(args("temperature" to 70))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("on top of each other"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a mode change sends SetMode`() = runBlocking {
        token()
        enqueue(devices(mode = "OFF"))
        enqueue("")

        val outcome = SetThermostatTool(gateway()).run(args("mode" to "heat"))

        assertFalse(outcome.isError)
        next(); next()
        val body = bodyJson(next())
        assertEquals(SdmClient.SET_MODE, body["command"]?.jsonPrimitive?.content)
        assertEquals("HEAT", body["params"]!!.jsonObject["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mode and temperature together apply the mode first`() = runBlocking {
        token()
        enqueue(devices(mode = "OFF"))
        enqueue("")
        enqueue("")

        val outcome = SetThermostatTool(gateway()).run(args("mode" to "heat", "temperature" to 72))

        assertFalse(outcome.isError)
        next(); next()
        assertEquals(SdmClient.SET_MODE, bodyJson(next())["command"]?.jsonPrimitive?.content)
        assertEquals(SdmClient.SET_HEAT, bodyJson(next())["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `leaving eco turns eco off before setting the mode`() = runBlocking {
        token()
        enqueue(devices(mode = "HEAT", eco = "MANUAL_ECO"))
        enqueue("")
        enqueue("")

        SetThermostatTool(gateway()).run(args("mode" to "heat"))

        next(); next()
        val eco = bodyJson(next())
        assertEquals(SdmClient.SET_ECO, eco["command"]?.jsonPrimitive?.content)
        assertEquals("OFF", eco["params"]!!.jsonObject["mode"]?.jsonPrimitive?.content)
        assertEquals(SdmClient.SET_MODE, bodyJson(next())["command"]?.jsonPrimitive?.content)
    }

    // ---------- the guards ----------

    @Test
    fun `a setpoint while off explains rather than silently turning it on`() = runBlocking {
        token()
        enqueue(devices(mode = "OFF", hvac = "OFF"))

        val outcome = SetThermostatTool(gateway()).run(args("temperature" to 70))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("it is off"))
        // Nothing was sent: no mode was quietly chosen on the user's behalf.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a setpoint while eco explains the lock`() = runBlocking {
        token()
        enqueue(devices(mode = "HEAT", eco = "MANUAL_ECO"))

        val outcome = SetThermostatTool(gateway()).run(args("temperature" to 70))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("eco"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a mode the thermostat does not have is refused with what it can do`() = runBlocking {
        token()
        enqueue(devices(available = """["HEAT","OFF"]"""))

        val outcome = SetThermostatTool(gateway()).run(args("mode" to "cool"))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("heat, off"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `neither argument is an error, not a no-op`() = runBlocking {
        val outcome = SetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertTrue(outcome.isError)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an account with no thermostat says so`() = runBlocking {
        token()
        enqueue("""{"devices":[]}""")

        val outcome = GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("cannot find a thermostat"))
    }

    @Test
    fun `rate limiting is reported in words`() = runBlocking {
        token()
        enqueue("""{"error":"RESOURCE_EXHAUSTED"}""", code = 429)

        val outcome = GetThermostatTool(gateway()).run(JsonObject(emptyMap()))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("rate limiting"))
    }
}
