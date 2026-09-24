package com.blenouvel.accordeur.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.ScaleCatalog
import com.blenouvel.accordeur.model.ScaleType
import com.blenouvel.accordeur.model.SpelledNote

/** Touches blanches (classes de hauteur) et touches noires (indice de la blanche à gauche, classe). */
private val WHITE_KEYS = listOf(0, 2, 4, 5, 7, 9, 11)
private val BLACK_KEYS = listOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)

/** Nom d'une touche : « Do », ou « Do♯ / Ré♭ » pour une touche noire. */
fun keyName(pitchClass: Int, notation: Notation): String {
    val natural = SpelledNote.NATURAL_PITCH_CLASSES.indexOf(pitchClass)
    if (natural >= 0) return NoteNames.spelled(SpelledNote(natural, 0), notation)
    val below = SpelledNote.NATURAL_PITCH_CLASSES.indexOf(pitchClass - 1)
    return NoteNames.spelled(SpelledNote(below, 1), notation) + " / " +
        NoteNames.spelled(SpelledNote(below + 1, -1), notation)
}

/** Choix de la tonalité sur un clavier d'une octave : les 12 notes visibles, un seul toucher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyPickerSheet(
    selected: Int,
    notation: Notation,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.key_sheet_title), style = MaterialTheme.typography.titleLarge)
            PianoKeys(
                selected = selected,
                notation = notation,
                onSelect = {
                    onSelect(it)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
internal fun PianoKeys(selected: Int, notation: Notation, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(176.dp),
    ) {
        val whiteWidth = maxWidth / WHITE_KEYS.size
        val blackWidth = whiteWidth * 0.86f
        Row(Modifier.fillMaxSize()) {
            for (pc in WHITE_KEYS) {
                PianoKey(
                    label = keyName(pc, notation),
                    selected = pc == selected,
                    background = scheme.surfaceContainerHighest,
                    content = scheme.onSurface,
                    labelAtBottom = true,
                    onClick = { onSelect(pc) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp),
                )
            }
        }
        for ((whiteIndex, pc) in BLACK_KEYS) {
            PianoKey(
                label = keyName(pc, notation).replace(" / ", "\n"),
                selected = pc == selected,
                background = scheme.surfaceContainerLowest,
                content = scheme.onSurfaceVariant,
                labelAtBottom = false,
                onClick = { onSelect(pc) },
                modifier = Modifier
                    .offset(x = whiteWidth * (whiteIndex + 1) - blackWidth / 2)
                    .width(blackWidth)
                    .height(104.dp),
                border = BorderStroke(1.dp, scheme.outline),
            )
        }
    }
}

@Composable
private fun PianoKey(
    label: String,
    selected: Boolean,
    background: Color,
    content: Color,
    labelAtBottom: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
        color = if (selected) scheme.primary else background,
        contentColor = if (selected) scheme.onPrimary else content,
        border = if (selected) null else border,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
            contentAlignment = if (labelAtBottom) Alignment.BottomCenter else Alignment.TopCenter,
        ) {
            Text(
                text = label,
                style = if (labelAtBottom) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Choix de la gamme ou du mode, groupés par famille, avec leurs degrés. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScalePickerSheet(
    selected: ScaleType,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                Text(
                    text = stringResource(R.string.scale_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            for ((category, scales) in ScaleCatalog.byCategory) {
                item(key = "category-${category.name}") {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(scales, key = { it.id }) { scale ->
                    val isSelected = scale.id == selected.id
                    ListItem(
                        headlineContent = {
                            Text(scale.name, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                        },
                        supportingContent = { Text(scale.degrees.joinToString("  ")) },
                        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .clickable {
                                onSelect(scale.id)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
