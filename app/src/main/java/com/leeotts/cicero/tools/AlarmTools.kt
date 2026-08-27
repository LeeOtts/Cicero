package com.leeotts.cicero.tools

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.leeotts.cicero.TAG
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.ai.ToolOutcome
import com.leeotts.cicero.ai.ToolSpec
import com.leeotts.cicero.data.ConversationRepository
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

/**
 * Alarms and timers go through the system clock app via [AlarmClock] intents.
 * That needs no permission and means the alarm survives this app being killed —
 * which matters for something you rely on to wake you up.
 */
class SetAlarmTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Set an alarm at a specific clock time. Use 24-hour time.",
        parameters = Schemas.obj(
            "hour" to Schemas.integer("Hour, 0 to 23"),
            "minute" to Schemas.integer("Minute, 0 to 59"),
            "label" to Schemas.string("What the alarm is for"),
            required = listOf("hour"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val hour = arguments.int("hour")
            ?: return ToolOutcome("I need a time for the alarm.", isError = true)
        val minute = arguments.int("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolOutcome("That is not a valid time.", isError = true)
        }
        val label = arguments.str("label")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "alarm for ${format(hour, minute)}" + (label?.let { " for $it" } ?: ""))
    }

    private fun launch(intent: Intent, what: String): ToolOutcome = try {
        context.startActivity(intent)
        ToolOutcome("Set $what.")
    } catch (e: Exception) {
        Log.e(TAG, "alarm intent failed", e)
        ToolOutcome("I could not set that. No clock app handled it.", isError = true)
    }

    private fun format(hour: Int, minute: Int): String {
        val suffix = if (hour < 12) "AM" else "PM"
        val h = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return if (minute == 0) "$h $suffix" else "$h:${minute.toString().padStart(2, '0')} $suffix"
    }
}

class SetTimerTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "set_timer",
        description = "Start a countdown timer for a number of seconds.",
        parameters = Schemas.obj(
            "seconds" to Schemas.integer("How many seconds to count down"),
            "label" to Schemas.string("What the timer is for"),
            required = listOf("seconds"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val seconds = arguments.int("seconds")
            ?: return ToolOutcome("I need a duration for the timer.", isError = true)
        if (seconds <= 0) return ToolOutcome("That is not a valid duration.", isError = true)

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            arguments.str("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolOutcome("Timer started for ${spoken(seconds)}.")
        } catch (e: Exception) {
            Log.e(TAG, "timer intent failed", e)
            ToolOutcome("I could not start that timer.", isError = true)
        }
    }

    private fun spoken(seconds: Int): String = when {
        seconds % 3600 == 0 && seconds >= 3600 -> "${seconds / 3600} hours".fixSingular("hour")
        seconds % 60 == 0 && seconds >= 60 -> "${seconds / 60} minutes".fixSingular("minute")
        else -> "$seconds seconds".fixSingular("second")
    }

    private fun String.fixSingular(unit: String) =
        if (startsWith("1 ")) "1 $unit" else this
}

/**
 * Reminders are kept in-app rather than handed to the clock app, because they
 * carry text we want back in the searchable log.
 */
class SetReminderTool(
    private val context: Context,
    private val repository: ConversationRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : Tool {

    override val spec = ToolSpec(
        name = "set_reminder",
        description = "Remind the user about something after a delay.",
        parameters = Schemas.obj(
            "text" to Schemas.string("What to remind the user about"),
            "in_minutes" to Schemas.integer("How many minutes from now"),
            required = listOf("text", "in_minutes"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val text = arguments.str("text")
            ?: return ToolOutcome("I need to know what to remind you about.", isError = true)
        val minutes = arguments.int("in_minutes")
            ?: return ToolOutcome("I need to know when to remind you.", isError = true)
        if (minutes <= 0) return ToolOutcome("That time has already passed.", isError = true)

        val at = now() + minutes * 60_000L
        val id = repository.addNote(text, remindAt = at, now = now())

        val manager = context.getSystemService(AlarmManager::class.java)
        // Shared with Reminders.cancel, so the two cannot drift apart.
        val pending = Reminders.pendingIntent(context, id, text)

        return try {
            // Exact alarms need a special permission on Android 12+. Fall back to
            // an inexact one rather than failing outright - a reminder that fires
            // a little late beats no reminder.
            if (manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                Log.i(TAG, "exact alarms not permitted; reminder scheduled inexactly")
            }
            ToolOutcome("I will remind you in $minutes minutes.")
        } catch (e: Exception) {
            Log.e(TAG, "reminder scheduling failed", e)
            ToolOutcome("I saved the note but could not schedule the reminder.", isError = true)
        }
    }
}

/** Formats a wall-clock time for [SetAlarmTool] callers that pass a Calendar. */
internal fun Calendar.hourMinute(): Pair<Int, Int> =
    get(Calendar.HOUR_OF_DAY) to get(Calendar.MINUTE)
