package com.leeotts.cicero.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.leeotts.cicero.TAG
import com.leeotts.cicero.util.isGranted
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the user is.
 *
 * DAT exposes no GPS - the glasses have none - so position comes from the phone
 * in the user's pocket. That is close enough for every question this app asks of
 * it ("what is near me", "how do I get to X"), and it costs no glasses battery.
 *
 * Application-scoped, so a fix taken by a tool and a fix drawn on the map come
 * from one client rather than two.
 */
class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    /**
     * Coarse is enough to answer "what is near me". Fine is better for
     * navigation, but either one lets the tools work rather than refusing.
     */
    fun hasPermission(): Boolean =
        appContext.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            appContext.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * A current fix, or the last known one, or null.
     *
     * getCurrentLocation may genuinely take seconds indoors and can resolve to
     * null, so it is bounded by a timeout and backed by lastLocation. A stale
     * fix beats no answer for "roughly where am I".
     */
    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission()) return null
        return fresh() ?: lastKnown()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(): Location? = withTimeoutOrNull(FIX_TIMEOUT_MS) {
        val cancellation = CancellationTokenSource()
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancellation.cancel() }
            client.getCurrentLocation(PRIORITY, cancellation.token)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener {
                    Log.w(TAG, "getCurrentLocation failed: ${it.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnown(): Location? = withTimeoutOrNull(LAST_KNOWN_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            client.lastLocation
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        }
    }

    private companion object {
        /** Balanced, not high: this is "which street am I on", not turn-by-turn. */
        const val PRIORITY = Priority.PRIORITY_BALANCED_POWER_ACCURACY
        const val FIX_TIMEOUT_MS = 10_000L
        const val LAST_KNOWN_TIMEOUT_MS = 2_000L
    }
}
