package com.leeotts.cicero.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.leeotts.cicero.CiceroApp
import com.leeotts.cicero.MainActivity
import com.leeotts.cicero.R
import com.leeotts.cicero.TAG
import com.leeotts.cicero.ai.BrainSettings
import com.leeotts.cicero.ai.TurnRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.concurrent.Executors

/**
 * Keeps "Hey Cicero" listening with the app closed and the screen off.
 *
 * A foreground service because Android allows background microphone access no
 * other way. The service itself costs almost nothing - it is the open
 * microphone that drains the battery, which is why the arming policy closes
 * that microphone rather than stopping the service.
 *
 * That distinction is load-bearing. From API 31 a foreground service cannot be
 * started from the background, so a service that stopped itself when the
 * glasses disconnected could not start itself again when they came back. It
 * stays alive and disarmed instead, waiting, for free. The same rule is why
 * there is no boot receiver, and why the only legal starts are the Settings
 * toggle and MainActivity.
 */
class WakeService : Service() {

    /**
     * One thread, not a pool. AudioRecord.read blocks, and it should block on a
     * thread of its own rather than tie up a Dispatchers.IO worker for hours.
     */
    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cicero-wake").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + audioDispatcher)

    private lateinit var settings: BrainSettings
    private lateinit var speaker: Speaker
    private lateinit var feedback: AndroidWakeFeedback
    private lateinit var coordinator: WakeCoordinator

    private var lastError: String? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as CiceroApp

        settings = BrainSettings(this)
        speaker = Speaker(this)
        feedback = AndroidWakeFeedback(this, speaker)
        installBundledKeyword(this)

        // Its own TurnRunner with a short history window: a follow-up minutes
        // later should still know what the last answer was about, but a
        // question an hour on must not silently inherit whatever the glasses
        // were pointed at then.
        val runner = TurnRunner(app, speaker, historyTtlMs = HISTORY_TTL_MS)

        coordinator = WakeCoordinator(
            sources = AndroidAudioSources(this),
            detectors = PorcupineDetectors(this),
            capture = RecognizerCapture(this),
            turns = object : TurnSink {
                override suspend fun run(question: String) {
                    runner.run(question)
                }
            },
            feedback = feedback,
            dispatcher = audioDispatcher,
            onError = ::report,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Before anything that can suspend: from API 34 a foreground service
        // that has not called this within a few seconds is killed outright.
        startForeground(
            NOTIFICATION_ID,
            notification(getString(R.string.wake_status_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        if (!started) {
            started = true
            start()
        }

        // Never sticky. A restart by the system after process death would be a
        // background start, which the platform refuses - it would crash rather
        // than resume.
        return START_NOT_STICKY
    }

    private fun start() {
        val arming = ArmingMonitor(
            context = this,
            glassesConnected = glassesConnected(),
            userEnabled = settings.config.map { it.wakeEnabled }.distinctUntilChanged(),
            rules = settings.config.map { it.armingRules }.distinctUntilChanged(),
        )

        scope.launch {
            arming.state.collect { updateNotification(it) }
        }
        scope.launch {
            coordinator.state.collect { updateNotificationForState() }
        }
        scope.launch {
            coordinator.run(sessions(arming.state))
        }
    }

    /**
     * Folds policy and settings into the single question the coordinator asks:
     * what should I be doing right now, with null meaning stay shut.
     */
    private fun sessions(arming: Flow<ArmingState>): Flow<WakeCredentials?> =
        combine(arming, settings.config) { state, config ->
            if (!state.armed) {
                null
            } else {
                WakeCredentials(
                    accessKey = config.wakeAccessKey,
                    sensitivity = config.wakeSensitivity,
                    mic = config.wakeMic,
                )
            }
        }.distinctUntilChanged()

    /**
     * Whether the glasses are there to be asked about.
     *
     * Read from the wearables SDK's own device set, which is already maintained
     * and costs nothing to observe - no polling, no extra receiver, no wakeups.
     */
    private fun glassesConnected(): Flow<Boolean> =
        (application as CiceroApp).glassesConnected.distinctUntilChanged()

    private fun report(message: String) {
        Log.w(TAG, "wake word: $message")
        lastError = message
        updateNotificationForState()
    }

    private var armingState = ArmingState(armed = false, reason = DisarmedReason.OFF)

    private fun updateNotification(state: ArmingState) {
        armingState = state
        if (state.armed) lastError = null
        updateNotificationForState()
    }

    private fun updateNotificationForState() {
        val text = lastError ?: when (coordinator.state.value) {
            WakeCoordinator.State.CAPTURING -> getString(R.string.wake_status_listening_question)
            WakeCoordinator.State.THINKING -> getString(R.string.wake_status_thinking)
            WakeCoordinator.State.SPEAKING -> getString(R.string.wake_status_answering)
            WakeCoordinator.State.LISTENING -> getString(R.string.wake_status_armed)
            WakeCoordinator.State.DISARMED -> getString(pausedReason())
        }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification(text))
        }
    }

    /**
     * Why it is not listening, in words.
     *
     * A user who cannot tell whether the app is awake decides it is broken and
     * turns it off, so the notification always says which it is and why.
     */
    private fun pausedReason() = when (armingState.reason) {
        DisarmedReason.NO_GLASSES -> R.string.wake_status_paused_glasses
        DisarmedReason.LOW_BATTERY -> R.string.wake_status_paused_battery
        DisarmedReason.IN_CALL -> R.string.wake_status_paused_call
        DisarmedReason.MIC_BUSY -> R.string.wake_status_paused_mic
        DisarmedReason.OFF, null -> R.string.wake_status_paused
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_channel_name),
                // Low: an ongoing microphone notice is required, but it should
                // never make a sound or push itself in front of anything.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.wake_action_stop), stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        feedback.release()
        speaker.shutdown()
        audioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "cicero.wake"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.leeotts.cicero.action.STOP_WAKE"

        /** How long a thread stays open for a spoken follow-up. */
        private const val HISTORY_TTL_MS = 5 * 60 * 1000L

        /**
         * Starts listening.
         *
         * Must be called from a visible Activity: API 31 forbids starting a
         * foreground service from the background, and API 34 rejects a
         * microphone-typed one outright unless the app is in the foreground.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, WakeService::class.java))
            }.onFailure { Log.e(TAG, "could not start the wake service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }

        /** Whether the settings say it should be running. */
        suspend fun shouldRun(context: Context) =
            BrainSettings(context).config.first().wakeEnabled
    }
}
