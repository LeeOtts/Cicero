package com.leeotts.cicero.tools

import android.content.Context
import com.leeotts.cicero.ai.Brain
import com.leeotts.cicero.ai.Tool
import com.leeotts.cicero.data.ConversationRepository
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.location.DestinationLog
import com.leeotts.cicero.location.LocationProvider

/** Assembles the tool list for a turn. */
object ToolRegistry {

    /**
     * @param brain used to drop [LookTool] for a backend that cannot see. Offering
     *   a camera to a text-only model just produces an image it will ignore or
     *   choke on, and a confidently wrong answer is worse than "I cannot see".
     */
    fun build(
        context: Context,
        repository: ConversationRepository,
        glasses: GlassesController,
        location: LocationProvider,
        destinations: DestinationLog,
        brain: Brain,
    ): List<Tool> = buildList {
        if (brain.supportsVision) add(LookTool(glasses))
        add(SetAlarmTool(context))
        add(SetTimerTool(context))
        add(SetReminderTool(context, repository))
        add(SaveNoteTool(repository))
        add(SearchLogTool(repository))
        add(MediaControlTool(context))
        // Order is deliberate and stable: the tool list is the first thing in a
        // cached prompt prefix, so reordering it invalidates the cache.
        add(WhereAmITool(context, location))
        add(NavigateTool(context, destinations))
        add(FindNearbyTool(context, location, destinations))
    }
}
