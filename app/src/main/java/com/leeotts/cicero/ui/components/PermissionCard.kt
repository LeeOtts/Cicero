package com.leeotts.cicero.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.leeotts.cicero.R
import com.leeotts.cicero.ui.theme.Space

/**
 * Asks for one permission, with the reason stated before the system dialog
 * appears rather than after.
 *
 * Collapses to a single confirmation line once granted, so a fully-set-up screen
 * is not a wall of cards about things that are already fine.
 */
@Composable
fun PermissionCard(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (granted) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.perm_granted, title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    SectionCard(modifier = modifier, title = title) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/**
 * A system flag Compose cannot observe - a runtime permission, a settings
 * toggle. Re-read on resume, because the user may have changed it in Settings
 * while we were in the background.
 */
@Composable
fun rememberSystemFlag(check: () -> Boolean): MutableState<Boolean> {
    val state = remember { mutableStateOf(check()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.value = check() }
    return state
}
