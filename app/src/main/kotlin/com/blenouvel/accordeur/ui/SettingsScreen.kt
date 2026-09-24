package com.blenouvel.accordeur.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.TunerUiState
import com.blenouvel.accordeur.audio.EngineState
import com.blenouvel.accordeur.audio.MicSource
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.data.DetectionMode
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.data.ThemeMode
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.ui.theme.NumericStyle
import com.blenouvel.accordeur.ui.theme.TunerTheme
import com.blenouvel.accordeur.ui.theme.dynamicColorAvailable
import kotlin.math.roundToInt

/** Actions de l'écran de réglages (reliées au ViewModel). */
class SettingsActions(
    val setA4: (Double) -> Unit,
    val setTolerance: (Int) -> Unit,
    val setNotation: (Notation) -> Unit,
    val setDetectionMode: (DetectionMode) -> Unit,
    val setTunerMode: (TunerMode) -> Unit,
    val setTheme: (ThemeMode) -> Unit,
    val setDynamicColor: (Boolean) -> Unit,
    val setHaptics: (Boolean) -> Unit,
    val setKeepScreenOn: (Boolean) -> Unit,
    val setMicSource: (MicSource) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: TunerUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ReferencePitch(settings, actions.setA4)
            Tolerance(settings, actions.setTolerance)
            HorizontalDivider()

            Setting(stringResource(R.string.settings_notation)) {
                Segmented(
                    options = Notation.entries,
                    selected = settings.notation,
                    label = {
                        stringResource(
                            when (it) {
                                Notation.FRENCH -> R.string.notation_french
                                Notation.ENGLISH -> R.string.notation_english
                                Notation.BOTH -> R.string.notation_both
                            },
                        )
                    },
                    onSelect = actions.setNotation,
                )
            }
            Setting(stringResource(R.string.settings_detection), stringResource(R.string.settings_detection_desc)) {
                Segmented(
                    options = DetectionMode.entries,
                    selected = settings.detectionMode,
                    label = { stringResource(if (it == DetectionMode.AUTO) R.string.detection_auto else R.string.detection_manual) },
                    onSelect = actions.setDetectionMode,
                )
            }
            Setting(stringResource(R.string.settings_mode), stringResource(R.string.settings_mode_desc)) {
                Segmented(
                    options = TunerMode.entries,
                    selected = settings.tunerMode,
                    label = { stringResource(if (it == TunerMode.MONO) R.string.mode_mono else R.string.mode_poly) },
                    onSelect = actions.setTunerMode,
                )
            }
            HorizontalDivider()

            Setting(stringResource(R.string.settings_theme)) {
                Segmented(
                    options = ThemeMode.entries,
                    selected = settings.theme,
                    label = {
                        stringResource(
                            when (it) {
                                ThemeMode.DARK -> R.string.theme_dark
                                ThemeMode.LIGHT -> R.string.theme_light
                                ThemeMode.SYSTEM -> R.string.theme_system
                            },
                        )
                    },
                    onSelect = actions.setTheme,
                )
            }
            if (dynamicColorAvailable) {
                SwitchRow(stringResource(R.string.settings_dynamic_color), settings.dynamicColor, actions.setDynamicColor)
            }
            SwitchRow(stringResource(R.string.settings_haptics), settings.haptics, actions.setHaptics)
            SwitchRow(stringResource(R.string.settings_keep_screen_on), settings.keepScreenOn, actions.setKeepScreenOn)
            HorizontalDivider()

            MicSourceSetting(state, actions.setMicSource)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Choix de la source micro, avec la source réellement en service et un test en direct (niveau,
 * note entendue) pour comparer les sources en jouant la grosse corde.
 */
@Composable
private fun MicSourceSetting(state: TunerUiState, onSelect: (MicSource) -> Unit) {
    val running = state.engineState as? EngineState.Running
    val selected = state.settings.micSource
    Setting(stringResource(R.string.settings_mic_source), stringResource(R.string.settings_mic_source_desc)) {
        Column(Modifier.selectableGroup()) {
            for (source in MicSource.entries.filter { it.isAvailable }) {
                val description = if (source == MicSource.UNPROCESSED && running != null && !running.unprocessedDeclared) {
                    stringResource(R.string.mic_unprocessed_undeclared)
                } else {
                    micSourceDescription(source)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = source == selected, onClick = { onSelect(source) }, role = Role.RadioButton)
                        .padding(vertical = 6.dp),
                ) {
                    RadioButton(selected = source == selected, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(micSourceLabel(source), style = MaterialTheme.typography.bodyLarge)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val status = when {
            running == null -> stringResource(R.string.about_idle)
            selected != MicSource.AUTO && running.source != selected -> stringResource(R.string.mic_fallback, micSourceLabel(selected)) +
                " · " + stringResource(R.string.mic_active, micSourceLabel(running.source), running.sampleRate)
            else -> stringResource(R.string.mic_active, micSourceLabel(running.source), running.sampleRate)
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        MicTest(state)
    }
}

/** Test en direct : barre de niveau et note entendue (mode mono). */
@Composable
private fun MicTest(state: TunerUiState) {
    val level by animateFloatAsState(state.level, tween(120), label = "testLevel")
    val colors = TunerTheme.colors
    val note = state.note
    val cents = state.cents
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .width(96.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level)
                    .height(6.dp)
                    .background(if (state.signal) MaterialTheme.colorScheme.primary else colors.tick),
            )
        }
        Text(
            text = when {
                note != null && cents != null && !state.holding ->
                    stringResource(R.string.mic_test_note, NoteNames.label(note, state.settings.notation), formatCents(cents))
                state.signal -> stringResource(R.string.mic_test_listening)
                else -> stringResource(R.string.mic_test_idle)
            },
            style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun micSourceLabel(source: MicSource): String = stringResource(
    when (source) {
        MicSource.AUTO -> R.string.mic_auto
        MicSource.UNPROCESSED -> R.string.mic_unprocessed
        MicSource.VOICE_PERFORMANCE -> R.string.mic_voice_performance
        MicSource.VOICE_RECOGNITION -> R.string.mic_voice_recognition
        MicSource.CAMCORDER -> R.string.mic_camcorder
        MicSource.MIC -> R.string.mic_standard
    },
)

@Composable
private fun micSourceDescription(source: MicSource): String = stringResource(
    when (source) {
        MicSource.AUTO -> R.string.mic_auto_desc
        MicSource.UNPROCESSED -> R.string.mic_unprocessed_desc
        MicSource.VOICE_PERFORMANCE -> R.string.mic_voice_performance_desc
        MicSource.VOICE_RECOGNITION -> R.string.mic_voice_recognition_desc
        MicSource.CAMCORDER -> R.string.mic_camcorder_desc
        MicSource.MIC -> R.string.mic_standard_desc
    },
)

@Composable
private fun ReferencePitch(settings: Settings, onChange: (Double) -> Unit) {
    // Valeur locale pendant le glissement ; enregistrée au relâchement (pas d'écriture à chaque pixel).
    var draft by remember(settings.a4) { mutableFloatStateOf(settings.a4.toFloat()) }
    val a4Name = NoteNames.primary(9, settings.notation)
    Setting(
        title = stringResource(R.string.settings_reference),
        trailing = stringResource(R.string.reference_pitch_short, a4Name, formatA4(roundHalf(draft))),
    ) {
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(roundHalf(draft)) },
            valueRange = NoteMapper.MIN_A4.toFloat()..NoteMapper.MAX_A4.toFloat(),
            steps = ((NoteMapper.MAX_A4 - NoteMapper.MIN_A4) * 2).toInt() - 1,
        )
        if (settings.a4 != NoteMapper.DEFAULT_A4) {
            TextButton(onClick = { onChange(NoteMapper.DEFAULT_A4) }) {
                Text(stringResource(R.string.settings_reference_reset))
            }
        }
    }
}

@Composable
private fun Tolerance(settings: Settings, onChange: (Int) -> Unit) {
    var draft by remember(settings.toleranceCents) { mutableFloatStateOf(settings.toleranceCents.toFloat()) }
    Setting(
        title = stringResource(R.string.settings_tolerance),
        description = stringResource(R.string.settings_tolerance_desc),
        trailing = stringResource(R.string.settings_tolerance_value, draft.roundToInt()),
    ) {
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft.roundToInt()) },
            valueRange = Settings.MIN_TOLERANCE.toFloat()..Settings.MAX_TOLERANCE.toFloat(),
            steps = Settings.MAX_TOLERANCE - Settings.MIN_TOLERANCE - 1,
        )
    }
}

private fun roundHalf(value: Float): Double = (value * 2f).roundToInt() / 2.0

@Composable
private fun Setting(
    title: String,
    description: String? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
