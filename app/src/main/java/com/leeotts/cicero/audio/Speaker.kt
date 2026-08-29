package com.leeotts.cicero.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Speaks the assistant's answers aloud.
 *
 * Routing is deliberately left alone. Output goes out as ordinary media, which
 * the system already sends to the glasses over A2DP whenever they are the
 * active output — so this needs no Bluetooth code, no DAT session, and no
 * Developer Mode.
 *
 * In particular it does NOT open an HFP/SCO route. Doing so would drop the
 * glasses to 8 kHz mono and take the microphone away from Meta AI; leaving A2DP
 * alone is what lets Cicero and "Hey Meta" coexist.
 */
class Speaker(context: Context) {

    private val _speaking = MutableStateFlow(false)

    /** Drives the stop control, so it is only offered when there is something to stop. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var ready = false

    /** Set when speak() is called before the engine finishes starting up. */
    private var pending: String? = null

    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            Log.w(TAG, "text to speech unavailable (status $status)")
            pending = null
            return@TextToSpeech
        }
        pending?.let { pending = null; speak(it) }
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Callbacks arrive on a binder thread; MutableStateFlow is safe there.
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) { _speaking.value = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                _speaking.value = false
            }

            @Deprecated("Required by the base class", ReplaceWith(""))
            override fun onError(utteranceId: String?) { _speaking.value = false }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "speech failed with code $errorCode")
                _speaking.value = false
            }
        })
    }

    /** Replaces anything still being spoken — a new answer supersedes the old one. */
    fun speak(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        if (!ready) {
            // The engine takes a moment to bind, and the first answer often
            // arrives inside that window.
            pending = spoken
            return
        }
        runCatching {
            engine.language = Locale.getDefault()
            engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.onFailure {
            Log.e(TAG, "speak failed", it)
            _speaking.value = false
        }
    }

    /** Cuts off a long answer. */
    fun stop() {
        pending = null
        if (ready) runCatching { engine.stop() }
        // onStop does not fire for an utterance that never started.
        _speaking.value = false
    }

    fun shutdown() {
        pending = null
        runCatching { engine.stop(); engine.shutdown() }
        ready = false
        _speaking.value = false
    }

    private companion object {
        const val UTTERANCE_ID = "cicero-answer"
    }
}
