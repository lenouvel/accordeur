package com.blenouvel.accordeur.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalTunerColors = staticCompositionLocalOf { DarkTunerColors }

/** Vrai si l'appareil propose la couleur dynamique (Material You, Android 12+). */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun AccordeurTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && dynamicColorAvailable -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalTunerColors provides if (darkTheme) DarkTunerColors else LightTunerColors) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}

/** Accès court aux couleurs sémantiques : `TunerTheme.colors.inTune`. */
object TunerTheme {
    val colors: TunerColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTunerColors.current
}
