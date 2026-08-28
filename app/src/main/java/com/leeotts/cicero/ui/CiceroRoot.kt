package com.leeotts.cicero.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.leeotts.cicero.AssistantViewModel
import com.leeotts.cicero.GlassesViewModel
import com.leeotts.cicero.MapViewModel
import com.leeotts.cicero.NotesViewModel
import com.leeotts.cicero.R
import com.leeotts.cicero.data.Conversation
import com.leeotts.cicero.show
import com.leeotts.cicero.ui.nav.CiceroDrawerContent
import com.leeotts.cicero.ui.nav.CiceroNavHost
import com.leeotts.cicero.ui.nav.Route
import com.leeotts.cicero.ui.nav.topLevelDestinations
import com.leeotts.cicero.ui.theme.CiceroTheme
import kotlinx.coroutines.launch

/**
 * The app shell: theme, drawer, app bar, snackbar host, nav graph.
 *
 * The drawer sits *outside* the Scaffold so its scrim covers the app bar too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiceroRoot(
    assistant: AssistantViewModel,
    glasses: GlassesViewModel,
    notes: NotesViewModel,
    map: MapViewModel,
) {
    val config by assistant.config.collectAsStateWithLifecycle()

    CiceroTheme(themeMode = config.themeMode) {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        val backStackEntry by navController.currentBackStackEntryAsState()
        val destination = backStackEntry?.destination
        val atTopLevel = topLevelDestinations.any { destination.matches(it.route) }

        val busy by assistant.busy.collectAsStateWithLifecycle()
        val recents by assistant.recentConversations.collectAsStateWithLifecycle()
        val conversations by assistant.conversations.collectAsStateWithLifecycle()
        val glassesState by glasses.glassesState.collectAsStateWithLifecycle()

        // One collector per event source, hoisted here so any screen can raise
        // a snackbar without owning a host of its own.
        LaunchedEffect(Unit) { notes.messages.collect { it.show(snackbarHostState) } }

        fun go(route: Route) {
            scope.launch { drawerState.close() }
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        Surface(Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // Off on detail screens, so an edge swipe there is not ambiguous
                // with a back gesture.
                gesturesEnabled = atTopLevel || drawerState.isOpen,
                drawerContent = {
                    ModalDrawerSheet {
                        CiceroDrawerContent(
                            glassesState = glassesState,
                            recents = recents,
                            isSelected = { destination.matches(it) },
                            onSelect = ::go,
                            onOpenThread = { id ->
                                // Via History, so Back from the thread lands on the
                                // list rather than wherever the drawer was opened.
                                go(Route.History)
                                navController.navigate(Route.Thread(id))
                            },
                        )
                    }
                },
            ) {
                Scaffold(
                    topBar = {
                        Column {
                            TopAppBar(
                                // A step off the page colour, so the bar reads as
                                // a bar without a divider or a shadow doing it.
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                                title = {
                                    Text(
                                        text = titleFor(destination, backStackEntry, conversations),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                navigationIcon = {
                                    if (atTopLevel) {
                                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                            Icon(
                                                Icons.Filled.Menu,
                                                stringResource(R.string.a11y_open_menu),
                                            )
                                        }
                                    } else {
                                        IconButton(onClick = { navController.navigateUp() }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                stringResource(R.string.a11y_back),
                                            )
                                        }
                                    }
                                },
                            )
                            // In the topBar slot so it never shifts content.
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    CiceroNavHost(
                        navController = navController,
                        assistant = assistant,
                        glasses = glasses,
                        notes = notes,
                        map = map,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

private fun NavDestination?.matches(route: Route): Boolean =
    this?.hasRoute(route::class) == true

@Composable
private fun titleFor(
    destination: NavDestination?,
    entry: NavBackStackEntry?,
    conversations: List<Conversation>,
): String {
    topLevelDestinations.firstOrNull { destination.matches(it.route) }
        ?.let { return stringResource(it.label) }

    // The route carries only the id, so the name comes from the list we already
    // collect for History.
    if (destination?.hasRoute(Route.Thread::class) == true) {
        val id = entry?.toRoute<Route.Thread>()?.conversationId
        conversations.firstOrNull { it.id == id }?.title
            ?.let { return it }
        return stringResource(R.string.title_conversation)
    }
    return stringResource(R.string.app_name)
}
