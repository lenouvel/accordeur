package com.blenouvel.accordeur

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blenouvel.accordeur.audio.ReferenceTone
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.model.FretLabels
import com.blenouvel.accordeur.model.FretNote
import com.blenouvel.accordeur.model.FretPosition
import com.blenouvel.accordeur.model.FretboardMap
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.RunStep
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
    /** Mode « positions » : n'éclairer qu'une box à la fois (le reste du manche est atténué). */
    val positionMode: Boolean = false,
    /** Nombre de positions disponibles pour la gamme et l'accordage courants. */
    val positionCount: Int = 0,
    /** Indice de la position (box) active, ou −1 hors mode positions. */
    val positionIndex: Int = -1,
    /** Cases de la box active (clés [ScalesViewModel.cellKey]) ; null hors mode positions. */
    val positionCells: Set<Int>? = null,
)

class ScalesViewModel(
    private val settingsStore: SettingsStore,
    private val tone: ReferenceTone,
) : ViewModel() {

    private val highlighted = MutableStateFlow<Set<Int>>(emptySet())
    private val interactive = MutableStateFlow(false)

    /** Mode positions : manche restreint (atténué) à une box à la fois. */
    private val positionMode = MutableStateFlow(false)

    /** Box choisie à la main (−1 = automatique : la box de la fondamentale). */
    private val positionChoice = MutableStateFlow(-1)

    /**
     * Position dans la séquence du guide. Hors mode positions : indice dans [runSequence]. En mode
     * positions : indice dans le parcours [FretboardMap.run] qui enchaîne les box.
     */
    private val step = MutableStateFlow(0)

    /** Hauteur MIDI de la dernière note validée (pour le focus « case la plus proche »). */
    private val lastMidi = MutableStateFlow(-1)

    /** Classe de hauteur entendue en dernier (anti-rebond : une note tenue ne compte qu'une fois). */
    private var lastHeardPc = -1

    private data class Guide(
        val interactive: Boolean,
        val positionMode: Boolean,
        val positionChoice: Int,
        val step: Int,
        val lastMidi: Int,
    )

    private val guide = combine(interactive, positionMode, positionChoice, step, lastMidi) { i, pm, pc, st, lm ->
        Guide(i, pm, pc, st, lm)
    }

    val uiState: StateFlow<ScalesUiState> =
        combine(settingsStore.settings, highlighted, guide) { settings, marks, g ->
            val scale = ScaleCatalog.byId(settings.scaleId)
            val notes = FretboardMap.notes(settings.tuning, settings.scaleRoot, scale, settings.scaleFrets)
            val positions = FretboardMap.positions(settings.tuning, settings.scaleRoot, scale, settings.scaleFrets)
            val inPositionMode = g.positionMode && positions.isNotEmpty()

            var focusDegree = -1
            var focusCells: Set<Int> = emptySet()
            var activeBox = -1
            var cells: Set<Int>? = null

            if (inPositionMode) {
                if (g.interactive) {
                    val run = FretboardMap.run(positions)
                    val current = run.getOrNull(if (run.isEmpty()) 0 else g.step.mod(run.size))
                    activeBox = current?.position ?: resolveBox(g.positionChoice, positions)
                    focusDegree = current?.degree ?: -1
                    focusCells = current?.let { setOf(cellKey(it.string, it.fret)) } ?: emptySet()
                } else {
                    activeBox = resolveBox(g.positionChoice, positions)
                }
                cells = positions.getOrNull(activeBox)?.cells
            } else if (g.interactive) {
                val sequence = runSequence(scale.size)
                focusDegree = if (sequence.isNotEmpty()) sequence[g.step.mod(sequence.size)] else -1
                focusCells = focusCells(notes, focusDegree, settings.scaleFocusNearest, g.lastMidi)
            }

            ScalesUiState(
                loaded = true,
                settings = settings,
                scale = scale,
                spelling = ScaleSpeller.spell(settings.scaleRoot, scale),
                notes = notes,
                highlighted = marks.filterTo(HashSet()) { it in 1 until scale.size },
                interactive = g.interactive,
                focusDegree = focusDegree,
                focusCells = focusCells,
                positionMode = inPositionMode,
                positionCount = positions.size,
                positionIndex = activeBox,
                positionCells = cells,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScalesUiState())

    fun setRoot(pitchClass: Int) {
        resetGuide()
        positionChoice.value = -1
        viewModelScope.launch { settingsStore.setScaleRoot(pitchClass) }
    }

    fun setScale(id: String) {
        if (id != uiState.value.scale.id) {
            highlighted.value = emptySet()
            positionChoice.value = -1
            resetGuide()
        }
        viewModelScope.launch { settingsStore.setScale(id) }
    }

    fun setFrets(frets: Int) {
        positionChoice.value = -1
        resetGuide()
        viewModelScope.launch { settingsStore.setScaleFrets(frets) }
    }

    fun setLabels(labels: FretLabels) {
        viewModelScope.launch { settingsStore.setScaleLabels(labels) }
    }

    /**
     * Active/désactive le mode interactif (guide de gamme au micro). À l'activation en mode
     * positions, le guide démarre sur la position affichée (et non à la première).
     */
    fun toggleInteractive() {
        val turningOn = !interactive.value
        interactive.value = turningOn
        lastMidi.value = -1
        lastHeardPc = -1
        step.value = if (turningOn && positionMode.value) startStepForBox(resolveBox(positionChoice.value, currentPositions())) else 0
    }

    /** Active/désactive le mode positions (une box à la fois). Repart de la box de la fondamentale. */
    fun togglePositions() {
        positionMode.update { !it }
        positionChoice.value = -1
        resetGuide()
    }

    /** Sélectionne la box [index] à la main ; en mode interactif, le guide reprend au début de la box. */
    fun setPosition(index: Int) {
        positionChoice.value = index
        if (interactive.value) {
            step.value = startStepForBox(index)
            lastHeardPc = -1
        }
    }

    /** Positions de la gamme pour les réglages courants. */
    private fun currentPositions(): List<FretPosition> {
        val s = uiState.value
        return FretboardMap.positions(s.settings.tuning, s.settings.scaleRoot, s.scale, s.settings.scaleFrets)
    }

    /** Indice, dans le parcours, de la première note de la box [box] (0 si absente). */
    private fun startStepForBox(box: Int): Int {
        val start = FretboardMap.run(currentPositions()).indexOfFirst { it.position == box }
        return if (start >= 0) start else 0
    }

    /** Note jouée détectée (hauteur MIDI). Fait avancer le guide si elle correspond au focus. */
    fun onNotePlayed(midi: Int) {
        if (!interactive.value) return
        val pc = midi.mod(12)
        if (pc == lastHeardPc) return // même note qui dure : ne compte qu'une fois
        lastHeardPc = pc
        val s = uiState.value
        if (s.positionMode) {
            val positions = FretboardMap.positions(s.settings.tuning, s.settings.scaleRoot, s.scale, s.settings.scaleFrets)
            val run = FretboardMap.run(positions)
            if (run.isEmpty()) return
            val current = run[step.value.mod(run.size)]
            if (pc == current.midi.mod(12)) {
                lastMidi.value = current.midi
                step.value = (step.value + 1).mod(run.size)
            }
            return
        }
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

        /** Box à afficher : le choix manuel s'il est valide, sinon la box de la fondamentale. */
        private fun resolveBox(choice: Int, positions: List<FretPosition>): Int =
            if (choice in positions.indices) choice else FretboardMap.rootPosition(positions)

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
