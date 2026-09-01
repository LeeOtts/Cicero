package com.leeotts.cicero

import android.app.Application
import android.util.Log
import com.leeotts.cicero.audio.VoiceHandoff
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.home.NestController
import com.leeotts.cicero.location.DestinationLog
import com.leeotts.cicero.location.LocationProvider
import com.meta.wearable.dat.core.Wearables

const val TAG = "Cicero"

class CiceroApp : Application() {

    /**
     * The one DAT session owner for the process.
     *
     * Two ViewModels used to build their own, so the Glasses screen reported a
     * different controller than the assistant's look tool actually used, and two
     * sessions could be open at once - which suspends Meta AI on the glasses.
     * GlassesController holds no Context, so an Application-scoped instance
     * leaks nothing.
     */
    val glasses: GlassesController by lazy { GlassesController() }

    /**
     * One location client for the process, so the fix a tool takes and the dot
     * the map draws are the same fix rather than two independent lookups.
     */
    val location: LocationProvider by lazy { LocationProvider(this) }

    /** Where the assistant was last asked to go, for the pin on the Map screen. */
    val destinations: DestinationLog by lazy { DestinationLog() }

    /**
     * Arbitrates the one microphone between the wake word and the Ask screen.
     *
     * Process-scoped because it describes a single piece of hardware: a second
     * instance would describe a second microphone that does not exist, and the
     * two holders would stop seeing each other.
     */
    val voice: VoiceHandoff by lazy { VoiceHandoff() }

    /**
     * One Nest client for the process.
     *
     * Shared for the same reason as the others, with a sharper edge: SDM allows
     * a thermostat five calls a minute and a hundred an hour, counted per device
     * rather than per app. A second instance would hold its own access token and
     * its own device cache, and spend that budget twice over.
     */
    val nest: NestController by lazy { NestController(this) }

    override fun onCreate() {
        super.onCreate()
        // Nothing else in the SDK may be touched before this succeeds.
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT initialize failed: ${error.description}") }
    }
}
