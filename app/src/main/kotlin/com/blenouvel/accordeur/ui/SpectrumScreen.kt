package com.blenouvel.accordeur.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.SpectrumUiState
import com.blenouvel.accordeur.audio.SpectrumAnalyzer
import com.blenouvel.accordeur.audio.SpectrumFrame
import com.blenouvel.accordeur.model.ChordNamer
import com.blenouvel.accordeur.model.Harmony
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.ui.theme.NumericStyle
import com.blenouvel.accordeur.ui.theme.OctaveStyle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Page Spectre : le spectre du micro (axe logarithmique, 20 Hz–20 kHz) avec les notes entendues,
 * puis le nom de la note ou de l'accord et sa décomposition par degrés.
 */
@Composable
fun SpectrumScreen(
    state: SpectrumUiState,
    notation: Notation,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                SpectrumView(
                    frame = state.frame,
                    harmony = state.harmony.takeIf { !state.holding },
                    notation = notation,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 8.dp),
                )
                // Coin des aigus (au-delà de 10 kHz) : jamais de note à cet endroit.
                IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            }
            HarmonyPanel(state, notation)
        }
    }
}

// --- Spectre ------------------------------------------------------------------------------------

/** Courbe affichée : rejoint le dernier spectre à chaque image (montée rapide, descente douce). */
@Stable
private class SpectrumMotion {
    val shown = FloatArray(SpectrumAnalyzer.DISPLAY_POINTS) { FLOOR_DB }

    /** Courbe dessinée : pics un peu élargis (lisibles plutôt qu'en aiguilles). */
    val drawn = FloatArray(SpectrumAnalyzer.DISPLAY_POINTS) { FLOOR_DB }
    var target: FloatArray? = null
    var top = MIN_TOP_DB
    var frame by mutableLongStateOf(0L)

    fun step(dt: Float) {
        val goal = target
        val up = 1f - exp(-dt / RISE_S)
        val down = 1f - exp(-dt / FALL_S)
        var max = FLOOR_DB
        for (i in shown.indices) {
            val g = goal?.get(i)?.coerceAtLeast(FLOOR_DB) ?: FLOOR_DB
            shown[i] += (g - shown[i]) * (if (g > shown[i]) up else down)
            if (shown[i] > max) max = shown[i]
        }
        val last = shown.size - 1
        for (i in shown.indices) {
            var v = shown[i]
            if (i > 0) v = maxOf(v, shown[i - 1] - NEAR_DB)
            if (i < last) v = maxOf(v, shown[i + 1] - NEAR_DB)
            if (i > 1) v = maxOf(v, shown[i - 2] - FAR_DB)
            if (i < last - 1) v = maxOf(v, shown[i + 2] - FAR_DB)
            drawn[i] = v
        }
        // Échelle : le haut suit le maximum (vite en montant, lentement en redescendant).
        val wanted = (max + HEADROOM_DB).coerceIn(MIN_TOP_DB, 0f)
        top += (wanted - top) * (if (wanted > top) up else 1f - exp(-dt / SCALE_FALL_S))
        frame++
    }

    /** Niveau affiché à la fréquence [hz] (interpolé entre les points). */
    fun levelAt(hz: Double): Float {
        val x = SpectrumAnalyzer.positionOf(hz) * (SpectrumAnalyzer.DISPLAY_POINTS - 1)
        val i = x.toInt().coerceIn(0, SpectrumAnalyzer.DISPLAY_POINTS - 2)
        val t = (x - i).toFloat().coerceIn(0f, 1f)
        return drawn[i] * (1 - t) + drawn[i + 1] * t
    }

    companion object {
        const val FLOOR_DB = -140f
        const val RANGE_DB = 66f
        const val NEAR_DB = 3f
        const val FAR_DB = 9f
        const val MIN_TOP_DB = -60f
        const val HEADROOM_DB = 6f
        const val RISE_S = 0.04f
        const val FALL_S = 0.18f
        const val SCALE_FALL_S = 1.5f
    }
}

