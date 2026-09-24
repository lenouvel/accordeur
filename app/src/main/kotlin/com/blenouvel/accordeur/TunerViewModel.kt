package com.blenouvel.accordeur

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blenouvel.accordeur.audio.AudioEngine
import com.blenouvel.accordeur.audio.EngineState
import com.blenouvel.accordeur.audio.PolyReading
import com.blenouvel.accordeur.audio.ReferenceTone
import com.blenouvel.accordeur.audio.TunerFrame
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.data.DetectionMode
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.data.ThemeMode
import com.blenouvel.accordeur.model.CustomTuningCodec
import com.blenouvel.accordeur.model.GuitarString
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** État complet de l'écran, recalculé à chaque analyse (~23 fois par seconde). */
data class TunerUiState(
    val settings: Settings = Settings(),
    val tuning: Tuning = Presets.DEFAULT,
    val engineState: EngineState = EngineState.Idle,
    /** Signal utile présent (sinon « écoute… »). */
    val signal: Boolean = false,
    /** Niveau micro normalisé 0…1. */
    val level: Float = 0f,
    /** Note affichée : note chromatique la plus proche (auto) ou corde verrouillée (manuel). */
    val note: Note? = null,
    /** Fréquence mesurée (Hz). */
    val frequency: Double? = null,
    /** Écart à la note affichée, en cents. */
    val cents: Double? = null,
    /** Valeur maintenue après la fin du son. */
    val holding: Boolean = false,
    /** Corde jouée (surlignée), −1 si aucune. */
    val activeString: Int = -1,
    /** Corde verrouillée, −1 en mode auto. */
    val lockedString: Int = -1,
    /** Cordes déjà accordées (« au vert »). */
    val tunedStrings: Set<Int> = emptySet(),
    /** Incrémenté quand une corde devient juste (déclenche l'haptique). */
    val tunedEvents: Int = 0,
    /** Mesures du mode poly. */
    val poly: PolyReading? = null,
    /** Corde dont le son de référence est en cours de lecture, −1 sinon. */
    val playingString: Int = -1,
) {
    val inTune: Boolean get() = cents?.let { abs(it) <= settings.toleranceCents } ?: false
}

