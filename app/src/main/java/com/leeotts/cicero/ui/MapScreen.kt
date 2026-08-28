package com.leeotts.cicero.ui

import android.Manifest
import android.annotation.SuppressLint
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.leeotts.cicero.BuildConfig
import com.leeotts.cicero.MapViewModel
import com.leeotts.cicero.R
import com.leeotts.cicero.ui.components.PermissionCard
import com.leeotts.cicero.ui.components.SectionCard
import com.leeotts.cicero.ui.components.rememberSystemFlag
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.TechnicalStyle
import com.leeotts.cicero.util.isGranted

/**
 * The in-app map.
 *
 * Deliberately the View-based [MapView] behind [AndroidView] rather than the
 * maps-compose wrapper: maps-compose pulls Compose from the 1.7 this project
 * pins up to 1.9, which would drag navigation and the whole Compose stack with
 * it. The interop below is the price of not doing that, and it is about thirty
 * lines.
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val here by viewModel.here.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    val locationGranted = rememberSystemFlag {
        context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        locationGranted.value = grants.values.any { it }
        if (locationGranted.value) viewModel.refresh()
    }

    LaunchedEffect(locationGranted.value) {
        if (locationGranted.value) viewModel.refresh()
    }

    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        MissingKey(modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
            PermissionCard(
                title = stringResource(R.string.perm_location_title),
                body = stringResource(R.string.perm_location_body),
                granted = locationGranted.value,
                actionLabel = stringResource(R.string.perm_location_action),
                onAction = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Map(
                showMyLocation = locationGranted.value,
                centreOn = destination?.takeIf { it.hasCoordinates }
                    ?.let { LatLng(it.latitude!!, it.longitude!!) }
                    ?: here?.let { LatLng(it.latitude, it.longitude) },
                markerLabel = destination?.label,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = Space.lg).padding(bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = destination?.label?.let {
                    stringResource(R.string.map_last_destination, it)
                } ?: stringResource(R.string.map_no_destination),
                style = TechnicalStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locationGranted.value) {
                OutlinedButton(
                    onClick = viewModel::refresh,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.map_recentre)) }
            }
        }
    }
}

/**
 * The map itself. Recentres and re-pins whenever [centreOn] changes, which is
 * how a destination recorded by a tool reaches the screen.
 */
@SuppressLint("MissingPermission")
@Composable
private fun Map(
    showMyLocation: Boolean,
    centreOn: LatLng?,
    markerLabel: String?,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapView()

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.getMapAsync { map ->
                map.uiSettings.isZoomControlsEnabled = true
                map.uiSettings.isMyLocationButtonEnabled = showMyLocation
                // Guarded by the caller: showMyLocation mirrors the granted flag.
                if (showMyLocation) map.isMyLocationEnabled = true

                map.clear()
                centreOn?.let { position ->
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, DEFAULT_ZOOM))
                    if (markerLabel != null) {
                        map.addMarker(MarkerOptions().position(position).title(markerLabel))
                    }
                }
            }
        },
    )
}

/**
 * MapView predates Compose and wants the full Activity lifecycle forwarded to
 * it, or it renders a grey rectangle. onCreate is called eagerly because the
 * composable can enter after ON_CREATE has already been dispatched; the registry
 * replays the rest to a newly added observer.
 */
@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            id = View.generateViewId()
            onCreate(null)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

/**
 * Without a key the SDK draws a grey grid and logs an authentication failure,
 * which reads as a bug. Say what is actually wrong instead.
 */
@Composable
private fun MissingKey(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md, Alignment.CenterVertically),
    ) {
        SectionCard(title = stringResource(R.string.map_no_key_title)) {
            Text(
                text = stringResource(R.string.map_no_key_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val DEFAULT_ZOOM = 15f
