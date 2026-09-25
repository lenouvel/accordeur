package com.blenouvel.accordeur

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blenouvel.accordeur.audio.AudioEngine
import com.blenouvel.accordeur.audio.EngineState
import com.blenouvel.accordeur.audio.MicSource
import com.blenouvel.accordeur.audio.PolyReading
import com.blenouvel.accordeur.audio.RecordingContext
import com.blenouvel.accordeur.audio.ReferenceTone
import com.blenouvel.accordeur.audio.SpectrumFrame
import com.blenouvel.accordeur.audio.TunerFrame
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.data.DetectionMode
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.data.SoundBank
import com.blenouvel.accordeur.data.ThemeMode
import com.blenouvel.accordeur.model.CustomTuningCodec
import com.blenouvel.accordeur.model.GuitarString
import com.blenouvel.accordeur.model.Harmony
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Page affichée. Le micro sert à l'accordeur (et à ses réglages) et au spectre, pas aux gammes. */
enum class Page { TUNER, SCALES, SPECTRUM, SETTINGS }

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
    /** Une prise de test est en cours (bouton ●). */
    val recording: Boolean = false,
    /** Durée de la prise en cours (s). */
    val recordingSeconds: Int = 0,
    /** Bilan de la banque de sons (null tant qu'il n'a pas été lu). */
    val bank: SoundBank.Stats? = null,
    /** Archive d'export de la banque en préparation. */
    val exporting: Boolean = false,
) {
    val inTune: Boolean get() = cents?.let { abs(it) <= settings.toleranceCents } ?: false
}

/** Page Spectre : spectre du moment, et note ou accord affiché (stabilisé, maintenu). */
data class SpectrumUiState(
    val frame: SpectrumFrame? = null,
    /** Note ou accord affiché sous le spectre. */
    val harmony: Harmony? = null,
    /** Plus rien n'est entendu : [harmony] est la dernière valeur, à afficher atténuée. */
    val holding: Boolean = false,
    /** Signal utile présent. */
    val signal: Boolean = false,
)

