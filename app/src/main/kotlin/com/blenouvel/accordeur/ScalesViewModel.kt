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
)

class ScalesViewModel(
    private val settingsStore: SettingsStore,
    private val tone: ReferenceTone,
) : ViewModel() {

    private val highlighted = MutableStateFlow<Set<Int>>(emptySet())

    val uiState: StateFlow<ScalesUiState> = combine(settingsStore.settings, highlighted) { settings, marks ->
        val scale = ScaleCatalog.byId(settings.scaleId)
        ScalesUiState(
            loaded = true,
            settings = settings,
            scale = scale,
            spelling = ScaleSpeller.spell(settings.scaleRoot, scale),
            notes = FretboardMap.notes(settings.tuning, settings.scaleRoot, scale, settings.scaleFrets),
            highlighted = marks.filterTo(HashSet()) { it in 1 until scale.size },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ScalesUiState())

    fun setRoot(pitchClass: Int) {
        viewModelScope.launch { settingsStore.setScaleRoot(pitchClass) }
    }

    fun setScale(id: String) {
        if (id != uiState.value.scale.id) highlighted.value = emptySet()
        viewModelScope.launch { settingsStore.setScale(id) }
    }

    fun setFrets(frets: Int) {
        viewModelScope.launch { settingsStore.setScaleFrets(frets) }
    }

    fun setLabels(labels: FretLabels) {
        viewModelScope.launch { settingsStore.setScaleLabels(labels) }
    }

    fun setLeftHanded(leftHanded: Boolean) {
        viewModelScope.launch { settingsStore.setLeftHanded(leftHanded) }
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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
