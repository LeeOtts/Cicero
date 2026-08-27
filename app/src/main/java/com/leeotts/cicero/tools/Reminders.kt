package com.leeotts.cicero.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The one place a reminder's PendingIntent is described.
 *
 * PendingIntent matching ignores extras but not the component or the request
 * code, so scheduling and cancelling have to agree on both — hence the shared
 * builder rather than two hand-rolled copies.
 */
object Reminders {

    fun intent(context: Context, noteId: Long, text: String): Intent =
        Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_TEXT, text)
            .putExtra(ReminderReceiver.EXTRA_ID, noteId)

    fun pendingIntent(context: Context, noteId: Long, text: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            intent(context, noteId, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** No-op when nothing is scheduled for [noteId]. */
    fun cancel(context: Context, noteId: Long) {
        val pending = PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }
}
