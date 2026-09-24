package com.blenouvel.accordeur.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.ui.theme.TunerTheme
import java.util.Locale
import kotlin.math.abs

/** Fréquence au format local : « 82,41 ». */
fun formatHz(frequency: Double): String = String.format(Locale.getDefault(), "%.2f", frequency)

/** La de référence : « 440 » ou « 440,5 ». */
fun formatA4(a4: Double): String =
    if (a4 % 1.0 == 0.0) a4.toInt().toString() else String.format(Locale.getDefault(), "%.1f", a4)

/** Écart en cents signé, avec un vrai signe moins : « +2,4 », « −12 », « 0,0 ». */
fun formatCents(cents: Double): String {
    val magnitude = abs(cents)
    val body = if (magnitude < 10.0) String.format(Locale.getDefault(), "%.1f", magnitude) else "%d".format(magnitude.toInt())
    return when {
        body.all { it == '0' || it == ',' || it == '.' } -> body
        cents > 0 -> "+$body"
        else -> "−$body"
    }
}

/** Nom de note + octave en plus petit : « Mi₂ » (« Mi2 »). */
fun noteWithOctave(note: Note, notation: Notation, octaveSize: TextUnit): AnnotatedString = buildAnnotatedString {
    append(NoteNames.primary(note.pitchClass, notation))
    withStyle(SpanStyle(fontSize = octaveSize)) { append(note.octave.toString()) }
}

/** Couleur sémantique selon l'écart : vert (dans la tolérance), ambre (proche), rouge (loin). */
@Composable
fun centsColor(cents: Double?, tolerance: Int): Color {
    val colors = TunerTheme.colors
    return when {
        cents == null -> colors.tick
        abs(cents) <= tolerance -> colors.inTune
        abs(cents) <= 3 * tolerance.coerceAtLeast(5) -> colors.warning
        else -> colors.offPitch
    }
}

/** « Parfait » sous ce seuil. */
fun isPerfect(cents: Double): Boolean = abs(cents) <= Settings.PERFECT_CENTS
