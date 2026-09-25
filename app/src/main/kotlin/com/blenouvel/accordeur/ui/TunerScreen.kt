package com.blenouvel.accordeur.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.TunerUiState
import com.blenouvel.accordeur.audio.EngineError
import com.blenouvel.accordeur.audio.EngineState
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.data.DetectionMode
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.ui.theme.NoteStyle
import com.blenouvel.accordeur.ui.theme.NumericStyle
import com.blenouvel.accordeur.ui.theme.OctaveStyle
import com.blenouvel.accordeur.ui.theme.TunerTheme
import kotlin.math.abs

/** Écran principal de l'accordeur. */
@Composable
fun TunerScreen(
    state: TunerUiState,
    onOpenSettings: () -> Unit,
    onOpenTunings: () -> Unit,
    onModeChange: (TunerMode) -> Unit,
    onDetectionModeChange: (DetectionMode) -> Unit,
    onStringTap: (Int) -> Unit,
    onStringLongPress: (Int) -> Unit,
    onToggleRecording: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val poly = settings.tunerMode == TunerMode.POLY

    // Haptique légère quand une corde devient juste (une fois par corde).
    val haptic = LocalHapticFeedback.current
    var seenTunedEvents by remember { mutableIntStateOf(state.tunedEvents) }
    LaunchedEffect(state.tunedEvents) {
        if (state.tunedEvents > seenTunedEvents && settings.haptics) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        seenTunedEvents = state.tunedEvents
    }

    // Surface : fond et couleur de contenu (icônes, textes) du thème, quel que soit le parent.
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(state, onOpenTunings, onOpenSettings, onToggleRecording)
            Spacer(Modifier.height(12.dp))
            ModeSelector(settings.tunerMode, onModeChange)

            val failure = (state.engineState as? EngineState.Failed)?.error
            if (failure != null && failure != EngineError.PERMISSION) {
                Spacer(Modifier.height(12.dp))
                EngineErrorCard(failure, onRetry)
            }

            Spacer(Modifier.weight(1f))
            if (poly) {
                val held = state.poly != null
                Text(
                    text = stringResource(if (held) R.string.poly_held else R.string.strum_all_strings),
                    style = if (held) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                PolyMeter(
                    stringCount = state.tuning.stringCount,
                    reading = state.poly,
                    toleranceCents = settings.toleranceCents,
                    modifier = Modifier.height(240.dp),
                )
                if (state.poly?.strings?.any { it.detected && !it.reliable } == true) {
                    Text(
                        text = stringResource(R.string.poly_approximate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                NoteDisplay(state)
                TunerMeter(
                    cents = state.cents,
                    toleranceCents = settings.toleranceCents,
                    holding = state.holding,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
                CentsReadout(state)
            }
            Spacer(Modifier.weight(1f))

            StringSelector(
                tuning = state.tuning,
                notation = settings.notation,
                activeString = state.activeString,
                lockedString = state.lockedString,
                tunedStrings = state.tunedStrings,
                playingString = state.playingString,
                poly = state.poly?.strings,
                toleranceCents = settings.toleranceCents,
                onTap = onStringTap,
                onLongPress = onStringLongPress,
            )
            Spacer(Modifier.height(12.dp))
            BottomHints(state, onDetectionModeChange)
            Spacer(Modifier.height(8.dp))
            LevelIndicator(state)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TopBar(state: TunerUiState, onOpenTunings: () -> Unit, onOpenSettings: () -> Unit, onToggleRecording: () -> Unit) {
    val notation = state.settings.notation
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Surface(
                onClick = onOpenTunings,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            text = stringResource(R.string.tuning_summary, state.tuning.name, state.tuning.stringCount),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = NoteNames.sequence(state.tuning, notation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_change_tuning),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        Text(
            text = stringResource(
                R.string.reference_pitch_short,
                NoteNames.primary(9, notation),
                formatA4(state.settings.a4),
            ),
            style = MaterialTheme.typography.labelLarge.merge(NumericStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (state.settings.recordBank) RecordButton(state.recording, onToggleRecording)
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: TunerMode, onModeChange: (TunerMode) -> Unit) {
    val modes = TunerMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.width(220.dp)) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(stringResource(if (m == TunerMode.MONO) R.string.mode_mono else R.string.mode_poly))
            }
        }
    }
}

/** Grande note (notation choisie), nom anglais en petit si « les deux », fréquence mesurée. */
@Composable
private fun NoteDisplay(state: TunerUiState) {
    val notation = state.settings.notation
    val colors = TunerTheme.colors
    val inTune = state.inTune && !state.holding
    val noteColor by animateColorAsState(
        if (inTune) colors.inTune else MaterialTheme.colorScheme.onSurface,
        tween(250),
        label = "noteColor",
    )
    val alpha by animateFloatAsState(if (state.holding) 0.45f else 1f, tween(300), label = "noteAlpha")
    val note: Note? = state.note
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(alpha)) {
        Box(Modifier.height(124.dp), contentAlignment = Alignment.Center) {
            if (note != null) {
                Text(
                    text = noteWithOctave(note, notation, OctaveStyle.fontSize),
                    style = NoteStyle,
                    color = noteColor,
                    maxLines = 1,
                )
            } else {
                Text(text = "—", style = NoteStyle, color = MaterialTheme.colorScheme.outline)
            }
        }
        val secondary = note?.let { NoteNames.secondary(it.pitchClass, notation) }
        Text(
            text = if (note != null && secondary != null) "$secondary${note.octave}" else " ",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                state.frequency != null -> stringResource(R.string.frequency_hz, formatHz(state.frequency))
                state.signal -> stringResource(R.string.listening)
                else -> stringResource(R.string.play_a_string)
            },
            style = MaterialTheme.typography.bodyLarge.merge(NumericStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Valeur numérique en cents + consigne (trop bas / juste / parfait / trop haut). */
@Composable
private fun CentsReadout(state: TunerUiState) {
    val cents = state.cents
    val tolerance = state.settings.toleranceCents
    val color = centsColor(cents, tolerance)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (state.holding) 0.45f else 1f)) {
        Text(
            text = cents?.let { stringResource(R.string.cents_value, formatCents(it)) } ?: " ",
            style = MaterialTheme.typography.headlineMedium.merge(NumericStyle),
            color = color,
        )
        Text(
            text = when {
                cents == null -> " "
                isPerfect(cents) -> stringResource(R.string.status_perfect)
                abs(cents) <= tolerance -> stringResource(R.string.status_in_tune)
                cents < 0 -> stringResource(R.string.status_too_low)
                else -> stringResource(R.string.status_too_high)
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (cents != null && abs(cents) <= tolerance) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BottomHints(state: TunerUiState, onDetectionModeChange: (DetectionMode) -> Unit) {
    val settings = state.settings
    val allTuned = state.tunedStrings.size == state.tuning.stringCount
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (settings.tunerMode == TunerMode.MONO) {
            val manual = settings.detectionMode == DetectionMode.MANUAL
            FilterChip(
                selected = manual,
                onClick = { onDetectionModeChange(if (manual) DetectionMode.AUTO else DetectionMode.MANUAL) },
                label = { Text(stringResource(if (manual) R.string.detection_manual else R.string.detection_auto)) },
            )
        }
        Text(
            text = when {
                state.playingString >= 0 -> stringResource(R.string.tone_playing)
                allTuned -> stringResource(R.string.all_strings_tuned)
                settings.tunerMode == TunerMode.POLY -> stringResource(R.string.hint_poly)
                settings.detectionMode == DetectionMode.MANUAL -> stringResource(R.string.hint_manual)
                else -> stringResource(R.string.hint_auto)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (allTuned) TunerTheme.colors.inTune else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Bouton de prise de test : ● pour lancer, ■ rouge pendant la prise. */
@Composable
private fun RecordButton(recording: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (recording) AppIcons.Stop else AppIcons.Record,
            contentDescription = stringResource(if (recording) R.string.cd_record_stop else R.string.cd_record_start),
            tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Niveau micro discret : « écoute… » + barre de niveau (+ durée de la prise en cours). */
@Composable
private fun LevelIndicator(state: TunerUiState) {
    val level by animateFloatAsState(state.level, tween(120), label = "level")
    val colors = TunerTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.recording) {
            val description = stringResource(R.string.cd_recording)
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .semantics { contentDescription = description },
            )
            Text(
                text = formatClock(state.recordingSeconds),
                style = MaterialTheme.typography.labelSmall.merge(NumericStyle),
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
            )
        }
        Text(
            text = if (state.signal) " " else stringResource(R.string.listening),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Box(
            Modifier
                .width(96.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level)
                    .height(4.dp)
                    .background(if (state.signal) MaterialTheme.colorScheme.primary else colors.tick),
            )
        }
    }
}

@Composable
private fun EngineErrorCard(error: EngineError, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(if (error == EngineError.READ_FAILED) R.string.error_read else R.string.error_unavailable),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}
