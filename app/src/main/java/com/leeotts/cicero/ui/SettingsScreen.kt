package com.leeotts.cicero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.leeotts.cicero.R
import com.leeotts.cicero.OAuthState
import com.leeotts.cicero.TestResult
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.ai.LocalUrlMode
import com.leeotts.cicero.ai.ModelList
import com.leeotts.cicero.ai.Provider
import com.leeotts.cicero.ai.Providers
import com.leeotts.cicero.ai.Target
import com.leeotts.cicero.ai.TaskRole
import com.leeotts.cicero.home.NestConfig
import com.leeotts.cicero.ui.components.ModelPicker
import com.leeotts.cicero.ui.components.PermissionCard
import com.leeotts.cicero.ui.components.SectionHeader
import com.leeotts.cicero.ui.components.rememberSystemFlag
import com.leeotts.cicero.ui.components.SecretField
import com.leeotts.cicero.ui.components.SettingField
import com.leeotts.cicero.ui.components.SettingToggle
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.ThemeMode
import com.leeotts.cicero.ui.theme.applyNightMode
import com.leeotts.cicero.util.assistantRoleRequest
import com.leeotts.cicero.util.hasNotificationAccess
import com.leeotts.cicero.util.isDefaultAssistant
import com.leeotts.cicero.util.isGranted
import com.leeotts.cicero.util.openAssistantSettings
import com.leeotts.cicero.util.openNotificationAccessSettings
import com.leeotts.cicero.util.openUrl

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    config: BrainConfig,
    busy: Boolean,
    testResult: TestResult?,
    oauth: OAuthState,
    models: (String) -> StateFlow<ModelList>,
    whisperModels: ModelList,
    onUpdate: ((BrainConfig) -> BrainConfig) -> Unit,
    onTest: () -> Unit,
    onLoadModels: (String, Boolean) -> Unit,
    onLoadWhisperModels: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onOAuthResumed: () -> Unit,
    nestConfig: NestConfig,
    nestTestResult: TestResult?,
    onUpdateNest: ((NestConfig) -> NestConfig) -> Unit,
    onTestNest: () -> Unit,
    onClearHistory: () -> Unit,
    onClearNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Warmed as each section appears, so the first tap on a picker shows names
    // rather than a spinner.
    LaunchedEffect(config.providerId) {
        onLoadModels(config.providerId, false)
        if (!config.provider.acceptsAudio) onLoadWhisperModels(false)
    }

    // A Custom Tab has no cancel callback, so returning to this screen is the
    // only signal that a sign-in was abandoned.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onOAuthResumed() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        SectionHeader(stringResource(R.string.settings_appearance))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = config.themeMode == mode,
                    onClick = {
                        onUpdate { it.copy(themeMode = mode) }
                        // Also tell the platform, so a cold start paints the right
                        // window background before Compose runs.
                        applyNightMode(context, mode)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    label = { Text(stringResource(themeModeLabel(mode))) },
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_which_ai))
        // A wrapping row rather than a dropdown: nine short proper nouns fit in
        // three lines, and every option stays visible without a tap.
        ProviderChips(selected = config.providerId) { id ->
            onUpdate { it.copy(providerId = id) }
        }

        ProviderSettings(
            provider = config.provider,
            config = config,
            oauth = oauth,
            models = models,
            onUpdate = onUpdate,
            onLoadModels = onLoadModels,
            onSignIn = onSignIn,
        )

        SectionHeader(stringResource(R.string.settings_routing))
        SettingToggle(
            stringResource(R.string.settings_routing_enabled),
            config.routingEnabled,
        ) { v -> onUpdate { it.copy(routingEnabled = v) } }
        if (config.routingEnabled) {
            Hint(stringResource(R.string.settings_routing_hint))
            RoleSetting(
                role = TaskRole.FAST,
                label = stringResource(R.string.settings_role_fast),
                config = config,
                models = models,
                onUpdate = onUpdate,
                onLoadModels = onLoadModels,
            )
            RoleSetting(
                role = TaskRole.DEEP,
                label = stringResource(R.string.settings_role_deep),
                config = config,
                models = models,
                onUpdate = onUpdate,
                onLoadModels = onLoadModels,
            )
        }

        // Gemini is the only backend that takes raw audio; everything else needs
        // a transcriber in front of it.
        if (!config.provider.acceptsAudio) {
            SectionHeader(stringResource(R.string.settings_speech_to_text))
            Hint(stringResource(R.string.settings_speech_hint))
            if (config.provider.userEditableUrl) {
                SettingToggle(
                    stringResource(R.string.settings_whisper_same_server),
                    config.whisperSameServer,
                ) { v -> onUpdate { it.copy(whisperSameServer = v) } }
            }
            // Hidden rather than greyed out while it follows the model's server:
            // a disabled copy of an address you cannot edit is just noise.
            if (!config.speechSharesServer) {
                SettingField(stringResource(R.string.settings_whisper_url), config.whisperBaseUrl) { v ->
                    onUpdate { it.copy(whisperBaseUrl = v) }
                }
            }
            ModelPicker(
                label = stringResource(R.string.settings_whisper_model),
                value = config.whisperModel,
                models = whisperModels,
                onExpand = { onLoadWhisperModels(false) },
                onRefresh = { onLoadWhisperModels(true) },
            ) { v -> onUpdate { it.copy(whisperModel = v) } }
        }

        Button(onClick = onTest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_test_connection))
        }
        testResult?.let { TestResultRow(it) }

        SectionHeader(stringResource(R.string.settings_wake_word))
        Hint(stringResource(R.string.settings_wake_word_hint))
        SettingToggle(
            stringResource(R.string.settings_wake_word_toggle),
            config.wakeWordEnabled,
        ) { v -> onUpdate { it.copy(wakeWordEnabled = v) } }

        SectionHeader(stringResource(R.string.settings_nest))
        Hint(stringResource(R.string.settings_nest_hint))
        SettingField(
            stringResource(R.string.settings_nest_project),
            nestConfig.projectId,
        ) { v -> onUpdateNest { it.copy(projectId = v) } }
        SettingField(
            stringResource(R.string.settings_nest_client_id),
            nestConfig.clientId,
        ) { v -> onUpdateNest { it.copy(clientId = v) } }
        SecretField(
            stringResource(R.string.settings_nest_client_secret),
            nestConfig.clientSecret,
        ) { v -> onUpdateNest { it.copy(clientSecret = v) } }
        SecretField(
            stringResource(R.string.settings_nest_refresh_token),
            nestConfig.refreshToken,
        ) { v -> onUpdateNest { it.copy(refreshToken = v) } }
        Button(onClick = onTestNest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_nest_test))
        }
        nestTestResult?.let { TestResultRow(it) }

        SectionHeader(stringResource(R.string.settings_data))
        Hint(stringResource(R.string.settings_data_hint))
        DataSection(onClearHistory = onClearHistory, onClearNotes = onClearNotes)

        SectionHeader(stringResource(R.string.settings_permissions))
        PermissionsSection()
    }
}

