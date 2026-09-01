package com.leeotts.cicero.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Feeds [shouldArm] with what the phone is currently doing.
 *
 * Every input is event-driven. Nothing here polls, and that is deliberate
 * rather than tidy: a periodic check would itself be a wakeup, on a component
 * whose entire purpose is to let the phone sleep.
 *
 * The glasses signal is passed in rather than read here, so this stays free of
 * the wearables SDK and the service can decide what "connected" means.
 */
class ArmingMonitor(
    context: Context,
    private val glassesConnected: Flow<Boolean>,
    private val userEnabled: Flow<Boolean>,
    private val rules: Flow<ArmingRules>,
) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Whether the microphone may be open, and why not when it may not.
     *
     * distinctUntilChanged matters: battery broadcasts arrive constantly and
     * every duplicate would tear down and rebuild the audio pipeline.
     */
    val state: Flow<ArmingState> = combine(
        glassesConnected,
        userEnabled,
        rules,
        power(),
        audioMode(),
    ) { glasses, enabled, currentRules, power, audio ->
        val inputs = ArmingInputs(
            userEnabled = enabled,
            glassesConnected = glasses,
            batteryPercent = power.percent,
            charging = power.charging,
            inCall = audio.inCall,
            micBusyElsewhere = audio.micBusy,
        )
        ArmingState(
            armed = shouldArm(inputs, currentRules),
            reason = disarmedReason(inputs, currentRules),
        )
    }.distinctUntilChanged()

    private data class Power(val percent: Int, val charging: Boolean)
    private data class AudioState(val inCall: Boolean, val micBusy: Boolean)

    /**
     * Battery level and charge state, from the sticky ACTION_BATTERY_CHANGED.
     *
     * Sticky, so registering delivers the current value immediately and there
     * is no window where the level is unknown and the policy has to guess.
     */
    private fun power(): Flow<Power> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(read(intent))
            }
        }
        val sticky = appContext.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        trySend(read(sticky))
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    private fun read(intent: Intent?): Power {
        // A phone that will not say defaults to "plenty and charging", so a
        // missing reading can never be the thing that silently stops the wake
        // word working.
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            status == -1
        return Power(percent, charging)
    }

    /**
     * Whether a call is up or another app holds the microphone.
     *
     * Both are contention rather than battery: a contended microphone returns
     * silence anyway, so yielding costs nothing and is the polite thing to do.
     */
    private fun audioMode(): Flow<AudioState> = callbackFlow {
        fun emit() {
            trySend(
                AudioState(
                    inCall = audioManager.mode != AudioManager.MODE_NORMAL,
                    micBusy = audioManager.activeRecordingConfigurations.isNotEmpty(),
                ),
            )
        }

        val modeListener = AudioManager.OnModeChangedListener { emit() }
        val recordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(
                configs: MutableList<android.media.AudioRecordingConfiguration>?,
            ) = emit()
        }

        audioManager.addOnModeChangedListener(appContext.mainExecutor, modeListener)
        audioManager.registerAudioRecordingCallback(recordingCallback, null)
        emit()

        awaitClose {
            runCatching { audioManager.removeOnModeChangedListener(modeListener) }
            runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        }
    }.distinctUntilChanged()
}

/** Whether to listen, and what to tell the user when the answer is no. */
data class ArmingState(val armed: Boolean, val reason: DisarmedReason?)
