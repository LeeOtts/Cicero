package com.leeotts.cicero.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leeotts.cicero.MapViewModel
import com.leeotts.cicero.R
import com.leeotts.cicero.ui.components.PermissionCard
import com.leeotts.cicero.ui.components.rememberSystemFlag
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.ui.theme.TechnicalStyle
import com.leeotts.cicero.util.isGranted
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The in-app map.
 *
 * MapLibre against OpenFreeMap tiles, deliberately: no API key, no account, no
 * billing and no request limit, where the Maps SDK for Android needs a Google
 * Cloud project with billing enabled before it will draw anything at all. The
 * navigate and find-nearby tools still hand off to whatever maps app is
 * installed - this screen is Cicero's own view, not a replacement for that.
 *
 * The View-based MapView behind AndroidView is the only option MapLibre offers,
 * which is fine: the interop is the same thirty lines either way.
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
                here = here?.let { LatLng(it.latitude, it.longitude) },
                destination = destination?.takeIf { it.hasCoordinates }
                    ?.let { LatLng(it.latitude!!, it.longitude!!) },
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
 * Two dots and a camera: where the user is, and the last place the assistant was
 * asked about.
 *
 * Circle layers rather than marker annotations - circles are core style API and
 * need no bitmap asset, where the annotation API wants an icon registered with
 * the style and has moved between MapLibre versions.
 */
@Composable
private fun Map(
    here: LatLng?,
    destination: LatLng?,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapView()
    val dark = isSystemInDarkTheme()
    val hereColour = MaterialTheme.colorScheme.primary.toArgb()
    val destinationColour = MaterialTheme.colorScheme.error.toArgb()
    val ringColour = MaterialTheme.colorScheme.surface.toArgb()

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    // Style loading is asynchronous and must happen once, not on every
    // recomposition, so it lives here rather than in AndroidView's update block.
    DisposableEffect(mapView, dark) {
        mapView.getMapAsync { loaded ->
            loaded.setStyle(if (dark) DARK_STYLE else LIGHT_STYLE) { loadedStyle ->
                map = loaded
                style = loadedStyle
            }
        }
        onDispose { }
    }

    LaunchedEffect(style, here, destination) {
        val currentStyle = style ?: return@LaunchedEffect
        currentStyle.plot(HERE_SOURCE, HERE_LAYER, here, hereColour, ringColour)
        currentStyle.plot(DEST_SOURCE, DEST_LAYER, destination, destinationColour, ringColour)

        // The destination is the thing you just asked about, so it wins the
        // camera; falling back to the user's own position when there is none.
        (destination ?: here)?.let {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, DEFAULT_ZOOM))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Adds the source and layer on first use, then just moves the point. */
private fun Style.plot(
    sourceId: String,
    layerId: String,
    at: LatLng?,
    fill: Int,
    ring: Int,
) {
    val existing = getSourceAs<GeoJsonSource>(sourceId)
    if (at == null) {
        existing?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        return
    }

    val point = Point.fromLngLat(at.longitude, at.latitude)
    if (existing != null) {
        existing.setGeoJson(point)
        return
    }

    addSource(GeoJsonSource(sourceId, point))
    addLayer(
        CircleLayer(layerId, sourceId).withProperties(
            PropertyFactory.circleRadius(DOT_RADIUS),
            PropertyFactory.circleColor(fill),
            PropertyFactory.circleStrokeWidth(DOT_RING),
            PropertyFactory.circleStrokeColor(ring),
        ),
    )
}

/**
 * MapView predates Compose and wants the Activity lifecycle forwarded to it, or
 * it renders nothing. onCreate is called eagerly because the composable can
 * enter after ON_CREATE has already been dispatched; the registry replays the
 * rest to a newly added observer.
 */
@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        // Must run before any MapView is constructed. Takes no token: MapLibre
        // has no account model, unlike the Mapbox SDK it forked from.
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
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
 * OpenFreeMap's public instance: no registration, no key, no request limit.
 * Attribution for OpenFreeMap, OpenMapTiles and OpenStreetMap is required and
 * MapLibre renders it from the style automatically.
 */
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

private const val HERE_SOURCE = "cicero-here-source"
private const val HERE_LAYER = "cicero-here-layer"
private const val DEST_SOURCE = "cicero-destination-source"
private const val DEST_LAYER = "cicero-destination-layer"

private const val DEFAULT_ZOOM = 15.0
private const val DOT_RADIUS = 7f
private const val DOT_RING = 3f
