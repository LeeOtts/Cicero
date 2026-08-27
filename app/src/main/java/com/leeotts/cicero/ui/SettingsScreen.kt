package com.leeotts.cicero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.leeotts.cicero.R
import com.leeotts.cicero.TestResult
import com.leeotts.cicero.ai.BrainChoice
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.ai.ModelList
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
import com.leeotts.cicero.util.hasNotificationAccess
import com.leeotts.cicero.util.isGranted
import com.leeotts.cicero.util.openNotificationAccessSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: BrainConfig,
    busy: Boolean,
    testResult: TestResult?,
    localModels: ModelList,
    whisperModels: ModelList,
    onUpdate: ((BrainConfig) -> BrainConfig) -> Unit,
    onTest: () -> Unit,
    onLoadLocalModels: (Boolean) -> Unit,
    onLoadWhisperModels: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Warmed as each section appears, so the first tap on a picker shows names
    // rather than a spinner.
    LaunchedEffect(config.choice) {
        if (config.choice == BrainChoice.LOCAL) onLoadLocalModels(false)
        if (config.choice != BrainChoice.GEMINI) onLoadWhisperModels(false)
    }

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
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            BrainChoice.entries.forEach { choice ->
                FilterChip(
                    selected = config.choice == choice,
                    onClick = { onUpdate { it.copy(choice = choice) } },
                    label = { Text(stringResource(choice.label)) },
                )
            }
        }

        when (config.choice) {
            BrainChoice.GEMINI -> {
                SettingField(stringResource(R.string.settings_gemini_model), config.geminiModel) { v ->
                    onUpdate { it.copy(geminiModel = v) }
                }
                SecretField(stringResource(R.string.settings_gemini_key), config.geminiKey) { v ->
                    onUpdate { it.copy(geminiKey = v) }
                }
                Hint(stringResource(R.string.settings_gemini_hint))
            }

            BrainChoice.LOCAL -> {
                SettingField(stringResource(R.string.settings_server_url), config.localBaseUrl) { v ->
                    onUpdate { it.copy(localBaseUrl = v) }
                }
                ModelPicker(
                    label = stringResource(R.string.settings_model_name),
                    value = config.localModel,
                    models = localModels,
                    onExpand = { onLoadLocalModels(false) },
                    onRefresh = { onLoadLocalModels(true) },
                ) { v -> onUpdate { it.copy(localModel = v) } }
                SecretField(stringResource(R.string.settings_api_key_optional), config.localKey) { v ->
                    onUpdate { it.copy(localKey = v) }
                }
                SettingToggle(stringResource(R.string.settings_local_vision), config.localVision) { v ->
                    onUpdate { it.copy(localVision = v) }
                }
                SettingToggle(stringResource(R.string.settings_local_tools), config.localTools) { v ->
                    onUpdate { it.copy(localTools = v) }
                }
                Hint(stringResource(R.string.settings_local_hint))
            }

            BrainChoice.CLAUDE -> {
                SettingField(stringResource(R.string.settings_claude_model), config.claudeModel) { v ->
                    onUpdate { it.copy(claudeModel = v) }
                }
                SecretField(stringResource(R.string.settings_claude_key), config.claudeKey) { v ->
                    onUpdate { it.copy(claudeKey = v) }
                }
            }
        }

        if (config.choice != BrainChoice.GEMINI) {
            SectionHeader(stringResource(R.string.settings_speech_to_text))
            Hint(stringResource(R.string.settings_speech_hint))
            if (config.choice == BrainChoice.LOCAL) {
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

        SectionHeader(stringResource(R.string.settings_permissions))
        PermissionsSection()
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
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
