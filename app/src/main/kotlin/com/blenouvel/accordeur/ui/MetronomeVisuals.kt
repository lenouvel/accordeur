package com.blenouvel.accordeur.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.audio.MetronomeTick
import com.blenouvel.accordeur.ui.theme.TunerTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Angle d'oscillation maximal du balancier (radians). */
private const val MAX_ANGLE = 0.5f

/**
 * Oscillation d'un vrai métronome : mouvement sinusoïdal (lent aux extrêmes, rapide au centre). La
 * tige atteint sa position extrême juste au moment du clic, vitesse nulle, comme un balancier.
 */
private val PendulumEasing = Easing { f -> ((1.0 - cos(PI * f)) / 2.0).toFloat() }

/**
 * Balancier type métronome mécanique : une demi-oscillation par temps (change de côté à chaque
 * temps, hors subdivisions). Au repos, la tige revient au centre.
 */
@Composable
fun MetronomePendulum(tick: MetronomeTick?, running: Boolean, bpm: Int, modifier: Modifier = Modifier) {
    var side by remember { mutableStateOf(false) }
    LaunchedEffect(tick?.serial) {
        if (running && tick != null && tick.subIndex == 0) side = !side
    }
    val target = when {
        !running -> 0f
        side -> MAX_ANGLE
        else -> -MAX_ANGLE
    }
    val beatMillis = (60_000 / bpm.coerceAtLeast(1)).coerceIn(80, 6_000)
    val angle by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (running) beatMillis else 300, easing = PendulumEasing),
        label = "pendulum",
    )

    val rod = TunerTheme.colors.tick
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val pivot = Offset(size.width / 2f, size.height * 0.94f)
        val length = size.height * 0.82f
        val end = Offset(
            x = pivot.x + length * sin(angle),
            y = pivot.y - length * cos(angle),
        )
        drawLine(
            color = rod,
            start = pivot,
            end = end,
            strokeWidth = 6f,
            cap = StrokeCap.Round,
        )
        drawCircle(color = accent, radius = size.height * 0.05f, center = end)
        drawCircle(color = rod, radius = 7f, center = pivot)
    }
}

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
