package com.leeotts.cicero.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Mirrors of the TextToSpeech result codes.
 *
 * Restated here so the rules that read them can run on the JVM, the same way
 * [WakeWordDetector] restates its sample rate. The values are the platform's;
 * they are not ours to choose.
 */
internal const val SYNTH_SUCCESS = 0
internal const val SYNTH_ERROR = -1
internal const val LANGUAGE_MISSING_DATA = -1
internal const val LANGUAGE_NOT_SUPPORTED = -2

/**
 * The slice of [TextToSpeech] the speaker drives.
 *
 * It exists so the half that was silently wrong can be tested with no device
 * and no engine. That half is not the callbacks: it is the RETURN CODES.
 * TextToSpeech reports "I will not say that" by handing back -1 from a method
 * that throws nothing, so the runCatching that used to wrap it caught nothing
 * and the app spoke into a void. Every method here returns exactly what the
 * platform returned; nothing is swallowed on this side of the line.
 */
internal interface Synthesizer {
    fun start(listener: Listener)

    /** LANG_COUNTRY_VAR_AVAILABLE(2)..LANG_AVAILABLE(0), or a negative refusal. */
    fun setLanguage(locale: Locale): Int

    /** [SYNTH_SUCCESS], or [SYNTH_ERROR] when the engine will not say it. */
    fun speak(text: String, utteranceId: String): Int
    fun stop()
    fun shutdown()

    interface Listener {
        /** [SYNTH_SUCCESS], or a status the caller must surface rather than log. */
        fun onReady(status: Int)
        fun onStart(utteranceId: String)

        /** Terminal, whether the utterance finished or was flushed by the next one. */
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int)
    }
}

/** Where synthesizers come from, so a test can hand over a fake one. */
internal interface Synthesizers {
    fun create(): Synthesizer
}

/**
 * Audio focus, behind a seam for the same reason as everything else in here:
 * when focus is taken and when it is given back is a rule, and rules are worth
 * testing. AudioManager is not.
 */
internal interface AudioFocus {
    /** False when focus was refused - during a phone call, say. */
    fun request(): Boolean
    fun abandon()
}

/**
 * The volume of the stream an answer is actually metered on.
 *
 * Not the media volume, and that distinction is the whole reason this exists.
 * USAGE_ASSISTANT plays on STREAM_ASSISTANT, which this phone does not alias to
 * music: it has its own slider, buried in the volume panel, that the rocker
 * never moves. Muted once it stays muted, and a healthy engine goes on
 * synthesising every answer into it with no error, no callback and no clue.
 */
internal interface AudioVolume {
    /** Null when the level cannot be read, which is not the same as zero. */
    fun level(): Int?
}

/**
 * Speaks the assistant's answers aloud.
 *
 * Routing is deliberately left alone. Output goes out as ordinary media, which
 * the system already sends to the glasses over A2DP whenever they are the
 * active output - so this needs no Bluetooth code, no DAT session, and no
 * Developer Mode.
 *
 * In particular it does NOT open an HFP/SCO route. Doing so would drop the
 * glasses to 8 kHz mono and take the microphone away from Meta AI; leaving A2DP
 * alone is what lets Cicero and "Hey Meta" coexist.
 *
 * Nothing here fails quietly any more, because that is how this class spent
 * months speaking every answer into a muted stream. The engine reports failure
 * with return codes rather than exceptions, so they are checked and published
 * on [problem] for the UI to show - the one kind of bad news an assistant
 * cannot deliver by talking.
 *
 * The synthesizer is built before the listener is attached, so an engine that
 * reports itself ready from inside its own constructor finds a whole object
 * rather than half of one.
 */
