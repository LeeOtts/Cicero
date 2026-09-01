package com.leeotts.cicero.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Captures the question after the wake word, using the phone's own recognizer.
 *
 * Wraps SpeechRecognizerHelper without changing it. That class is shared with
 * the Ask screen's microphone button, where it already handles the awkward
 * parts - partial results, superseded captures, and the platform's refusal to
 * run two recognizers at once - and perturbing it to suit a second caller would
 * put the working path at risk to serve the new one.
 *
 * The bridge is only two things: it is main-thread-only, and it is
 * callback-shaped where the coordinator wants a suspending call.
 */
class RecognizerCapture(context: Context) : UtteranceCapture {

    private val helper = SpeechRecognizerHelper(context.applicationContext)

    /** It opens its own microphone, so the detector's has to close first. */
    override val needsExclusiveMic = true

    /** False on a device with no recognition service - some custom ROMs. */
    val available: Boolean get() = helper.available

    override suspend fun capture(): String? = withContext(Dispatchers.Main) {
        if (!helper.available) return@withContext null

        var latest: String? = null
        helper.start { latest = it }

        // start() flips listening to true synchronously on this thread, so
        // waiting for it to go false cannot miss the transition. It goes false
        // whether the capture ended in a result or an error, and the last
        // partial is kept either way: words the user actually said beat
        // nothing, and a late error usually just means the endpointer gave up.
        try {
            helper.listening.first { !it }
        } finally {
            // Also the cancellation path, which is how the coordinator's
            // timeout stops a recognizer that never called back.
            helper.destroy()
        }
        latest?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun cancel() = helper.destroy()
}
