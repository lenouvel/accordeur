package com.blenouvel.accordeur.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.blenouvel.accordeur.model.FretNote
import com.blenouvel.accordeur.model.FretboardMap
import com.blenouvel.accordeur.ui.theme.TunerTheme
import kotlinx.coroutines.launch
import kotlin.math.floor

private val NAMES_ROW = 16.dp
private val OPEN_ROW = 40.dp
private val NUT = 6.dp
private val BOTTOM_PADDING = 6.dp
private val GUTTER = 28.dp
private val RIGHT_MARGIN = 8.dp
private val MIN_ROW = 31.dp
private val MAX_ROW = 64.dp

/**
 * Manche vertical (tête en haut, corde grave à gauche — à droite en mode gaucher).
 * La hauteur des cases s'adapte à l'écran ; au-delà, le manche défile verticalement.
 *
 * [degreeLabels] : texte de chaque degré (nom de note ou intervalle). La fondamentale est dessinée
 * dans la couleur d'accent, les degrés de [highlighted] en ambre, les notes à vide cerclées.
 * Toucher une case appelle [onTap] (corde, case), qu'elle appartienne ou non à la gamme.
 */
@Composable
fun FretboardView(
    stringNames: List<String>,
    frets: Int,
    notes: List<FretNote>,
    degreeLabels: List<String>,
    highlighted: Set<Int>,
    leftHanded: Boolean,
    description: String,
    onTap: (string: Int, fret: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tunerColors = TunerTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    var flashCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val stringCount = stringNames.size

    BoxWithConstraints(modifier) {
        val available = maxHeight - NAMES_ROW - OPEN_ROW - NUT - BOTTOM_PADDING
        val rowHeight = (available / frets).coerceIn(MIN_ROW, MAX_ROW)
        val totalHeight = NAMES_ROW + OPEN_ROW + NUT + rowHeight * frets + BOTTOM_PADDING
        val spacing = (maxWidth - GUTTER - RIGHT_MARGIN) / stringCount
        val fretRadius = min(spacing, rowHeight) * 0.42f
        val openRadius = min(spacing, OPEN_ROW) * 0.40f

        // Textes mesurés une fois pour toutes (pas de mesure pendant le dessin).
        val fretLabels = remember(degreeLabels, fretRadius, density) {
            degreeLabels.map { fitLabel(measurer, it, fretRadius, density) }
        }
        val openLabels = remember(degreeLabels, openRadius, density) {
            degreeLabels.map { fitLabel(measurer, it, openRadius, density) }
        }
        val smallStyle = MaterialTheme.typography.labelSmall
        val fretNumbers = remember(frets, smallStyle) { (1..frets).map { measurer.measure(it.toString(), smallStyle) } }
        val nameLayouts = remember(stringNames, smallStyle) { stringNames.map { measurer.measure(it, smallStyle) } }

        // Le manche défile verticalement s'il dépasse la place disponible (22 cases).
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .semantics { contentDescription = description }
                    .pointerInput(stringCount, frets, leftHanded, rowHeight) {
                        detectTapGestures { pos ->
                            val neckLeft = GUTTER.toPx()
                            val space = (size.width - neckLeft - RIGHT_MARGIN.toPx()) / stringCount
                            val column = floor((pos.x - neckLeft) / space).toInt()
                            if (column !in 0 until stringCount) return@detectTapGestures
                            val string = if (leftHanded) stringCount - 1 - column else column
                            val nutTop = (NAMES_ROW + OPEN_ROW).toPx()
                            val boardTop = nutTop + NUT.toPx()
                            val fret = when {
                                pos.y < NAMES_ROW.toPx() -> return@detectTapGestures
                                pos.y < boardTop -> 0
                                else -> (floor((pos.y - boardTop) / rowHeight.toPx()).toInt() + 1).coerceAtMost(frets)
                            }
                            flashCell = string to fret
                            scope.launch {
                                flash.snapTo(1f)
                                flash.animateTo(0f, tween(450))
                            }
                            onTap(string, fret)
                        }
                    },
            ) {
                val neckLeft = GUTTER.toPx()
                val neckRight = size.width - RIGHT_MARGIN.toPx()
                val space = (neckRight - neckLeft) / stringCount
                val namesH = NAMES_ROW.toPx()
                val nutTop = namesH + OPEN_ROW.toPx()
                val boardTop = nutTop + NUT.toPx()
                val rowH = rowHeight.toPx()
                val boardBottom = boardTop + rowH * frets

                fun stringX(string: Int): Float {
                    val column = if (leftHanded) stringCount - 1 - string else string
                    return neckLeft + space * (column + 0.5f)
                }

                fun rowCenter(fret: Int): Float =
                    if (fret == 0) namesH + OPEN_ROW.toPx() / 2f else boardTop + rowH * (fret - 0.5f)

                // Touche.
                drawRoundRect(
                    color = scheme.surfaceContainer,
                    topLeft = Offset(neckLeft, nutTop),
                    size = Size(neckRight - neckLeft, boardBottom - nutTop),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
                // Repères (simple / double à 12 et 24), placés entre deux cordes pour rester visibles.
                val inlayRadius = 4.dp.toPx()
                fun gapX(gap: Int) = neckLeft + space * (gap + 1).coerceIn(1, stringCount - 1)
                val singleGap = (stringCount - 2) / 2
                val leftGap = (stringCount - 2) / 3
                val rightGap = stringCount - 2 - leftGap
                for (fret in 1..frets) {
                    if (fret !in FretboardMap.INLAYS || stringCount < 2) continue
                    val y = rowCenter(fret)
                    if (fret % 12 == 0) {
                        drawCircle(tunerColors.track, inlayRadius, Offset(gapX(leftGap), y))
                        drawCircle(tunerColors.track, inlayRadius, Offset(gapX(rightGap), y))
                    } else {
                        drawCircle(tunerColors.track, inlayRadius, Offset(gapX(singleGap), y))
                    }
                }
                // Frettes.
                for (fret in 1..frets) {
                    val y = boardTop + rowH * fret
                    drawLine(scheme.outline, Offset(neckLeft, y), Offset(neckRight, y), strokeWidth = 1.5.dp.toPx())
                }
                // Sillet.
                drawRect(scheme.onSurfaceVariant, Offset(neckLeft, nutTop), Size(neckRight - neckLeft, NUT.toPx()))
                // Cordes : plus épaisses dans les graves.
                for (string in 0 until stringCount) {
                    val t = if (stringCount > 1) string / (stringCount - 1f) else 0f
                    val width = (2.6f - 1.6f * t).dp.toPx()
                    val x = stringX(string)
                    drawLine(scheme.outline, Offset(x, namesH + 4.dp.toPx()), Offset(x, boardBottom), strokeWidth = width)
                }
                // Numéros de cases (repères mis en valeur) et noms des cordes à vide.
                for (fret in 1..frets) {
                    val layout = fretNumbers[fret - 1]
                    val color = if (fret in FretboardMap.INLAYS) scheme.onSurface else scheme.outline
                    drawText(layout, color, Offset(neckLeft / 2f - layout.size.width / 2f, rowCenter(fret) - layout.size.height / 2f))
                }
                for (string in 0 until stringCount) {
                    val layout = nameLayouts[string]
                    drawText(
                        layout,
                        scheme.onSurfaceVariant,
                        Offset(stringX(string) - layout.size.width / 2f, (namesH - layout.size.height) / 2f),
                    )
                }

                // Notes de la gamme.
                for (note in notes) {
                    val center = Offset(stringX(note.string), rowCenter(note.fret))
                    val open = note.fret == 0
                    val radius = (if (open) openRadius else fretRadius).toPx()
                    val fill = when {
                        note.degree == 0 -> scheme.primary
                        note.degree in highlighted -> tunerColors.warning
                        else -> scheme.surfaceContainerHighest
                    }
                    val ink = when {
                        note.degree == 0 -> scheme.onPrimary
                        note.degree in highlighted -> Color(0xFF1B1300)
                        else -> scheme.onSurface
                    }
                    val layouts = if (open) openLabels else fretLabels
                    val layout = layouts.getOrNull(note.degree) ?: continue
                    if (open) {
                        // Corde à vide : pastille cerclée.
                        drawCircle(fill.copy(alpha = 0.22f), radius, center)
                        drawCircle(fill, radius - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                        drawLabel(layout, if (note.degree == 0 || note.degree in highlighted) fill else scheme.onSurface, center)
                    } else {
                        drawCircle(fill, radius, center)
                        drawLabel(layout, ink, center)
                    }
                }

                // Retour visuel du toucher.
                val cell = flashCell
                if (cell != null && flash.value > 0.01f) {
                    val (string, fret) = cell
                    val radius = (if (fret == 0) openRadius else fretRadius).toPx() + 5.dp.toPx()
                    drawCircle(
                        scheme.primary.copy(alpha = flash.value),
                        radius,
                        Offset(stringX(string), rowCenter(fret)),
                        style = Stroke(3.dp.toPx()),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLabel(layout: TextLayoutResult, color: Color, center: Offset) {
    drawText(layout, color, Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

private val LabelStyle = TextStyle(fontWeight = FontWeight.SemiBold)

/**
 * Étiquette mesurée pour une pastille de rayon [radius] : même taille pour toutes les notes,
 * réduite seulement si le texte (« Sol♯ », « ♭♭7 ») déborderait.
 */
private fun fitLabel(measurer: TextMeasurer, text: String, radius: Dp, density: Density): TextLayoutResult {
    val base = LabelStyle.copy(fontSize = with(density) { (radius * 0.9f).toSp() })
    val layout = measurer.measure(text, base)
    val maxWidth = with(density) { (radius * 1.66f).toPx() }
    if (layout.size.width <= maxWidth) return layout
    return measurer.measure(text, base.copy(fontSize = base.fontSize * (maxWidth / layout.size.width)))
}