class Speaker internal constructor(
    synths: Synthesizers,
    private val focus: AudioFocus,
    private val volume: AudioVolume,
) {

    constructor(context: Context) : this(
        AndroidSynthesizers(context.applicationContext),
        AndroidAudioFocus(context.applicationContext),
        AndroidAudioVolume(context.applicationContext),
    )

    private val _speaking = MutableStateFlow(false)

    /** Drives the stop control, so it is only offered when there is something to stop. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /**
     * Why the last answer could not be heard, when it could not be.
     *
     * Read by the UI and shown on screen. It has to be seen rather than heard:
     * every failure this reports is a failure to make a sound.
     */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var ready = false

    /** Set when speak() is called before the engine finishes starting up. */
    private var pending: String? = null

    private var utterances = 0L

    /**
     * Which utterance the flags below describe.
     *
     * Volatile because the engine's callbacks arrive on a binder thread while
     * speak() runs on the main one, the same split the old progress listener
     * lived with. The guard it enables matters more than the race it does not
     * quite close: a flushed utterance reports itself done *after* its
     * replacement has started, and without this that report would mark Cicero
     * silent while it was still talking.
     */
    @Volatile
    private var speakingId: String? = null

    @Volatile
    private var focusHeld = false

    private val synth = synths.create()

    private val listener = object : Synthesizer.Listener {

        override fun onReady(status: Int) {
            ready = status == SYNTH_SUCCESS
            if (!ready) {
                Log.w(TAG, "text to speech unavailable (status $status)")
                pending = null
                _problem.value = "No speech engine started, so answers cannot be read aloud."
                return
            }
            settleLanguage()
            pending?.let { pending = null; say(it, cue = false) }
        }

        /** Log only. [speaking] went true in say(), several hundred ms ago. */
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = finish(utteranceId)

        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(TAG, "speech failed with code $errorCode")
            if (utteranceId == speakingId) {
                _problem.value = "The speech engine stopped part-way through (code $errorCode)."
            }
            finish(utteranceId)
        }
    }

    init {
        synth.start(listener)
    }

    /** Replaces anything still being spoken - a new answer supersedes the old one. */
    fun speak(text: String) = say(text, cue = false)

    /**
     * A short "still working on it" phrase, said while a tool runs.
     *
     * Same queue as an answer, so the answer cuts the cue off the moment it
     * arrives. It does not raise [problem]: a cue that goes missing is a
     * disappointment, not a fault worth putting on screen.
     */
    fun speakCue(text: String) = say(text, cue = true)

    private fun say(text: String, cue: Boolean) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        if (!ready) {
            // The engine takes a moment to bind, and the first answer often
            // arrives inside that window. A cue is not worth holding: by the
            // time the engine binds, the thing it announced has happened.
            if (!cue) pending = spoken
            return
        }

        if (volume.level() == 0) {
            _problem.value = MUTED
        } else if (!cue) {
            _problem.value = null
        }

        // Refusal is logged, not obeyed. An assistant that goes quiet for a
        // reason the user cannot see is the whole failure being fixed here.
        if (!focusHeld && focus.request()) focusHeld = true

        val id = "$UTTERANCE_PREFIX${++utterances}"
        speakingId = id
        // Before the words rather than on onStart, which lands a few hundred ms
        // late. The wake-word gate reads this flag, and a gate that shuts after
        // the first word is already playing is not a gate.
        _speaking.value = true

        val result = runCatching { synth.speak(spoken, id) }.getOrElse {
            Log.e(TAG, "speak failed", it)
            SYNTH_ERROR
        }
        if (result != SYNTH_SUCCESS) {
            Log.w(TAG, "speech engine refused the answer (result $result)")
            if (!cue) _problem.value = "The speech engine would not read that out."
            finish(id)
        }
    }

    /** Cuts off a long answer. */
    fun stop() {
        pending = null
        speakingId = null
        if (ready) runCatching { synth.stop() }
        // onStop does not fire for an utterance that never started.
        _speaking.value = false
        releaseFocus()
    }

    fun shutdown() {
        pending = null
        speakingId = null
        ready = false
        runCatching { synth.stop(); synth.shutdown() }
        _speaking.value = false
        // Explicitly, and not as a formality: a ViewModel cleared mid-answer
        // would otherwise leave whatever else is playing ducked for good.
        releaseFocus()
    }

    /**
     * Settles on a language the engine will actually speak, once, at startup.
     *
     * Once rather than before every answer, which is what this used to do while
     * throwing the result away. The engine is left on its own default when no
     * rung of the ladder is available - an assistant with the wrong accent is
     * enormously better than one with no voice.
     */
    private fun settleLanguage() {
        val preferred = Locale.getDefault()
        if (resolveSpokenLocale(preferred, synth::setLanguage) != null) return
        Log.w(TAG, "no voice for ${preferred.toLanguageTag()}")
        _problem.value = "No voice is installed for ${preferred.displayLanguage}, " +
            "so answers may be read in another accent or not at all."
    }

    private fun finish(utteranceId: String) {
        // A superseded utterance's late callback owns nothing, the same guard
        // the recognizer uses when a second capture replaces the first.
        if (utteranceId != speakingId) return
        speakingId = null
        _speaking.value = false
        releaseFocus()
    }

    private fun releaseFocus() {
        if (!focusHeld) return
        focusHeld = false
        focus.abandon()
    }

    private companion object {
        const val UTTERANCE_PREFIX = "cicero-answer-"

        const val MUTED = "You will not hear this: the assistant volume is at zero. " +
            "It is separate from media volume - open the volume panel, expand it, " +
            "and raise the assistant slider."
    }
}

/**
 * The first locale the engine will actually speak, or null if there is none.
 *
 * A ladder rather than one call, because LANG_MISSING_DATA is silence with no
 * exception and no log: an engine carrying plain English answers -1 to a phone
 * set to en-GB and 0 to "en", and the difference between those two was the
 * difference between an assistant and a text box.
 *
 * [trySet] both tests and applies, because that is the only thing TextToSpeech
 * offers - so the engine is left on the first rung that worked. Pure otherwise,
 * so the rule can be checked without an engine.
 */
