package com.leeotts.cicero.glasses

import android.graphics.Bitmap as AndroidBitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.leeotts.cicero.TAG
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the DAT session and camera stream.
 *
 * Sessions are deliberately short-lived: an open session suspends Meta AI's own
 * features, so we warm one only around a capture and tear it down afterwards.
 * Later phases call [warmUp] speculatively when the wake word fires, then
 * [capture] only if Gemini actually asks to look.
 *
 * Every DAT call is handled with `fold` rather than `getOrElse`/non-local
 * returns, because the SDK's result lambdas are not guaranteed to be inline.
 */
class GlassesController {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** One instance is shared process-wide, so two callers can arrive at once -
     *  the assistant's look tool and the Glasses screen's shutter button. */
    private val lock = Mutex()

    /**
     * Teardown that outlives its caller. ViewModel.onCleared() cannot suspend
     * and its own scope is already cancelled by then, so [releaseAsync] needs a
     * scope of its own. Main.immediate keeps the DAT calls on the thread every
     * other caller makes them from.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: DeviceSession? = null
    private var camera: Camera? = null

    /** Written by the openSession/attachCamera/startStream helpers, which cannot
     *  return a reason of their own, and read back by [startOnce]. */
    private var lastError: String? = null

    /** Opens a session and drives the stream to STREAMING so [capture] is instant. */
    suspend fun warmUp(): Boolean = lock.withLock { warmUpLocked() != null }

    /**
     * Returns the streaming camera, or null having set [state] to Failed.
     *
     * Handing the camera back rather than a Boolean is what keeps [capture]
     * from having to look one up that might have gone: Ready and a live camera
     * are the same fact, and only this function may decide it holds.
     */
    private suspend fun warmUpLocked(): Camera? {
        // releaseLocked() clears the state along with the fields, so a Ready
        // with no camera is a bug rather than a session worth reusing.
        camera?.let { if (_state.value is State.Ready) return it }

        repeat(START_ATTEMPTS) { attempt ->
            _state.value = State.Connecting
            val reason = startOnce() ?: run {
                _state.value = State.Ready
                return camera
            }
            if (attempt == START_ATTEMPTS - 1) return fail(reason)
            // Worth a second go, and cheap now that awaitStreaming() reports a
            // stream that stopped rather than sitting out the whole timeout.
            Log.w(TAG, "GlassesController: $reason; retrying from a fresh session")
            releaseLocked()
        }
        return null
    }

    /** Opens a session, attaches the camera and starts the stream once. */
    private suspend fun startOnce(): String? {
        lastError = null

        val newSession = openSession() ?: return lastError ?: "createSession gave no session"
        session = newSession
        newSession.start()

        if (!newSession.awaitStarted()) return "session never reached STARTED"

        val newCamera = attachCamera(newSession) ?: return lastError ?: "addCamera gave no camera"
        camera = newCamera

        if (!startStream(newCamera)) return lastError ?: "stream.start failed"

        return newCamera.awaitStreaming()
    }

    /** Captures a single still, warming the session up first if needed. */
    suspend fun capture(): AndroidBitmap? = lock.withLock {
        val camera = warmUpLocked() ?: return@withLock null

        var out: AndroidBitmap? = null
        camera.stream.capturePhoto().fold(
            onSuccess = { photo -> out = photo.toBitmap() },
            onFailure = { error, _ -> Log.e(TAG, "capturePhoto: ${error.description}") },
        )
        out
    }

