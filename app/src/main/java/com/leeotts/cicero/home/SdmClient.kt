package com.leeotts.cicero.home

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * One Nest thermostat, flattened out of the trait soup the API returns.
 *
 * [deviceId] is the full resource name - "enterprises/x/devices/y" - because
 * that is what the command endpoint is addressed by.
 */
data class ThermostatState(
    val deviceId: String,
    val room: String?,
    /** HEAT, COOL, HEATCOOL or OFF. */
    val mode: String,
    val availableModes: List<String>,
    val eco: Boolean,
    /** HEATING, COOLING or OFF - what the equipment is doing right now. */
    val hvac: String,
    val ambientC: Double?,
    val humidityPercent: Double?,
    val heatC: Double?,
    val coolC: Double?,
    /** The scale the thermostat itself displays, so Cicero speaks the same one. */
    val fahrenheit: Boolean,
) {
    val label: String get() = room?.let { "$it thermostat" } ?: "thermostat"

    /** Renders a Celsius reading in whatever unit the device shows. */
    fun display(celsius: Double?): Int? = celsius?.let {
        (if (fahrenheit) toFahrenheit(it) else it).roundToInt()
    }

    /** Reads a number the user spoke, in the unit they would have said it in. */
    fun toCelsius(displayed: Int): Double =
        if (fahrenheit) toCelsius(displayed.toDouble()) else displayed.toDouble()

    val unit: String get() = if (fahrenheit) "degrees" else "degrees Celsius"
}

internal fun toFahrenheit(celsius: Double) = celsius * 9.0 / 5.0 + 32.0
internal fun toCelsius(fahrenheit: Double) = (fahrenheit - 32.0) * 5.0 / 9.0

/**
 * The Smart Device Management REST surface, narrowed to what a thermostat needs.
 *
 * [baseUrl] is a constructor parameter for the same reason the brains take one:
 * so a MockWebServer can stand in for Google in a JVM test.
 */
class SdmClient(private val baseUrl: String = SDM_BASE) {

    /** Every device on the account, as raw objects - the caller picks. */
    internal suspend fun listDevices(projectId: String, token: String): List<JsonObject> {
        val body = SdmHttp.getJson("$baseUrl/enterprises/$projectId/devices", token)
        return body["devices"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    }

    /** The first thermostat on the account, or null if there is none. */
    suspend fun thermostat(projectId: String, token: String): ThermostatState? =
        listDevices(projectId, token)
            .firstOrNull { it.string("type") == THERMOSTAT_TYPE }
            ?.let { parseThermostat(it) }

    suspend fun execute(
        deviceId: String,
        command: String,
        params: JsonObject,
        token: String,
    ) {
        SdmHttp.postJson(
            "$baseUrl/$deviceId:executeCommand",
            JsonObject(mapOf("command" to json(command), "params" to params)),
            token,
        )
    }

    companion object {
        const val SDM_BASE = "https://smartdevicemanagement.googleapis.com/v1"
        const val THERMOSTAT_TYPE = "sdm.devices.types.THERMOSTAT"

        const val SET_MODE = "sdm.devices.commands.ThermostatMode.SetMode"
        const val SET_ECO = "sdm.devices.commands.ThermostatEco.SetMode"
        const val SET_HEAT = "sdm.devices.commands.ThermostatTemperatureSetpoint.SetHeat"
        const val SET_COOL = "sdm.devices.commands.ThermostatTemperatureSetpoint.SetCool"
        const val SET_RANGE = "sdm.devices.commands.ThermostatTemperatureSetpoint.SetRange"
    }
}

// --- parsing ----------------------------------------------------------------

internal fun parseThermostat(device: JsonObject): ThermostatState {
    val traits = device["traits"]?.jsonObject ?: JsonObject(emptyMap())
    fun trait(name: String) = traits["sdm.devices.traits.$name"]?.jsonObject

    val setpoint = trait("ThermostatTemperatureSetpoint")
    val ecoTrait = trait("ThermostatEco")

    return ThermostatState(
        deviceId = device.string("name").orEmpty(),
        room = device["parentRelations"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.string("displayName"),
        mode = trait("ThermostatMode")?.string("mode") ?: "OFF",
        availableModes = trait("ThermostatMode")?.get("availableModes")?.jsonArray
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            .orEmpty(),
        // Any eco mode other than OFF locks the setpoints out.
        eco = (ecoTrait?.string("mode") ?: "OFF") != "OFF",
        hvac = trait("ThermostatHvac")?.string("status") ?: "OFF",
        ambientC = trait("Temperature")?.number("ambientTemperatureCelsius"),
        humidityPercent = trait("Humidity")?.number("ambientHumidityPercent"),
        heatC = setpoint?.number("heatCelsius"),
        coolC = setpoint?.number("coolCelsius"),
        // Absent means Celsius; only an explicit FAHRENHEIT switches the unit.
        fahrenheit = trait("Settings")?.string("temperatureScale") == "FAHRENHEIT",
    )
}

private fun JsonObject.string(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.ifBlank { null }

private fun JsonObject.number(key: String): Double? =
    this[key]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }

private fun json(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
