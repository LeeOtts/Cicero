package com.leeotts.cicero

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.glasses.MockGlassesSupport
import com.meta.wearable.dat.mockdevice.MockDeviceKit
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
        val pkg = context.packageName
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        shell.executeShellCommand("pm grant $pkg android.permission.BLUETOOTH_CONNECT")
        // Required for the MockDeviceKit camera feed, which opens the local camera.
        shell.executeShellCommand("pm grant $pkg android.permission.CAMERA")
        Thread.sleep(1_000) // pm grant is async via shell
    }

    @After
    fun tearDown() {
        runCatching { MockDeviceKit.getInstance(context).disable() }
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
        val bitmap = controller.capture()
        val state = controller.state.value
        controller.release()

        assertNotNull(
            "capture() returned null; controller state was $state",
            bitmap,
        )
        assertTrue("bitmap had no pixels", bitmap!!.width > 0 && bitmap.height > 0)
    }
}
