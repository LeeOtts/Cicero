package com.leeotts.cicero.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.leeotts.cicero.R
import com.leeotts.cicero.ai.BrainConfig
import com.leeotts.cicero.audio.MicSource
import com.leeotts.cicero.audio.WakeService
import com.leeotts.cicero.audio.installedKeyword
import com.leeotts.cicero.audio.keywordFile
import com.leeotts.cicero.ui.components.PermissionCard
import com.leeotts.cicero.ui.components.SecretField
import com.leeotts.cicero.ui.components.rememberSystemFlag
import com.leeotts.cicero.ui.components.SettingToggle
import com.leeotts.cicero.util.isGranted
import kotlin.math.roundToInt

/**
 * The "Hey Cicero" controls.
 *
 * Two things here are not decoration. The battery block is where the feature
 * stops being a novelty and becomes something you can leave on - an open
 * microphone costs 5-10% of the battery an hour, and the glasses gate is what
 * turns that into a cost you only pay while wearing them. And the status line
 * exists because a user who cannot tell whether the app is listening concludes
 * it is broken and turns it off.
 */
@Composable
fun WakeWordSection(
    config: BrainConfig,
    onUpdate: ((BrainConfig) -> BrainConfig) -> Unit,
) {
    val context = LocalContext.current

    val micGranted = rememberSystemFlag { context.isGranted(Manifest.permission.RECORD_AUDIO) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted.value = granted
        // Starting here rather than on the toggle: the service cannot open a
        // microphone it has no permission for, and asking first then starting
        // is the only ordering that works on a fresh install.
        if (granted && config.wakeEnabled) WakeService.start(context)
    }

    val keyword = rememberSystemFlag { installedKeyword(context) != null }
    val importKeyword = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) keyword.value = installKeyword(context, uri)
        // A replaced keyword only takes effect on a fresh engine, and the
        // service rebuilds one whenever the settings change - so nudge them.
        if (keyword.value) onUpdate { it }
    }

    SettingToggle(
        label = stringResource(R.string.settings_wake_enable),
        checked = config.wakeEnabled,
    ) { enabled ->
        onUpdate { it.copy(wakeEnabled = enabled) }
        if (!enabled) {
            WakeService.stop(context)
        } else if (micGranted.value) {
            WakeService.start(context)
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Hint(stringResource(R.string.settings_wake_enable_hint))

    SecretField(
        stringResource(R.string.settings_wake_access_key),
        config.wakeAccessKey,
    ) { v -> onUpdate { it.copy(wakeAccessKey = v) } }
    Hint(stringResource(R.string.settings_wake_access_key_hint))

    Text(
        text = stringResource(
            if (keyword.value) {
                R.string.settings_wake_keyword_installed
            } else {
                R.string.settings_wake_keyword_missing
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (keyword.value) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    TextButton(onClick = { importKeyword.launch(arrayOf("*/*")) }) {
        Text(stringResource(R.string.settings_wake_keyword_import))
    }

    // --- microphone ---------------------------------------------------------

    Text(
        stringResource(R.string.settings_wake_mic),
        style = MaterialTheme.typography.titleSmall,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        MicSource.entries.forEachIndexed { index, source ->
            SegmentedButton(
                selected = config.wakeMic == source,
                onClick = { onUpdate { it.copy(wakeMic = source) } },
                shape = SegmentedButtonDefaults.itemShape(index, MicSource.entries.size),
            ) {
                Text(
                    stringResource(
                        when (source) {
                            MicSource.PHONE -> R.string.settings_wake_mic_phone
                            MicSource.GLASSES -> R.string.settings_wake_mic_glasses
                        },
                    ),
                )
            }
        }
    }
    if (config.wakeMic == MicSource.GLASSES) {
        // Rendered, not buried in a docstring: this choice stops "Hey Meta"
        // working, and the user is entitled to know that before making it.
        Text(
            stringResource(R.string.settings_wake_mic_glasses_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        stringResource(R.string.settings_wake_sensitivity),
        style = MaterialTheme.typography.titleSmall,
    )
    Slider(
        value = config.wakeSensitivity,
        onValueChange = { v -> onUpdate { it.copy(wakeSensitivity = v) } },
        valueRange = 0f..1f,
    )
    Hint(stringResource(R.string.settings_wake_sensitivity_hint))

    // --- battery ------------------------------------------------------------

    Text(
        stringResource(R.string.settings_wake_battery_title),
        style = MaterialTheme.typography.titleSmall,
    )
    SettingToggle(
        label = stringResource(R.string.settings_wake_arm_with_glasses),
        checked = config.wakeArmOnlyWithGlasses,
    ) { v -> onUpdate { it.copy(wakeArmOnlyWithGlasses = v) } }
    Hint(stringResource(R.string.settings_wake_arm_with_glasses_hint))

    Text(
        stringResource(R.string.settings_wake_battery_floor, config.wakeBatteryFloor),
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = config.wakeBatteryFloor.toFloat(),
        onValueChange = { v -> onUpdate { it.copy(wakeBatteryFloor = v.roundToInt()) } },
        valueRange = 0f..50f,
        steps = 9,
    )
    Hint(stringResource(R.string.settings_wake_battery_floor_hint))

    SettingToggle(
        label = stringResource(R.string.settings_wake_unprocessed),
        checked = config.wakeUnprocessedAudio,
    ) { v -> onUpdate { it.copy(wakeUnprocessedAudio = v) } }
    Hint(stringResource(R.string.settings_wake_unprocessed_hint))

    if (!isIgnoringBatteryOptimisations(context)) {
        TextButton(onClick = { requestBatteryExemption(context) }) {
            Text(stringResource(R.string.settings_wake_battery_optimisation))
        }
        Hint(stringResource(R.string.settings_wake_battery_optimisation_hint))
    }

    PermissionCard(
        title = stringResource(R.string.perm_microphone_title),
        body = stringResource(R.string.perm_microphone_body),
        granted = micGranted.value,
        actionLabel = stringResource(R.string.perm_microphone_action),
        onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
    )
}

/** Copies a user-chosen .ppn into the app's own storage. */
private fun installKeyword(context: android.content.Context, uri: Uri): Boolean =
    runCatching {
        val target = keywordFile(context)
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            target.outputStream().use(input::copyTo)
        }
        target.length() > 0
    }.getOrDefault(false)

private fun isIgnoringBatteryOptimisations(context: android.content.Context): Boolean =
    runCatching {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

/**
 * Asks Android to stop managing this app's background execution.
 *
 * The opposite of everything above it, and deliberately last: it is only worth
 * doing when an aggressive OEM keeps killing the service despite its
 * notification.
 */
private fun requestBatteryExemption(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
