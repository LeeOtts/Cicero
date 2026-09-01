package com.leeotts.cicero.audio

import android.content.Context
import android.util.Log
import com.leeotts.cicero.TAG
import com.leeotts.cicero.ai.BrainFactory
import com.leeotts.cicero.ai.BrainSettings
import com.leeotts.cicero.ai.Transcriber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Picks how to capture the question, which follows from where it is heard.
 *
 * On the phone, Android's own recognizer is right there and already solved:
 * on-device, free, and no audio leaves the handset. Over Bluetooth HFP there is
 * no recognizer in the path at all, so the raw samples have to be endpointed
 * here and sent to whatever the speech settings point at.
 */
class AndroidUtteranceCaptures(
    context: Context,
    private val dispatcher: CoroutineDispatcher,
) : UtteranceCaptures {

    private val appContext = context.applicationContext
    private val settings = BrainSettings(appContext)

    override fun create(mic: MicSource, source: AudioSource): UtteranceCapture = when (mic) {
        MicSource.PHONE -> phoneCapture(source)
        MicSource.GLASSES -> PcmCapture(source, transcriber(), dispatcher)
    }

    /**
     * Falls back to the remote transcriber on a device with no recognition
     * service. Some AOSP and custom ROMs ship without one, and the wake word
     * failing silently there would be indistinguishable from a bad keyword.
     */
    private fun phoneCapture(source: AudioSource): UtteranceCapture {
        val recognizer = RecognizerCapture(appContext)
        if (recognizer.available) return recognizer
        Log.w(TAG, "no on-device recognizer; transcribing the phone mic remotely")
        return PcmCapture(source, transcriber(), dispatcher)
    }

    /**
     * The speech backend the current settings imply - NoOpTranscriber for a
     * model that takes audio directly, Whisper otherwise.
     *
     * Read blocking because create() is called once per listening session, off
     * the main thread, on the audio dispatcher that is about to block on
     * AudioRecord anyway.
     */
    private fun transcriber(): Transcriber = runBlocking {
        val config = settings.config.first()
        BrainFactory.transcriber(config, BrainFactory.brain(config))
    }
}
