package com.leeotts.cicero.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.leeotts.cicero.TAG

/**
 * Routing the microphone to the glasses over Bluetooth HFP.
 *
 * Extracted from ScoProbe so the probe and the wake word's glasses source share
 * one implementation rather than two copies that can drift. The probe is how
 * the sample rate question gets answered; the source is what acts on the
 * answer, and they must be routing identically for the measurement to mean
 * anything.
 *
 * Worth restating what opening this route costs, because it is easy to reach
 * for and hard to undo: SCO takes the microphone away from Meta AI outright,
 * drops all glasses audio to narrowband mono, and drains both devices. Speaker
 * refuses to open it for exactly that reason. Nothing here should be called
 * unless the user has explicitly chosen the glasses microphone.
 */

/**
 * The API 31+ replacement for the deprecated startBluetoothSco(). False when no
 * Bluetooth SCO device is offered, which means the glasses are not connected
 * for voice - the phone may still be paired for media.
 *
 * Requires BLUETOOTH_CONNECT: without it the device list comes back unnamed and
 * this cannot find the glasses.
 */
internal fun AudioManager.selectBluetoothScoDevice(): Boolean {
    val sco = availableCommunicationDevices
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        ?: run {
            Log.w(TAG, "no BLUETOOTH_SCO communication device available")
            return false
        }
    val ok = setCommunicationDevice(sco)
    Log.i(TAG, "setCommunicationDevice(${sco.productName}) = $ok")
    return ok
}

/** Hands the route back, so media returns to A2DP and Meta AI gets the mic. */
internal fun AudioManager.clearRoute() {
    runCatching { clearCommunicationDevice() }
}

/** The SCO route needs a moment to settle before AudioRecord sees it. */
internal const val ROUTE_SETTLE_MS = 2_000L
