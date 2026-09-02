package com.leeotts.cicero.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.leeotts.cicero.CiceroApp
import com.leeotts.cicero.MainActivity
import com.leeotts.cicero.R
import com.leeotts.cicero.TAG
import com.leeotts.cicero.util.isGranted
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CHANNEL_ID = "wake_word"
private const val NOTIFICATION_ID = 42

/**
 * How long the Ask screen is given to claim the microphone after the wake word
 * fires. Past this the hand-off is assumed to have failed and listening
 * resumes, rather than leaving the wake word dead until the app is reopened.
 */
private const val HANDOFF_GRACE_MS = 3_000L

/**
 * Listens for the wake word while the glasses are connected.
 *
 * A foreground service because that is the only way to hold a microphone with
 * the app closed, which is the entire point of a wake word. It is started from
 * the foreground and then left running: Android 12+ refuses a background start
 * outright, and a microphone-type service started from the background gets no
 * microphone at all. So the service stays alive and gates capture internally -
 * same battery saving, without an illegal start.
 *
 * Capture runs only while the glasses are connected. That is the large half of
 * the battery saving; the VAD gate inside [WakeWordDetector] is the small half.
 */
class WakeWordService : Service() {

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    /**
     * Outlives any one capture.
     *
     * The gate closes on every hand-off - the Ask screen takes the microphone,
     * so capture is cancelled - and mapping three graphs again on the way back
     * would put that cost on the far side of every single detection. Built on
     * the capture thread rather than in onCreate, because loading them is far
     * too slow for the main thread.
     */
    private var detector: WakeWordDetector? = null

    private val voice get() = (application as CiceroApp).voice

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        // Nothing to do without the microphone, and asking is the UI's job -
        // a service cannot show a permission dialog.
        if (!isGranted(Manifest.permission.RECORD_AUDIO)) {
            Log.w(TAG, "WakeWordService: RECORD_AUDIO not granted, stopping")
            stopSelf()
            return
        }

        scope.launch {
            combine(
                Wearables.devices,
                voice.micHeld,
                voice.pendingListen,
            ) { devices, micHeld, pending ->
                shouldListenForWakeWord(devices.isNotEmpty(), micHeld, pending)
            }
                .distinctUntilChanged()
                .collect { listen ->
                    Log.i(TAG, "WakeWordService: capture ${if (listen) "on" else "off"}")
                    if (listen) startCapture() else stopCapture()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        detector?.close()
        detector = null
        super.onDestroy()
    }

    private fun startCapture() {
        if (captureJob?.isActive == true) return
        captureJob = scope.launch(Dispatchers.IO) { capture() }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
    }

    private suspend fun capture() {
        val detector = detector ?: runCatching { WakeWordDetector(WakeWordAssets(this).load()) }
            .onFailure {
                Log.e(TAG, "WakeWordService: could not load models", it)
                stopSelf()
            }
            .getOrNull()
            ?.also { this.detector = it }
            ?: return

        // Whatever was mid-phrase when the microphone last changed hands is of
        // no use now, and a stale buffer can score against fresh audio.
        detector.reset()

        if (!listenUntilDetected(detector)) return
        handOff()
        // handOff raises the request, which shuts the gate, which cancels this
        // job. Returning is tidier than racing that; the gate starts a fresh
        // pass once the microphone comes back.
    }

    /** True when the wake word fired, false when capture was cancelled or failed. */
    private suspend fun listenUntilDetected(detector: WakeWordDetector): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            WAKE_WORD_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = runCatching {
            AudioRecord(
                // VOICE_RECOGNITION leaves the phone's own AGC and noise
                // suppression out of the way; the models were trained on
                // ordinary speech, not on processed speech.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WAKE_WORD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, WAKE_WORD_CHUNK * 2) * 2,
            )
        }.getOrElse {
            Log.e(TAG, "WakeWordService: AudioRecord unavailable", it)
            return false
        }

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "WakeWordService: AudioRecord did not initialise")
                return false
            }
            record.startRecording()
            val buffer = ShortArray(WAKE_WORD_CHUNK)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // Negative is an error code, not a short read; another app
                    // taking the microphone lands here.
                    Log.w(TAG, "WakeWordService: read returned $read")
                    return false
                }
                if (detector.feed(buffer, read)) {
                    Log.i(TAG, "WakeWordService: wake word at ${detector.lastScore}")
                    return true
                }
            }
            return false
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    /**
     * Gives the microphone to the Ask screen.
     *
     * The request is raised first and on its own closes the gate, so capture
     * stops before the activity even exists - there is no window in which both
     * this service and the recognizer are reading the microphone. The recorder
     * is already released by the time this runs; a recognizer started while the
     * service still held the device hears nothing and reports no error.
     */
    private fun handOff() {
        voice.requestListening()
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.e(TAG, "WakeWordService: could not open Cicero", it) }

        // Launched on the service's own scope, not the capture job: the gate is
        // about to cancel that job, and the safety net has to outlive it. Without
        // this a hand-off nobody picks up - a dismissed activity, or speech
        // recognition unavailable - would leave the request set and the wake word
        // deaf until the app was reopened.
        scope.launch {
            val claimed = withTimeoutOrNull(HANDOFF_GRACE_MS) { voice.micHeld.first { it } } != null
            if (claimed) return@launch
            Log.w(TAG, "WakeWordService: nothing claimed the microphone, resuming")
            voice.abandonListenRequest()
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_word_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_word_notification))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
