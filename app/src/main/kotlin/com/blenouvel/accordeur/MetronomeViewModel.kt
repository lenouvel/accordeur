package com.blenouvel.accordeur

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blenouvel.accordeur.audio.Metronome
import com.blenouvel.accordeur.audio.MetronomeTick
import com.blenouvel.accordeur.audio.TempoListener
import com.blenouvel.accordeur.audio.TempoTakeMeta
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.model.ClickSound
import com.blenouvel.accordeur.model.DynamicVolume
import com.blenouvel.accordeur.model.MetronomeConfig
import com.blenouvel.accordeur.model.SilentMeasures
import com.blenouvel.accordeur.model.Subdivision
import com.blenouvel.accordeur.model.TapTempo
import com.blenouvel.accordeur.model.TempoAutomation
import com.blenouvel.accordeur.model.TempoGrader
import com.blenouvel.accordeur.model.TempoPhase
import com.blenouvel.accordeur.model.TempoVisual
import com.blenouvel.accordeur.model.TimingReading
import com.blenouvel.accordeur.model.gradingDivision
import com.blenouvel.accordeur.model.tempoName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/** État de la vue « Métronome ». */
data class MetronomeUiState(
    val config: MetronomeConfig = MetronomeConfig(),
    val running: Boolean = false,
    val tick: MetronomeTick? = null,
    /** Nom de tempo italien du BPM affiché (tempo courant si automation en cours). */
    val tempoName: String = tempoName(MetronomeConfig().bpm),
    /** Suivi du tempo au micro activé. */
    val listen: Boolean = false,
    /** Métronome au casque (pas de filtrage du clic). */
    val listenHeadphones: Boolean = false,
    val listenLatencyMs: Int = 0,
    val tempoVisual: TempoVisual = TempoVisual.METER,
    /** Dernière lecture du suivi (null si inactif). */
    val timing: TimingReading? = null,
    /** Enregistrement d'une prise en cours. */
    val recording: Boolean = false,
    val recordingSeconds: Int = 0,
    /** Bilan de la banque tempo. */
    val bankTakes: Int = 0,
    val bankBytes: Long = 0L,
    /** Phase du volume dynamique (null si désactivé). */
    val tempoPhase: TempoPhase? = null,
) {
    /** BPM affiché : celui du clic courant (automation) à l'arrêt on retombe sur le réglage. */
    val displayBpm: Int get() = if (running) tick?.bpm ?: config.bpm else config.bpm
}

