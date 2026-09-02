package com.leeotts.cicero.audio

import android.util.Log
import com.leeotts.cicero.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The microphone hand-off between the wake word and the Ask screen.
 *
 * There is one microphone, and two things in this app want it: [WakeWordService]
 * holds an AudioRecord open for as long as it is listening, and
 * [SpeechRecognizerHelper] needs that same microphone the moment the wake word
 * fires. Whoever asks second gets silence rather than an error, so the two are
 * arbitrated here rather than left to chance.
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

    fun holdMicrophone(held: Boolean) {
        if (_micHeld.value == held) return
        Log.i(TAG, "VoiceHandoff: microphone ${if (held) "taken" else "released"}")
        _micHeld.value = held
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
 * Pure, so the rule can be tested without a service, a microphone or glasses.
 */
internal fun shouldListenForWakeWord(
    glassesConnected: Boolean,
    micHeld: Boolean,
    listenRequested: Boolean,
): Boolean = glassesConnected && !micHeld && !listenRequested
