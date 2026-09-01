package com.leeotts.cicero.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.StateFlow

/**
 * The chime and the buzz that tell the user the wake word landed.
 *
 * Not decoration. Closing the detector's microphone so the recognizer can have
 * it opens a short deaf window, and without a cue the user starts talking into
 * it and loses the first word of their question. The earcon is what makes that
 * gap survivable.
 *
 * Uses a short tone rather than a sound file so there is nothing to ship, and
 * NOTIFICATION rather than the media stream so it does not duck or interrupt
 * whatever is playing through the glasses.
 */
class AndroidWakeFeedback(
    context: Context,
    private val speaker: Speaker,
) : WakeFeedback {

    private val appContext = context.applicationContext

    private val tones = runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
    }.getOrNull()

    private val vibrator: Vibrator? = runCatching {
        val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
            as? VibratorManager
        manager?.defaultVibrator
    }.getOrNull()

    override val speaking: StateFlow<Boolean> get() = speaker.speaking

    override fun earcon() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
    }

    override fun haptic() {
        // The phone is usually in a pocket, where the buzz lands better than
        // the chime. Silently absent on a device without a vibrator.
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createOneShot(HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    override fun stopSpeaking() = speaker.stop()

    fun release() {
        tones?.release()
    }

    private companion object {
        const val TONE_VOLUME = 70
        const val TONE_MS = 120
        const val HAPTIC_MS = 40L
    }
}

