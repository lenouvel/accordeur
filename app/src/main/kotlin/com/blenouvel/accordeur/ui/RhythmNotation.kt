package com.blenouvel.accordeur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.model.RhythmCell
import com.blenouvel.accordeur.model.Subdivision

/**
 * Choix du schéma de division du temps : chaque schéma est dessiné en notation musicale (têtes de
 * notes, hampes, ligatures, points, silences, chiffre de n-olet), comme sur une partition. Tous
 * affichés en grille de 4 colonnes sur la largeur ; le schéma courant est mis en avant.
 */
@Composable
fun DivisionPicker(selected: Subdivision, onSelect: (Subdivision) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Subdivision.entries.chunked(COLUMNS).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sub in rowItems) {
                    DivisionCell(sub, sub == selected, measurer, Modifier.weight(1f)) { onSelect(sub) }
                }
                repeat(COLUMNS - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val COLUMNS = 4

@Composable
private fun DivisionCell(
    sub: Subdivision,
    isSelected: Boolean,
    measurer: TextMeasurer,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (isSelected) scheme.onSecondaryContainer else scheme.onSurface
    val description = stringResource(subdivisionLabel(sub))
    val tupletStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = ink)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        modifier = modifier
            .height(46.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            drawDivision(sub, ink, measurer, tupletStyle)
        }
    }
}

private class NotePos(val x: Float, val cell: RhythmCell, val beams: Int, val dotted: Boolean)

/** Dessine un schéma rythmique en notation (un temps, de gauche à droite). */
private fun DrawScope.drawDivision(sub: Subdivision, color: Color, measurer: TextMeasurer, tupletStyle: TextStyle) {
    val w = size.width
    val h = size.height
    val grid = sub.grid.toFloat()
    val noteY = h * 0.74f
    val stemTop = h * (if (sub.tuplet > 0) 0.30f else 0.20f)
    val headRx = h * 0.12f
    val headRy = h * 0.09f
    val stemW = (h * 0.04f).coerceAtLeast(1.5f)
    val beamW = h * 0.10f
    val beamGap = beamW * 1.6f

    val positions = ArrayList<NotePos>(sub.cells.size)
    var startU = 0
    for (c in sub.cells) {
        val x = ((startU + c.units / 2f) / grid) * w
        val (beams, dotted) = glyphOf(c, sub)
        positions.add(NotePos(x, c, beams, dotted))
        startU += c.units
    }
    fun stemX(p: NotePos) = p.x + headRx * 0.85f

    // Têtes de notes, hampes, points ; silences à part.
    for (p in positions) {
        if (p.cell.rest) {
            drawRestGlyph(p.x, noteY, h, p.beams, color)
            continue
        }
        drawOval(
            color = color,
            topLeft = Offset(p.x - headRx, noteY - headRy),
            size = Size(headRx * 2f, headRy * 2f),
        )
        drawLine(color, Offset(stemX(p), noteY - headRy * 0.2f), Offset(stemX(p), stemTop), strokeWidth = stemW)
        if (p.dotted) drawCircle(color, radius = headRy * 0.45f, center = Offset(p.x + headRx * 1.8f, noteY))
    }

    // Dessine un paquet ligaturé (primaire + secondaires), ou un crochet si une seule note.
    fun drawBeamGroup(grp: List<NotePos>) {
        if (grp.isEmpty()) return
        if (grp.size == 1) {
            val x = stemX(grp[0])
            for (b in 0 until grp[0].beams) {
                val y = stemTop + b * beamGap
                drawLine(color, Offset(x, y), Offset(x + w * 0.11f, y + beamW * 1.3f), strokeWidth = beamW * 0.9f)
            }
            return
        }
        val x0 = stemX(grp.first())
        val x1 = stemX(grp.last())
        drawRect(color, topLeft = Offset(x0, stemTop), size = Size(x1 - x0, beamW))
        val y2 = stemTop + beamGap
        var k = 0
        while (k < grp.size) {
            if (grp[k].beams >= 2) {
                var m = k
                while (m + 1 < grp.size && grp[m + 1].beams >= 2) m++
                if (m > k) {
                    drawRect(color, topLeft = Offset(stemX(grp[k]), y2), size = Size(stemX(grp[m]) - stemX(grp[k]), beamW))
                } else {
                    val sx = stemX(grp[k])
                    val stub = w * 0.10f
                    val left = k > 0
                    drawRect(color, topLeft = Offset(if (left) sx - stub else sx, y2), size = Size(stub, beamW))
                }
                k = m + 1
            } else {
                k++
            }
        }
    }

    // Ligatures : on groupe les notes jouées consécutives, découpées par [beamGroup] si demandé.
    var i = 0
    while (i < positions.size) {
        val p = positions[i]
        if (p.cell.rest || p.beams == 0) {
            i++
            continue
        }
        var j = i
        while (j + 1 < positions.size && !positions[j + 1].cell.rest && positions[j + 1].beams >= 1) j++
        val run = positions.subList(i, j + 1)
        val groupSize = if (sub.beamGroup > 0) sub.beamGroup else run.size
        var g = 0
        while (g < run.size) {
            drawBeamGroup(run.subList(g, minOf(g + groupSize, run.size)))
            g += groupSize
        }
        i = j + 1
    }

    // Chiffre du n-olet (3, 5, 6…).
    if (sub.tuplet > 0) {
        val layout = measurer.measure(sub.tuplet.toString(), style = tupletStyle)
        drawText(layout, topLeft = Offset((w - layout.size.width) / 2f, 0f))
    }
}

