package com.leeotts.cicero.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Modern Roman — classical marble meets glowing node network.
 *
 * Three brand colours anchor both schemes:
 *   Imperial Purple  #4B0082   the action colour
 *   Electric Cobalt  #2A00D0   accent and status glow
 *   Warm Off-White   #F5F5F0   the marble
 *
 * Light is marble: off-white ground, purple ink.
 * Dark is the same bust at night: the purple becomes a container at full
 * strength, lightened tints become the glow, and the warm off-white carries
 * through as the text colour so the marble never leaves.
 *
 * Cobalt is deliberately reserved for small areas — the status node in
 * light mode, focus rings, the icon gradient. At large fill sizes it reads heavy
 * and fringes on OLED, so the dark scheme tempers its secondaryContainer rather
 * than using the raw brand hex. Purple owns every button and selected item.
 */

// ----- brand constants, referenced by the icon and the node motif -----
val ImperialPurple = Color(0xFF4B0082)
val ElectricCobalt = Color(0xFF2A00D0)
val WarmOffWhite = Color(0xFFF5F5F0)

val CiceroLight = lightColorScheme(
    primary = ImperialPurple,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF1E0040),

    secondary = ElectricCobalt,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1DFFF),
    onSecondaryContainer = Color(0xFF0F0066),

    // Warm bronze — the patina note that keeps the marble from reading cold.
    tertiary = Color(0xFF6E5D3E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E8C4),
    onTertiaryContainer = Color(0xFF251A00),

    background = WarmOffWhite,
    onBackground = Color(0xFF1B1B18),
    surface = WarmOffWhite,
    onSurface = Color(0xFF1B1B18),
    surfaceVariant = Color(0xFFE6E3DB),
    onSurfaceVariant = Color(0xFF48463F),

    // Cards sit *darker* than the ground, so elevation reads without shadow.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1EFE8),
    surfaceContainer = Color(0xFFEDEBE4),
    surfaceContainerHigh = Color(0xFFE7E5DD),
    surfaceContainerHighest = Color(0xFFE1DFD7),

    outline = Color(0xFF79766D),
    outlineVariant = Color(0xFFCAC7BE),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    inverseSurface = Color(0xFF302F2B),
    inverseOnSurface = WarmOffWhite,
    inversePrimary = Color(0xFFCBA9FF),
    scrim = Color(0xFF000000),
)

val CiceroDark = darkColorScheme(
    primary = Color(0xFFCBA9FF),
    onPrimary = Color(0xFF33005C),
    primaryContainer = ImperialPurple,
    onPrimaryContainer = Color(0xFFEADDFF),

    secondary = Color(0xFFB3B0FF),
    onSecondary = Color(0xFF1A0080),
    // Tempered, not the raw brand hex: as a chip or segmented-button fill,
    // #2A00D0 glares against the near-black ground.
    secondaryContainer = Color(0xFF2E1B7D),
    onSecondaryContainer = Color(0xFFE1DFFF),

    tertiary = Color(0xFFDAC38F),
    onTertiary = Color(0xFF3D2F0A),
    tertiaryContainer = Color(0xFF55461E),
    onTertiaryContainer = Color(0xFFF7E8C4),

    background = Color(0xFF121016),
    onBackground = WarmOffWhite,
    surface = Color(0xFF121016),
    onSurface = WarmOffWhite,
    surfaceVariant = Color(0xFF48454F),
    onSurfaceVariant = Color(0xFFCAC4CF),

    surfaceContainerLowest = Color(0xFF0D0B11),
    surfaceContainerLow = Color(0xFF1A171F),
    surfaceContainer = Color(0xFF1D1A23),
    surfaceContainerHigh = Color(0xFF272430),
    surfaceContainerHighest = Color(0xFF322E3B),

    outline = Color(0xFF948F9A),
    outlineVariant = Color(0xFF48454F),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    inverseSurface = WarmOffWhite,
    inverseOnSurface = Color(0xFF302F2B),
    inversePrimary = ImperialPurple,
    scrim = Color(0xFF000000),
)
