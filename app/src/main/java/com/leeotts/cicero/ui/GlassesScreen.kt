package com.leeotts.cicero.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leeotts.cicero.GlassesViewModel
import com.leeotts.cicero.R
import com.leeotts.cicero.ui.components.GlassesStatusRow
import com.leeotts.cicero.ui.components.PermissionCard
import com.leeotts.cicero.ui.components.SectionCard
import com.leeotts.cicero.ui.components.rememberSystemFlag
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.TechnicalStyle
import com.leeotts.cicero.util.findActivity
import com.leeotts.cicero.util.isGranted
import com.leeotts.cicero.util.openAppDetailsSettings
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission

@Composable
fun GlassesScreen(
    viewModel: GlassesViewModel,
    modifier: Modifier = Modifier,
) {
    val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
    val registration by viewModel.registration.collectAsStateWithLifecycle()
    val cameraGranted by viewModel.cameraGranted.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val photo by viewModel.photo.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val bluetoothGranted = rememberSystemFlag {
        context.isGranted(Manifest.permission.BLUETOOTH_CONNECT)
    }
    // shouldShowRequestPermissionRationale is false both *before* the first ask
    // and *after* a permanent denial, so it only means "permanently denied" once
    // we know we have actually asked.
    var asked by remember { mutableStateOf(false) }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        asked = true
        bluetoothGranted.value = granted
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        Wearables.RequestPermissionContract(),
    ) { result ->
        result.fold(
            onSuccess = viewModel::onCameraPermission,
            onFailure = { error, _ -> viewModel.onPermissionFailure(error.description) },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        PermissionCard(
            title = stringResource(R.string.perm_bluetooth_title),
            body = stringResource(R.string.perm_bluetooth_body),
            granted = bluetoothGranted.value,
            actionLabel = stringResource(R.string.perm_bluetooth_action),
            onAction = {
                val activity = context.findActivity()
                val permanentlyDenied = asked && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                // Once permanently denied the system dialog never appears again,
                // so the app's settings page is the only route left.
                if (permanentlyDenied) {
                    context.openAppDetailsSettings()
                } else {
                    bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
        )

        SectionCard {
            GlassesStatusRow(glassesState)
            Text(
                text = stringResource(
                    R.string.glasses_registration,
                    registration?.toString()
                        ?: stringResource(R.string.glasses_registration_unknown),
                ),
                style = TechnicalStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            onClick = { context.findActivity()?.let(Wearables::startRegistration) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.glasses_connect)) }

        Button(
            onClick = { cameraLauncher.launch(Permission.CAMERA) },
            enabled = !cameraGranted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (cameraGranted) {
                        R.string.glasses_camera_granted
                    } else {
                        R.string.glasses_grant_camera
                    },
                ),
            )
        }

        // Emulator path: fakes registration + permissions, no Meta AI app needed.
        OutlinedButton(
            onClick = { viewModel.enableMock(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.glasses_enable_mock)) }

        Button(
            onClick = viewModel::capture,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (busy) R.string.glasses_capturing else R.string.glasses_take_photo,
                ),
            )
        }

        photo?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.a11y_latest_capture),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
