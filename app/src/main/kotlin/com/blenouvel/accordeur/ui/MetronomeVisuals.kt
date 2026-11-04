package com.blenouvel.accordeur.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.audio.MetronomeTick
import com.blenouvel.accordeur.model.TempoGrader
import com.blenouvel.accordeur.model.TempoVisual
import com.blenouvel.accordeur.model.TimingReading
import com.blenouvel.accordeur.model.TimingVerdict
import com.blenouvel.accordeur.ui.theme.TunerTheme
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rangée de points, un par temps : le temps courant s'allume (plus gros), le 1er temps en couleur
 * d'accent quand l'accent est actif. Au repos, tout est atténué.
 */
@Composable
fun BeatDots(tick: MetronomeTick?, beatsPerMeasure: Int, accentFirst: Boolean, running: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val idle = TunerTheme.colors.tick
    val active = scheme.onBackground
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until beatsPerMeasure.coerceAtLeast(1)) {
            val lit = running && tick != null && tick.beatIndex == i
            val isAccent = i == 0 && accentFirst
            val color = when {
                lit && isAccent -> accent
                lit -> active
                isAccent -> accent.copy(alpha = 0.35f)
                else -> idle
            }
            val diameter by animateFloatAsState(
                targetValue = if (lit) 18f else 11f,
                animationSpec = tween(durationMillis = 90),
                label = "dot",
            )
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(color = color, radius = (diameter / 2f).dp.toPx())
                }
            }
        }
    }
}

// --- Suivi du tempo au micro -------------------------------------------------------------------

/** Plage d'écart affichée de part et d'autre du zéro (ms). */
private const val RANGE_MS = 70f

/** Couleur reflétant le verdict rythmique (gris au repos, vert dans le tempo, ambre hors tempo). */
@Composable
private fun verdictColor(reading: TimingReading?, active: Boolean): Color {
    val colors = TunerTheme.colors
    return when {
        !active || reading == null -> colors.tick
        reading.verdict == TimingVerdict.ON_TIME -> colors.inTune
        reading.verdict == TimingVerdict.RUSHING || reading.verdict == TimingVerdict.DRAGGING -> colors.warning
        else -> colors.tick
    }
}

private fun hasData(reading: TimingReading?, active: Boolean): Boolean =
    active && reading != null && reading.verdict != TimingVerdict.NONE && !reading.meanDeviationMs.isNaN()

/**
 * Bloc de retour du suivi de tempo : l'animation choisie ([visual]) puis une légende (verdict +
 * régularité). Les animations tournent avec le métronome ([running]) ; la couleur ne reflète le
 * verdict que lorsque le suivi est actif ([active] = métronome lancé **et** écoute activée).
 */
@Composable
fun TempoFeedback(
    visual: TempoVisual,
    reading: TimingReading?,
    active: Boolean,
    tick: MetronomeTick?,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when (visual) {
                TempoVisual.METER -> TempoMeter(reading, active, Modifier.fillMaxSize())
                TempoVisual.HALO -> TempoHalo(reading, active, tick, running, Modifier.fillMaxSize())
                TempoVisual.RING -> TempoRing(reading, active, tick, running, Modifier.fillMaxSize())
            }
        }
        TempoCaption(reading, active)
    }
}

/**
 * Vumètre horizontal du niveau d'entrée micro (crête, 0..1), avec un repère à 95 % et un voyant
 * d'écrêtage. Collecte le niveau lui-même pour ne rafraîchir que ce composant (pas tout l'écran).
 */
