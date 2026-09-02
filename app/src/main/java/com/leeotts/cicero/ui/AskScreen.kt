package com.leeotts.cicero.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leeotts.cicero.Exchange
import com.leeotts.cicero.R
import com.leeotts.cicero.audio.SpeechRecognizerHelper
import com.leeotts.cicero.ui.components.Bubble
import com.leeotts.cicero.ui.components.EmptyState
import com.leeotts.cicero.ui.components.assistantBubbleStyle
import com.leeotts.cicero.ui.components.rememberSystemFlag
import com.leeotts.cicero.ui.components.userBubbleStyle
import com.leeotts.cicero.CiceroApp
import com.leeotts.cicero.ui.theme.Radius
import com.leeotts.cicero.ui.theme.Space
import com.leeotts.cicero.util.findActivity
import com.leeotts.cicero.util.isGranted
import com.leeotts.cicero.util.openAppDetailsSettings

@Composable
fun AskScreen(
    exchanges: List<Exchange>,
    busy: Boolean,
    speaking: Boolean,
    backendLabel: String,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var question by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Phone-mic voice input - a stopgap for typing until the glasses' own
    // wake-word pipeline lands. Owned here rather than the ViewModel: it only
    // ever produces text for this field, the same as the keyboard does.
    val context = LocalContext.current
    val recognizer = remember { SpeechRecognizerHelper(context) }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }
    val listening by recognizer.listening.collectAsStateWithLifecycle()
    val speechAvailable = remember { recognizer.available }

    val micGranted = rememberSystemFlag { context.isGranted(Manifest.permission.RECORD_AUDIO) }

    // Read as the capture ends rather than as it starts. A capture runs for
    // seconds, and both of these move underneath it: a lambda holding the ones
    // that existed at start() would send through a stale callback.
    val currentOnAsk by rememberUpdatedState(onAsk)
    val currentlyBusy by rememberUpdatedState(busy)

    /**
     * Opens the microphone for one question.
     *
     * [autoSend] belongs to the wake word alone. It fired so the phone could
     * stay in a pocket, and a question left waiting on the send button would
     * defeat the whole point of it. A capture the user started by tapping the
     * mic button is theirs to edit first.
     */
    fun beginListening(autoSend: Boolean = false) {
        recognizer.start(
            onFinal = { text ->
                question = text
                // A question asked while the previous answer is still in flight
                // is dropped silently by ask(). Leave it in the field to be sent
                // by hand rather than swallowing it.
                if (autoSend && !currentlyBusy) {
                    currentOnAsk(text)
                    question = ""
                }
            },
            onText = { text -> question = text },
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted.value = granted
        if (granted) {
            beginListening()
            return@rememberLauncherForActivityResult
        }
        // Two refusals and Android stops showing the dialog at all - it denies
        // instantly and silently, which would make this button look broken.
        // The mic may also have been refused on the Glasses screen already, so
        // this can happen on the very first tap here.
        val activity = context.findActivity()
        val noDialogLeft = activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            )
        if (noDialogLeft) context.openAppDetailsSettings()
    }

    // The other half of the wake-word hand-off. The service releases the
    // microphone, raises the request and launches this screen; picking it up
    // here is what makes the wake word lead into a question rather than just
    // opening the app. Publishing [listening] back is what stops the service
    // reopening the microphone underneath the recognizer.
    val voice = remember(context) { (context.applicationContext as CiceroApp).voice }
    val pendingListen by voice.pendingListen.collectAsStateWithLifecycle()
    LaunchedEffect(listening) { voice.holdMicrophone(listening) }
    DisposableEffect(Unit) { onDispose { voice.holdMicrophone(false) } }
    LaunchedEffect(pendingListen, speechAvailable, micGranted.value) {
        if (!pendingListen || !speechAvailable || !micGranted.value) return@LaunchedEffect
        // Taken, not just read, so a recomposition cannot start a second capture.
        if (voice.takeListenRequest()) beginListening(autoSend = true)
    }

    // Follow the conversation down as it grows, and again when the thinking
    // bubble appears or resolves.
    LaunchedEffect(exchanges.size, busy) {
        val last = exchanges.size - if (busy) 0 else 1
        if (last >= 0) listState.animateScrollToItem(maxOf(last, 0))
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (exchanges.isEmpty() && !busy) {
                EmptyState(
                    title = stringResource(R.string.ask_empty_title),
                    body = stringResource(R.string.ask_empty_body, backendLabel),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.lg),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    itemsIndexed(exchanges) { _, exchange ->
                        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Bubble(
                                text = exchange.question,
                                style = userBubbleStyle(),
                            )
                            Bubble(
                                text = exchange.answer,
                                style = assistantBubbleStyle(isError = exchange.isError),
                                footer = exchange.backend,
                            )
                        }
                    }

                    if (busy) {
                        item { ThinkingBubble() }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .imePadding()
                .padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text(stringResource(R.string.ask_placeholder)) },
                    enabled = !busy,
                    maxLines = 4,
                    shape = RoundedCornerShape(Radius.bubble),
                    modifier = Modifier.weight(1f),
                )
                if (speechAvailable) {
                    FilledTonalIconButton(
                        onClick = {
                            when {
                                listening -> recognizer.cancel()
                                !micGranted.value -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                else -> beginListening()
                            }
                        },
                        // Stopping has to stay possible even once a question is
                        // away, or a live mic becomes unstoppable.
                        enabled = listening || !busy,
                    ) {
                        Icon(
                            if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = stringResource(
                                if (listening) R.string.ask_stop_listening else R.string.ask_start_listening,
                            ),
                        )
                    }
                }
                // Only while there is something to cut off - an answer read
                // through the glasses can run longer than the user wants.
                if (speaking) {
                    FilledTonalIconButton(onClick = onStopSpeaking) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = stringResource(R.string.ask_stop_speaking),
                        )
                    }
                }
                FilledIconButton(
                    // Stop first: a capture left running would transcribe over
                    // the field this very tap is clearing.
                    onClick = { recognizer.cancel(); onAsk(question); question = "" },
                    enabled = !busy && question.isNotBlank(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.ask_send),
                    )
                }
            }
            if (exchanges.isNotEmpty()) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ask_new_thread)) }
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(Radius.bubble),
            )
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(stringResource(R.string.ask_thinking), style = MaterialTheme.typography.bodyMedium)
    }
}
