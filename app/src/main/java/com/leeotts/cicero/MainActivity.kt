package com.leeotts.cicero

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.leeotts.cicero.audio.WakeWordService
import com.leeotts.cicero.ui.CiceroRoot
import com.leeotts.cicero.util.isGranted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        watchWakeWordSetting()
    }

    /**
     * Starts and stops the wake-word service as the setting changes.
     *
     * Driven from here rather than from the ViewModel because the start has to
     * happen while an activity is started - Android 12+ refuses to start a
     * foreground service from the background, and a microphone-type service
     * started that way would get no microphone even if it were allowed.
     */
    private fun watchWakeWordSetting() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                assistant.config
                    .map { it.wakeWordEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        // The service stops itself if the microphone is refused,
                        // but there is no reason to start it only to find out.
                        if (enabled && isGranted(Manifest.permission.RECORD_AUDIO)) {
                            WakeWordService.start(this@MainActivity)
                        } else {
                            WakeWordService.stop(this@MainActivity)
                        }
                    }
            }
        }
    }
}
