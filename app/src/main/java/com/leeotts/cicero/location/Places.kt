package com.leeotts.cicero.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** A place the assistant has been asked about. */
data class Place(
    val label: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
}

/**
 * Address lookup through the platform [Geocoder].
 *
 * Deliberately not the Places API: Geocoder ships with Android, needs no key and
 * no billing account, and answers the only two questions this app asks - what is
 * this spot called, and roughly where is that name. Anything richer (opening
 * hours, ratings) is a question for the web search the brains already have.
 */
object Places {

    /** Reverse geocode: coordinates to something worth saying out loud. */
    suspend fun describe(context: Context, latitude: Double, longitude: Double): String? =
        geocode(context) { it.getFromLocation(latitude, longitude, 1) }
            ?.let(::speakable)

    /** Forward geocode, so a named destination can be dropped on the map. */
    suspend fun locate(context: Context, query: String): Place? =
        geocode(context) { it.getFromLocationName(query, 1) }
            ?.let { Place(speakable(it) ?: query, it.latitude, it.longitude) }

    /**
     * The synchronous overloads are deprecated from API 33 in favour of a
     * callback pair, but they still work and are far simpler to await. minSdk
     * here is 31, so the old path has to exist regardless.
     */
    @Suppress("DEPRECATION")
    private suspend fun geocode(
        context: Context,
        lookup: (Geocoder) -> List<Address>?,
    ): Address? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "no geocoder backend on this device")
            return@withContext null
        }
        runCatching { lookup(Geocoder(context, Locale.getDefault()))?.firstOrNull() }
            .getOrElse {
                Log.w(TAG, "geocode failed: ${it.message}")
                null
            }
    }

    /**
     * A phrase a person would use, not a postal address: "Deansgate, Manchester"
     * rather than a house number and postcode read aloud in full.
     */
    private fun speakable(address: Address): String? {
        val parts = listOfNotNull(
            address.featureName?.takeUnless { it.equals(address.thoroughfare, ignoreCase = true) }
                ?.takeUnless { it.all(Char::isDigit) },
            address.thoroughfare,
            address.locality ?: address.subAdminArea,
        ).distinct()
        return parts.joinToString(", ").ifBlank { address.getAddressLine(0) }
    }
}
