package com.leeotts.cicero.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leeotts.cicero.R
import com.leeotts.cicero.data.Conversation
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.ui.components.GlassesStatusRow
import com.leeotts.cicero.ui.components.Wordmark
import com.leeotts.cicero.ui.theme.Space

@Composable
fun CiceroDrawerContent(
    glassesState: GlassesController.State,
    recents: List<Conversation>,
    isSelected: (Route) -> Boolean,
    onSelect: (Route) -> Unit,
    onOpenThread: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = Space.xl, vertical = Space.lg)) {
            Wordmark()
            GlassesStatusRow(glassesState, Modifier.padding(top = Space.sm))
        }

        HorizontalDivider(Modifier.padding(horizontal = Space.md))

        topLevelDestinations.forEach { destination ->
            NavigationDrawerItem(
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
                selected = isSelected(destination.route),
                onClick = { onSelect(destination.route) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }

        if (recents.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = Space.md, vertical = Space.sm))
            Text(
                text = stringResource(R.string.nav_recent).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.sm),
            )
            recents.forEach { conversation ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = conversation.title
                                ?: stringResource(R.string.untitled_conversation),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    selected = false,
                    onClick = { onOpenThread(conversation.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }

        // Breathing room under the last item, above the gesture area.
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {}
    }
}