    /**
     * PhotoData is a sealed interface: the SDK hands back either a decoded
     * Bitmap or raw HEIC bytes depending on the device and stream config.
     */
    private fun PhotoData.toBitmap(): AndroidBitmap? = when (this) {
        is PhotoData.Bitmap -> bitmap
        is PhotoData.HEIC -> {
            val bytes = ByteArray(data.remaining())
            data.duplicate().get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /**
     * Drops the session and camera.
     *
     * Suspends so it can take the same lock as [capture]. This instance is
     * shared process-wide and both callers release in a finally, so an unlocked
     * teardown could close the session the other one is mid-capture on.
     */
    suspend fun release() = lock.withLock { releaseLocked() }

    /**
     * Teardown for callers that cannot suspend - ViewModel.onCleared(), whose
     * own scope is already cancelled. Fire-and-forget by nature: nothing is
     * left to report to.
     */
    fun releaseAsync() {
        scope.launch { release() }
    }

    /**
     * The teardown itself, for code already holding the lock.
     *
     * The state goes back to Idle with the fields it describes. Leaving it at
     * Ready once these are null is what let the next capture skip the rebuild
     * and then find no stream - every shot after the first, since callers
     * release after each one. [fail] assigns its own state after calling this.
     */
    private fun releaseLocked() {
        runCatching { camera?.stop() }
        runCatching { session?.stop() }
        camera = null
        session = null
        _state.value = State.Idle
    }

    private fun openSession(): DeviceSession? {
        var out: DeviceSession? = null
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { out = it },
            onFailure = { error, _ -> lastError = "createSession: ${error.description}" },
        )
        return out
    }

    private fun attachCamera(session: DeviceSession): Camera? {
        var out: Camera? = null
        // MEDIUM over Bluetooth Classic usually looks better than HIGH: the
        // adaptive per-frame compression has less work to do.
        session.addCamera(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15),
        ).fold(
            onSuccess = { out = it },
            onFailure = { error, _ -> lastError = "addCamera: ${error.description}" },
        )
        return out
    }

    private fun startStream(camera: Camera): Boolean {
        var ok = false
        camera.stream.start().fold(
            onSuccess = { ok = true },
            onFailure = { error, _ -> lastError = "stream.start: ${error.description}" },
        )
        return ok
    }

    /**
     * These wait ONLY for the positive target state and rely on the timeout for
     * failure. Both flows are StateFlows, so a predicate that also accepts the
     * idle state (STOPPED / IDLE) matches the *current* value immediately and
     * returns before start() has had any effect.
     */
    private suspend fun DeviceSession.awaitStarted(): Boolean =
        withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            state
                .onEach { Log.d(TAG, "session state -> $it") }
                .first { it == DeviceSessionState.STARTED }
        } != null

    /**
     * Returns null once streaming, or the reason it will not.
     *
     * A stream that gives up reports STOPPED rather than hanging, so waiting out
     * the whole timeout after that only delays the failure and describes it
     * wrongly. The first STOPPED is the pre-start value every StateFlow replays,
     * so only one seen *after* the stream has moved counts as giving up - the
     * mock does exactly that, within a second, when CAMERA is not granted.
     */
    private suspend fun Camera.awaitStreaming(): String? {
        var moved = false
        val reached = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            stream.state
                .onEach { Log.d(TAG, "stream state -> $it") }
                .first { state ->
                    when (state) {
                        StreamState.STREAMING -> true
                        StreamState.STOPPED, StreamState.CLOSED -> moved
                        else -> {
                            moved = true
                            false
                        }
                    }
                }
        }
        return when (reached) {
            StreamState.STREAMING -> null
            null -> "stream never reached STREAMING"
            else -> "stream gave up before streaming (reached $reached)"
        }
    }

    /** Always returns null so callers can `return fail(...)`. */
    private fun fail(reason: String): Nothing? {
        Log.e(TAG, "GlassesController: $reason")
        releaseLocked()
        _state.value = State.Failed(reason)
        return null
    }

    private companion object {
        /**
         * A stream that gives up while starting is usually transient - a
         * Bluetooth hiccup on real glasses, or MockDeviceKit's encoder failing
         * to extract its codec config on an emulator - and a fresh session gets
         * it on the second go. Two, so a genuinely dead camera still fails
         * promptly rather than retrying its way through a long wait.
         */
        const val START_ATTEMPTS = 2
        const val SESSION_TIMEOUT_MS = 15_000L
        /** Starting a stream crosses Bluetooth Classic and negotiates a quality
         *  ladder. It took 6.5s on an emulator, so give real hardware room. */
        const val STREAM_TIMEOUT_MS = 30_000L
    }
}
