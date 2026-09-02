package com.leeotts.cicero.audio

import android.media.ToneGenerator
import android.util.Log
import com.leeotts.cicero.TAG

/**
 * The short tones that stand in for the screen.
 *
 * The phone is in a pocket and the screen is behind a pair of sunglasses.
 * Without these there is no way to tell "the microphone is open" from "the wake
 * word did not fire", and from outside the two feel identical - you say your
 * question either way, and only one of them was heard.
 *
 * On the same stream as the answers, deliberately. Cicero is mutable on its own
 * slider, and a tone that survived that mute would promise a reply the user is
 * never going to hear.
 *
 * No audio focus, unlike [Speaker]. One generator is kept alive for as long as
 * the Ask screen is, which keeps the output stream warm, and ducking music for
 * a quarter of a second either side of a beep is worse than a beep that does
 * not duck.
 */
class Cues {

    private var generator: ToneGenerator? = null
    private var built = false

    /** The microphone just opened. Say something. */
    fun listening() = play(ToneGenerator.TONE_PROP_BEEP)

    /** That is the end of the question; Cicero has it from here. */
    fun heard() = play(ToneGenerator.TONE_PROP_BEEP2)

    /** Releases the native AudioTrack. The Ask screen calls this on dispose. */
    fun release() {
        runCatching { generator?.release() }
        generator = null
        built = false
    }

    private fun play(tone: Int) {
        val generator = generator() ?: return
        runCatching { generator.startTone(tone, DURATION_MS) }
            .onFailure { Log.w(TAG, "tone failed", it) }
    }

    /**
     * Built on first use rather than in the constructor, because it opens a
     * native AudioTrack and the Ask screen is composed long before anyone asks
     * it anything. A device that will not give one up gets no tones rather than
     * a crash; they are a courtesy, not the feature.
     */
    private fun generator(): ToneGenerator? {
        if (!built) {
            built = true
            generator = runCatching { ToneGenerator(speechStream(), VOLUME) }
                .onFailure { Log.w(TAG, "no tone generator", it) }
                .getOrNull()
        }
        return generator
    }

    private companion object {
        /**
         * 250 ms, not the 100 these are usually given. An output stream that
         * has been idle takes around 200 ms to open over A2DP, and a shorter
         * beep is swallowed whole by that - which reads, from the glasses, as
         * the wake word having done nothing at all.
         */
        const val DURATION_MS = 250

        /** Out of 100, relative to the stream. Enough to carry, not enough to startle. */
        const val VOLUME = 80
    }
}
