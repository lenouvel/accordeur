package com.blenouvel.accordeur.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import com.blenouvel.accordeur.ui.theme.NumericStyle

/** Accordage en cours d'édition : [id] null = nouvel accordage. */
private data class EditorRequest(val id: String?, val name: String, val notes: List<Note>)

/** Choix de l'accordage (6 / 7 cordes / personnalisés) et éditeur d'accordages personnalisés. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningSheet(
    current: Tuning,
    customTunings: List<Tuning>,
    notation: Notation,
    onSelect: (String) -> Unit,
    onSave: (id: String?, name: String, notes: List<Note>) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<EditorRequest?>(null) }
    var deleting by remember { mutableStateOf<Tuning?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val request = editing
        if (request == null) {
            TuningList(
                current = current,
                customTunings = customTunings,
                notation = notation,
                onSelect = {
                    onSelect(it)
                    onDismiss()
                },
                onCreate = { editing = EditorRequest(null, "", current.strings.map { it.note }) },
                onEdit = { t -> editing = EditorRequest(t.id, t.name, t.strings.map { it.note }) },
                onDelete = { deleting = it },
            )
        } else {
            val defaultName = stringResource(R.string.default_custom_name)
            TuningEditor(
                request = request,
                notation = notation,
                onCancel = { editing = null },
                onSave = { name, notes ->
                    onSave(request.id, name.ifBlank { defaultName }, notes)
                    editing = null
                    onDismiss()
                },
            )
        }
    }

    deleting?.let { tuning ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.delete_tuning_confirm, tuning.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(tuning.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TuningList(
    current: Tuning,
    customTunings: List<Tuning>,
    notation: Notation,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (Tuning) -> Unit,
    onDelete: (Tuning) -> Unit,
) {
    val six = Presets.all.filter { it.stringCount == 6 }
    val seven = Presets.all.filter { it.stringCount == 7 }
    val others = Presets.all.filter { it.stringCount != 6 && it.stringCount != 7 }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.navigationBarsPadding(),
    ) {
        item {
            Text(
                text = stringResource(R.string.tuning_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        section(R.string.section_six_strings, six, current, notation, onSelect, null, null)
        section(R.string.section_seven_strings, seven, current, notation, onSelect, null, null)
        if (others.isNotEmpty()) section(R.string.section_other, others, current, notation, onSelect, null, null)
        if (customTunings.isNotEmpty()) {
            section(R.string.section_custom, customTunings, current, notation, onSelect, onEdit, onDelete)
        }
        item {
            OutlinedButton(
                onClick = onCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.create_tuning))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    titleRes: Int,
    tunings: List<Tuning>,
    current: Tuning,
    notation: Notation,
    onSelect: (String) -> Unit,
    onEdit: ((Tuning) -> Unit)?,
    onDelete: ((Tuning) -> Unit)?,
) {
    item(key = "title-$titleRes") {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(tunings, key = { it.id }) { tuning ->
        val selected = tuning.id == current.id
        ListItem(
            headlineContent = { Text(tuning.name, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
            supportingContent = { Text(NoteNames.sequence(tuning, notation)) },
            leadingContent = { RadioButton(selected = selected, onClick = null) },
            trailingContent = if (onEdit != null && onDelete != null) {
                {
                    Row {
                        IconButton(onClick = { onEdit(tuning) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_edit))
                        }
                        IconButton(onClick = { onDelete(tuning) }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
                        }
                    }
                }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable { onSelect(tuning.id) }
                .padding(horizontal = 8.dp),
        )
    }
}

/** Éditeur : nom, nombre de cordes (4–8, ajout/retrait côté grave), note de chaque corde. */
@Composable
private fun TuningEditor(
    request: EditorRequest,
    notation: Notation,
    onCancel: () -> Unit,
    onSave: (String, List<Note>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(request.name) }
    var notes by remember { mutableStateOf(request.notes) }
    val defaultName = stringResource(R.string.default_custom_name)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(if (request.id == null) R.string.new_tuning else R.string.edit_tuning),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text(stringResource(R.string.tuning_name)) },
            placeholder = { Text(defaultName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.string_count), modifier = Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { if (notes.size > Tuning.MIN_STRINGS) notes = notes.drop(1) },
                enabled = notes.size > Tuning.MIN_STRINGS,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.remove_string))
            }
            Text(
                text = notes.size.toString(),
                style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
            FilledTonalIconButton(
                onClick = {
                    // Nouvelle corde grave, une quarte sous la plus grave.
                    if (notes.size < Tuning.MAX_STRINGS) notes = listOf(clampNote(notes.first().transpose(-5))) + notes
                },
                enabled = notes.size < Tuning.MAX_STRINGS,
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.add_string))
            }
        }
        notes.forEachIndexed { index, note ->
            NoteRow(
                stringNumber = notes.size - index,
                note = note,
                notation = notation,
                onChange = { updated -> notes = notes.toMutableList().also { it[index] = clampNote(updated) } },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { onSave(name.trim(), notes) }) { Text(stringResource(R.string.save)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NoteRow(stringNumber: Int, note: Note, notation: Notation, onChange: (Note) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.string_number, stringNumber),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onChange(note.transpose(-12)) }) { Text(stringResource(R.string.octave_down)) }
        IconButton(onClick = { onChange(note.transpose(-1)) }) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.semitone_down))
        }
        Text(
            text = NoteNames.label(note, if (notation == Notation.BOTH) Notation.FRENCH else notation),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        IconButton(onClick = { onChange(note.transpose(1)) }) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.semitone_up))
        }
        TextButton(onClick = { onChange(note.transpose(12)) }) { Text(stringResource(R.string.octave_up)) }
    }
}

/** Plage détectable : Ré1 (36,7 Hz) … Do6 (1047 Hz). */
private fun clampNote(note: Note): Note = Note.fromMidi(note.midi.coerceIn(MIN_MIDI, MAX_MIDI))

private const val MIN_MIDI = 26
private const val MAX_MIDI = 84