class TunerViewModel(
    private val settingsStore: SettingsStore,
    private val engine: AudioEngine,
    private val referenceTone: ReferenceTone,
    private val bank: SoundBank,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TunerUiState())
    val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()

    private val _spectrumState = MutableStateFlow(SpectrumUiState())
    val spectrumState: StateFlow<SpectrumUiState> = _spectrumState.asStateFlow()

    /** Harmonies des dernières trames : on affiche la plus fréquente (pas de clignotement). */
    private val recentHarmonies = ArrayDeque<Harmony?>()
    private var shownHarmony: Harmony? = null

    private var settings = Settings()
    private var settingsLoaded = false
    private var lockedString = -1
    private var tunedStrings = emptySet<Int>()
    private var tunedEvents = 0
    private var streakString = -1
    private var inTuneStreak = 0
    private var outOfTuneStreak = 0
    private var toneJob: Job? = null
    private var page = Page.TUNER
    private var takeStartedAt = 0L

    /** Changer de source relance la capture (arrêt ≤ 43 ms) : hors du fil principal, dans l'ordre. */
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)

    init {
        viewModelScope.launch { settingsStore.settings.collect { onSettings(it) } }
        viewModelScope.launch { engine.frames.collect { onFrame(it) } }
        viewModelScope.launch { engine.state.collect { s -> _uiState.update { it.copy(engineState = s) } } }
        engine.recorder.onBankChanged = { refreshBank() }
    }

    // --- Cycle de vie --------------------------------------------------------------------

    fun start() = engine.start()

    fun stop() {
        stopReferenceTone()
        engine.stop()
    }

    /** Page affichée : quitter l'accordeur termine la prise en cours ; le spectre a sa propre analyse. */
    fun setPage(page: Page) {
        this.page = page
        if (page != Page.TUNER) stopRecording()
        val spectrum = page == Page.SPECTRUM
        if (spectrum != engine.spectrum) {
            engine.spectrum = spectrum
            recentHarmonies.clear()
            shownHarmony = null
            _spectrumState.value = SpectrumUiState()
        }
    }

    // --- Prises de test (bouton ●) --------------------------------------------------------

    fun toggleRecording() {
        if (engine.recorder.armed) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!settings.recordBank || page != Page.TUNER) return
        takeStartedAt = System.nanoTime()
        engine.recorder.startTake()
        _uiState.update { it.copy(recording = true, recordingSeconds = 0) }
    }

    fun stopRecording() {
        engine.recorder.stopTake()
        _uiState.update { it.copy(recording = false, recordingSeconds = 0) }
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
    fun setMicSource(value: MicSource) = launchSetting { settingsStore.setMicSource(value) }
    fun setRecordBank(value: Boolean) = launchSetting { settingsStore.setRecordBank(value) }

    // --- Banque de sons de test --------------------------------------------------------

    fun refreshBank() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = bank.stats()
            _uiState.update { it.copy(bank = stats) }
        }
    }

    /** Prépare l'archive zip de la banque puis la confie à [onReady] (partage). */
    fun exportBank(onReady: (File) -> Unit) {
        if (_uiState.value.exporting) return
        _uiState.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) { runCatching { bank.exportZip() }.getOrNull() }
            _uiState.update { it.copy(exporting = false) }
            if (zip != null) onReady(zip)
        }
    }

    fun clearBank() {
        viewModelScope.launch(Dispatchers.IO) {
            bank.clear()
            refreshBank()
        }
    }

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
        if (!new.recordBank) stopRecording()
        settingsLoaded = true
        pushTargets()
        if (engine.source != new.micSource) {
            viewModelScope.launch(engineDispatcher) { engine.source = new.micSource }
        }
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
        val spectrum = frame?.spectrum
        if (frame != null && spectrum != null) {
            onSpectrumFrame(frame, spectrum)
            return
        }
        if (frame == null) {
            resetStreak()
            _uiState.update {
                it.copy(
                    signal = false, level = 0f, note = null, frequency = null, cents = null, holding = false,
                    activeString = -1, poly = null, recording = false,
                )
            }
            return
        }
        // Prise terminée d'elle-même (durée maximale) : le bouton revient au repos.
        val recording = engine.recorder.armed
        val seconds = if (recording) ((System.nanoTime() - takeStartedAt) / 1_000_000_000L).toInt() else 0
        _uiState.update { it.copy(recording = recording, recordingSeconds = seconds) }
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

    /** Page Spectre : l'harmonie affichée est celle qui domine sur les ~0,35 dernières secondes. */
    private fun onSpectrumFrame(frame: TunerFrame, spectrum: SpectrumFrame) {
        val current = Harmony.of(spectrum.notes.map { Note.fromMidi(it.midi) })
        recentHarmonies.addLast(current)
        while (recentHarmonies.size > VOTE_FRAMES) recentHarmonies.removeFirst()
        val counts = HashMap<String, Int>()
        for (h in recentHarmonies) if (h != null) counts[h.key] = (counts[h.key] ?: 0) + 1
        val best = counts.maxByOrNull { it.value }
        if (best != null && best.value >= VOTE_MIN) shownHarmony = recentHarmonies.last { it?.key == best.key }
        _spectrumState.value = SpectrumUiState(
            frame = spectrum,
            harmony = shownHarmony,
            holding = current == null,
            signal = frame.signal,
        )
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
        val locked = if (settings.tunerMode == TunerMode.MONO) lockedString else -1
        engine.mode = settings.tunerMode
        engine.targets = TunerTargets(stringsHz = settings.tuning.frequencies(settings.a4), lockedIndex = locked)
        engine.recorder.context = RecordingContext(
            mode = settings.tunerMode,
            detection = settings.detectionMode.name,
            lockedString = locked,
            a4 = settings.a4,
            tuningId = settings.tuning.id,
            tuningName = settings.tuning.name,
            tuningNotes = settings.tuning.strings.joinToString(" ") { ASCII_NOTES[it.pitchClass] + it.octave },
        )
        engine.recorder.enabled = settings.recordBank
    }

    private companion object {
        /** Noms de notes des fiches de la banque (lisibles par Note.parse). */
        val ASCII_NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        /** ~0,35 s à 23 analyses/s. */
        const val STREAK_FRAMES = 8

        /** Vote sur les 8 dernières trames (~0,35 s) ; au moins 3 voix pour changer d'harmonie. */
        const val VOTE_FRAMES = 8
        const val VOTE_MIN = 3
        const val TONE_ECHO_MS = 400L
        const val LEVEL_FLOOR_DB = -100.0
        const val LEVEL_RANGE_DB = 70.0
    }
}
