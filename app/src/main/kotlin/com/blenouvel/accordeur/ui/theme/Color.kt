package com.blenouvel.accordeur.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Palette restreinte : fond quasi noir, une seule couleur d'accent, sémantique vert / ambre / rouge.

private val Accent = Color(0xFF8AB4FF)
private val AccentDark = Color(0xFF2F62D8)

val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1B3A),
    primaryContainer = Color(0xFF1D2A45),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Accent,
    onSecondary = Color(0xFF0B1B3A),
    secondaryContainer = Color(0xFF1D2A45),
    onSecondaryContainer = Color(0xFFD6E2FF),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF0B0D10),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1B1F25),
    onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceContainerLowest = Color(0xFF08090B),
    surfaceContainerLow = Color(0xFF111418),
    surfaceContainer = Color(0xFF15181D),
    surfaceContainerHigh = Color(0xFF1B1F25),
    surfaceContainerHighest = Color(0xFF22272E),
    outline = Color(0xFF3A4048),
    outlineVariant = Color(0xFF262B32),
)

val LightColors = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B1B3A),
    secondary = AccentDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6FF),
    onSecondaryContainer = Color(0xFF0B1B3A),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF111418),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE7EAEF),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEBEEF2),
    surfaceContainerHigh = Color(0xFFE5E8ED),
    surfaceContainerHighest = Color(0xFFDFE3E8),
    outline = Color(0xFFB5BBC3),
    outlineVariant = Color(0xFFD5D9DF),
)

/** Couleurs sémantiques de l'accordeur (indépendantes de Material You). */
@Immutable
data class TunerColors(
    val inTune: Color,
    val warning: Color,
    val offPitch: Color,
    val track: Color,
    val tick: Color,
)

val DarkTunerColors = TunerColors(
    inTune = Color(0xFF3DDC84),
    warning = Color(0xFFFFB020),
    offPitch = Color(0xFFFF5A5F),
    track = Color(0xFF1E232A),
    tick = Color(0xFF5B626B),
)

val LightTunerColors = TunerColors(
    inTune = Color(0xFF1E9E5A),
    warning = Color(0xFFC77800),
    offPitch = Color(0xFFD93A3F),
    track = Color(0xFFE1E5EA),
    tick = Color(0xFF9AA1A9),
)
