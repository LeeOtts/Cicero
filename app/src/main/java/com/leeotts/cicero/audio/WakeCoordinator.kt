package com.leeotts.cicero.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Listens for the wake word and turns each detection into one assistant turn.
 *
 * Contains no Android types at all, which is what lets the whole state machine
 * be driven from a JVM test with fakes. That purity is structural rather than
 * assumed: the module sets unitTests.returnDefaultValues, so an accidental
 * Android call would quietly return 0 or null instead of failing the test.
 *
 * The loop is deliberately dumb about *why* it should be listening. Whether the
 * microphone may be open at all is decided by ArmingPolicy and arrives here as
 * [armed]; this class only honours it, promptly.
 */
class WakeCoordinator(
    private val sources: AudioSources,
    private val detectors: WakeDetectors,
    private val captures: UtteranceCaptures,
    private val turns: TurnSink,
    private val feedback: WakeFeedback,
    private val dispatcher: CoroutineDispatcher,
    /** Reported so the service notification can say what is going on. */
    private val onError: (String) -> Unit = {},
) {

    enum class State {
        /** The microphone is shut, by policy. Costs nothing. */
        DISARMED,

        /** Listening for the wake word. */
        LISTENING,

        /** The wake word landed; capturing the question. */
        CAPTURING,

        /** Waiting on the model. */
        THINKING,

        /** Reading the answer out. */
        SPEAKING,
    }

    private val _state = MutableStateFlow(State.DISARMED)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Runs until cancelled.
     *
     * One input, deliberately: each emission is the whole answer to "what
     * should I be doing right now", and null means the microphone stays shut.
     * The caller folds the arming policy and the user's settings together into
     * this, which keeps every Android-shaped input out of here.
     *
     * collectLatest is the whole mechanism. A new value cancels the previous
     * session, so disarming closes the microphone promptly rather than at the
     * end of whatever it was doing. That prompt close is the battery contract,
     * and it is asserted in the tests rather than hoped for.
     */
    suspend fun run(sessions: Flow<WakeCredentials?>) {
        sessions.collectLatest { credentials ->
            if (credentials == null) {
                _state.value = State.DISARMED
                return@collectLatest
            }
            try {
                listen(credentials)
            } finally {
                // Reached on cancellation too, which is the path that matters:
                // it is how disarming releases the microphone.
                _state.value = State.DISARMED
            }
        }
    }

    private suspend fun listen(credentials: WakeCredentials) {
        val detector = detectors.create(credentials.accessKey, credentials.sensitivity)
            .getOrElse {
                onError(it.message ?: "The wake word engine could not start.")
                return
            }
        val source = sources.create(credentials.mic)
        val capture = captures.create(credentials.mic, source)
        val buffer = FrameBuffer(detector.frameLength)

        try {
            if (!source.open()) {
                onError("The ${source.description} is unavailable.")
                return
            }
            _state.value = State.LISTENING

            // Read far more than one frame at a time. At 16 kHz this is ~100 ms
            // per read, so about 10 wakeups a second instead of 31 - fewer
            // interrupts and deeper idle in between, which on an always-on
            // microphone is worth having. FrameBuffer puts it back into the
            // exact frames the detector insists on.
            val block = ShortArray(READ_SAMPLES)

            while (true) {
                coroutineContext.ensureActive()
                val read = withContext(dispatcher) { source.read(block) }
                if (read < 0) return
                if (read == 0) continue

                var detected = false
                for (frame in buffer.offer(block, read)) {
                    if (detector.process(frame) >= 0) {
                        detected = true
                        break
                    }
                }
                if (!detected) continue

                buffer.reset()
                // Android's recognizer opens its own microphone and the
                // platform allows only one, so this one has to go first. That
                // costs a ~300 ms deaf window, which is exactly what the earcon
                // is for. The raw-PCM strategy reads this same stream instead
                // and must not have it closed underneath it.
                val handOver = capture.needsExclusiveMic
                if (handOver) source.close()
                runTurn(capture)
                if (handOver && !source.open()) {
                    onError("The ${source.description} did not reopen.")
                    return
                }
                _state.value = State.LISTENING
            }
        } finally {
            source.close()
            detector.close()
        }
    }

    private suspend fun runTurn(capture: UtteranceCapture) {
        // Barge-in, before the earcon so the two are never heard together: a
        // wake word during an answer means the user wants to say something
        // else, not to hear the rest of this.
        feedback.stopSpeaking()
        feedback.earcon()
        feedback.haptic()

        _state.value = State.CAPTURING
        val question = try {
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { capture.capture() }
        } finally {
            capture.cancel()
        }

        // Nothing said: a false trigger, or the user thought better of it.
        // Silently back to listening - announcing it would make every false
        // positive twice as annoying as it needs to be.
        if (question.isNullOrBlank()) return

        _state.value = State.THINKING
        try {
            turns.run(question)
        } catch (e: Exception) {
            // TurnRunner already speaks its own failures, so there is nothing
            // to say here. What matters is that a thrown turn does not kill the
            // loop and leave the service alive but deaf.
            coroutineContext.ensureActive()
            onError(e.message ?: "That turn failed.")
        }
        _state.value = State.SPEAKING
    }

    private companion object {
        /** ~100 ms at 16 kHz. See the comment at the read site. */
        const val READ_SAMPLES = 1_600

        /**
         * Caps the whole capture, so a recognizer that never calls back cannot
         * wedge the service with the microphone held open.
         */
        const val CAPTURE_TIMEOUT_MS = 10_000L
    }
}

/** What the engine needs to start, and which microphone to start it on. */
data class WakeCredentials(
    val accessKey: String,
    val sensitivity: Float,
    val mic: MicSource,
)
