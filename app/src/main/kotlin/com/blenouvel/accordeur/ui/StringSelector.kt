package com.blenouvel.accordeur.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blenouvel.accordeur.R
import com.blenouvel.accordeur.audio.PolyString
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Tuning
import com.blenouvel.accordeur.ui.theme.TunerTheme

/**
 * Rangée des cordes (grave → aiguë). La corde jouée s'auto-surligne, un tap la verrouille,
 * un appui long joue son son de référence ; chaque corde passe au vert une fois juste.
 */
@Composable
fun StringSelector(
    tuning: Tuning,
    notation: Notation,
    activeString: Int,
    lockedString: Int,
    tunedStrings: Set<Int>,
    playingString: Int,
    poly: List<PolyString>?,
    toleranceCents: Int,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        tuning.strings.forEachIndexed { index, string ->
            StringChip(
                label = NoteNames.primary(string.pitchClass, notation),
                octave = string.octave,
                secondary = NoteNames.secondary(string.pitchClass, notation),
                number = tuning.stringCount - index,
                active = index == activeString,
                locked = index == lockedString,
                tuned = index in tunedStrings,
                playing = index == playingString,
                polyCents = poly?.getOrNull(index)?.takeIf { it.detected }?.cents,
                polyFresh = poly?.getOrNull(index)?.fresh ?: false,
                toleranceCents = toleranceCents,
                onTap = { onTap(index) },
                onLongPress = { onLongPress(index) },
                modifier = Modifier.weight(1f).widthIn(max = 60.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StringChip(
    label: String,
    octave: Int,
    secondary: String?,
    number: Int,
    active: Boolean,
    locked: Boolean,
    tuned: Boolean,
    playing: Boolean,
    polyCents: Double?,
    polyFresh: Boolean,
    toleranceCents: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tunerColors = TunerTheme.colors
    val polyColor = polyCents?.let { centsColor(it, toleranceCents) }
    val background by animateColorAsState(
        targetValue = when {
            active && tuned -> tunerColors.inTune
            active -> scheme.primary
            else -> scheme.surfaceContainerHigh
        },
        animationSpec = tween(200),
        label = "stringBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            active -> scheme.onPrimary
            tuned -> tunerColors.inTune
            else -> scheme.onSurface
        },
        animationSpec = tween(200),
        label = "stringContent",
    )
    val borderColor = when {
        polyColor != null -> polyColor
        locked -> scheme.primary
        tuned -> tunerColors.inTune
        else -> Color.Transparent
    }
    val pulse = if (playing) {
        val transition = rememberInfiniteTransition(label = "tonePulse")
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
            label = "tonePulseScale",
        )
        scale
    } else {
        1f
    }
    val stateDescription = buildList {
        add(stringResource(R.string.string_number, number))
        add("$label$octave")
        if (locked) add(stringResource(R.string.cd_locked))
        if (tuned) add(stringResource(R.string.cd_tuned))
    }.joinToString(", ")
    val selectLabel = stringResource(R.string.cd_select_string)
    val toneLabel = stringResource(R.string.cd_play_tone)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .scale(pulse)
                .clip(CircleShape)
                .background(background)
                .then(if (borderColor != Color.Transparent) Modifier.border(2.5.dp, borderColor, CircleShape) else Modifier)
                .combinedClickable(
                    onClickLabel = selectLabel,
                    onLongClickLabel = toneLabel,
                    onLongClick = onLongPress,
                    onClick = onTap,
                )
                .semantics { contentDescription = stateDescription },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = label,
                    color = content,
                    fontSize = if (label.length > 3) 13.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = octave.toString(),
                    color = content.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (locked) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 3.dp)
                        .size(10.dp),
                )
            } else if (tuned && !active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = tunerColors.inTune,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 3.dp)
                        .size(10.dp),
                )
            }
        }
        if (secondary != null) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (polyCents != null) {
            Text(
                text = formatCents(polyCents),
                style = MaterialTheme.typography.labelSmall,
                color = centsColor(polyCents, toleranceCents).copy(alpha = if (polyFresh) 1f else HELD_ALPHA),
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