@Composable
fun VuMeter(level: StateFlow<Float>, modifier: Modifier = Modifier) {
    val lvl by level.collectAsStateWithLifecycle()
    val colors = TunerTheme.colors
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val clipping = lvl >= 0.98f
    val fill = when {
        lvl >= 0.95f -> colors.offPitch
        lvl >= 0.85f -> colors.warning
        else -> colors.inTune
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.metro_input_level),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            if (clipping) {
                Text(stringResource(R.string.metro_input_clip), style = MaterialTheme.typography.labelSmall, color = colors.offPitch)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val r = size.height / 2f
            drawRoundRect(colors.track, cornerRadius = CornerRadius(r, r))
            val w = size.width * lvl.coerceIn(0f, 1f)
            if (w > 0f) {
                drawRoundRect(fill, size = Size(max(w, size.height), size.height), cornerRadius = CornerRadius(r, r))
            }
            val x = size.width * 0.95f // seuil d'écrêtage
            drawLine(scheme.onSurface, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}

/** Verdict (grand) + régularité (petit). */
@Composable
private fun TempoCaption(reading: TimingReading?, active: Boolean) {
    val colors = TunerTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (text, color) = when {
        !active -> "" to muted
        reading == null || reading.verdict == TimingVerdict.NONE -> stringResource(R.string.tempo_waiting) to muted
        reading.verdict == TimingVerdict.ON_TIME -> stringResource(R.string.tempo_on_time) to colors.inTune
        reading.verdict == TimingVerdict.RUSHING -> stringResource(R.string.tempo_rushing) to colors.warning
        else -> stringResource(R.string.tempo_dragging) to colors.warning
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
        if (active && reading != null && !reading.spreadMs.isNaN()) {
            Text(
                text = stringResource(R.string.tempo_spread, reading.spreadMs.roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    }
}

/**
 * Jauge horizontale : zone verte = tolérance au centre, repère de la dernière attaque (atténué) et
 * aiguille de l'écart moyen. À gauche « en avance », à droite « en retard ».
 */
@Composable
private fun TempoMeter(reading: TimingReading?, active: Boolean, modifier: Modifier = Modifier) {
    val colors = TunerTheme.colors
    val background = MaterialTheme.colorScheme.background
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val live = hasData(reading, active)
    val onTime = reading?.verdict == TimingVerdict.ON_TIME

    val meanNorm = ((reading?.meanDeviationMs ?: 0.0).toFloat() / RANGE_MS).coerceIn(-1f, 1f)
    val lastNorm = ((reading?.lastDeviationMs ?: 0.0).toFloat() / RANGE_MS).coerceIn(-1f, 1f)
    val needle by animateFloatAsState(
        targetValue = if (live) meanNorm else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "meterNeedle",
    )
    val color by animateColorAsState(verdictColor(reading, active), tween(250), label = "meterColor")
    val zoneAlpha by animateFloatAsState(if (onTime) 0.9f else 0.28f, tween(250), label = "meterZone")
    val alpha by animateFloatAsState(if (live) 1f else 0.35f, tween(300), label = "meterAlpha")
    val tol = TempoGrader.DEFAULT_TOLERANCE_MS.toFloat()

    Column(modifier, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val pad = 28.dp.toPx()
            val halfW = cx - pad
            val track = 10.dp.toPx()
            drawLine(colors.track, Offset(cx - halfW, cy), Offset(cx + halfW, cy), track, StrokeCap.Round)
            val zoneHalf = (tol / RANGE_MS) * halfW
            drawLine(colors.inTune.copy(alpha = zoneAlpha), Offset(cx - zoneHalf, cy), Offset(cx + zoneHalf, cy), track, StrokeCap.Round)
            drawLine(colors.inTune, Offset(cx, cy - 15.dp.toPx()), Offset(cx, cy + 15.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            if (live) {
                drawCircle(color.copy(alpha = alpha * 0.3f), 6.dp.toPx(), Offset(cx + lastNorm * halfW, cy))
            }
            val nx = cx + needle * halfW
            drawCircle(color.copy(alpha = maxOf(alpha, 0.35f)), 9.dp.toPx(), Offset(nx, cy))
            drawCircle(background, 3.5.dp.toPx(), Offset(nx, cy))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.tempo_early), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(
                stringResource(R.string.tempo_late),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * Halo circulaire : un disque qui pulse à chaque temps (saut d'échelle qui retombe sur l'intervalle),
 * teinté selon la justesse rythmique, et décalé horizontalement selon l'écart moyen (gauche = en
 * avance, droite = en retard). Un halo diffus l'entoure, plus franc quand on est dans le tempo.
 */
@Composable
private fun TempoHalo(reading: TimingReading?, active: Boolean, tick: MetronomeTick?, running: Boolean, modifier: Modifier = Modifier) {
    val live = hasData(reading, active)
    val onTime = reading?.verdict == TimingVerdict.ON_TIME
    val color by animateColorAsState(verdictColor(reading, active), tween(250), label = "haloColor")

    val pulse = remember { Animatable(0f) }
    val beatKey = if (running && tick != null) tick.measure * 100 + tick.beatIndex + if (tick.countIn) 1 else 0 else -1
    LaunchedEffect(beatKey) {
        if (running && tick != null && tick.subIndex == 0) {
            val beatMillis = (60_000 / tick.bpm.coerceAtLeast(1)).coerceIn(90, 1500)
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(durationMillis = beatMillis, easing = FastOutSlowInEasing))
        }
    }
    val offset by animateFloatAsState(
        targetValue = if (live) ((reading?.meanDeviationMs ?: 0.0).toFloat() / RANGE_MS).coerceIn(-1f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "haloOffset",
    )
    val baseAlpha by animateFloatAsState(if (live || running) 1f else 0.4f, tween(300), label = "haloAlpha")

    Canvas(modifier) {
        val base = min(size.width, size.height) * 0.16f
        val cx = size.width / 2f + offset * (size.width / 2f - base * 2.2f)
        val cy = size.height / 2f
        val p = pulse.value
        val haloRadius = base * (2.0f + 0.8f * p)
        val haloAlpha = (if (onTime) 0.30f else 0.14f) * (0.35f + 0.65f * p) * baseAlpha
        drawCircle(color.copy(alpha = haloAlpha), haloRadius, Offset(cx, cy))
        drawCircle(color.copy(alpha = 0.18f * baseAlpha), base * 1.5f, Offset(cx, cy))
        drawCircle(color.copy(alpha = baseAlpha), base * (1f + 0.28f * p), Offset(cx, cy))
    }
}

/**
 * Horloge : une main balaie le cercle une fois par mesure (repart du haut à chaque 1er temps), les
 * temps sont des points sur le cercle (le temps courant s'allume), le tout teinté selon la justesse.
 * Au centre, un disque pulse à chaque temps.
 */
@Composable
private fun TempoRing(reading: TimingReading?, active: Boolean, tick: MetronomeTick?, running: Boolean, modifier: Modifier = Modifier) {
    val colors = TunerTheme.colors
    val onTime = reading?.verdict == TimingVerdict.ON_TIME
    val color by animateColorAsState(verdictColor(reading, active), tween(250), label = "ringColor")
    val beats = (tick?.beatsPerMeasure ?: 4).coerceAtLeast(1)
    val currentBeat = if (running) tick?.beatIndex ?: -1 else -1

    var measureSerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick?.serial) {
        if (running && tick != null && tick.beatIndex == 0 && tick.subIndex == 0) measureSerial++
    }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(measureSerial, running) {
        if (running && tick != null) {
            val measureMillis = (60_000.0 / tick.bpm.coerceAtLeast(1) * beats).toInt().coerceIn(300, 20_000)
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis = measureMillis, easing = LinearEasing))
        }
    }
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(tick?.serial) {
        if (running && tick != null && tick.subIndex == 0) {
            val beatMillis = (60_000 / tick.bpm.coerceAtLeast(1)).coerceIn(90, 1500)
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(durationMillis = beatMillis, easing = FastOutSlowInEasing))
        }
    }
    val alpha by animateFloatAsState(if (running) 1f else 0.4f, tween(300), label = "ringAlpha")

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) * 0.38f
        val ringStroke = 6.dp.toPx()
        // Cercle.
        drawCircle(colors.track.copy(alpha = alpha), radius, Offset(cx, cy), style = Stroke(width = ringStroke))
        // Points des temps (12 h = 1er temps, sens horaire).
        for (i in 0 until beats) {
            val a = (-90.0 + 360.0 * i / beats) * Math.PI / 180.0
            val p = Offset(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat())
            val lit = i == currentBeat
            val dotColor = if (lit) color else colors.tick
            drawCircle(dotColor.copy(alpha = alpha), (if (lit) 7.dp else 4.dp).toPx(), p)
        }
        // Main qui balaie.
        if (running) {
            val a = (-90.0 + 360.0 * sweep.value) * Math.PI / 180.0
            val tip = Offset(cx + (radius - ringStroke) * cos(a).toFloat(), cy + (radius - ringStroke) * sin(a).toFloat())
            drawLine(color.copy(alpha = alpha), Offset(cx, cy), tip, 3.dp.toPx(), StrokeCap.Round)
        }
        // Cœur qui pulse.
        val p = pulse.value
        val haloAlpha = (if (onTime) 0.28f else 0.12f) * (0.35f + 0.65f * p) * alpha
        drawCircle(color.copy(alpha = haloAlpha), radius * 0.45f * (0.8f + 0.5f * p), Offset(cx, cy))
        drawCircle(color.copy(alpha = alpha), (10.dp.toPx()) * (1f + 0.3f * p), Offset(cx, cy))
    }
}
