package com.leeotts.cicero.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.leeotts.cicero.TAG
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import java.io.File

/**
 * Drives MockDeviceKit so the DAT session/camera path can be exercised on an
 * emulator with no physical glasses and no Meta AI app installed.
 *
 * enable() fakes registration (registrationState goes to Registered) and grants
 * device permissions by default, which is what makes this work without Meta AI.
 */
object MockGlassesSupport {

    private var glasses: MockGlasses? = null

    fun enable(context: Context): String {
        val kit = MockDeviceKit.getInstance(context)
        if (!kit.isEnabled) kit.enable()

        // enable() is called more than once per process - the Glasses screen and
        // the instrumentation tests both do it - and pairGlasses() adds another
        // device every time. Clear what is already paired first, so
        // AutoDeviceSelector is never choosing between a live device and one
        // left powered off by an earlier call.
        unpairAll(kit)

        var paired: MockGlasses? = null
        var failure: String? = null
        // RAYBAN_META_OPTICS is the closest model to the real Blayzer Optics (Gen 2).
        kit.pairGlasses(GlassesModel.RAYBAN_META_OPTICS).fold(
            onSuccess = { paired = it },
            onFailure = { error, _ -> failure = error.toString() },
        )
        val device = paired ?: return "mock pair failed: $failure"

        // The SDK only offers a device that is powered on, open and being worn.
        device.powerOn()
        device.unfold()
        device.don()
        // A camera FEED is what lets the stream reach STREAMING; the captured
        // image only backs capturePhoto(). Both are needed.
        device.services.camera.setCameraFeed(CameraFacing.BACK)
        device.services.camera.setCapturedImage(testImageUri(context))
        glasses = device

        Log.i(TAG, "MockDeviceKit enabled, paired ${GlassesModel.RAYBAN_META_OPTICS.displayName}")
        return "mock glasses ready (${GlassesModel.RAYBAN_META_OPTICS.displayName})"
    }

    fun disable(context: Context) {
        val kit = MockDeviceKit.getInstance(context)
        unpairAll(kit)
        kit.disable()
    }

    /**
     * Unpairs everything the kit holds, not just the device in [glasses]:
     * a caller that disabled the kit directly leaves that field pointing at a
     * device this object can no longer reach.
     */
    private fun unpairAll(kit: MockDeviceKitInterface) {
        // Copied first - unpairDevice mutates the collection behind
        // pairedDevices. Wrapped because a disabled kit has nothing to list.
        runCatching {
            kit.pairedDevices.toList().forEach { runCatching { kit.unpairDevice(it) } }
        }
        glasses = null
    }

    /**
     * Synthetic capture target. Generated rather than shipped as an asset so it
     * is obviously fake and needs no binary in the repo.
     */
    private fun testImageUri(context: Context): Uri {
        val file = File(context.cacheDir, "mock-capture.png")
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(24, 28, 38))
            val bar = Paint().apply { isAntiAlias = true }
            val colors = intArrayOf(
                Color.rgb(226, 74, 74), Color.rgb(226, 158, 74),
                Color.rgb(96, 186, 122), Color.rgb(74, 148, 226),
            )
            colors.forEachIndexed { i, c ->
                bar.color = c
                drawRect(40f + i * 140f, 80f, 160f + i * 140f, 260f, bar)
            }
            drawText(
                "MOCK CAPTURE",
                48f, 360f,
                Paint().apply {
                    color = Color.WHITE
                    textSize = 56f
                    isAntiAlias = true
                },
            )
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
