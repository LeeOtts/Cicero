package com.leeotts.cicero.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.leeotts.cicero.R

/*
 * Three registers:
 *   Display  Cinzel        Roman inscriptional capitals — the classical half.
 *   Body     IBM Plex Sans neutral and legible; carries all reading text.
 *   Technical IBM Plex Mono model ids, backends, timestamps — the machine half.
 *
 * Cinzel is unreadable below ~20sp, so it is confined to display sizes and the
 * wordmark. It never appears in body, labels, or the app bar title.
 */

val DisplayFamily = FontFamily(
    Font(R.font.cinzel_regular, FontWeight.Normal),
    Font(R.font.cinzel_bold, FontWeight.Bold),
)

val BodyFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val TechnicalFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
)

/**
 * Mono, for content that *is* machine output — model ids, backend names,
 * timestamps. A semantic choice about the text, not a size role, so it sits
 * outside [CiceroTypography] and is applied at the call site.
 */
val TechnicalStyle: TextStyle = TextStyle(
    fontFamily = TechnicalFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.4.sp,
)

private val base = Typography()

val CiceroTypography = Typography(
    // ----- Cinzel: wordmark and empty-state titles only -----
    displayLarge = base.displayLarge.copy(fontFamily = DisplayFamily, letterSpacing = 4.sp),
    displayMedium = base.displayMedium.copy(fontFamily = DisplayFamily, letterSpacing = 3.sp),
    displaySmall = base.displaySmall.copy(fontFamily = DisplayFamily, letterSpacing = 2.sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = DisplayFamily, letterSpacing = 1.5.sp),

    // ----- Plex Sans: everything that gets read -----
    headlineMedium = base.headlineMedium.copy(fontFamily = BodyFamily),
    headlineSmall = base.headlineSmall.copy(fontFamily = BodyFamily),
    titleLarge = base.titleLarge.copy(fontFamily = BodyFamily),
    titleMedium = base.titleMedium.copy(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = BodyFamily, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = BodyFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = BodyFamily, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(fontFamily = BodyFamily),
    labelLarge = base.labelLarge.copy(fontFamily = BodyFamily, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = BodyFamily),
    labelSmall = base.labelSmall.copy(fontFamily = BodyFamily),
)
