package com.leeotts.cicero.tools

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.leeotts.cicero.TAG

/** One incoming message, as it appeared in the notification shade. */
data class CapturedMessage(
    /** User-visible app name, e.g. "WhatsApp" — spoken aloud, so not a package name. */
    val app: String,
    val sender: String,
    val body: String,
    val at: Long,
)

/**
 * The rolling record of incoming messages that Meta AI will not keep.
 *
 * Meta AI can only read a message back inside a short window after announcing
 * it — there is no inbox query on non-display glasses. Holding the last
 * [CAPACITY] messages here is what makes "what did she say an hour ago"
 * answerable at all.
 *
 * In memory by design: this is a convenience buffer, not an archive, and
 * message bodies are worth keeping off disk. It empties when the process dies.
 */
object MessageLog {

    private const val CAPACITY = 50

    private val entries = ArrayDeque<CapturedMessage>()

    @Synchronized
    fun record(message: CapturedMessage) {
        // Messaging apps repost a notification on every update — a new message in
        // the same thread re-delivers the earlier ones too. Without this, asking
        // for the last three messages returns the same one three times.
        if (entries.any { it.sender == message.sender && it.body == message.body }) return

        entries.addLast(message)
        while (entries.size > CAPACITY) entries.removeFirst()
        // Sender and app only - message bodies stay out of logcat.
        Log.d(TAG, "captured message from ${message.sender} on ${message.app}")
    }

    /**
     * The [count] most recent messages, oldest first — the order a person
     * recounts them in ("Sam said X, then Alex said Y").
     *
     * @param sender case-insensitive partial match, so "sam" finds "Sam Nowak".
     */
    @Synchronized
    fun recent(count: Int, sender: String? = null): List<CapturedMessage> {
        val matching = if (sender == null) entries else entries.filter {
            it.sender.contains(sender, ignoreCase = true)
        }
        return matching.takeLast(count)
    }

    /** Distinct senders seen, newest first — used to answer "who messaged me". */
    @Synchronized
    fun senders(): List<String> = entries.reversed().map { it.sender }.distinct()

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Grants Notification Access, which does double duty: it is the only way to
 * enumerate other apps' media sessions (see [MediaControlTool]), and the only
 * way to see incoming messages without holding READ_SMS — and unlike READ_SMS
 * it covers WhatsApp, Messenger and Signal too.
 */
class CiceroNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return

        // Group summaries restate messages already captured individually, and
        // ongoing notifications are things like a call in progress.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()

        // MessagingStyle is a strong signal on its own; CATEGORY_MESSAGE catches
        // the SMS apps that do not use it. Together they cover every messenger
        // without naming a single package.
        if (style == null && notification.category != Notification.CATEGORY_MESSAGE) return

        val app = appLabel(sbn.packageName)

        if (style != null) {
            style.messages.forEach { message ->
                val body = message.text?.toString()?.trim().orEmpty()
                if (body.isEmpty()) return@forEach
                MessageLog.record(
                    CapturedMessage(
                        app = app,
                        // A one-to-one chat leaves person null — the title is the
                        // contact. A group chat names the speaker.
                        sender = message.person?.name?.toString()
                            ?: style.conversationTitle?.toString()
                            ?: UNKNOWN_SENDER,
                        body = body,
                        at = message.timestamp,
                    ),
                )
            }
            return
        }

        val extras = notification.extras
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (body.isNullOrEmpty()) return
        MessageLog.record(
            CapturedMessage(
                app = app,
                sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: UNKNOWN_SENDER,
                body = body,
                at = sbn.postTime,
            ),
        )
    }

    /** Package names are not speakable; "WhatsApp" is. */
    private fun appLabel(packageName: String): String = labels.getOrPut(packageName) {
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrElse {
            Log.d(TAG, "no label for $packageName")
            packageName
        }
    }

    private companion object {
        const val UNKNOWN_SENDER = "Someone"
        val labels = mutableMapOf<String, String>()
    }
}