@Composable
private fun SpectrumView(
    frame: SpectrumFrame?,
    harmony: Harmony?,
    notation: Notation,
    modifier: Modifier = Modifier,
) {
    val motion = remember { SpectrumMotion() }
    motion.target = frame?.levels
    LaunchedEffect(motion) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                motion.step(dt)
            }
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 48)
    val colors = MaterialTheme.colorScheme
    val axisStyle = MaterialTheme.typography.labelSmall.merge(NumericStyle).copy(color = colors.onSurfaceVariant)
    val noteStyle = MaterialTheme.typography.labelLarge.copy(color = colors.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
    val partialStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val path = remember { Path() }
    val fill = remember { Path() }
    val notes = frame?.notes.orEmpty()
    val description = stringResource(R.string.cd_spectrum)

    Canvas(modifier.semantics { contentDescription = description }) {
        motion.frame // relecture : redessin à chaque image
        val pillHeight = measurer.measure("La2", noteStyle).size.height + 4.dp.toPx()
        val pillTop = 6.dp.toPx()
        val elbow = pillTop + pillHeight + 14.dp.toPx()
        val axisHeight = 16.dp.toPx()
        // Une rangée d'étiquettes au-dessus de la courbe, reliées à leur note par un trait coudé.
        val plot = Rect(0f, elbow + 4.dp.toPx(), size.width, size.height - axisHeight)
        fun x(hz: Double) = (SpectrumAnalyzer.positionOf(hz) * size.width).toFloat()
        fun y(db: Float) = plot.top + ((motion.top - db) / SpectrumMotion.RANGE_DB).coerceIn(0f, 1f) * plot.height

        // Grille : fréquences repères, lignes de niveau tous les 20 dB.
        for ((hz, label) in GRID) {
            val gx = x(hz)
            drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(gx, plot.top), Offset(gx, plot.bottom), 1f)
            if (label != null) {
                val text = measurer.measure(label, axisStyle)
                val lx = (gx - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, topLeft = Offset(lx, plot.bottom + 2.dp.toPx()))
            }
        }
        var level = (motion.top / 20f).toInt() * 20f
        while (level > motion.top - SpectrumMotion.RANGE_DB) {
            val ly = y(level)
            drawLine(colors.outlineVariant.copy(alpha = 0.25f), Offset(0f, ly), Offset(size.width, ly), 1f)
            level -= 20f
        }

        // Courbe et remplissage.
        path.reset()
        fill.reset()
        val count = SpectrumAnalyzer.DISPLAY_POINTS
        for (i in 0 until count) {
            val px = i * size.width / (count - 1)
            val py = y(motion.drawn[i])
            if (i == 0) {
                path.moveTo(px, py)
                fill.moveTo(px, plot.bottom)
            }
            path.lineTo(px, py)
            fill.lineTo(px, py)
        }
        fill.lineTo(size.width, plot.bottom)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.32f), colors.primary.copy(alpha = 0.03f)), plot.top, plot.bottom))
        drawPath(path, colors.primary, style = Stroke(width = 1.5.dp.toPx()))

        // Notes entendues : étiquettes sur une ligne, écartées juste assez pour ne pas se chevaucher
        // (un accord serré tient en quelques millimètres de l'axe), trait coudé jusqu'à la note.
        val texts = notes.map { measurer.measure(noteLabel(Note.fromMidi(it.midi), notation), noteStyle) }
        val widths = FloatArray(texts.size) { texts[it].size.width + 12.dp.toPx() }
        val centers = FloatArray(notes.size) { x(notes[it].frequency) }
        val lefts = spread(centers, widths, gap = 6.dp.toPx(), limit = size.width - 48.dp.toPx())
        val placed = ArrayList<Rect>()
        val lineColor = colors.primary.copy(alpha = 0.7f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        for (i in notes.indices) {
            val box = Rect(lefts[i], pillTop, lefts[i] + widths[i], pillTop + pillHeight)
            placed += box
            drawLine(lineColor, Offset(box.center.x, box.bottom), Offset(centers[i], elbow), 1.dp.toPx())
            drawLine(lineColor, Offset(centers[i], elbow), Offset(centers[i], y(motion.levelAt(notes[i].frequency))), 1.dp.toPx(), pathEffect = dash)
            drawRoundRect(colors.primaryContainer, box.topLeft, box.size, CornerRadius(pillHeight / 2))
            drawText(texts[i], topLeft = Offset(box.left + 6.dp.toPx(), box.top + 2.dp.toPx()))
        }

        // Corde seule : chaque partiel marquant annoté de sa note (La3, Mi4, Do♯5…).
        val single = notes.singleOrNull()
        if (single != null && harmony?.single == true && frame != null) {
            drawPartials(frame, single.frequency, notation, measurer, partialStyle, colors.background.copy(alpha = 0.75f), motion, placed, ::x, ::y)
        }
    }
}

