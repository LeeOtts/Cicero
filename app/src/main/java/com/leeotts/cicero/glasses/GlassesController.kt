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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
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

    private var session: DeviceSession? = null
    private var camera: Camera? = null

    /** Opens a session and drives the stream to STREAMING so [capture] is instant. */
    suspend fun warmUp(): Boolean = lock.withLock { warmUpLocked() }

    private suspend fun warmUpLocked(): Boolean {
        if (_state.value is State.Ready) return true
        _state.value = State.Connecting

        val newSession = openSession() ?: return false
        session = newSession
        newSession.start()

        if (!newSession.awaitStarted()) return fail("session never reached STARTED")

        val newCamera = attachCamera(newSession) ?: return false
        camera = newCamera

        if (!startStream(newCamera)) return false
        if (!newCamera.awaitStreaming()) return fail("stream never reached STREAMING")

        _state.value = State.Ready
        return true
    }

    /** Captures a single still, warming the session up first if needed. */
    suspend fun capture(): AndroidBitmap? = lock.withLock {
        if (!warmUpLocked()) return@withLock null
        val stream = camera?.stream ?: run {
            fail("camera stream went away")
            return@withLock null
        }

        var out: AndroidBitmap? = null
        stream.capturePhoto().fold(
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

    fun release() {
        runCatching { camera?.stop() }
        runCatching { session?.stop() }
        camera = null
        session = null
    }

    private fun openSession(): DeviceSession? {
        var out: DeviceSession? = null
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { out = it },
            onFailure = { error, _ -> fail("createSession: ${error.description}") },
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
            onFailure = { error, _ -> fail("addCamera: ${error.description}") },
        )
        return out
    }

    private fun startStream(camera: Camera): Boolean {
        var ok = false
        camera.stream.start().fold(
            onSuccess = { ok = true },
            onFailure = { error, _ -> fail("stream.start: ${error.description}") },
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

    private suspend fun Camera.awaitStreaming(): Boolean =
        withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            stream.state
                .onEach { Log.d(TAG, "stream state -> $it") }
                .first { it == StreamState.STREAMING }
        } != null

    /** Always returns false so callers can `return fail(...)`. */
    private fun fail(reason: String): Boolean {
        Log.e(TAG, "GlassesController: $reason")
        release()
        _state.value = State.Failed(reason)
        return false
    }

    private companion object {
        const val SESSION_TIMEOUT_MS = 15_000L
        /** Starting a stream crosses Bluetooth Classic and negotiates a quality
         *  ladder. It took 6.5s on an emulator, so give real hardware room. */
        const val STREAM_TIMEOUT_MS = 30_000L
    }
}