/**
 * The two irreversible buttons.
 *
 * Both are destructive and neither can be undone, so each goes through a
 * confirmation naming exactly what goes - unlike a single note, which is cheap
 * enough to delete outright and put back from the snackbar.
 */
@Composable
private fun DataSection(
    onClearHistory: () -> Unit,
    onClearNotes: () -> Unit,
) {
    var confirming by remember { mutableStateOf<DataConfirmation?>(null) }

    OutlinedButton(
        onClick = { confirming = DataConfirmation.HISTORY },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_clear_history)) }

    OutlinedButton(
        onClick = { confirming = DataConfirmation.NOTES },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_clear_notes)) }

    confirming?.let { target ->
        val isHistory = target == DataConfirmation.HISTORY
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    stringResource(
                        if (isHistory) R.string.settings_clear_history_title
                        else R.string.settings_clear_notes_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (isHistory) R.string.settings_clear_history_body
                        else R.string.settings_clear_notes_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isHistory) onClearHistory() else onClearNotes()
                        confirming = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private enum class DataConfirmation { HISTORY, NOTES }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChips(selected: String, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Providers.all.forEach { provider ->
            FilterChip(
                selected = selected == provider.id,
                onClick = { onPick(provider.id) },
                label = { Text(provider.displayName) },
            )
        }
    }
}

