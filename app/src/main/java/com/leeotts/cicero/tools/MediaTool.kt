package com.leeotts.cicero.tools

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.leeotts.cicero.TAG
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.util.hasNotificationAccess
import com.leeotts.cicero.ai.ToolOutcome
import com.leeotts.cicero.ai.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Transport control for whatever is currently playing.
 *
 * Deliberately generic rather than Spotify-specific: Audible has no public API,
 * and MediaSession reaches it, Spotify, podcasts and anything else that
 * publishes a session. The cost is that it can only control what is *already*
 * playing — it cannot start a named album or book.
 *
 * Requires the user to grant Notification Access, which is the only way an app
 * may enumerate other apps' media sessions.
 */
class MediaControlTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "media_control",
        description = "Control whatever is currently playing: music, an audiobook or a podcast. " +
            "Cannot start a specific title.",
        parameters = Schemas.obj(
            "action" to Schemas.enumOf(
                "What to do",
                listOf("play", "pause", "next", "previous", "forward", "back", "status"),
            ),
            required = listOf("action"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val action = arguments.str("action")?.lowercase()
            ?: return ToolOutcome("I need to know what to do with the music.", isError = true)

        if (!context.hasNotificationAccess()) {
            return ToolOutcome(
                "I need Notification Access to control playback. " +
                    "Turn it on for Cicero in Android settings.",
                isError = true,
            )
        }

        val controller = activeController()
            ?: return ToolOutcome("Nothing is playing right now.", isError = true)

        val title = controller.metadata
            ?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: "it"

        return try {
            val controls = controller.transportControls
            when (action) {
                "play" -> { controls.play(); ToolOutcome("Playing $title.") }
                "pause" -> { controls.pause(); ToolOutcome("Paused.") }
                "next" -> { controls.skipToNext(); ToolOutcome("Skipped ahead.") }
                "previous" -> { controls.skipToPrevious(); ToolOutcome("Went back.") }
                // Audiobooks want a 30 second jump, which skipToNext does not give.
                "forward" -> seekBy(controller, SKIP_MS, "Skipped forward 30 seconds.")
                "back" -> seekBy(controller, -SKIP_MS, "Went back 30 seconds.")
                "status" -> ToolOutcome(describe(controller, title))
                else -> ToolOutcome("I do not know how to $action.", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "media control failed", e)
            ToolOutcome("That did not work.", isError = true)
        }
    }

    private fun seekBy(controller: MediaController, deltaMs: Long, spoken: String): ToolOutcome {
        val position = controller.playbackState?.position
            ?: return ToolOutcome("I could not tell where you are in it.", isError = true)
        controller.transportControls.seekTo((position + deltaMs).coerceAtLeast(0L))
        return ToolOutcome(spoken)
    }

    private fun describe(controller: MediaController, title: String): String {
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val artist = controller.metadata
            ?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        return buildString {
            append(if (playing) "Playing " else "Paused on ")
            append(title)
            artist?.let { append(" by $it") }
        }
    }

    /** Prefers a session that is actually playing over one merely present. */
    private fun activeController(): MediaController? = try {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, CiceroNotificationListener::class.java)
        val sessions = manager.getActiveSessions(component)
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    } catch (e: SecurityException) {
        Log.e(TAG, "no notification access for media sessions", e)
        null
    }

    private companion object {
        const val SKIP_MS = 30_000L
    }
}