internal fun resolveSpokenLocale(preferred: Locale, trySet: (Locale) -> Int): Locale? =
    listOf(preferred, Locale.forLanguageTag(preferred.language), Locale.US)
        .filter { it.language.isNotEmpty() }
        .distinctBy { it.toLanguageTag() }
        .firstOrNull { trySet(it) >= 0 }

/**
 * What Cicero's voice plays as, tones included.
 *
 * USAGE_ASSISTANT keeps Cicero mutable on its own, apart from music, which is
 * the point of choosing it. The cost is [AudioVolume]'s reason for existing.
 *
 * Lazy so that it stays on the Android side of the seam. As an eager top-level
 * val it was built when this file's class was loaded, which meant every unit
 * test in it - none of which go anywhere near an AudioAttributes - died in the
 * static initialiser against the stubbed builder.
 */
internal val SPEECH_ATTRIBUTES: AudioAttributes by lazy {
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

/**
 * The stream [SPEECH_ATTRIBUTES] is metered on.
 *
 * The attributes know their own stream; when they decline to say, fall back to
 * STREAM_ASSISTANT, which is what USAGE_ASSISTANT plays on and which
 * AudioManager does not expose as a constant.
 */
internal fun speechStream(): Int =
    SPEECH_ATTRIBUTES.volumeControlStream.takeIf { it >= 0 } ?: STREAM_ASSISTANT

private const val STREAM_ASSISTANT = 11

private class AndroidAudioVolume(context: Context) : AudioVolume {

    private val manager = context.getSystemService(AudioManager::class.java)

    // Abstaining beats crashing: this only exists to explain a silence, and an
    // explanation that throws is worse than the silence.
    override fun level(): Int? = runCatching { manager.getStreamVolume(speechStream()) }.getOrNull()
}

private class AndroidAudioFocus(context: Context) : AudioFocus {

    private val manager = context.getSystemService(AudioManager::class.java)

    /**
     * Built once and reused, because focus is abandoned by identity. A second
     * request object would abandon nothing and leave whatever is playing ducked
     * for good.
     *
     * TRANSIENT_MAY_DUCK because an answer is a sentence: the podcast should
     * dip and carry on, not stop and need restarting.
     */
    private val request =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            // Cicero does the ducking; it does not get ducked.
            .setWillPauseWhenDucked(false)
            .build()

    override fun request(): Boolean = runCatching {
        manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }.getOrDefault(false)

    override fun abandon() {
        runCatching { manager.abandonAudioFocusRequest(request) }
    }
}

private class AndroidSynthesizers(private val context: Context) : Synthesizers {
    override fun create(): Synthesizer = AndroidSynthesizer(context)
}

/**
 * Must be used from the main thread, same as [SpeechRecognizerHelper].
 *
 * The init status is stashed rather than forwarded on arrival, because
 * TextToSpeech is allowed to report a failure from inside its own constructor -
 * before [engine] has been assigned. Forwarding it straight through would hand
 * the Speaker a synthesizer with nothing behind it.
 */
private class AndroidSynthesizer(private val context: Context) : Synthesizer {

    private var engine: TextToSpeech? = null
    private var listener: Synthesizer.Listener? = null
    private var status: Int? = null
    private var delivered = false

    private val progress = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            listener?.onStart(utteranceId.orEmpty())
        }

        override fun onDone(utteranceId: String?) {
            listener?.onDone(utteranceId.orEmpty())
        }

        /**
         * An utterance flushed by the next one. Reported as done, because that
         * is what it is; the id tells the Speaker it is stale.
         */
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            listener?.onDone(utteranceId.orEmpty())
        }

        @Deprecated("Required by the base class", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            listener?.onError(utteranceId.orEmpty(), SYNTH_ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            listener?.onError(utteranceId.orEmpty(), errorCode)
        }
    }

    override fun start(listener: Synthesizer.Listener) {
        this.listener = listener
        val created = TextToSpeech(context) { status ->
            this.status = status
            if (engine != null) deliver(status)
        }
        created.setAudioAttributes(SPEECH_ATTRIBUTES)
        created.setOnUtteranceProgressListener(progress)
        engine = created
        status?.let { deliver(it) }
    }

    private fun deliver(status: Int) {
        if (delivered) return
        delivered = true
        listener?.onReady(status)
    }

    override fun setLanguage(locale: Locale): Int =
        engine?.setLanguage(locale) ?: LANGUAGE_NOT_SUPPORTED

    override fun speak(text: String, utteranceId: String): Int =
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ?: SYNTH_ERROR

    override fun stop() {
        engine?.stop()
    }

    override fun shutdown() {
        engine?.shutdown()
        engine = null
    }
}