/**
 * One section for every provider.
 *
 * There is deliberately no `when (provider)` here. Everything that used to make
 * a provider special - its address, its key, its model list, whether it can see
 * - is a field on [Provider] now, so adding a tenth provider changes nothing on
 * this screen.
 */
@Composable
private fun ProviderSettings(
    provider: Provider,
    config: BrainConfig,
    oauth: OAuthState,
    models: (String) -> StateFlow<ModelList>,
    onUpdate: ((BrainConfig) -> BrainConfig) -> Unit,
    onLoadModels: (String, Boolean) -> Unit,
    onSignIn: () -> Unit,
) {
    val context = LocalContext.current
    val modelList by remember(provider.id) { models(provider.id) }.collectAsStateWithLifecycle()
    val key = config.keys[provider.id].orEmpty()

    if (provider.userEditableUrl) {
        SettingField(stringResource(R.string.settings_server_url), config.localBaseUrl) { v ->
            onUpdate { it.copy(localBaseUrl = v) }
        }
        SettingField(stringResource(R.string.settings_tailscale_url), config.localTailscaleUrl) { v ->
            onUpdate { it.copy(localTailscaleUrl = v) }
        }
        Hint(stringResource(R.string.settings_tailscale_hint))
        // Hidden rather than greyed out while there is nothing to switch between.
        if (config.localTailscaleUrl.isNotBlank()) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LocalUrlMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = config.localUrlMode == mode,
                        onClick = { onUpdate { it.copy(localUrlMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, LocalUrlMode.entries.size),
                        // Equal thirds, and one line each. A label that wraps
                        // grows its own segment and breaks the row out of shape
                        // at a large display font.
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                text = stringResource(localUrlModeLabel(mode)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Hint(stringResource(localUrlModeHint(config.localUrlMode)))
        }
    }

    ModelPicker(
        label = stringResource(R.string.settings_model_name),
        value = config.modelFor(provider),
        models = modelList,
        onExpand = { onLoadModels(provider.id, false) },
        onRefresh = { onLoadModels(provider.id, true) },
    ) { v -> onUpdate { it.copy(models = it.models + (provider.id to v)) } }

    // Signing in beats pasting a key, so it is offered until there IS a key -
    // after which the field is shown so it can be seen, replaced or cleared.
    if (provider.oauth && key.isBlank()) {
        Button(
            onClick = onSignIn,
            enabled = oauth !is OAuthState.Exchanging,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_sign_in, provider.displayName))
        }
        Hint(stringResource(R.string.settings_openrouter_hint))
    } else {
        SecretField(stringResource(R.string.settings_api_key), key) { v ->
            onUpdate { it.copy(keys = it.keys + (provider.id to v)) }
        }
    }

    when (oauth) {
        OAuthState.Waiting -> Hint(stringResource(R.string.settings_oauth_waiting))
        OAuthState.Exchanging -> Hint(stringResource(R.string.settings_oauth_exchanging))
        is OAuthState.Failed -> Hint(oauth.message)
        OAuthState.Idle -> Unit
    }

    // Only the self-hosted entry has capabilities nobody can know in advance.
    if (provider.userEditableCaps) {
        SettingToggle(stringResource(R.string.settings_local_vision), config.localVision) { v ->
            onUpdate { it.copy(localVision = v) }
        }
        SettingToggle(stringResource(R.string.settings_local_tools), config.localTools) { v ->
            onUpdate { it.copy(localTools = v) }
        }
    }

    TextButton(onClick = { context.openUrl(provider.signupUrl) }) {
        Text(stringResource(R.string.settings_get_key, provider.displayName))
    }
}

