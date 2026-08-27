package com.leeotts.cicero

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.leeotts.cicero.ui.CiceroRoot

class MainActivity : ComponentActivity() {

    private val assistant: AssistantViewModel by viewModels()
    private val glasses: GlassesViewModel by viewModels()
    private val notes: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Scaffold and the drawer sheet apply their own window insets, so nothing
        // here should pad by hand.
        enableEdgeToEdge()
        setContent { CiceroRoot(assistant = assistant, glasses = glasses, notes = notes) }
    }
}