/** Nombre de ligatures/crochets (0 = noire, 1 = croche, 2 = double) et point éventuel. */
private fun glyphOf(cell: RhythmCell, sub: Subdivision): Pair<Int, Boolean> = when (sub.tuplet) {
    0 -> when (cell.units) {
        4 -> 0 to false // noire
        3 -> 1 to true // croche pointée
        2 -> 1 to false // croche
        else -> 2 to false // double-croche
    }
    3 -> when (cell.units) {
        2, 3 -> 0 to false // noire du triolet
        else -> 1 to false // croche du triolet
    }
    else -> 2 to false // quintolet / sextolet : doubles-croches
}

/** Silence approché (crochet(s) de soupir/demi-soupir), [beams] donnant la valeur. */
private fun DrawScope.drawRestGlyph(cx: Float, noteY: Float, h: Float, beams: Int, color: Color) {
    val r = h * 0.05f
    val count = beams.coerceAtLeast(1)
    for (b in 0 until count) {
        val cy = noteY - h * 0.2f + b * (h * 0.15f)
        drawCircle(color, radius = r, center = Offset(cx - r, cy))
        drawLine(color, Offset(cx, cy - r), Offset(cx - r * 1.6f, cy + r * 2.4f), strokeWidth = h * 0.03f)
    }
}

/** Nom (accessibilité) du schéma de division. */
fun subdivisionLabel(sub: Subdivision): Int = when (sub) {
    Subdivision.QUARTER -> R.string.sub_quarter
    Subdivision.EIGHTHS -> R.string.sub_eighths
    Subdivision.TRIPLET -> R.string.sub_triplet
    Subdivision.SIXTEENTHS -> R.string.sub_sixteenths
    Subdivision.EIGHTH_TWO_SIXTEENTHS -> R.string.sub_8_16_16
    Subdivision.TWO_SIXTEENTHS_EIGHTH -> R.string.sub_16_16_8
    Subdivision.TRIPLET_QUARTER_EIGHTH -> R.string.sub_triplet_q_e
    Subdivision.SIXTEENTH_EIGHTH_SIXTEENTH -> R.string.sub_16_8_16
    Subdivision.DOTTED_EIGHTH_SIXTEENTH -> R.string.sub_dotted8_16
    Subdivision.EIGHTH_REST_EIGHTH -> R.string.sub_rest8_8
    Subdivision.TRIPLET_EIGHTH_REST_EIGHTH -> R.string.sub_triplet_e_r_e
    Subdivision.SIXTEENTH_DOTTED_EIGHTH -> R.string.sub_16_dotted8
    Subdivision.QUINTUPLET -> R.string.sub_quintuplet
    Subdivision.SEPTUPLET -> R.string.sub_septuplet
    Subdivision.SEXTUPLET -> R.string.sub_sextuplet
    Subdivision.SEXTUPLET_DUPLE -> R.string.sub_sextuplet_2
}
