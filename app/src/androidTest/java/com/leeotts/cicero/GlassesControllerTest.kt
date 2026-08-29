package com.leeotts.cicero

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.glasses.MockGlassesSupport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
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
}
