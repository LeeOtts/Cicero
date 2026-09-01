package com.leeotts.cicero.audio

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
        _micHeld.value = held
    }

    fun requestListening() {
        _pendingListen.value = true
    }

    /** Takes the request if there is one, so it is acted on exactly once. */
    fun takeListenRequest(): Boolean {
        if (!_pendingListen.value) return false
        _pendingListen.value = false
        return true
    }
}
