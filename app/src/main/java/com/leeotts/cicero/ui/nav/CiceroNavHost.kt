package com.leeotts.cicero.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.leeotts.cicero.AssistantViewModel
import com.leeotts.cicero.GlassesViewModel
import com.leeotts.cicero.NotesViewModel
import com.leeotts.cicero.R
import com.leeotts.cicero.ai.BrainChoice
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.ui.AskScreen
import com.leeotts.cicero.ui.GlassesScreen
import com.leeotts.cicero.ui.HistoryScreen
import com.leeotts.cicero.ui.NotesScreen
import com.leeotts.cicero.ui.SettingsScreen
import com.leeotts.cicero.ui.ThreadScreen

@Composable
fun CiceroNavHost(
    navController: NavHostController,
    assistant: AssistantViewModel,
    glasses: GlassesViewModel,
    notes: NotesViewModel,
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
            AskScreen(
                exchanges = exchanges,
                busy = busy,
                backendLabel = backendLabel(config),
                onAsk = assistant::ask,
                onClear = assistant::clearConversation,
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
            val localModels by assistant.localModels.collectAsStateWithLifecycle()
            val whisperModels by assistant.whisperModels.collectAsStateWithLifecycle()
            SettingsScreen(
                config = config,
                busy = busy,
                testResult = testResult,
                localModels = localModels,
                whisperModels = whisperModels,
                onUpdate = assistant::update,
                onTest = assistant::testConnection,
                onLoadLocalModels = assistant::loadLocalModels,
                onLoadWhisperModels = assistant::loadWhisperModels,
            )
        }

        composable<Route.Glasses> {
            GlassesScreen(viewModel = glasses)
        }
    }
}

@Composable
internal fun backendLabel(config: BrainConfig): String {
    val model = when (config.choice) {
        BrainChoice.GEMINI -> config.geminiModel
        BrainChoice.CLAUDE -> config.claudeModel
        BrainChoice.LOCAL ->
            config.localModel.ifBlank { stringResource(R.string.backend_no_model) }
    }
    return stringResource(R.string.backend_with_model, stringResource(config.choice.label), model)
}
