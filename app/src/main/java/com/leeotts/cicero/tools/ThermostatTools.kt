package com.leeotts.cicero.tools

import com.leeotts.cicero.ai.BrainException
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.ai.ToolOutcome
import com.leeotts.cicero.ai.ToolSpec
import com.leeotts.cicero.home.NestGateway
import com.leeotts.cicero.home.SdmClient
import com.leeotts.cicero.home.ThermostatState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/** Reads the Nest thermostat. */
class GetThermostatTool(private val nest: NestGateway) : Tool {

    override val spec = ToolSpec(
        name = "get_thermostat",
        description = "Read the house thermostat: the temperature and humidity indoors, " +
            "which mode it is in, what it is set to, and whether it is running.",
        parameters = Schemas.empty,
        // A round trip to Google's SDM API, over whatever network is to hand.
        progressPhrase = "Checking the thermostat.",
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome = guarded {
        val state = nest.thermostat() ?: return@guarded noThermostat()
        ToolOutcome(describe(state))
    }
}

/**
 * Changes the thermostat.
 *
 * The mode matters more than it looks. SDM rejects a setpoint that does not
 * match the current mode - SetHeat only lands in HEAT, SetCool only in COOL,
 * SetRange only in HEATCOOL - and refuses all three outright while the
 * thermostat is off or in eco. The failures come back opaque, so this reads the
 * state first and explains in words rather than passing on a shrug.
 */
class SetThermostatTool(private val nest: NestGateway) : Tool {

