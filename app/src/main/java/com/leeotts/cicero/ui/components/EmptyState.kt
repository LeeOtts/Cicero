package com.leeotts.cicero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.leeotts.cicero.ui.theme.Space

/**
 * Fixed, not random: a re-rolled layout would shimmer on every recomposition.
 * Coordinates are fractions of the canvas.
 */
private val NODES = listOf(
    0.18f to 0.22f,
    0.47f to 0.11f,
    0.78f to 0.28f,
    0.30f to 0.52f,
    0.62f to 0.58f,
    0.14f to 0.80f,
    0.85f to 0.74f,
)

private val EDGES = listOf(0 to 1, 1 to 2, 0 to 3, 3 to 4, 2 to 4, 3 to 5, 4 to 6)

/** The node-network motif, faint, behind an empty screen. */
@Composable
fun NodeNetwork(modifier: Modifier = Modifier) {
    val colour = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val points = NODES.map { (x, y) -> Offset(x * size.width, y * size.height) }
        EDGES.forEach { (a, b) ->
            drawLine(
                color = colour.copy(alpha = 0.12f),
                start = points[a],
                end = points[b],
                strokeWidth = 1.dp.toPx(),
            )
        }
        points.forEach { drawCircle(colour.copy(alpha = 0.18f), radius = 3.dp.toPx(), center = it) }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NodeNetwork(Modifier.matchParentSize())
        Column(
            modifier = Modifier.padding(Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
