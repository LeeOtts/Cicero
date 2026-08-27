package com.leeotts.cicero.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.leeotts.cicero.tools.CiceroNotificationListener
import java.io.File

/**
 * Unwraps the Activity behind a Compose LocalContext.
 *
 * activity-compose 1.9.3 has no LocalActivity — that arrived in 1.10.0 — and the
 * DAT SDK needs a real Activity to start registration.
 */
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Notification Access is not a runtime permission — it is a per-app toggle in a
 * system settings screen, so it has to be read out of Settings.Secure.
 */
fun Context.hasNotificationAccess(): Boolean =
    Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        .orEmpty()
        .contains(packageName)

/**
 * Lands on Cicero's own row rather than a list of every app on the device.
 * The detail action is not honoured everywhere, hence the fallback.
 */
fun Context.openNotificationAccessSettings() {
    val component = ComponentName(this, CiceroNotificationListener::class.java)
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            component.flattenToString(),
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { startActivity(detail) }.recoverCatching {
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Hands files to whatever app can take them, through the app's own FileProvider.
 * Anything shared this way must be covered by res/xml/file_paths.xml, or
 * getUriForFile throws.
 */
fun Context.shareFiles(files: List<File>, mimeType: String, chooserTitle: String) {
    val existing = files.filter { it.exists() }
    if (existing.isEmpty()) return
    runCatching {
        val uris = ArrayList(
            existing.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) }
        )
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(mimeType)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(
            Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Where the user has to go once a permission is permanently denied. */
fun Context.openAppDetailsSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
