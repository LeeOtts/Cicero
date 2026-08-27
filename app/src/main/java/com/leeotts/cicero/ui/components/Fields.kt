package com.leeotts.cicero.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.leeotts.cicero.R
import com.leeotts.cicero.ai.ModelList
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun SettingField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Masked by default, with a reveal — typing a long API key blind is miserable. */
@Composable
fun SecretField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (revealed) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = if (revealed) {
                        stringResource(R.string.a11y_hide_field, label)
                    } else {
                        stringResource(R.string.a11y_show_field, label)
                    },
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun SettingToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A model name that can be picked as well as typed.
 *
 * Model ids are long and exact — one wrong character comes back from the server
 * as "not loaded" — so the endpoint's own list is offered. It stays an editable
 * field on purpose: a server that won't answer /v1/models must not lock out a
 * name the user knows is right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    label: String,
    value: String,
    models: ModelList,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onRefresh: () -> Unit,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val ids = (models as? ModelList.Loaded)?.ids.orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            expanded = open
            // Listed when the list is opened, not on every keystroke in the
            // server URL field above it.
            if (open) onExpand()
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                if (models is ModelList.Loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                }
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // An unreachable server says so here. An empty list instead would
            // read as "this server has no models", which is a different problem.
            val note = when {
                models is ModelList.Failed -> models.message
                models is ModelList.Loading -> stringResource(R.string.settings_models_loading)
                ids.isEmpty() -> stringResource(R.string.settings_models_none)
                else -> null
            }
            if (note != null) {
                DropdownMenuItem(
                    text = { Text(note, style = MaterialTheme.typography.bodySmall) },
                    enabled = false,
                    onClick = {},
                )
            }
            ids.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = {
                        onChange(id)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_models_refresh)) },
                onClick = onRefresh,
            )
        }
    }
}
