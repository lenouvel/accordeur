package com.blenouvel.accordeur.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.audio.PolyReading
import com.blenouvel.accordeur.audio.PolyString
import com.blenouvel.accordeur.ui.theme.TunerTheme

/**
 * Mode poly : une colonne par corde (alignée sur la rangée de cordes), +50 cents en haut,
 * −50 en bas, bande verte = tolérance. Pastille pleine = mesure fiable, anneau = approximative
 * (partiels communs avec une autre corde), rien = corde non entendue. Les valeurs sont maintenues :
 * celles de la dernière attaque sont vives, les plus anciennes atténuées.
 */
@Composable
fun PolyMeter(
    stringCount: Int,
    reading: PolyReading?,
    toleranceCents: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        for (i in 0 until stringCount) {
            PolyColumn(
                state = reading?.strings?.getOrNull(i),
                toleranceCents = toleranceCents,
                modifier = Modifier.weight(1f).widthIn(max = 60.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun PolyColumn(state: PolyString?, toleranceCents: Int, modifier: Modifier) {
    val colors = TunerTheme.colors
    val detected = state?.detected == true
    val cents = if (detected) state.cents else null
    val position by animateFloatAsState(
        targetValue = (cents ?: 0.0).toFloat().coerceIn(-50f, 50f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "polyPosition",
    )
    val alpha by animateFloatAsState(
        targetValue = when {
            !detected -> 0f
            state.fresh -> 1f
            else -> HELD_ALPHA
        },
        animationSpec = tween(250),
        label = "polyAlpha",
    )
    val marker = centsColor(cents, toleranceCents)
    val reliable = state?.reliable ?: true

    Canvas(modifier) {
        val pad = 12.dp.toPx()
        val trackWidth = 8.dp.toPx()
        val cx = size.width / 2f
        val half = size.height / 2f - pad
        fun y(c: Float) = size.height / 2f - c / 50f * half

        drawRoundRect(
            color = colors.track,
            topLeft = Offset(cx - trackWidth / 2f, pad),
            size = Size(trackWidth, size.height - 2f * pad),
            cornerRadius = CornerRadius(trackWidth / 2f),
        )
        val tolerance = toleranceCents.toFloat().coerceAtMost(50f)
        drawRoundRect(
            color = colors.inTune.copy(alpha = 0.35f),
            topLeft = Offset(cx - trackWidth / 2f, y(tolerance)),
            size = Size(trackWidth, y(-tolerance) - y(tolerance)),
            cornerRadius = CornerRadius(trackWidth / 2f),
        )
        drawLine(
            color = colors.inTune,
            start = Offset(cx - 12.dp.toPx(), size.height / 2f),
            end = Offset(cx + 12.dp.toPx(), size.height / 2f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (alpha > 0.01f) {
            val center = Offset(cx, y(position))
            if (reliable) {
                drawCircle(color = marker.copy(alpha = alpha), radius = 9.dp.toPx(), center = center)
            } else {
                drawCircle(
                    color = marker.copy(alpha = alpha),
                    radius = 8.dp.toPx(),
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

/** Opacité d'une valeur maintenue d'une attaque précédente. */
internal const val HELD_ALPHA = 0.5f