class MetronomeViewModel(
    private val settingsStore: SettingsStore,
    private val engine: Metronome,
    private val listener: TempoListener,
) : ViewModel() {

    private val tap = TapTempo()
    private val grader = TempoGrader()
    private val _timing = MutableStateFlow<TimingReading?>(null)
    private val _aux = MutableStateFlow(TempoAux())

    /** Niveau d'entrée micro (0..1) pour le vumètre ; isolé du reste pour ne pas rafraîchir tout l'écran. */
    val inputLevel: StateFlow<Float> = listener.level

    // État touché uniquement depuis le dispatcher principal (collecteurs + start/stop) : pas de course.
    private var currentSettings = Settings()
    private var listenerRunning = false
    private val clickTimes = ArrayDeque<Double>() // instants prévus des clics audibles (ms)
    private val clickOffsets = ArrayDeque<Double>() // écarts attaque − clic proche (ms) : auto-latence
    private var autoLatencyMs = Double.NaN
    private var takeStartedNanos = 0L

    // Volume dynamique : machine à états (prépa → descente → détection → resynchro).
    private var phase: TempoPhase? = null
    private var barsInPhase = 0
    private var lastMeasure = Int.MIN_VALUE
    private var targetGain = DEFAULT_TARGET_GAIN

    /** État annexe du suivi (enregistrement + banque + phase), hors flux de réglages. */
    private data class TempoAux(
        val recording: Boolean = false,
        val recordingSeconds: Int = 0,
        val bankTakes: Int = 0,
        val bankBytes: Long = 0L,
        val tempoPhase: TempoPhase? = null,
    )

    val uiState: StateFlow<MetronomeUiState> =
        combine(settingsStore.settings, engine.running, engine.tick, _timing, _aux) { settings, running, tick, timing, aux ->
            val config = settings.metronome
            val bpm = if (running) tick?.bpm ?: config.bpm else config.bpm
            MetronomeUiState(
                config = config,
                running = running,
                tick = tick,
                tempoName = tempoName(bpm),
                listen = settings.metroListen,
                listenHeadphones = settings.metroListenHeadphones,
                listenLatencyMs = settings.metroListenLatencyMs,
                tempoVisual = settings.metroTempoVisual,
                timing = if (running && settings.metroListen) timing else null,
                recording = aux.recording,
                recordingSeconds = aux.recordingSeconds,
                bankTakes = aux.bankTakes,
                bankBytes = aux.bankBytes,
                tempoPhase = if (running && settings.metroDynEnabled) aux.tempoPhase else null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MetronomeUiState())

    init {
        listener.recorder.onBankChanged = { refreshBank() }
        refreshBank()
        // La config persistée pilote le moteur ; on en tire aussi la grille de notation et l'écoute.
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                currentSettings = settings
                engine.updateConfig(settings.metronome)
                grader.setDivision(settings.metronome.subdivision.gradingDivision)
                syncListener()
            }
        }
        // Temps du métronome et attaques du micro arrivent sur le fil principal : traitement séquentiel.
        viewModelScope.launch {
            merge(
                engine.tick.filterNotNull().map<MetronomeTick, Event> { Event.Tick(it) },
                listener.onsets.map<Long, Event> { Event.Onset(it) },
            ).collect { event ->
                when (event) {
                    is Event.Tick -> handleTick(event.tick)
                    is Event.Onset -> handleOnset(event.nanos)
                }
            }
        }
    }

    private sealed interface Event {
        data class Tick(val tick: MetronomeTick) : Event
        data class Onset(val nanos: Long) : Event
    }

    private fun handleTick(tick: MetronomeTick) {
        val intervalMs = if (tick.bpm > 0) 60_000.0 / tick.bpm else Double.NaN
        val timeMs = tick.timeNanos / 1e6
        if (tick.subIndex == 0) {
            grader.onBeat(timeMs, intervalMs)
            listener.recorder.event('B', tick.timeNanos, tick.bpm.toDouble())
        }
        if (tick.audible) {
            clickTimes.addLast(timeMs)
            while (clickTimes.isNotEmpty() && timeMs - clickTimes.first() > CLICK_MEMORY_MS) clickTimes.removeFirst()
            listener.recorder.event('C', tick.timeNanos)
        }
        // Compteur de durée de la prise (les tops arrivent plusieurs fois par seconde).
        if (listener.recorder.recording) {
            val seconds = ((SystemClock.elapsedRealtimeNanos() - takeStartedNanos) / 1_000_000_000L).toInt()
            _aux.value = _aux.value.copy(recording = true, recordingSeconds = seconds)
        }
        updateDynamics(tick)
    }

    /** Vrai quand on analyse réellement le jeu (hors volume dynamique, ou phase de détection). */
    private fun analyzing(): Boolean = !currentSettings.metroDynEnabled || phase == TempoPhase.DETECT

    /** Volume dynamique : fait avancer la phase aux mesures, calcule le gain du clic, pilote le moteur. */
    private fun updateDynamics(tick: MetronomeTick) {
        val dyn = currentSettings.metronome.dynamics
        if (!dyn.enabled) {
            if (phase != null) {
                phase = null
                _aux.value = _aux.value.copy(tempoPhase = null)
            }
            if (engine.clickGain != 1f) engine.clickGain = 1f
            return
        }
        if (phase == null) {
            phase = TempoPhase.PREP
            barsInPhase = 0
            lastMeasure = tick.measure
        }
        // Avance d'une mesure (hors décompte) à chaque nouvelle mesure.
        if (tick.measure != lastMeasure) {
            lastMeasure = tick.measure
            if (!tick.countIn) {
                barsInPhase++
                advancePhase(dyn)
            }
        }
        // Cible « audible max » : on vise la crête d'entrée juste sous l'écrêtage, en détection.
        if (phase == TempoPhase.DETECT) {
            val lvl = listener.level.value
            if (lvl > 0.97f) targetGain = (targetGain * 0.94f).coerceAtLeast(MIN_TARGET_GAIN)
            else if (lvl < 0.80f) targetGain = (targetGain * 1.03f).coerceAtMost(1f)
        }
        val beats = tick.beatsPerMeasure.coerceAtLeast(1)
        val frac = (tick.beatIndex + 1).toFloat() / beats
        engine.clickGain = when (phase) {
            TempoPhase.PREP, TempoPhase.RESYNC -> 1f
            TempoPhase.DETECT -> targetGain
            TempoPhase.DESCENT -> {
                val p = ((barsInPhase + frac) / dyn.descentBars).coerceIn(0f, 1f)
                val eased = p * p * (3f - 2f * p) // smoothstep
                1f + (targetGain - 1f) * eased
            }
            null -> 1f
        }
        if (_aux.value.tempoPhase != phase) _aux.value = _aux.value.copy(tempoPhase = phase)
    }

    private fun advancePhase(dyn: DynamicVolume) {
        when (phase) {
            TempoPhase.PREP -> if (barsInPhase >= dyn.prepBars) enterPhase(TempoPhase.DESCENT)
            TempoPhase.DESCENT -> if (barsInPhase >= dyn.descentBars) enterPhase(TempoPhase.DETECT)
            TempoPhase.RESYNC -> if (barsInPhase >= dyn.resyncBars) enterPhase(TempoPhase.DESCENT)
            TempoPhase.DETECT, null -> {}
        }
    }

    private fun enterPhase(next: TempoPhase) {
        phase = next
        barsInPhase = 0
        if (next == TempoPhase.DETECT) grader.clearHistory() // repart propre pour mesurer la dérive
    }

    private fun handleOnset(nanos: Long) {
        if (!currentSettings.metroListen) return
        // Hors phase de détection (clic fort = micro saturé), on consigne l'attaque mais on ne note pas.
        if (!analyzing()) {
            listener.recorder.event('O', nanos)
            return
        }
        val timeMs = nanos / 1e6
        val manual = currentSettings.metroListenLatencyMs.toDouble()
        // Écart à l'instant prévu du clic le plus proche (si des clics récents existent).
        val nearestClick = clickTimes.minByOrNull { abs(it - timeMs) }
        val clickOffset = if (nearestClick != null) timeMs - nearestClick else Double.NaN

        val latency: Double
        if (!currentSettings.metroListenHeadphones) {
            // Au haut-parleur, le clic revient au micro ~latence après son instant prévu (pic serré) :
            // on en déduit la latence acoustique, puis on écarte les attaques collées au clic (son
            // propre son entrant dans le micro). On les garde dans la banque, marquées 'o'.
            if (!clickOffset.isNaN() && clickOffset in 0.0..CLICK_LATENCY_MAX_MS) {
                clickOffsets.addLast(clickOffset)
                while (clickOffsets.size > LATENCY_WINDOW) clickOffsets.removeFirst()
                autoLatencyMs = clickOffsets.sorted()[clickOffsets.size / 2]
            }
            latency = (if (!autoLatencyMs.isNaN()) autoLatencyMs else 0.0) + manual
            if (!clickOffset.isNaN() &&
                clickOffset >= latency - CLICK_GUARD_BEFORE_MS &&
                clickOffset <= latency + CLICK_GUARD_AFTER_MS
            ) {
                listener.recorder.event('o', nanos)
                return
            }
        } else {
            latency = manual
        }

        val reading = grader.onOnset(timeMs, latency)
        listener.recorder.event('O', nanos, reading?.lastDeviationMs ?: Double.NaN)
        if (reading != null) {
            _timing.value = reading
            // Dérive franche en détection → on remonte le volume pour resynchroniser.
            if (phase == TempoPhase.DETECT && reading.matched >= TempoGrader.DEFAULT_MIN_FOR_VERDICT &&
                abs(reading.meanDeviationMs) > DRIFT_MS
            ) {
                enterPhase(TempoPhase.RESYNC)
            }
        }
    }

    private fun refreshBank() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = listener.recorder.stats()
            _aux.value = _aux.value.copy(bankTakes = stats.takes, bankBytes = stats.bytes)
        }
    }

    /** Démarre/arrête l'écoute du micro selon l'état du métronome et l'option. */
    private fun syncListener() {
        val want = engine.running.value && currentSettings.metroListen
        if (want && !listenerRunning) {
            grader.reset()
            clickTimes.clear()
            clickOffsets.clear()
            autoLatencyMs = Double.NaN
            _timing.value = null
            listener.start()
            listenerRunning = true
        } else if (!want && listenerRunning) {
            listener.stop() // finalise une éventuelle prise en cours
            listenerRunning = false
            _timing.value = null
            _aux.value = _aux.value.copy(recording = false, recordingSeconds = 0)
            refreshBank()
        }
    }

    // --- Lecture -----------------------------------------------------------------------------

    fun toggleRun() {
        if (uiState.value.running) stop() else start()
    }

    fun start() {
        engine.updateConfig(uiState.value.config)
        resetDynamics()
        engine.start()
        syncListener()
    }

    fun stop() {
        engine.stop()
        resetDynamics()
        syncListener()
    }

    private fun resetDynamics() {
        phase = null
        barsInPhase = 0
        lastMeasure = Int.MIN_VALUE
        targetGain = DEFAULT_TARGET_GAIN
        engine.clickGain = 1f
        _aux.value = _aux.value.copy(tempoPhase = null)
    }

    // --- Tempo -------------------------------------------------------------------------------

    fun setBpm(value: Int) = launchSetting { settingsStore.setMetroBpm(value) }

    fun nudgeBpm(delta: Int) = setBpm(uiState.value.config.bpm + delta)

    fun tap() {
        val bpm = tap.tap(SystemClock.elapsedRealtime()) ?: return
        setBpm(bpm)
    }

    // --- Mesure, subdivision, son ------------------------------------------------------------

    fun setBeatsPerMeasure(value: Int) = launchSetting { settingsStore.setMetroBeats(value) }

    fun setSubdivision(value: Subdivision) = launchSetting { settingsStore.setMetroSubdivision(value) }

    fun setAccentFirst(value: Boolean) = launchSetting { settingsStore.setMetroAccentFirst(value) }

    fun setSound(value: ClickSound) = launchSetting { settingsStore.setMetroSound(value) }

    // --- Décompte, mesures silencieuses, automation ------------------------------------------

    fun setCountInBars(value: Int) = launchSetting { settingsStore.setMetroCountIn(value) }

    fun setSilent(value: SilentMeasures) = launchSetting { settingsStore.setMetroSilent(value) }

    fun setAutomation(value: TempoAutomation) = launchSetting { settingsStore.setMetroAutomation(value) }

    // --- Suivi du tempo au micro -------------------------------------------------------------

    fun setListen(value: Boolean) = launchSetting { settingsStore.setMetroListen(value) }

    fun setListenHeadphones(value: Boolean) = launchSetting { settingsStore.setMetroListenHeadphones(value) }

    fun setListenLatency(ms: Int) = launchSetting { settingsStore.setMetroListenLatency(ms) }

    fun setTempoVisual(value: TempoVisual) = launchSetting { settingsStore.setMetroTempoVisual(value) }

    fun setDynamics(value: DynamicVolume) = launchSetting { settingsStore.setMetroDynamics(value) }

    /** Bouton ● / ■ : enregistre une prise (banque de test) pendant que l'écoute tourne. */
    fun toggleRecording() {
        val recorder = listener.recorder
        if (recorder.armed) {
            recorder.stopTake()
            _aux.value = _aux.value.copy(recording = false, recordingSeconds = 0)
        } else {
            if (!engine.running.value || !currentSettings.metroListen) return
            takeStartedNanos = SystemClock.elapsedRealtimeNanos()
            recorder.startTake(takeMeta())
            _aux.value = _aux.value.copy(recording = true, recordingSeconds = 0)
        }
    }

    private fun takeMeta(): TempoTakeMeta {
        val config = currentSettings.metronome
        return TempoTakeMeta(
            bpm = uiState.value.displayBpm,
            beatsPerMeasure = config.beatsPerMeasure,
            subdivision = config.subdivision.name,
            division = config.subdivision.gradingDivision,
            toleranceMs = TempoGrader.DEFAULT_TOLERANCE_MS,
            latencyMs = currentSettings.metroListenLatencyMs,
            headphones = currentSettings.metroListenHeadphones,
            sound = config.sound.name,
        )
    }

    override fun onCleared() {
        engine.stop()
        listener.stop()
    }

    private inline fun launchSetting(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Durée de mémorisation des clics pour le filtrage (ms). */
        const val CLICK_MEMORY_MS = 1_500.0

        /** Écart clic max retenu pour estimer la latence acoustique (ms). */
        const val CLICK_LATENCY_MAX_MS = 130.0

        /** Nombre d'écarts gardés pour la médiane de latence. */
        const val LATENCY_WINDOW = 16

        /** Fenêtre anti-clic autour de (clic + latence) : avant / après (ms). */
        const val CLICK_GUARD_BEFORE_MS = 45.0
        const val CLICK_GUARD_AFTER_MS = 55.0

        /** Gain de clic de départ en détection (puis ajusté vers « juste sous l'écrêtage »). */
        const val DEFAULT_TARGET_GAIN = 0.5f
        const val MIN_TARGET_GAIN = 0.08f

        /** Écart moyen (ms) au-delà duquel on considère que le joueur a décroché → resynchro. */
        const val DRIFT_MS = 90.0
    }
}
