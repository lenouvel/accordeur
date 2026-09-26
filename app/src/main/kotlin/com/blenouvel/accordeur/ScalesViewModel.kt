package com.blenouvel.accordeur

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blenouvel.accordeur.audio.ReferenceTone
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.model.FretLabels
import com.blenouvel.accordeur.model.FretNote
import com.blenouvel.accordeur.model.FretboardMap
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.ScaleCatalog
import com.blenouvel.accordeur.model.ScaleSpeller
import com.blenouvel.accordeur.model.ScaleType
import com.blenouvel.accordeur.model.SpelledNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** État de la vue « Gammes ». */
data class ScalesUiState(
    /** Faux tant que les réglages ne sont pas chargés (évite d'afficher un manche par défaut). */
    val loaded: Boolean = false,
    val settings: Settings = Settings(),
    val scale: ScaleType = ScaleCatalog.DEFAULT,
    /** Notes de la gamme orthographiées, fondamentale en premier. */
    val spelling: List<SpelledNote> = ScaleSpeller.spell(0, ScaleCatalog.DEFAULT),
    /** Toutes les positions de la gamme sur le manche affiché. */
    val notes: List<FretNote> = emptyList(),
    /** Degrés mis en évidence par l'utilisateur (indices ≥ 1 ; la fondamentale l'est toujours). */
    val highlighted: Set<Int> = emptySet(),
    /** Mode interactif : on suit ce qui est joué et on met en focus la note suivante de la gamme. */
    val interactive: Boolean = false,
    /** Degré (indice dans la gamme) mis en focus par le guide, ou −1. */
    val focusDegree: Int = -1,
    /** Cases en focus (clés [ScalesViewModel.cellKey]) : la note suivante à jouer. */
    val focusCells: Set<Int> = emptySet(),
)

class ScalesViewModel(
    private val settingsStore: SettingsStore,
    private val tone: ReferenceTone,
) : ViewModel() {

    private val highlighted = MutableStateFlow<Set<Int>>(emptySet())
    private val interactive = MutableStateFlow(false)

    /** Position dans la séquence du guide (ascendante puis descendante, voir [runSequence]). */
    private val step = MutableStateFlow(0)

    /** Hauteur MIDI de la dernière note validée (pour le focus « case la plus proche »). */
    private val lastMidi = MutableStateFlow(-1)

    /** Classe de hauteur entendue en dernier (anti-rebond : une note tenue ne compte qu'une fois). */
    private var lastHeardPc = -1

    val uiState: StateFlow<ScalesUiState> =
        combine(settingsStore.settings, highlighted, interactive, step, lastMidi) { settings, marks, interactive, step, lastMidi ->
            val scale = ScaleCatalog.byId(settings.scaleId)
            val notes = FretboardMap.notes(settings.tuning, settings.scaleRoot, scale, settings.scaleFrets)
            val sequence = runSequence(scale.size)
            val focusDegree = if (interactive && sequence.isNotEmpty()) sequence[step.mod(sequence.size)] else -1
            ScalesUiState(
                loaded = true,
                settings = settings,
                scale = scale,
                spelling = ScaleSpeller.spell(settings.scaleRoot, scale),
                notes = notes,
                highlighted = marks.filterTo(HashSet()) { it in 1 until scale.size },
                interactive = interactive,
                focusDegree = focusDegree,
                focusCells = focusCells(notes, focusDegree, settings.scaleFocusNearest, lastMidi),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScalesUiState())

    fun setRoot(pitchClass: Int) {
        viewModelScope.launch { settingsStore.setScaleRoot(pitchClass) }
    }

    fun setScale(id: String) {
        if (id != uiState.value.scale.id) {
            highlighted.value = emptySet()
            resetGuide()
        }
        viewModelScope.launch { settingsStore.setScale(id) }
    }

    fun setFrets(frets: Int) {
        viewModelScope.launch { settingsStore.setScaleFrets(frets) }
    }

    fun setLabels(labels: FretLabels) {
        viewModelScope.launch { settingsStore.setScaleLabels(labels) }
    }

    /** Active/désactive le mode interactif (guide de gamme au micro). Remet le guide au début. */
    fun toggleInteractive() {
        interactive.update { !it }
        resetGuide()
    }

    /** Note jouée détectée (hauteur MIDI). Fait avancer le guide si elle correspond au focus. */
    fun onNotePlayed(midi: Int) {
        if (!interactive.value) return
        val pc = midi.mod(12)
        if (pc == lastHeardPc) return // même note qui dure : ne compte qu'une fois
        lastHeardPc = pc
        val s = uiState.value
        val degree = s.focusDegree
        if (degree < 0 || degree >= s.scale.intervals.size) return
        val focusPc = (s.settings.scaleRoot + s.scale.intervals[degree]).mod(12)
        if (pc == focusPc) {
            lastMidi.value = midi
            val len = runSequence(s.scale.size).size
            if (len > 0) step.value = (step.value + 1).mod(len)
        }
    }

    /** Plus de signal : la prochaine note (même hauteur) recomptera. */
    fun onSilence() {
        lastHeardPc = -1
    }

    private fun resetGuide() {
        step.value = 0
        lastMidi.value = -1
        lastHeardPc = -1
    }

    /** Met en évidence (ou non) un degré de la gamme, ex. 3 et 5 pour voir l'arpège. */
    fun toggleDegree(index: Int) {
        if (index <= 0) return
        highlighted.update { if (index in it) it - index else it + index }
    }

    fun clearHighlights() {
        highlighted.value = emptySet()
    }

    /** Joue la note MIDI [midi] (case touchée sur le manche). */
    fun play(midi: Int) {
        val a4 = uiState.value.settings.a4
        viewModelScope.launch { tone.play(NoteMapper.frequencyOf(midi, a4), ReferenceTone.NOTE_MS) }
    }

    override fun onCleared() {
        tone.stop()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Clé compacte d'une case (corde, frette) pour l'ensemble des cases en focus. */
        fun cellKey(string: Int, fret: Int): Int = string * 100 + fret

        /**
         * Séquence des degrés du guide : montée 0…n−1, fondamentale à l'octave, puis descente
         * n−1…1 (longueur 2n), en boucle. Vide si la gamme n'a pas de degré.
         */
        fun runSequence(n: Int): IntArray {
            if (n <= 0) return IntArray(0)
            if (n == 1) return intArrayOf(0)
            val seq = IntArray(2 * n)
            for (i in 0 until n) seq[i] = i // montée 0..n-1
            seq[n] = 0 // fondamentale à l'octave (sommet)
            for (i in 1 until n) seq[n + i] = n - i // descente n-1..1
            return seq
        }

        private fun focusCells(notes: List<FretNote>, focusDegree: Int, nearest: Boolean, lastMidi: Int): Set<Int> {
            if (focusDegree < 0) return emptySet()
            val matches = notes.filter { it.degree == focusDegree }
            if (matches.isEmpty()) return emptySet()
            if (!nearest) return matches.mapTo(HashSet()) { cellKey(it.string, it.fret) }
            // La plus proche de la dernière note jouée (ou la plus grave au démarrage).
            val target = if (lastMidi >= 0) lastMidi else matches.minOf { it.midi }
            val best = matches.minByOrNull { kotlin.math.abs(it.midi - target) } ?: return emptySet()
            return setOf(cellKey(best.string, best.fret))
        }
    }
}
