package com.leeotts.cicero.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.leeotts.cicero.R
import com.leeotts.cicero.glasses.GlassesController

/**
 * The glowing node: a filled core inside a soft bloom, carrying the glasses
 * connection state.
 *
 * The meaning is entirely colour and motion, so the row it sits in always
 * carries a text label too — never rely on the dot alone.
 */
@Composable
fun NodeIndicator(
    state: GlassesController.State,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val colour = when (state) {
        GlassesController.State.Idle -> scheme.outlineVariant
        GlassesController.State.Connecting -> scheme.secondary
        GlassesController.State.Ready -> scheme.secondary
        is GlassesController.State.Failed -> scheme.error
    }

    // A dot that pulses forever is exactly what the animation-scale setting
    // exists to stop, so honour it.
    val context = LocalContext.current
    val animationsOn = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

    val pulse = if (state is GlassesController.State.Connecting && animationsOn) {
        val transition = rememberInfiniteTransition(label = "node")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "nodePulse",
        )
        value
    } else {
        1f
    }

    val glowing = state is GlassesController.State.Ready ||
        state is GlassesController.State.Connecting

    Canvas(modifier.size(size)) {
        val centre = center
        val core = this.size.minDimension / 4f

        if (glowing) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colour.copy(alpha = 0.45f * pulse), Color.Transparent),
                    center = centre,
                    radius = this.size.minDimension / 2f,
                ),
                radius = this.size.minDimension / 2f,
                center = centre,
            )
        }

        if (state is GlassesController.State.Idle) {
            drawCircle(color = colour, radius = core, center = centre, style = stroke(1.dp.toPx()))
        } else {
            drawCircle(color = colour.copy(alpha = pulse), radius = core, center = centre)
        }
    }
}

private fun stroke(width: Float) = androidx.compose.ui.graphics.drawscope.Stroke(width = width)

/** The node plus its text label — what the drawer header and app bar actually use. */
@Composable
fun GlassesStatusRow(
    state: GlassesController.State,
    modifier: Modifier = Modifier,
) {
    val label = glassesStatusLabel(state)
    val description = stringResource(R.string.a11y_glasses_status, label)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeIndicator(state)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun glassesStatusLabel(state: GlassesController.State): String = when (state) {
    is GlassesController.State.Failed ->
        stringResource(R.string.glasses_status_failed, state.reason)

    else -> stringResource(statusLabelRes(state))
}

@StringRes
private fun statusLabelRes(state: GlassesController.State): Int = when (state) {
    GlassesController.State.Idle -> R.string.glasses_status_idle
    GlassesController.State.Connecting -> R.string.glasses_status_connecting
    GlassesController.State.Ready -> R.string.glasses_status_ready
    // Handled by the caller; this branch keeps the when exhaustive.
    is GlassesController.State.Failed -> R.string.glasses_status_failed
}
