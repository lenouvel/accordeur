package com.blenouvel.accordeur.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.ui.theme.TunerTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val RANGE_CENTS = 50f
private const val HALF_SWEEP_DEGREES = 60f

/** Angle (degrés, 0 = 3 h, sens horaire) d'un écart en cents sur l'arc −50…+50. */
private fun angleOf(cents: Float): Float = -90f + cents / RANGE_CENTS * HALF_SWEEP_DEGREES

/**
 * Jauge en arc −50…+50 cents : zone verte = tolérance, graduations tous les 5 cents,
 * aiguille animée (ressort) par-dessus le lissage 1€ du traitement.
 * [cents] null : aucune mesure (aiguille masquée). [holding] : valeur maintenue (aiguille estompée).
 */
@Composable
fun TunerMeter(
    cents: Double?,
    toleranceCents: Int,
    holding: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = TunerTheme.colors
    val target = (cents ?: 0.0).toFloat().coerceIn(-RANGE_CENTS - 4f, RANGE_CENTS + 4f)
    val needle by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "needle",
    )
    val needleColor by animateColorAsState(centsColor(cents, toleranceCents), tween(250), label = "needleColor")
    val needleAlpha by animateFloatAsState(
        targetValue = when {
            cents == null -> 0f
            holding -> 0.35f
            else -> 1f
        },
        animationSpec = tween(300),
        label = "needleAlpha",
    )
    val inTune = cents != null && !holding && abs(cents) <= toleranceCents
    val zoneAlpha by animateFloatAsState(if (inTune) 0.9f else 0.28f, tween(250), label = "zoneAlpha")

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val labels = remember(textMeasurer, labelStyle) {
        listOf(-50, -25, 0, 25, 50).map { value ->
            val text = when {
                value > 0 -> "+$value"
                value < 0 -> "−${-value}"
                else -> "0"
            }
            value to textMeasurer.measure(text, labelStyle)
        }
    }
    val pivotInner = MaterialTheme.colorScheme.background
    val description = cents?.let { "${formatCents(it)} cents" } ?: ""

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .semantics { contentDescription = description },
    ) {
        val trackWidth = 10.dp.toPx()
        val labelGap = 22.dp.toPx()
        val center = Offset(size.width / 2f, size.height - 14.dp.toPx())
        val radius = min(size.width * 0.44f, center.y - labelGap - 12.dp.toPx())
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        // Piste.
        drawArc(
            color = colors.track,
            startAngle = angleOf(-RANGE_CENTS),
            sweepAngle = 2f * HALF_SWEEP_DEGREES,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = trackWidth, cap = StrokeCap.Round),
        )
        // Zone « juste ».
        val tolerance = toleranceCents.toFloat().coerceAtMost(RANGE_CENTS)
        drawArc(
            color = colors.inTune.copy(alpha = zoneAlpha),
            startAngle = angleOf(-tolerance),
            sweepAngle = angleOf(tolerance) - angleOf(-tolerance),
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = trackWidth, cap = StrokeCap.Round),
        )

        // Graduations tous les 5 cents, plus longues tous les 25.
        val tickOuter = radius - trackWidth / 2f - 6.dp.toPx()
        for (c in -50..50 step 5) {
            val major = c % 25 == 0
            val length = if (major) 14.dp.toPx() else 7.dp.toPx()
            val a = Math.toRadians(angleOf(c.toFloat()).toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(
                color = if (c == 0) colors.inTune else colors.tick,
                start = center + dir * (tickOuter - length),
                end = center + dir * tickOuter,
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        // Libellés.
        for ((value, layout) in labels) {
            val a = Math.toRadians(angleOf(value.toFloat()).toDouble())
            val p = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * (radius + labelGap)
            drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }

        // Aiguille.
        if (needleAlpha > 0.01f) {
            val a = Math.toRadians(angleOf(needle).toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(
                color = needleColor.copy(alpha = needleAlpha),
                start = center,
                end = center + dir * (radius - trackWidth - 4.dp.toPx()),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = needleColor.copy(alpha = maxOf(needleAlpha, 0.35f)), radius = 9.dp.toPx(), center = center)
        drawCircle(color = pivotInner, radius = 3.5.dp.toPx(), center = center)
    }
}
