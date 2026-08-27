package com.leeotts.cicero

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/** A one-shot message for the snackbar host in the app shell. */
data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

suspend fun UiMessage.show(host: SnackbarHostState) {
    val result = host.showSnackbar(
        message = text,
        actionLabel = actionLabel,
        withDismissAction = actionLabel == null,
        duration = SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) action?.invoke()
}
