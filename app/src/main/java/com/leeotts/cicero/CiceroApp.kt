package com.leeotts.cicero

import android.app.Application
import android.util.Log
import com.leeotts.cicero.glasses.GlassesController
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
    override fun onCreate() {
        super.onCreate()
        // Nothing else in the SDK may be touched before this succeeds.
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT initialize failed: ${error.description}") }
    }
}
