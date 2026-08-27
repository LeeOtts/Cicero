package com.leeotts.cicero.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.leeotts.cicero.R
import com.leeotts.cicero.ui.theme.DisplayFamily

/**
 * CICERO, set in Cinzel.
 *
 * Roman inscriptional capitals were cut, not written, so they carry generous
 * letter spacing — tightening this makes the wordmark look like a font choice
 * rather than an inscription.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.wordmark),
        fontFamily = DisplayFamily,
        fontSize = 22.sp,
        letterSpacing = 6.sp,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}
