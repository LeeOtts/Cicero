package com.leeotts.cicero.audio

import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformLatest

/**
 * How long after an answer ends before the wake word may listen again.
 *
 * onDone fires when the last sample leaves the phone, not when it leaves the
 * glasses: over A2DP there are a couple of hundred milliseconds still in the
 * air, and a room takes a moment more to go quiet. A recorder opened on the
 * exact edge of onDone hears Cicero finish its own sentence, which is how an
 * assistant wakes itself up.
 *
 * Must stay well under [HANDOFF_GRACE_MS]. A tail longer than the grace period
 * would let a wake word firing just as an answer ends have its hand-off
 * abandoned before the Ask screen could claim the microphone.
 */
internal const val SPEECH_TAIL_MS = 600L

/**
 * The microphone hand-off between the wake word and the Ask screen.
 *
 * There is one microphone, and two things in this app want it: [WakeWordService]
 * holds an AudioRecord open for as long as it is listening, and
 * [SpeechRecognizerHelper] needs that same microphone the moment the wake word
 * fires. Whoever asks second gets silence rather than an error, so the two are
 * arbitrated here rather than left to chance.
 *
 * [Speaker] is arbitrated here too, though it wants the speaker rather than the
 * microphone. It has to be: the wake word listening while Cicero talks is the
 * same collision seen from the other end.
 *
 * App-scoped for the same reason as the singletons in CiceroApp: this describes
 * one piece of hardware, and a second instance would describe a second one that
 * does not exist.
 */
class VoiceHandoff {

    private val _micHeld = MutableStateFlow(false)

    /** True while the Ask screen's recognizer has the microphone. */
    val micHeld: StateFlow<Boolean> = _micHeld.asStateFlow()

    private val _pendingListen = MutableStateFlow(false)

    /**
     * Set when the wake word has fired and the Ask screen should start
     * listening as soon as it exists.
     *
     * A flag rather than an event, because the service raises this *before*
     * launching the activity - an event delivered to nothing would be lost in
     * exactly the case that matters.
     */
    val pendingListen: StateFlow<Boolean> = _pendingListen.asStateFlow()

    private val _speaking = MutableStateFlow(false)

    /** True while Cicero is talking. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /**
     * True while Cicero is talking, and for [SPEECH_TAIL_MS] afterwards.
     *
     * Only the falling edge waits. The rising edge has to be instant, or the
     * recorder is still open while the first word plays - the same bug from the
     * other end. transformLatest is what makes back-to-back answers safe: a new
     * true cancels the pending false, so the gate never cracks open between two
     * things Cicero says. The StateFlow underneath already drops repeats, so
     * there is nothing here to distinguish them further.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val speakingWithTail: Flow<Boolean> = _speaking
        .transformLatest { speaking ->
            if (!speaking) delay(SPEECH_TAIL_MS)
            emit(speaking)
        }

    fun holdMicrophone(held: Boolean) {
        if (_micHeld.value == held) return
        Log.i(TAG, "VoiceHandoff: microphone ${if (held) "taken" else "released"}")
        _micHeld.value = held
    }

    /** Logged like every other change of hands, for the same reason. */
    fun holdSpeaker(speaking: Boolean) {
        if (_speaking.value == speaking) return
        Log.i(TAG, "VoiceHandoff: speaker ${if (speaking) "taken" else "released"}")
        _speaking.value = speaking
    }

    fun requestListening() {
        Log.i(TAG, "VoiceHandoff: listening requested")
        _pendingListen.value = true
    }

    /**
     * Takes the request if there is one, so it is acted on exactly once, and
     * claims the microphone in the same step.
     *
     * The claim comes first and is not optional. Clearing the request and
     * leaving the claim to whoever starts the recognizer opens a gap - both
     * flags false - and the service fills gaps by reopening its recorder. On a
     * phone that gap was around 80 ms, long enough to start an AudioRecord
     * underneath a recognizer that was still starting up and kill it: the
     * recognizer gave up after ~300 ms and reported ERROR_NO_MATCH, which reads
     * exactly like the user having said nothing.
     */
    fun takeListenRequest(): Boolean {
        if (!_pendingListen.value) return false
        Log.i(TAG, "VoiceHandoff: listen request taken")
        // Through holdMicrophone rather than the field, so the claim shows up
        // in the log like every other change of hands. Setting the field
        // directly left a trace where the microphone was released without ever
        // appearing to have been taken.
        holdMicrophone(true)
        _pendingListen.value = false
        return true
    }

    /**
     * Drops a request nobody claimed, without claiming the microphone.
     *
     * Separate from [takeListenRequest] because the service uses this to
     * recover from a hand-off that went nowhere - claiming on its behalf would
     * leave the microphone marked as held by no one.
     */
    fun abandonListenRequest() {
        if (!_pendingListen.value) return
        Log.w(TAG, "VoiceHandoff: listen request abandoned")
        _pendingListen.value = false
    }
}

/**
 * Whether the wake word may hold the microphone.
 *
 * An outstanding request counts as busy in its own right. Waiting instead for
 * the Ask screen to report the microphone taken leaves a gap between the wake
 * word firing and the screen existing, and the service spent that gap
 * recording - so a second phrase could be detected while the recognizer from
 * the first was still listening, with both reading the same microphone.
 *
 * Cicero talking is the third way the microphone is busy. The models were
 * trained on a person saying the wake word, and nothing whatsoever stops them
 * scoring against Cicero's own voice coming back through the room - so an
 * answer containing anything wake-word-shaped would set the whole exchange off
 * again, with the user having said nothing at all.
 *
 * Pure, so the rule can be tested without a service, a microphone or glasses.
 */
internal fun shouldListenForWakeWord(
    glassesConnected: Boolean,
    micHeld: Boolean,
    listenRequested: Boolean,
    speaking: Boolean,
): Boolean = glassesConnected && !micHeld && !listenRequested && !speaking
