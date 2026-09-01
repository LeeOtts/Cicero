package com.leeotts.cicero.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * The seams the wake pipeline is built from.
 *
 * Every one of these exists so WakeCoordinator can be driven from a JVM test
 * with no microphone, no JNI and no network - the same reason SpeechEngine and
 * SpeechEngines sit in front of Android's SpeechRecognizer, and Secrets sits in
 * front of the Keystore.
 */

/** Which microphone the wake word listens on. */
enum class MicSource {
    /** The phone's own microphone. Coexists with "Hey Meta". */
    PHONE,

    /** The glasses, over Bluetooth HFP. Takes the microphone from Meta AI. */
    GLASSES,
}

/**
 * A continuous stream of 16 kHz mono PCM16 frames.
 *
 * An interface because the pipeline must not care whether the samples came from
 * the phone or from the glasses over HFP. Porcupine is uncompromising about the
 * format, so the contract is stated in its terms and each implementation is
 * responsible for meeting it - including any resampling.
 */
interface AudioSource {
    val sampleRate: Int get() = 16_000

    /** Opens the microphone. False when the route is unavailable. */
    fun open(): Boolean

    /**
     * Blocks until samples are available. Returns the number written into
     * [into], 0 on a recoverable hiccup, or -1 once the source is closed.
     */
    fun read(into: ShortArray): Int

    /** Idempotent; called from a different thread than [read]. */
    fun close()

    /** Human-readable, for the notification and the log. */
    val description: String
}

/** Where sources come from, so a test can hand over a fake. */
interface AudioSources {
    fun create(source: MicSource): AudioSource
}

/**
 * One wake-word engine, fed one frame at a time.
 *
 * Deliberately a hair's breadth from Porcupine's own API - [process] takes
 * exactly [frameLength] samples and returns which keyword fired, or -1. Keeping
 * the seam this thin means the fake in the tests is honest about what the real
 * engine will and will not tolerate.
 */
interface WakeDetector {
    val frameLength: Int
    val sampleRate: Int
    fun process(frame: ShortArray): Int
    fun close()
}

/** Where detectors come from. Failure is a value, never an exception. */
interface WakeDetectors {
    /**
     * A bad access key and a missing or version-mismatched keyword file are the
     * two overwhelmingly likely failures, and both must be reportable text in
     * Settings rather than a crash inside a foreground service.
     */
    fun create(accessKey: String, sensitivity: Float): Result<WakeDetector>
}

/** Captures the question the user asks after the wake word. */
interface UtteranceCapture {
    /**
     * Whether the detector must hand the microphone over first.
     *
     * The two strategies genuinely differ. Android's recognizer opens its own
     * microphone and the platform will not tolerate two at once, so the
     * detector has to close first and reopen after - a short deaf window the
     * earcon covers for. Reading raw PCM has no such problem: it is the same
     * open stream, simply read past the wake word, so closing it would throw
     * away the beginning of the question.
     */
    val needsExclusiveMic: Boolean

    /** The spoken question, or null when nothing was said. */
    suspend fun capture(): String?
    fun cancel()
}

/**
 * Where captures come from.
 *
 * Takes the live [AudioSource] because a strategy may want to keep reading it
 * rather than open one of its own.
 */
interface UtteranceCaptures {
    fun create(mic: MicSource, source: AudioSource): UtteranceCapture
}

/** Runs a captured question to a spoken answer. Backed by TurnRunner. */
interface TurnSink {
    suspend fun run(question: String)
}

/** The chime, the buzz, and the ability to cut an answer short. */
interface WakeFeedback {
    /** Tells the user the wake word landed and it is their turn to speak. */
    fun earcon()
    fun haptic()

    /** Barge-in: stops an answer that is still being read out. */
    fun stopSpeaking()

    val speaking: StateFlow<Boolean>
}
