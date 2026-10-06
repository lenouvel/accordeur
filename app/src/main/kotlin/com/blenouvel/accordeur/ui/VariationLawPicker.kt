package com.blenouvel.accordeur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.model.VariationLaw

/**
 * Choix de la loi de variation du tempo pendant l'automation : chaque option montre la courbe
 * tempo → temps (droite, convexe, concave, en S, aller-retour, escalier). L'option active est
 * mise en avant.
 */
@Composable
fun VariationLawPicker(selected: VariationLaw, onSelect: (VariationLaw) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (law in VariationLaw.entries) {
            val isSelected = law == selected
            val ink = if (isSelected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
            val description = stringResource(lawLabel(law))
            Surface(
                onClick = { onSelect(law) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Canvas(
                    modifier = Modifier
                        .size(44.dp)
                        .padding(8.dp),
                ) {
                    drawLaw(law, ink)
                }
            }
        }
    }
}

/** Dessine la courbe de la loi [law] (temps en abscisse, tempo en ordonnée). */
private fun DrawScope.drawLaw(law: VariationLaw, color: Color) {
    val x0 = 0f
    val x1 = size.width
    val yBottom = size.height
    val yTop = 0f
    fun px(p: Float) = x0 + p * (x1 - x0)
    fun py(e: Float) = yBottom - e * (yBottom - yTop)

    val path = Path()
    if (law == VariationLaw.STEP) {
        val steps = 4
        path.moveTo(px(0f), py(0f))
        for (i in 0 until steps) {
            val e0 = i / steps.toFloat()
            val e1 = (i + 1) / steps.toFloat()
            val pEnd = (i + 1) / steps.toFloat()
            path.lineTo(px(pEnd), py(e0))
            path.lineTo(px(pEnd), py(e1))
        }
    } else {
        val n = 24
        path.moveTo(px(0f), py(law.ease(0f)))
        for (i in 1..n) {
            val p = i / n.toFloat()
            path.lineTo(px(p), py(law.ease(p)))
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = size.height * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Nom (accessibilité) de la loi de variation. */
fun lawLabel(law: VariationLaw): Int = when (law) {
    VariationLaw.LINEAR -> R.string.law_linear
    VariationLaw.ACCEL -> R.string.law_accel
    VariationLaw.DECEL -> R.string.law_decel
    VariationLaw.SINE -> R.string.law_sine
    VariationLaw.TRIANGLE -> R.string.law_triangle
    VariationLaw.STEP -> R.string.law_step
}
