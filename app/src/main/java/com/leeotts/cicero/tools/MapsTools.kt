package com.leeotts.cicero.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.util.Log
import com.leeotts.cicero.TAG
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.ai.ToolOutcome
import com.leeotts.cicero.ai.ToolSpec
import com.leeotts.cicero.location.DestinationLog
import com.leeotts.cicero.location.LocationProvider
import com.leeotts.cicero.location.Place
import com.leeotts.cicero.location.Places
import kotlinx.serialization.json.JsonObject

/**
 * Maps, through intents rather than the Maps Platform APIs.
 *
 * Everything here is free and needs no key: `google.navigation:` and `geo:` are
 * public URI schemes any maps app can answer. The Maps SDK key this project also
 * carries is only for drawing the in-app map, not for these.
 */

private const val NO_PERMISSION =
    "I do not have permission to use location yet. It can be granted on the Map screen."

/** Where the user is, in words. */
class WhereAmITool(
    private val context: Context,
    private val location: LocationProvider,
) : Tool {

    override val spec = ToolSpec(
        name = "where_am_i",
        description = "Find out where the user is right now. Use this before answering " +
            "anything that depends on their location, and before finding places nearby.",
        parameters = Schemas.empty,
        // A cold GPS fix indoors can take a while, or never arrive at all.
        progressPhrase = "Finding where you are.",
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        if (!location.hasPermission()) return ToolOutcome(NO_PERMISSION, isError = true)

        val fix = location.current() ?: return ToolOutcome(
            "I could not get a location fix. There may be no signal indoors.",
            isError = true,
        )

        val described = Places.describe(context, fix.latitude, fix.longitude)
        // The coordinates go back too, so a following tool call or a web search
        // can use them without asking again.
        val coordinates = "%.5f, %.5f".format(fix.latitude, fix.longitude)
        return ToolOutcome(
            if (described != null) "Near $described ($coordinates)." else "At $coordinates.",
        )
    }
}

/**
 * Hands the user over to turn-by-turn navigation.
 *
 * This is the one maps action worth having on glasses: the answer to "how do I
 * get there" is not a spoken paragraph of directions, it is the navigation
 * actually starting.
 */
class NavigateTool(
    private val context: Context,
    private val destinations: DestinationLog,
) : Tool {

    override val spec = ToolSpec(
        name = "navigate_to",
        description = "Start turn-by-turn navigation to a place in Google Maps. Use when the " +
            "user wants to GO somewhere, not merely to know about it.",
        parameters = Schemas.obj(
            "destination" to Schemas.string(
                "Where to go: an address, a place name, or something like " +
                    "\"the nearest petrol station\"",
            ),
            "mode" to Schemas.enumOf(
                "How they are travelling",
                listOf("driving", "walking", "bicycling", "transit"),
            ),
            required = listOf("destination"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val destination = arguments.str("destination")
            ?: return ToolOutcome("I did not catch where to navigate to.", isError = true)
        val mode = MODES[arguments.str("mode")] ?: "d"

        val uri = Uri.parse(
            "google.navigation:q=${Uri.encode(destination)}&mode=$mode",
        )
        if (!context.launchMaps(uri)) {
            return ToolOutcome(
                "I could not open a maps app to navigate there.",
                isError = true,
            )
        }

        // Best effort, and only for the pin on the Map screen - navigation has
        // already started either way, so a failure here changes nothing.
        destinations.record(Places.locate(context, destination) ?: Place(destination))
        return ToolOutcome("Navigating to $destination.")
    }

    private companion object {
        val MODES = mapOf(
            "driving" to "d",
            "walking" to "w",
            "bicycling" to "b",
            "transit" to "r",
        )
    }
}

/** Shows what is around the user, on the map, without a Places API bill. */
class FindNearbyTool(
    private val context: Context,
    private val location: LocationProvider,
    private val destinations: DestinationLog,
) : Tool {

    override val spec = ToolSpec(
        name = "find_nearby",
        description = "Open a map of places near the user - cafes, petrol stations, cash " +
            "machines, chemists. Use when they want to SEE what is around them.",
        parameters = Schemas.obj(
            "query" to Schemas.string("What to look for, for example \"coffee\" or \"pharmacy\""),
            required = listOf("query"),
        ),
        progressPhrase = "Looking at what is nearby.",
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.str("query")
            ?: return ToolOutcome("I did not catch what to look for.", isError = true)

        val fix: Location? = if (location.hasPermission()) location.current() else null
        // geo:0,0 tells the maps app to search wherever it thinks the user is,
        // which is the right fallback when we have no fix of our own.
        val centre = fix?.let { "${it.latitude},${it.longitude}" } ?: "0,0"
        val uri = Uri.parse("geo:$centre?q=${Uri.encode(query)}")

        if (!context.launchMaps(uri)) {
            return ToolOutcome("I could not open a maps app.", isError = true)
        }

        destinations.record(Place(query, fix?.latitude, fix?.longitude))
        return ToolOutcome(
            if (fix != null) "Showing $query near you." else "Showing $query on the map.",
        )
    }
}

/**
 * Prefers Google Maps, but falls back to whatever else can take the URI, so a
 * device without it still navigates rather than reporting failure.
 */
private fun Context.launchMaps(uri: Uri): Boolean {
    val preferred = Intent(Intent.ACTION_VIEW, uri)
        .setPackage(GOOGLE_MAPS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val anyApp = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    for (intent in listOf(preferred, anyApp)) {
        try {
            startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "no handler for $uri via ${intent.`package` ?: "any app"}: ${e.message}")
        }
    }
    return false
}

private const val GOOGLE_MAPS = "com.google.android.apps.maps"