    override val spec = ToolSpec(
        name = "set_thermostat",
        description = "Change the house thermostat: a target temperature, a mode, or both. " +
            "The temperature is in whatever unit the thermostat itself displays.",
        parameters = Schemas.obj(
            "temperature" to Schemas.integer("Target temperature, in the thermostat's own unit"),
            "mode" to Schemas.enumOf(
                "What the thermostat should do",
                listOf("heat", "cool", "heatcool", "off", "eco"),
            ),
        ),
        // Reads the state before it writes, so two round trips rather than one.
        progressPhrase = "Setting the thermostat.",
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome = guarded {
        val wantedTemp = arguments.int("temperature")
        val wantedMode = arguments.str("mode")?.lowercase()
        if (wantedTemp == null && wantedMode == null) {
            return@guarded ToolOutcome("Tell me a temperature or a mode to set.", isError = true)
        }

        val state = nest.thermostat() ?: return@guarded noThermostat()
        val done = mutableListOf<String>()
        var mode = state.mode
        var eco = state.eco

        if (wantedMode != null) {
            if (wantedMode == "eco") {
                nest.execute(state, SdmClient.SET_ECO, buildJsonObject { put("mode", "MANUAL_ECO") })
                eco = true
                done += "eco mode on"
            } else {
                val target = wantedMode.uppercase()
                if (state.availableModes.isNotEmpty() && target !in state.availableModes) {
                    val can = state.availableModes.joinToString(", ") { it.lowercase() }
                    return@guarded ToolOutcome(
                        "This thermostat cannot do $wantedMode. It can do $can.",
                        isError = true,
                    )
                }
                // Turning eco off is its own command; SetMode alone leaves it on,
                // and every setpoint after that would then be refused.
                if (eco) {
                    nest.execute(state, SdmClient.SET_ECO, buildJsonObject { put("mode", "OFF") })
                    eco = false
                }
                nest.execute(state, SdmClient.SET_MODE, buildJsonObject { put("mode", target) })
                mode = target
                done += if (target == "OFF") "off" else "mode $wantedMode"
            }
        }

        if (wantedTemp == null) {
            return@guarded ToolOutcome("Set the ${state.label} to ${done.joinToString(" and ")}.")
        }

        val refusal = when {
            eco -> "it is in eco mode, so the temperature is locked until eco is off"
            mode == "OFF" ->
                "it is off, so there is no temperature to set - turn on the heat or cooling first"
            else -> null
        }
        if (refusal != null) {
            return@guarded ToolOutcome(partial(state, done, refusal), isError = true)
        }

        val celsius = state.toCelsius(wantedTemp)
        val moved = when (mode) {
            "HEAT" -> {
                nest.execute(state, SdmClient.SET_HEAT, buildJsonObject { put("heatCelsius", celsius) })
                null
            }

            "COOL" -> {
                nest.execute(state, SdmClient.SET_COOL, buildJsonObject { put("coolCelsius", celsius) })
                null
            }

            // A bare "set it to seventy" is genuinely ambiguous when there are
            // two setpoints, so move the nearer one and say which one moved.
            "HEATCOOL" -> {
                val heat = state.heatC
                val cool = state.coolC
                if (heat == null || cool == null) {
                    return@guarded ToolOutcome(
                        "I couldn't read the current heat and cool settings.",
                        isError = true,
                    )
                }
                val moveHeat = abs(celsius - heat) <= abs(celsius - cool)
                val newHeat = if (moveHeat) celsius else heat
                val newCool = if (moveHeat) cool else celsius
                if (newCool - newHeat < MIN_SPREAD_C) {
                    return@guarded ToolOutcome(
                        "That would put the heat and cool settings on top of each other. " +
                            "The thermostat needs a few degrees between them.",
                        isError = true,
                    )
                }
                nest.execute(
                    state,
                    SdmClient.SET_RANGE,
                    buildJsonObject {
                        put("heatCelsius", newHeat)
                        put("coolCelsius", newCool)
                    },
                )
                if (moveHeat) "heat" else "cool"
            }

            else -> return@guarded ToolOutcome(
                "The thermostat is in $mode, which I cannot set a temperature for.",
                isError = true,
            )
        }

        done += "$wantedTemp ${state.unit}" + (moved?.let { " on the $it setting" } ?: "")
        ToolOutcome("Set the ${state.label} to ${done.joinToString(" and ")}.")
    }

    private companion object {
        /** Nest keeps roughly three Fahrenheit between the two setpoints. */
        const val MIN_SPREAD_C = 1.6
    }
}

// --- shared ------------------------------------------------------------------

/**
 * Turns the gateway's speakable failures into tool errors.
 *
 * The tool loop would catch these anyway, but a ToolOutcome marked isError lets
 * the model see that it failed and say so, instead of the message arriving as
 * though it were an answer.
 */
private suspend inline fun guarded(block: () -> ToolOutcome): ToolOutcome = try {
    block()
} catch (e: BrainException) {
    ToolOutcome(e.spokenMessage, isError = true)
}

private fun noThermostat() =
    ToolOutcome("I cannot find a thermostat on your Nest account.", isError = true)

private fun partial(state: ThermostatState, done: List<String>, because: String): String =
    if (done.isEmpty()) {
        "I could not set the ${state.label}: $because."
    } else {
        "I set the ${state.label} to ${done.joinToString(" and ")}, " +
            "but not the temperature: $because."
    }

private fun describe(state: ThermostatState): String = buildString {
    append(state.label.replaceFirstChar { it.uppercase() })
    append(": ")
    val ambient = state.display(state.ambientC)
    append(if (ambient != null) "$ambient ${state.unit} inside" else "no temperature reading")
    state.humidityPercent?.let { append(", ${it.toInt()} percent humidity") }
    append(". ")

    when {
        state.eco -> append("Eco mode is on")
        state.mode == "OFF" -> append("It is off")
        else -> {
            append("Mode ${state.mode.lowercase()}")
            target(state)?.let { append(", set to $it") }
        }
    }
    append(". ")
    append(
        when (state.hvac) {
            "HEATING" -> "Heating right now."
            "COOLING" -> "Cooling right now."
            else -> "Not running right now."
        },
    )
}

private fun target(state: ThermostatState): String? = when (state.mode) {
    "HEAT" -> state.display(state.heatC)?.toString()
    "COOL" -> state.display(state.coolC)?.toString()
    "HEATCOOL" -> {
        val heat = state.display(state.heatC)
        val cool = state.display(state.coolC)
        if (heat != null && cool != null) "between $heat and $cool" else null
    }

    else -> null
}
