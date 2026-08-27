package com.leeotts.cicero

import android.app.Application
import android.util.Log
import com.leeotts.cicero.glasses.GlassesController
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
    override fun onCreate() {
        super.onCreate()
        // Nothing else in the SDK may be touched before this succeeds.
        Wearables.initialize(this)
            .onFailure { error, _ -> Log.e(TAG, "DAT initialize failed: ${error.description}") }
    }
}
