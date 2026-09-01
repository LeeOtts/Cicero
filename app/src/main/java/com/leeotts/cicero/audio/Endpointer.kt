package com.leeotts.cicero.audio

/**
 * Decides when the user has finished speaking, from the audio alone.
 *
 * Only the glasses path needs this. On the phone, Android's SpeechRecognizer
 * does its own endpointing and hands back a final result; over Bluetooth HFP
 * there is no recognizer in the loop, just raw PCM, and something has to decide
 * where the question stops before it can be sent for transcription.
 *
 * Deliberately crude - a root-mean-square level against a fixed threshold, the
 * same measure ScoProbe uses to report peak amplitude. A real VAD would be
 * better in a noisy room and is not worth a model here: the phrase has already
 * been gated by a wake word, so this is only deciding where a known-present
 * utterance ends.
 *
 * Pure and driven entirely by [offer], so the whole state machine is testable
 * with synthetic frames and no clock.
 */
class Endpointer(
    private val sampleRate: Int = 16_000,
    /** RMS above which a frame counts as speech, in PCM16 units. */
    private val threshold: Int = 500,
    /** Silence that ends an utterance, once speech has started. */
    private val trailingSilenceMs: Int = 900,
    /** Give up if the user never actually says anything. */
    private val leadingSilenceLimitMs: Int = 3_000,
    /** Hard cap, so a stuck-open microphone cannot record forever. */
    private val maxUtteranceMs: Int = 10_000,
) {

    enum class State {
        /** Waiting for the user to start. */
        WAITING,

        /** Speech in progress. */
        SPEAKING,

        /** Finished with something worth transcribing. */
        DONE,

        /** Nothing was said; there is no audio to send. */
        ABANDONED,
    }

    var state: State = State.WAITING
        private set

    private var elapsedMs = 0
    private var silenceMs = 0

    /** True once [state] can no longer change. */
    val finished: Boolean get() = state == State.DONE || state == State.ABANDONED

    /**
     * Feeds one frame and returns the state after it.
     *
     * Time is taken from the sample count rather than the wall clock, so a test
     * runs instantly and a slow reader cannot make an utterance look longer
     * than it was.
     */
    fun offer(frame: ShortArray): State {
        if (finished) return state

        val frameMs = frame.size * 1000 / sampleRate
        elapsedMs += frameMs
        val speech = rms(frame) >= threshold

        when (state) {
            State.WAITING -> {
                if (speech) {
                    state = State.SPEAKING
                    silenceMs = 0
                } else if (elapsedMs >= leadingSilenceLimitMs) {
                    // The wake word fired and then nothing followed - a false
                    // trigger, or the user thought better of it. Either way
                    // there is nothing to transcribe and no turn to run.
                    state = State.ABANDONED
                }
            }

            State.SPEAKING -> {
                silenceMs = if (speech) 0 else silenceMs + frameMs
                if (silenceMs >= trailingSilenceMs) state = State.DONE
            }

            else -> Unit
        }

        // Checked last, so a frame that completes an utterance is honoured
        // before the cap can discard it.
        if (!finished && elapsedMs >= maxUtteranceMs) {
            state = if (state == State.SPEAKING) State.DONE else State.ABANDONED
        }
        return state
    }

    private fun rms(frame: ShortArray): Int {
        if (frame.isEmpty()) return 0
        var sum = 0.0
        for (sample in frame) {
            val v = sample.toDouble()
            sum += v * v
        }
        return Math.sqrt(sum / frame.size).toInt()
    }
}
