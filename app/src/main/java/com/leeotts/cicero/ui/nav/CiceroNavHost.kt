package com.leeotts.cicero.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.leeotts.cicero.AssistantViewModel
import com.leeotts.cicero.GlassesViewModel
import com.leeotts.cicero.MapViewModel
import com.leeotts.cicero.NotesViewModel
import com.leeotts.cicero.R
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.ui.AskScreen
import com.leeotts.cicero.ui.GlassesScreen
import com.leeotts.cicero.ui.HistoryScreen
import com.leeotts.cicero.ui.MapScreen
import com.leeotts.cicero.ui.NotesScreen
import com.leeotts.cicero.ui.SettingsScreen
import com.leeotts.cicero.ui.ThreadScreen
import com.leeotts.cicero.util.findActivity

@Composable
fun CiceroNavHost(
    navController: NavHostController,
    assistant: AssistantViewModel,
    glasses: GlassesViewModel,
    notes: NotesViewModel,
    map: MapViewModel,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Route.Ask,
        modifier = modifier,
    ) {
        composable<Route.Ask> {
            val config by assistant.config.collectAsStateWithLifecycle()
            val busy by assistant.busy.collectAsStateWithLifecycle()
            val exchanges by assistant.exchanges.collectAsStateWithLifecycle()
            val speaking by assistant.speaking.collectAsStateWithLifecycle()
            AskScreen(
                exchanges = exchanges,
                busy = busy,
                speaking = speaking,
                backendLabel = backendLabel(config),
                onAsk = assistant::ask,
                onClear = assistant::clearConversation,
                onStopSpeaking = assistant::stopSpeaking,
            )
        }

        composable<Route.History> {
            val conversations by assistant.conversations.collectAsStateWithLifecycle()
            HistoryScreen(
                conversations = conversations,
                onOpen = { id -> navController.navigate(Route.Thread(id)) },
            )
        }

        composable<Route.Thread> { entry ->
            val id = entry.toRoute<Route.Thread>().conversationId
            val turns by remember(id) { assistant.turns(id) }
                .collectAsStateWithLifecycle(emptyList())
            ThreadScreen(turns)
        }

        composable<Route.Notes> {
            NotesScreen(viewModel = notes)
        }

        composable<Route.Settings> {
            val config by assistant.config.collectAsStateWithLifecycle()
            val busy by assistant.busy.collectAsStateWithLifecycle()
            val testResult by assistant.testResult.collectAsStateWithLifecycle()
            val oauth by assistant.oauth.collectAsStateWithLifecycle()
            val whisperModels by assistant.whisperModels.collectAsStateWithLifecycle()
            val nestConfig by assistant.nestConfig.collectAsStateWithLifecycle()
            val nestTestResult by assistant.nestTestResult.collectAsStateWithLifecycle()
            val context = LocalContext.current
            SettingsScreen(
                config = config,
                busy = busy,
                testResult = testResult,
                oauth = oauth,
                models = assistant::models,
                whisperModels = whisperModels,
                onUpdate = assistant::update,
                onTest = assistant::testConnection,
                onLoadModels = assistant::loadModels,
                onLoadWhisperModels = assistant::loadWhisperModels,
                // Custom Tabs needs an Activity so the tab joins this task and
                // the callback can pop it again.
                onSignIn = { context.findActivity()?.let(assistant::signIn) },
                onOAuthResumed = assistant::oauthResumed,
                nestConfig = nestConfig,
                nestTestResult = nestTestResult,
                onUpdateNest = assistant::updateNest,
                onTestNest = assistant::testNest,
            )
        }

        composable<Route.Glasses> {
            GlassesScreen(viewModel = glasses)
        }

        composable<Route.Map> {
            MapScreen(viewModel = map)
        }
    }
}

@Composable
internal fun backendLabel(config: BrainConfig): String {
    val provider = config.provider
    val model = config.modelFor(provider).ifBlank { stringResource(R.string.backend_no_model) }
    return stringResource(R.string.backend_with_model, provider.displayName, model)
}
