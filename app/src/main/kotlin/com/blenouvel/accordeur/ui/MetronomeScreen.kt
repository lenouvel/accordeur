package com.blenouvel.accordeur.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blenouvel.accordeur.MetronomeUiState
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.model.ClickSound
import com.blenouvel.accordeur.model.MetronomeRange
import com.blenouvel.accordeur.model.SilentMeasures
import com.blenouvel.accordeur.model.Subdivision
import com.blenouvel.accordeur.model.TempoAutomation
import com.blenouvel.accordeur.ui.theme.NumericStyle
import kotlin.math.roundToInt

/** Actions de la vue « Métronome ». */
class MetronomeActions(
    val onToggleRun: () -> Unit,
    val onBpmChange: (Int) -> Unit,
    val onNudgeBpm: (Int) -> Unit,
    val onTap: () -> Unit,
    val onBeatsChange: (Int) -> Unit,
    val onSubdivisionChange: (Subdivision) -> Unit,
    val onAccentChange: (Boolean) -> Unit,
    val onSoundChange: (ClickSound) -> Unit,
    val onCountInChange: (Int) -> Unit,
    val onSilentChange: (SilentMeasures) -> Unit,
    val onAutomationChange: (TempoAutomation) -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * Métronome : tempo (slider, +/−, tap), mesure et subdivisions, accent, son, puis les
 * modes d'entraînement (décompte, mesures silencieuses, automation du tempo, mémoires). Pendule et
 * points en tête. L'écran défile ; le métronome ne sonne que sur cette page.
 */
@Composable
fun MetronomeScreen(state: MetronomeUiState, actions: MetronomeActions, modifier: Modifier = Modifier) {
    val config = state.config
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // En-tête : état de mesure (ou décompte) à gauche, réglages à droite.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tick = state.tick
                val status = when {
                    !state.running || tick == null -> ""
                    tick.countIn -> stringResource(R.string.metro_count_in_running, tick.countInLeft)
                    tick.measure > 0 -> stringResource(R.string.metro_measure, tick.measure)
                    else -> ""
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            }

            // Visuel : pendule + rangée de points.
            MetronomePendulum(
                tick = state.tick,
                running = state.running,
                bpm = state.displayBpm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
            BeatDots(
                tick = state.tick,
                beatsPerMeasure = config.beatsPerMeasure,
                accentFirst = config.accentFirst,
                running = state.running,
                modifier = Modifier.fillMaxWidth(),
            )

            // BPM + nom de tempo.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = state.displayBpm.toString(),
                        style = MaterialTheme.typography.displayLarge.merge(NumericStyle).copy(fontWeight = FontWeight.Light),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.metro_bpm_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Text(
                    text = state.tempoName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Slider + nudges.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SymbolButton("−", stringResource(R.string.cd_decrease)) { actions.onNudgeBpm(-1) }
                Slider(
                    value = config.bpm.toFloat(),
                    onValueChange = { actions.onBpmChange(it.roundToInt()) },
                    valueRange = MetronomeRange.MIN_BPM.toFloat()..MetronomeRange.MAX_BPM.toFloat(),
                    modifier = Modifier.weight(1f),
                )
                SymbolButton("+", stringResource(R.string.cd_increase)) { actions.onNudgeBpm(1) }
            }

            // Tap + lecture.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = actions.onTap, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.metro_tap))
                }
                FilledTonalIconButton(
                    onClick = actions.onToggleRun,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = if (state.running) AppIcons.Stop else AppIcons.Play,
                        contentDescription = stringResource(if (state.running) R.string.cd_metro_stop else R.string.cd_metro_start),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Mesure (temps par mesure).
            LabeledRow(stringResource(R.string.metro_beats)) {
                Stepper(
                    value = config.beatsPerMeasure,
                    min = MetronomeRange.MIN_BEATS,
                    max = MetronomeRange.MAX_BEATS,
                    onChange = actions.onBeatsChange,
                )
            }

            // Subdivision (notation).
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(stringResource(R.string.metro_subdivision))
                DivisionPicker(
                    selected = config.subdivision,
                    onSelect = actions.onSubdivisionChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Accent.
            LabeledRow(stringResource(R.string.metro_accent)) {
                Switch(checked = config.accentFirst, onCheckedChange = actions.onAccentChange)
            }

            // Son.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(stringResource(R.string.metro_sound))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (sound in ClickSound.entries) {
                        FilterChip(
                            selected = sound == config.sound,
                            onClick = { actions.onSoundChange(sound) },
                            label = { Text(stringResource(soundLabel(sound)), maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }

            // --- Entraînement ---

            // Décompte.
            Section(stringResource(R.string.metro_count_in)) {
                LabeledRow(stringResource(R.string.metro_count_in)) {
                    Stepper(
                        value = config.countInBars,
                        min = 0,
                        max = MetronomeRange.MAX_COUNT_IN,
                        onChange = actions.onCountInChange,
                    )
                }
            }

            // Mesures silencieuses.
            Section(stringResource(R.string.metro_silent_title)) {
                LabeledRow(stringResource(R.string.metro_enable)) {
                    Switch(
                        checked = config.silent.enabled,
                        onCheckedChange = { actions.onSilentChange(config.silent.copy(enabled = it)) },
                    )
                }
                SectionDesc(stringResource(R.string.metro_silent_desc))
                if (config.silent.enabled) {
                    LabeledRow(stringResource(R.string.metro_silent_play)) {
                        Stepper(config.silent.playBars, 1, MetronomeRange.MAX_SILENT_BARS) {
                            actions.onSilentChange(config.silent.copy(playBars = it))
                        }
                    }
                    LabeledRow(stringResource(R.string.metro_silent_mute)) {
                        Stepper(config.silent.muteBars, 1, MetronomeRange.MAX_SILENT_BARS) {
                            actions.onSilentChange(config.silent.copy(muteBars = it))
                        }
                    }
                }
            }

            // Automation du tempo.
            Section(stringResource(R.string.metro_auto_title)) {
                val auto = config.automation
                LabeledRow(stringResource(R.string.metro_enable)) {
                    Switch(checked = auto.enabled, onCheckedChange = { actions.onAutomationChange(auto.copy(enabled = it)) })
                }
                SectionDesc(stringResource(R.string.metro_auto_desc))
                if (auto.enabled) {
                    LabeledRow(stringResource(R.string.metro_auto_start)) {
                        Stepper(auto.startBpm, MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM, step = 5) {
                            actions.onAutomationChange(auto.copy(startBpm = it))
                        }
                    }
                    LabeledRow(stringResource(R.string.metro_auto_target)) {
                        Stepper(auto.targetBpm, MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM, step = 5) {
                            actions.onAutomationChange(auto.copy(targetBpm = it))
                        }
                    }
                    LabeledRow(stringResource(R.string.metro_auto_step)) {
                        Stepper(auto.stepBpm, 1, 60) { actions.onAutomationChange(auto.copy(stepBpm = it)) }
                    }
                    LabeledRow(stringResource(R.string.metro_auto_bars)) {
                        Stepper(auto.stepBars, 1, 32) { actions.onAutomationChange(auto.copy(stepBars = it)) }
                    }
                    LabeledRow(stringResource(R.string.metro_auto_loop)) {
                        Switch(checked = auto.loop, onCheckedChange = { actions.onAutomationChange(auto.copy(loop = it)) })
                    }
                    FieldLabel(stringResource(R.string.metro_auto_law))
                    VariationLawPicker(selected = auto.law, onSelect = { actions.onAutomationChange(auto.copy(law = it)) })
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

// --- Petits composants réutilisables -----------------------------------------------------------

/** Ligne « libellé … contrôle » (contrôle aligné à droite). */
@Composable
private fun LabeledRow(label: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun SectionDesc(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Carte d'une section d'entraînement : titre puis contenu. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** − valeur + (bornée). */
@Composable
private fun Stepper(value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SymbolButton("−", stringResource(R.string.cd_decrease), enabled = value > min) {
            onChange((value - step).coerceAtLeast(min))
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp),
        )
        SymbolButton("+", stringResource(R.string.cd_increase), enabled = value < max) {
            onChange((value + step).coerceAtMost(max))
        }
    }
}

/** Bouton rond portant un symbole texte (évite les icônes absentes de material-icons-core). */
@Composable
private fun SymbolButton(symbol: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = description },
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

private fun soundLabel(sound: ClickSound): Int = when (sound) {
    ClickSound.CLICK -> R.string.sound_click
    ClickSound.WOOD -> R.string.sound_wood
    ClickSound.BEEP -> R.string.sound_beep
    ClickSound.CLAVE -> R.string.sound_clave
    ClickSound.COWBELL -> R.string.sound_cowbell
}
