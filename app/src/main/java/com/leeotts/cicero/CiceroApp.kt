package com.leeotts.cicero

import android.app.Application
import android.util.Log
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.home.NestController
import com.leeotts.cicero.location.DestinationLog
import com.leeotts.cicero.location.LocationProvider
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
     * One Nest client for the process.
     *
     * Shared for the same reason as the others, with a sharper edge: SDM allows
     * a thermostat five calls a minute and a hundred an hour, counted per device
     * rather than per app. A second instance would hold its own access token and
     * its own device cache, and spend that budget twice over.
     */
    val nest: NestController by lazy { NestController(this) }

    /**
     * Whether any glasses are currently paired and reachable.
     *
     * The wake word's battery policy is built on this: with the glasses in a
     * drawer there is nothing to look at, so holding the microphone open buys
     * nothing and costs 5-10% of the battery an hour. Gating on it is the
     * single largest saving in that feature.
     *
     * Free to observe. The SDK already maintains this set, so there is no
     * polling and no extra receiver - which matters, because a periodic check
     * would itself be the wakeup the policy exists to avoid.
     *
     * Note this is a looser boundary than GlassesController's: a device here
     * may still be refused by createSession. That is the right test for "is
     * there any point listening", which is all it is used for.
     */
    val glassesConnected: Flow<Boolean> =
        Wearables.devices.map { it.isNotEmpty() }

    override fun onCreate() {
        super.onCreate()
        // Nothing else in the SDK may be touched before this succeeds.
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT initialize failed: ${error.description}") }
    }
}
