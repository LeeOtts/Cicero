package com.leeotts.cicero.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The slice of [SpeechRecognizer] the helper drives.
 *
 * It exists so the capture lifecycle - which callbacks are still allowed to
 * land, when listening flips false - can be tested on the JVM with no device
 * and no microphone. Bundle parsing happens in the adapter below, which keeps
 * this free of Android types.
 */
internal interface SpeechEngine {
    fun start(listener: Listener)
    fun stop()
    fun destroy()

    interface Listener {
        /** The best guess so far; fires repeatedly as the user speaks. */
        fun onTranscript(text: String)

        /**
         * The words the capture settled on. Fires once, and only when the
         * recognizer produced a result - never after an error, where the last
         * partial is all there is and nothing was decided.
         */
        fun onFinalTranscript(text: String)

        /** Terminal, whether the capture ended in a result or an error. */
        fun onFinished()
    }
}

/** Where engines come from, so a test can hand over a fake one. */
internal interface SpeechEngines {
    val available: Boolean
    fun create(): SpeechEngine
}

/**
 * Captures one spoken question through Android's built-in speech recognizer -
 * the phone mic, not the glasses. A stopgap for typing until the glasses' own
 * wake-word pipeline (see the phase plan) lands.
 *
 * Must be used from the main thread, same as [android.speech.tts.TextToSpeech]
 * in [Speaker].
 */
class SpeechRecognizerHelper internal constructor(private val engines: SpeechEngines) {

    constructor(context: Context) : this(AndroidSpeechEngines(context.applicationContext))

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** False on devices with no recognition service - some AOSP/custom ROMs. */
    val available: Boolean
        get() = engines.available

    /**
     * The capture whose callbacks may still touch shared state.
     *
     * A stopped engine keeps delivering for a moment afterwards, so without
     * this a superseded capture's late words would overwrite the live one's.
     */
    private var current: SpeechEngine? = null

    /**
     * Starts one capture, transcribing into the field as the user speaks.
     *
     * [onText] fires repeatedly with the best guess so far and then once more
     * with the final result, so the caller can simply overwrite what it holds
     * each time. Nothing is emitted at all when nothing was heard.
     *
     * [onFinal] fires once, just after the last [onText], with the words the
     * recognizer settled on - the caller's cue that the user has stopped
     * speaking rather than merely paused. It is skipped when the capture ended
     * in an error or was stopped by hand, so acting on it can never send words
     * the user meant to take back.
     *
     * A no-op if already listening or [available] is false - the caller is
     * expected to check [available] before offering the mic button at all.
     *
     * [onText] comes last so a trailing lambda still reads as the transcript.
     */
    fun start(onFinal: (String) -> Unit = {}, onText: (String) -> Unit) {
        if (_listening.value || !available) return
        // Defensive: nothing should be left running here, and a second live
        // engine is the one thing the platform will not tolerate.
        retire()

        val engine = engines.create()
        current = engine
        _listening.value = true

        val listener = object : SpeechEngine.Listener {
            override fun onTranscript(text: String) {
                if (current === engine) onText(text)
            }

            override fun onFinalTranscript(text: String) {
                if (current !== engine) return
                // Through onText as well, so the field shows the words that
                // are about to be acted on rather than the last partial.
                onText(text)
                onFinal(text)
            }

            override fun onFinished() = release(engine)
        }

        runCatching { engine.start(listener) }.onFailure {
            Log.e(TAG, "startListening failed", it)
            release(engine)
        }
    }

    /**
     * Stops listening now, keeping the words already transcribed.
     *
     * The engine is released outright rather than left to deliver a closing
     * result. Winding one down was worse in both directions: its late result
     * would overwrite edits made after the tap, and while it stayed bound a
     * second tap opened a competing recognizer the platform refuses - it
     * arbitrates one session at a time and answers the loser with
     * ERROR_RECOGNIZER_BUSY. Nothing the user watched appear is lost: the
     * field already holds the last partial.
     */
    fun cancel() = destroy()

    /** Releases the engine outright; nothing further is delivered. */
    fun destroy() {
        retire()
        _listening.value = false
    }

    /** Drops the current engine, so its late callbacks no longer own anything. */
    private fun retire() {
        val engine = current ?: return
        current = null
        runCatching { engine.destroy() }
    }

    /**
     * Ends one capture. A superseded engine is disposed of without touching the
     * live capture's state - it no longer owns either the text or the flag.
     */
    private fun release(engine: SpeechEngine) {
        runCatching { engine.destroy() }
        if (current !== engine) return
        current = null
        _listening.value = false
    }
}

private class AndroidSpeechEngines(private val context: Context) : SpeechEngines {
    override val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun create(): SpeechEngine = AndroidSpeechEngine(context)
}

private class AndroidSpeechEngine(context: Context) : SpeechEngine {

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    override fun start(listener: SpeechEngine.Listener) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                Log.w(TAG, "speech recognition failed with code $error")
                listener.onFinished()
            }

            override fun onResults(results: Bundle?) {
                best(results)?.let(listener::onFinalTranscript)
                listener.onFinished()
            }

            /** What makes the words appear as they are spoken rather than at the end. */
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let(listener::onTranscript)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                // A language TAG, not a Locale: putExtra would bind the object
                // to the Serializable overload, and the service reads this key
                // with getStringExtra - so it would compile and be dropped.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            },
        )
    }

    override fun stop() {
        recognizer.stopListening()
    }

    override fun destroy() {
        recognizer.destroy()
    }

    private fun best(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