/** Which provider and model answer one kind of turn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleSetting(
    role: TaskRole,
    label: String,
    config: BrainConfig,
    models: (String) -> StateFlow<ModelList>,
    onUpdate: ((BrainConfig) -> BrainConfig) -> Unit,
    onLoadModels: (String, Boolean) -> Unit,
) {
    val target = config.targetFor(role)
    val provider = target.provider
    val modelList by remember(provider.id) { models(provider.id) }.collectAsStateWithLifecycle()

    Text(label, style = MaterialTheme.typography.labelLarge)
    ProviderChips(selected = provider.id) { id ->
        onUpdate { cfg ->
            cfg.copy(roles = cfg.roles + (role to Target(id, cfg.modelFor(Providers.byId(id)))))
        }
    }
    ModelPicker(
        label = stringResource(R.string.settings_model_name),
        value = target.model,
        models = modelList,
        onExpand = { onLoadModels(provider.id, false) },
        onRefresh = { onLoadModels(provider.id, true) },
    ) { v -> onUpdate { cfg -> cfg.copy(roles = cfg.roles + (role to Target(provider.id, v))) } }

    // The one routing mistake the user cannot see coming: most providers have no
    // server-side search, and the assistant is told to say so rather than answer
    // a question about today from training data.
    if (role == TaskRole.FAST && !provider.webSearch) {
        Hint(stringResource(R.string.settings_routing_no_search))
    }
}

/**
 * The two grants Cicero needs but never used to ask for: reminders were
 * scheduled against a POST_NOTIFICATIONS permission that was declared and never
 * requested, so they fired silently into nothing.
 */
@Composable
private fun PermissionsSection() {
    val context = LocalContext.current

    val notificationsGranted = rememberSystemFlag {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsGranted.value = it }

    val notificationAccess = rememberSystemFlag { context.hasNotificationAccess() }

    val isAssistant = rememberSystemFlag { context.isDefaultAssistant() }
    // The result is ignored: the role dialog reports what the user chose, but
    // rememberSystemFlag re-reads the real state on resume either way.
    val assistantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isAssistant.value = context.isDefaultAssistant() }

    PermissionCard(
        title = stringResource(R.string.perm_notifications_title),
        body = stringResource(R.string.perm_notifications_body),
        granted = notificationsGranted.value,
        actionLabel = stringResource(R.string.perm_notifications_action),
        onAction = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )

    PermissionCard(
        title = stringResource(R.string.perm_listener_title),
        body = stringResource(R.string.perm_listener_body),
        granted = notificationAccess.value,
        actionLabel = stringResource(R.string.perm_listener_action),
        onAction = { context.openNotificationAccessSettings() },
    )

    PermissionCard(
        title = stringResource(R.string.perm_assistant_title),
        body = stringResource(R.string.perm_assistant_body),
        granted = isAssistant.value,
        actionLabel = stringResource(R.string.perm_assistant_action),
        onAction = {
            // Not every build grants this role from a dialog; the ones that
            // refuse expect the user to pick it in Settings.
            val request = context.assistantRoleRequest()
            if (request != null) assistantLauncher.launch(request)
            else context.openAssistantSettings()
        },
    )
}

/** Success and failure have to be tellable apart at a glance, not by reading. */
@Composable
private fun TestResultRow(result: TestResult) {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        // Top, not centre: a failure message can run to several lines, and a
        // vertically-centred icon then floats away from the first word.
        verticalAlignment = Alignment.Top,
    ) {
        when (result) {
            TestResult.Running -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )

            is TestResult.Ok -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp),
            )

            is TestResult.Failed -> Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = when (result) {
                TestResult.Running -> stringResource(R.string.settings_testing)
                is TestResult.Ok -> result.detail
                is TestResult.Failed -> result.message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (result is TestResult.Failed) scheme.error else scheme.onSurface,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@StringRes
private fun localUrlModeLabel(mode: LocalUrlMode): Int = when (mode) {
    LocalUrlMode.AUTO -> R.string.settings_local_url_auto
    LocalUrlMode.LAN -> R.string.settings_local_url_lan
    LocalUrlMode.TAILSCALE -> R.string.settings_local_url_tailscale
}

/** The labels are one word each, so the hint is where each mode is spelled out. */
@StringRes
private fun localUrlModeHint(mode: LocalUrlMode): Int = when (mode) {
    LocalUrlMode.AUTO -> R.string.settings_local_url_auto_hint
    LocalUrlMode.LAN -> R.string.settings_local_url_lan_hint
    LocalUrlMode.TAILSCALE -> R.string.settings_local_url_tailscale_hint
}

@StringRes
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