class TunerViewModel(
    private val settingsStore: SettingsStore,
    private val engine: AudioEngine,
    private val referenceTone: ReferenceTone,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TunerUiState())
    val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()

    private var settings = Settings()
    private var settingsLoaded = false
    private var lockedString = -1
    private var tunedStrings = emptySet<Int>()
    private var tunedEvents = 0
    private var streakString = -1
    private var inTuneStreak = 0
    private var outOfTuneStreak = 0
    private var toneJob: Job? = null

    init {
        viewModelScope.launch { settingsStore.settings.collect { onSettings(it) } }
        viewModelScope.launch { engine.frames.collect { onFrame(it) } }
        viewModelScope.launch { engine.state.collect { s -> _uiState.update { it.copy(engineState = s) } } }
    }

    // --- Cycle de vie --------------------------------------------------------------------

    fun start() = engine.start()

    fun stop() {
        stopReferenceTone()
        engine.stop()
    }

    override fun onCleared() {
        stop()
    }

    // --- Actions de l'écran principal --------------------------------------------------

    /** Tap sur une corde : verrou/déverrouillage (auto), sélection (manuel), passage en mono (poly). */
    fun onStringTapped(index: Int) {
        if (index !in settings.tuning.strings.indices) return
        when {
            settings.tunerMode == TunerMode.POLY -> {
                lockedString = index
                viewModelScope.launch { settingsStore.setTunerMode(TunerMode.MONO) }
            }
            settings.detectionMode == DetectionMode.AUTO && lockedString == index -> lockedString = -1
            else -> lockedString = index
        }
        resetStreak()
        publishLock()
    }

    fun setTunerMode(mode: TunerMode) {
        viewModelScope.launch { settingsStore.setTunerMode(mode) }
    }

    fun setDetectionMode(mode: DetectionMode) {
        lockedString = if (mode == DetectionMode.MANUAL) {
            when {
                lockedString >= 0 -> lockedString
                _uiState.value.activeString >= 0 -> _uiState.value.activeString
                else -> 0
            }
        } else {
            -1
        }
        publishLock()
        viewModelScope.launch { settingsStore.setDetectionMode(mode) }
    }

    fun selectTuning(id: String) {
        viewModelScope.launch { settingsStore.selectTuning(id) }
    }

    /** Joue le son de référence de la corde [index] ; l'analyse est suspendue pendant la lecture. */
    fun playReferenceTone(index: Int) {
        val string = settings.tuning.strings.getOrNull(index) ?: return
        toneJob?.cancel()
        engine.muteFor(ReferenceTone.DURATION_MS + TONE_ECHO_MS)
        _uiState.update { it.copy(playingString = index) }
        toneJob = viewModelScope.launch {
            referenceTone.play(string.frequency(settings.a4))
            delay(ReferenceTone.DURATION_MS.toLong())
            referenceTone.stop()
            _uiState.update { it.copy(playingString = -1) }
        }
    }

    fun stopReferenceTone() {
        toneJob?.cancel()
        toneJob = null
        referenceTone.stop()
        engine.unmute()
        _uiState.update { it.copy(playingString = -1) }
    }

    // --- Réglages ------------------------------------------------------------------------

    fun setA4(value: Double) = launchSetting { settingsStore.setA4(value) }
    fun setTolerance(cents: Int) = launchSetting { settingsStore.setTolerance(cents) }
    fun setNotation(value: Notation) = launchSetting { settingsStore.setNotation(value) }
    fun setTheme(value: ThemeMode) = launchSetting { settingsStore.setTheme(value) }
    fun setDynamicColor(value: Boolean) = launchSetting { settingsStore.setDynamicColor(value) }
    fun setHaptics(value: Boolean) = launchSetting { settingsStore.setHaptics(value) }
    fun setKeepScreenOn(value: Boolean) = launchSetting { settingsStore.setKeepScreenOn(value) }

    /** Crée ([id] = null) ou modifie un accordage personnalisé, puis le sélectionne. */
    fun saveCustomTuning(id: String?, name: String, notes: List<Note>) {
        if (notes.size !in Tuning.MIN_STRINGS..Tuning.MAX_STRINGS) return
        val tuning = Tuning(
            id = id ?: CustomTuningCodec.newId(System.currentTimeMillis()),
            name = CustomTuningCodec.sanitize(name),
            strings = notes.map { GuitarString.of(it) },
            isCustom = true,
        )
        launchSetting { settingsStore.saveCustomTuning(tuning) }
    }

    fun deleteCustomTuning(id: String) = launchSetting { settingsStore.deleteCustomTuning(id) }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // --- Flux entrants -----------------------------------------------------------------

    private fun onSettings(new: Settings) {
        val old = settings
        settings = new
        if (settingsLoaded && old.tuning != new.tuning) {
            lockedString = -1
            tunedStrings = emptySet()
            resetStreak()
        }
        if (new.detectionMode == DetectionMode.MANUAL && lockedString !in new.tuning.strings.indices) {
            lockedString = 0
        }
        if (settingsLoaded && old.detectionMode == DetectionMode.MANUAL && new.detectionMode == DetectionMode.AUTO) {
            lockedString = -1
        }
        if (lockedString >= new.tuning.stringCount) lockedString = -1
        settingsLoaded = true
        pushTargets()
        _uiState.update {
            it.copy(
                settings = new,
                tuning = new.tuning,
                lockedString = lockedString,
                tunedStrings = tunedStrings,
                poly = if (new.tunerMode == TunerMode.POLY) it.poly else null,
            )
        }
    }

    private fun onFrame(frame: TunerFrame?) {
        if (frame == null) {
            resetStreak()
            _uiState.update {
                it.copy(signal = false, level = 0f, note = null, frequency = null, cents = null, holding = false, activeString = -1, poly = null)
            }
            return
        }
        val level = ((frame.levelDb - LEVEL_FLOOR_DB) / LEVEL_RANGE_DB).toFloat().coerceIn(0f, 1f)
        if (settings.tunerMode == TunerMode.POLY) {
            onPolyFrame(frame, level)
            return
        }
        if (!frame.hasPitch) {
            resetStreak()
            _uiState.update {
                it.copy(
                    signal = frame.signal, level = level, note = null, frequency = null, cents = null,
                    holding = false, activeString = lockedString, poly = null,
                )
            }
            return
        }

        val tuning = settings.tuning
        val a4 = settings.a4
        val frequency = frame.frequency
        val note: Note
        val cents: Double
        val stringIndex: Int
        if (lockedString in tuning.strings.indices) {
            note = tuning.strings[lockedString].note
            cents = NoteMapper.centsFrom(frequency, note, a4)
            stringIndex = lockedString
        } else {
            val reading = NoteMapper.nearest(frequency, a4)
            note = reading.note
            cents = reading.cents
            stringIndex = matchingString(tuning, note)
        }
        if (!frame.holding) trackTuned(stringIndex, cents)

        _uiState.update {
            it.copy(
                signal = frame.signal,
                level = level,
                note = note,
                frequency = frequency,
                cents = cents,
                holding = frame.holding,
                activeString = stringIndex,
                lockedString = lockedString,
                tunedStrings = tunedStrings,
                tunedEvents = tunedEvents,
                poly = null,
            )
        }
    }

    private fun onPolyFrame(frame: TunerFrame, level: Float) {
        val poly = frame.poly
        if (poly != null && poly.strings.size == settings.tuning.stringCount) {
            val tolerance = settings.toleranceCents
            val updated = tunedStrings.toMutableSet()
            poly.strings.forEachIndexed { i, s ->
                if (s.detected) {
                    if (abs(s.cents) <= tolerance) updated += i else updated -= i
                }
            }
            tunedStrings = updated
        }
        _uiState.update {
            it.copy(
                signal = frame.signal, level = level, note = null, frequency = null, cents = null,
                holding = false, activeString = -1, tunedStrings = tunedStrings, poly = poly,
            )
        }
    }

    /** Corde de l'accordage correspondant à la note (préférence à la corde déjà surlignée). */
    private fun matchingString(tuning: Tuning, note: Note): Int {
        val current = _uiState.value.activeString
        if (current in tuning.strings.indices && tuning.strings[current].midi == note.midi) return current
        return tuning.strings.indexOfFirst { it.midi == note.midi }
    }

    /**
     * Une corde passe « au vert » après ~0,35 s dans la tolérance (haptique une seule fois),
     * et en sort si elle est mesurée franchement fausse pendant la même durée.
     */
    private fun trackTuned(stringIndex: Int, cents: Double) {
        if (stringIndex < 0) {
            resetStreak()
            return
        }
        if (stringIndex != streakString) {
            streakString = stringIndex
            inTuneStreak = 0
            outOfTuneStreak = 0
        }
        val tolerance = settings.toleranceCents.toDouble()
        if (abs(cents) <= tolerance) {
            inTuneStreak++
            outOfTuneStreak = 0
            if (inTuneStreak >= STREAK_FRAMES && stringIndex !in tunedStrings) {
                tunedStrings = tunedStrings + stringIndex
                tunedEvents++
            }
        } else {
            inTuneStreak = 0
            if (abs(cents) > 2 * tolerance) outOfTuneStreak++
            if (outOfTuneStreak >= STREAK_FRAMES && stringIndex in tunedStrings) {
                tunedStrings = tunedStrings - stringIndex
            }
        }
    }

    private fun resetStreak() {
        streakString = -1
        inTuneStreak = 0
        outOfTuneStreak = 0
    }

    private fun publishLock() {
        pushTargets()
        _uiState.update { it.copy(lockedString = lockedString, activeString = if (lockedString >= 0) lockedString else it.activeString) }
    }

    private fun pushTargets() {
        engine.mode = settings.tunerMode
        engine.targets = TunerTargets(
            stringsHz = settings.tuning.frequencies(settings.a4),
            lockedIndex = if (settings.tunerMode == TunerMode.MONO) lockedString else -1,
        )
    }

    private companion object {
        /** ~0,35 s à 23 analyses/s. */
        const val STREAK_FRAMES = 8
        const val TONE_ECHO_MS = 400L
        const val LEVEL_FLOOR_DB = -100.0
        const val LEVEL_RANGE_DB = 70.0
    }
}
