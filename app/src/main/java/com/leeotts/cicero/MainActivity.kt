package com.leeotts.cicero

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.leeotts.cicero.audio.WakeService
import com.leeotts.cicero.ui.CiceroRoot
import com.leeotts.cicero.util.isGranted
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val assistant: AssistantViewModel by viewModels()
    private val glasses: GlassesViewModel by viewModels()
    private val notes: NotesViewModel by viewModels()
    private val map: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Scaffold and the drawer sheet apply their own window insets, so nothing
        // here should pad by hand.
        enableEdgeToEdge()
        setContent {
            CiceroRoot(assistant = assistant, glasses = glasses, notes = notes, map = map)
        }
    }

    /**
     * Re-establishes the wake word if the settings ask for it.
     *
     * This is the only place a cold-started app can start listening again, and
     * it is why the toggle warns that listening does not survive a reboot on
     * its own: from API 31 a foreground service cannot be started from the
     * background, and API 34 refuses a microphone-typed one outright unless the
     * app is in the foreground. A boot receiver would simply throw.
     *
     * Starting an already-running service is a no-op, so this can be
     * unconditional beyond the two checks that would make it fail.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (WakeService.shouldRun(this@MainActivity) &&
                isGranted(Manifest.permission.RECORD_AUDIO)
            ) {
                WakeService.start(this@MainActivity)
            }
        }
    }
}
