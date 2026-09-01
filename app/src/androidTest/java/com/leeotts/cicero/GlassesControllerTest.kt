package com.leeotts.cicero

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.glasses.MockGlassesSupport
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real GlassesController against MockDeviceKit, so the DAT
 * session -> camera -> capturePhoto path is verified without physical glasses
 * or the Meta AI app.
 */
@RunWith(AndroidJUnit4::class)
class GlassesControllerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        grant(Manifest.permission.BLUETOOTH_CONNECT)
        // Required for the MockDeviceKit camera feed, which opens the local camera.
        grant(Manifest.permission.CAMERA)
    }

    /**
     * Granted through uiAutomation rather than "pm grant" via
     * executeShellCommand(), which returns before the grant has landed - the
     * platform logs that advice itself. The mock checks CAMERA when the video
     * stream is configured, roughly a second into capture(); a grant still in
     * flight at that point makes the stream give up short of STREAMING, and the
     * only symptom is a capture that returns null.
     */
    private fun grant(permission: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, permission)
        assertTrue(
            "$permission was not granted",
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    @After
    fun tearDown() {
        // Through MockGlassesSupport, so the paired device is unpaired and its
        // own state is cleared. Disabling the kit alone leaves both behind for
        // whatever runs next in this process.
        runCatching { MockGlassesSupport.disable(context) }
    }

    @Test
    fun mockGlassesPairAndReportReady() {
        val status = MockGlassesSupport.enable(context)
        assertTrue("unexpected mock status: $status", status.startsWith("mock glasses ready"))
    }

    /**
     * Regression test for a real-hardware bug: a session that fails almost
     * immediately (glasses paired and powered but not worn) used to be
     * indistinguishable from one that simply hangs - startAndAwaitStarted() only
     * matched on STARTED, so it burned the full SESSION_TIMEOUT_MS waiting for a
     * state that had already gone to STOPPED, and DeviceSession.errors was never
     * observed at all. On real glasses this turned a ~100ms failure into a 15s
     * (times two retries, 30s) one with no indication of why. A mock device that
     * is paired and powered but never don()'d reproduces the same fast
     * STARTING -> STOPPING/STOPPED transition deterministically.
     */
    @Test
    fun captureFailsFastWhenGlassesAreUnreachable() = runBlocking {
        val kit = MockDeviceKit.getInstance(context)
        if (!kit.isEnabled) kit.enable()
        unpairAll(kit)

        var paired: MockGlasses? = null
        kit.pairGlasses(GlassesModel.RAYBAN_META_OPTICS).fold(
            onSuccess = { paired = it },
            onFailure = { error, _ -> throw IllegalStateException("mock pair failed: $error") },
        )
        val device = requireNotNull(paired)
        device.powerOn()
        device.unfold()
        // Deliberately not don() - not worn, so the session starts but the SDK
        // ends it right away instead of ever reaching STARTED.
        val controller = GlassesController()
        var bitmap: android.graphics.Bitmap? = null
        val elapsedMs = measureTimeMillis {
            bitmap = try {
                controller.capture()
            } finally {
                controller.release()
            }
        }

        assertNull("expected capture() to fail against an unreachable device", bitmap)
        assertTrue(
            "capture() took ${elapsedMs}ms - the fail-fast path did not engage " +
                "(old behaviour waited out the full 15s SESSION_TIMEOUT_MS per attempt, " +
                "30s across both retries)",
            elapsedMs < 5_000,
        )

        unpairAll(kit)
    }

    private fun unpairAll(kit: MockDeviceKitInterface) {
        runCatching { kit.pairedDevices.toList().forEach { runCatching { kit.unpairDevice(it) } } }
    }

    @Test
    fun capturesAPhotoThroughTheRealController() = runBlocking {
        MockGlassesSupport.enable(context)

        val controller = GlassesController()
        // Sampled before release(), which resets the state to Idle - after that
        // it no longer says why a capture failed.
        var stateAfterCapture: GlassesController.State = GlassesController.State.Idle
        val bitmap = try {
            controller.capture().also { stateAfterCapture = controller.state.value }
        } finally {
            // Also releases when capture() throws, so a failure here does not
            // leave the session and camera open for whatever runs next.
            controller.release()
        }
        assertNotNull(
            "capture() returned null; controller state was $stateAfterCapture",
            bitmap,
        )
        assertTrue("bitmap had no pixels", bitmap!!.width > 0 && bitmap.height > 0)
    }

    /**
     * The second shot used to fail with "camera stream went away": release()
     * cleared the session and camera but left the state at Ready, so the fast
     * path in warmUp() skipped the rebuild and then found no stream. The app
     * releases after every capture, so this was every shot after the first.
     */
    @Test
    fun capturesTwiceWithAReleaseInBetween() = runBlocking {
        MockGlassesSupport.enable(context)

        val controller = GlassesController()
        try {
            val first = controller.capture()
            assertNotNull("first capture returned null; state was ${controller.state.value}", first)
            // What GlassesViewModel.capture() does in its finally: an open
            // session suspends Meta AI's own features on the glasses.
            controller.release()

            val second = controller.capture()
            assertNotNull(
                "second capture returned null; state was ${controller.state.value}",
                second,
            )
        } finally {
            controller.release()
        }
    }

    /**
     * warmUp() exists so the wake word can open the session speculatively and
     * the capture that follows is instant. That only pays off if capture()
     * reuses what warmUp() built instead of opening a second session, so the
     * Ready state has to survive across the two calls.
     */
    @Test
    fun warmUpThenCaptureReusesTheSession() = runBlocking {
        MockGlassesSupport.enable(context)

        val controller = GlassesController()
        try {
            assertTrue(
                "warmUp() failed; state was ${controller.state.value}",
                controller.warmUp(),
            )
            assertEquals(GlassesController.State.Ready, controller.state.value)

            assertNotNull("capture() after warmUp() returned null", controller.capture())
            assertEquals(GlassesController.State.Ready, controller.state.value)
        } finally {
            controller.release()
        }
    }
}
