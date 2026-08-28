package com.leeotts.cicero.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.leeotts.cicero.R
import kotlinx.serialization.Serializable

/**
 * Every place the app can be.
 *
 * Type-safe routes rather than string patterns: kotlinx-serialization is already
 * on the classpath for the brain backends, and the only argument in the graph is
 * a conversation id.
 *
 * The parent is deliberately *not* @Serializable — navigation serializes each
 * destination on its own, and annotating the parent would ask for a polymorphic
 * serializer nothing here needs.
 */
sealed interface Route {
    @Serializable
    data object Ask : Route

    @Serializable
    data object History : Route

    @Serializable
    data class Thread(val conversationId: Long) : Route

    @Serializable
    data object Notes : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Glasses : Route

    @Serializable
    data object Map : Route
}

data class TopLevelDestination(
    val route: Route,
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

/**
 * The drawer's contents, and the definition of "top level" — the app bar shows a
 * hamburger on these and an Up arrow everywhere else. One list, both jobs.
 *
 * [Route.Thread] is absent on purpose: it is reached from History, not the drawer.
 */
val topLevelDestinations = listOf(
    TopLevelDestination(Route.Ask, R.string.nav_ask, Icons.AutoMirrored.Filled.Chat),
    TopLevelDestination(Route.History, R.string.nav_history, Icons.Filled.History),
    TopLevelDestination(Route.Notes, R.string.nav_notes, Icons.Filled.EditNote),
    TopLevelDestination(Route.Settings, R.string.nav_settings, Icons.Filled.Settings),
    TopLevelDestination(Route.Glasses, R.string.nav_glasses, Icons.Filled.Visibility),
    TopLevelDestination(Route.Map, R.string.nav_map, Icons.Filled.Place),
)
