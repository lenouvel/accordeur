package com.blenouvel.accordeur.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.ScalesUiState
import com.blenouvel.accordeur.model.FretLabels
import com.blenouvel.accordeur.model.FretboardMap
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.ui.theme.TunerTheme

/** Actions de la vue « Gammes ». */
class ScalesActions(
    val onRootChange: (Int) -> Unit,
    val onScaleChange: (String) -> Unit,
    val onFretsChange: (Int) -> Unit,
    val onLabelsChange: (FretLabels) -> Unit,
    val onLeftHandedChange: (Boolean) -> Unit,
    val onToggleDegree: (Int) -> Unit,
    val onPlay: (midi: Int) -> Unit,
    val onOpenTunings: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * Gammes et modes sur le manche : tonalité, gamme, accordage (6, 7 cordes ou personnalisé),
 * 12 / 15 / 22 cases, notes ou degrés, gaucher. Manche vertical, les réglages restent en haut.
 */
@Composable
fun ScalesScreen(state: ScalesUiState, actions: ScalesActions, modifier: Modifier = Modifier) {
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var showScales by rememberSaveable { mutableStateOf(false) }
    val settings = state.settings
    val notation = settings.notation
    val tuning = settings.tuning
    val rootName = NoteNames.spelled(state.spelling.first(), notation)
    val noteNames = state.spelling.map { NoteNames.spelled(it, notation) }
    val formulaDescription = stringResource(R.string.formula_description, state.scale.formula)

    // Surface : fond et couleur de contenu (icônes, textes) du thème, quel que soit le parent.
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // Tonalité · gamme · réglages
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectorChip(
                    label = stringResource(R.string.scales_key),
                    value = rootName,
                    onClick = { showKeys = true },
                    modifier = Modifier.width(104.dp),
                )
                SelectorChip(
                    label = stringResource(R.string.scales_scale),
                    value = state.scale.name,
                    onClick = { showScales = true },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            }
            // Accordage · nombre de cases
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Les notes à vide sont écrites en tête du manche : inutile de les répéter ici.
                SelectorChip(
                    label = stringResource(R.string.scales_tuning),
                    value = stringResource(R.string.tuning_summary, tuning.name, tuning.stringCount),
                    onClick = actions.onOpenTunings,
                    modifier = Modifier.weight(1f),
                )
                FretCountSelector(settings.scaleFrets, actions.onFretsChange)
            }
            // Notes de la gamme et degrés (toucher un degré le met en évidence).
            DegreeLegend(state, noteNames, actions.onToggleDegree)
            // Étiquettes · gaucher · formule
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelsSelector(settings.scaleLabels, actions.onLabelsChange)
                FilterChip(
                    selected = settings.leftHanded,
                    onClick = { actions.onLeftHandedChange(!settings.leftHanded) },
                    label = { Text(stringResource(R.string.left_handed), maxLines = 1, softWrap = false) },
                )
                Text(
                    text = state.scale.formula,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = formulaDescription },
                )
            }

            if (state.loaded) {
                val labels = if (settings.scaleLabels == FretLabels.NOTES) noteNames else state.scale.degrees
                FretboardView(
                    stringNames = tuning.strings.map { NoteNames.primary(it.pitchClass, notation) },
                    frets = settings.scaleFrets,
                    notes = state.notes,
                    degreeLabels = labels,
                    highlighted = state.highlighted,
                    leftHanded = settings.leftHanded,
                    description = stringResource(
                        R.string.fretboard_description,
                        rootName,
                        state.scale.name,
                        tuning.name,
                        settings.scaleFrets,
                    ),
                    onTap = { string, fret -> tuning.strings.getOrNull(string)?.let { actions.onPlay(it.midi + fret) } },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }

    if (showKeys) {
        KeyPickerSheet(
            selected = settings.scaleRoot,
            notation = notation,
            onSelect = actions.onRootChange,
            onDismiss = { showKeys = false },
        )
    }
    if (showScales) {
        ScalePickerSheet(
            selected = state.scale,
            onSelect = actions.onScaleChange,
            onDismiss = { showScales = false },
        )
    }
}

/** Bouton de sélection compact : petit libellé, valeur, flèche. */
@Composable
fun SelectorChip(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = value,
                    // Noms longs (« Mineure naturelle (éolien) ») : police réduite plutôt que tronquée.
                    style = if (value.length > LONG_VALUE) {
                        MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 20.sp)
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FretCountSelector(frets: Int, onChange: (Int) -> Unit) {
    val options = FretboardMap.FRET_COUNTS
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.scales_frets),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.width(150.dp)) {
            options.forEachIndexed { index, count ->
                SegmentedButton(
                    selected = count == frets,
                    onClick = { onChange(count) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {},
                ) {
                    Text(count.toString())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelsSelector(labels: FretLabels, onChange: (FretLabels) -> Unit) {
    val options = FretLabels.entries
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == labels,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
            ) {
                Text(stringResource(if (option == FretLabels.NOTES) R.string.labels_notes else R.string.labels_degrees))
            }
        }
    }
}

/**
 * Notes de la gamme avec leur degré. La fondamentale est en couleur d'accent ; toucher un autre
 * degré le met en évidence sur le manche (ex. 3 et 5 pour visualiser l'arpège).
 */
@Composable
private fun DegreeLegend(state: ScalesUiState, noteNames: List<String>, onToggle: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val warning = TunerTheme.colors.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        noteNames.forEachIndexed { index, name ->
            val degree = state.scale.degrees[index]
            val isRoot = index == 0
            val marked = index in state.highlighted
            val background = when {
                isRoot -> scheme.primary
                marked -> warning
                else -> scheme.surfaceContainerHigh
            }
            val content = when {
                isRoot -> scheme.onPrimary
                marked -> Color(0xFF1B1300)
                else -> scheme.onSurface
            }
            val description = stringResource(R.string.cd_highlight_degree, degree, name)
            Surface(
                onClick = { onToggle(index) },
                enabled = !isRoot,
                shape = RoundedCornerShape(12.dp),
                color = background,
                contentColor = content,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(degree, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

/** Au-delà, la valeur d'un [SelectorChip] est écrite plus petit. */
private const val LONG_VALUE = 22
