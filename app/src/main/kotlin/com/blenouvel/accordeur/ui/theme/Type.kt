package com.blenouvel.accordeur.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppTypography = Typography()

/** Grande note centrale. */
val NoteStyle = TextStyle(fontSize = 112.sp, lineHeight = 116.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)

/** Octave accolée à la note. */
val OctaveStyle = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal)

/** Chiffres à chasse fixe (évite que le texte « danse » quand la valeur change). */
val NumericStyle = TextStyle(fontFeatureSettings = "tnum")