private fun DrawScope.drawPartials(
    frame: SpectrumFrame,
    fundamental: Double,
    notation: Notation,
    measurer: TextMeasurer,
    style: TextStyle,
    backdrop: Color,
    motion: SpectrumMotion,
    placed: MutableList<Rect>,
    x: (Double) -> Float,
    y: (Float) -> Float,
) {
    var labels = 0
    for (h in 2..MAX_PARTIAL_LABELS + 1) {
        val expected = h * fundamental
        val peak = frame.peaks.firstOrNull { abs(ln(it.frequency / expected)) < PARTIAL_MATCH } ?: continue
        val px = x(peak.frequency)
        val text = measurer.measure(noteLabel(nearestNote(peak.frequency), notation), style)
        val py = y(motion.levelAt(peak.frequency)) - text.size.height - 3.dp.toPx()
        val left = (px - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
        val box = Rect(Offset(left, py.coerceAtLeast(0f)), Size(text.size.width.toFloat(), text.size.height.toFloat()))
        if (placed.any { it.overlaps(box.inflate(2.dp.toPx())) }) continue
        placed += box
        // Fond discret : l'étiquette reste lisible par-dessus les pics voisins.
        val pad = 3.dp.toPx()
        drawRoundRect(backdrop, Offset(box.left - pad, box.top), Size(box.width + 2 * pad, box.height), CornerRadius(pad * 2))
        drawText(text, topLeft = box.topLeft)
        if (++labels == MAX_PARTIAL_LABELS) break
    }
}

/**
 * Positions (bord gauche) d'étiquettes de largeurs [widths] voulues centrées sur [centers] (croissants) :
 * les voisines qui se chevauchent sont écartées symétriquement, dans [0, limit].
 */
private fun spread(centers: FloatArray, widths: FloatArray, gap: Float, limit: Float): FloatArray {
    val n = centers.size
    val lefts = FloatArray(n) { centers[it] - widths[it] / 2 }
    repeat(60) {
        var moved = false
        for (i in 1 until n) {
            val overlap = lefts[i - 1] + widths[i - 1] + gap - lefts[i]
            if (overlap > 0.5f) {
                lefts[i - 1] -= overlap / 2
                lefts[i] += overlap / 2
                moved = true
            }
        }
        for (i in 0 until n) lefts[i] = lefts[i].coerceIn(0f, (limit - widths[i]).coerceAtLeast(0f))
        if (!moved) return lefts
    }
    return lefts
}

private fun nearestNote(hz: Double): Note = Note.fromMidi((69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)).roundToInt())

/** « La2 » (notation choisie ; nom français si « les deux »). */
private fun noteLabel(note: Note, notation: Notation): String = NoteNames.primary(note.pitchClass, notation) + note.octave

/** Repères de l'axe des fréquences (libellé null : ligne seule). */
private val GRID = listOf(
    50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500",
    1000.0 to "1k", 2000.0 to "2k", 5000.0 to "5k", 10000.0 to "10k",
)

private const val MAX_PARTIAL_LABELS = 7
private val PARTIAL_MATCH = ln(2.0) / 24.0 * 0.8

// --- Note ou accord -----------------------------------------------------------------------------

/** Nom de la note ou de l'accord (notation anglaise pour les accords) et décomposition par degrés. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HarmonyPanel(state: SpectrumUiState, notation: Notation) {
    val harmony = state.harmony
    val alpha by animateFloatAsState(if (state.holding) 0.45f else 1f, tween(300), label = "harmonyAlpha")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(PANEL_HEIGHT)
            .padding(top = 8.dp)
            .alpha(alpha),
    ) {
        val chord = harmony?.chord
        when {
            harmony == null -> {
                Text("—", style = TitleStyle, color = MaterialTheme.colorScheme.outline)
                Text(
                    text = stringResource(if (state.signal) R.string.listening else R.string.spectrum_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            harmony.single -> {
                val note = harmony.notes.first()
                Text(noteWithOctave(note, notation, OctaveStyle.fontSize * 0.6f), style = TitleStyle, maxLines = 1)
                val hz = state.frame?.notes?.firstOrNull()?.frequency
                Text(
                    text = if (hz != null && !state.holding) stringResource(R.string.frequency_hz, formatHz(hz)) else " ",
                    style = MaterialTheme.typography.bodyLarge.merge(NumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            chord != null -> {
                Text(chord.symbol, style = TitleStyle, maxLines = 1)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (tone in chord.tones) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = NoteNames.spelled(tone.note, notation),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = tone.degree.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            else -> {
                // Deux notes qui ne forment pas d'accord : les notes et leur intervalle.
                val low = harmony.notes.first()
                val high = harmony.notes.first { it.pitchClass != low.pitchClass }
                Text(
                    text = NoteNames.primary(low.pitchClass, notation) + " – " + NoteNames.primary(high.pitchClass, notation),
                    style = TitleStyle,
                    maxLines = 1,
                )
                Text(
                    text = ChordNamer.intervalName(high.pitchClass - low.pitchClass),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

private val TitleStyle = TextStyle(fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
private val PANEL_HEIGHT = 150.dp
