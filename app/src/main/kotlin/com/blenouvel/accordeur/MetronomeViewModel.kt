package com.blenouvel.accordeur

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blenouvel.accordeur.audio.Metronome
import com.blenouvel.accordeur.audio.MetronomeTick
import com.blenouvel.accordeur.audio.TempoListener
import com.blenouvel.accordeur.data.Settings
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.model.ClickSound
import com.blenouvel.accordeur.model.MetronomeConfig
import com.blenouvel.accordeur.model.SilentMeasures
import com.blenouvel.accordeur.model.Subdivision
import com.blenouvel.accordeur.model.TapTempo
import com.blenouvel.accordeur.model.TempoAutomation
import com.blenouvel.accordeur.model.TempoGrader
import com.blenouvel.accordeur.model.TempoVisual
import com.blenouvel.accordeur.model.TimingReading
import com.blenouvel.accordeur.model.gradingDivision
import com.blenouvel.accordeur.model.tempoName
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

    // État touché uniquement depuis le dispatcher principal (collecteurs + start/stop) : pas de course.
    private var currentSettings = Settings()
    private var listenerRunning = false
    private val clickTimes = ArrayDeque<Double>() // instants prévus des clics audibles (ms)

    val uiState: StateFlow<MetronomeUiState> =
        combine(settingsStore.settings, engine.running, engine.tick, _timing) { settings, running, tick, timing ->
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MetronomeUiState())

    init {
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
        if (tick.subIndex == 0) grader.onBeat(timeMs, intervalMs)
        if (tick.audible) {
            clickTimes.addLast(timeMs)
            while (clickTimes.isNotEmpty() && timeMs - clickTimes.first() > CLICK_MEMORY_MS) clickTimes.removeFirst()
        }
    }

    private fun handleOnset(nanos: Long) {
        if (!currentSettings.metroListen) return
        val timeMs = nanos / 1e6
        val latency = currentSettings.metroListenLatencyMs.toDouble()
        // Au haut-parleur, le clic rentre dans le micro : on ignore les attaques collées à un clic.
        if (!currentSettings.metroListenHeadphones && isClick(timeMs, latency)) return
        val reading = grader.onOnset(timeMs, latency) ?: return
        _timing.value = reading
    }

    /** Vrai si [timeMs] tombe dans la fenêtre de garde d'un clic audible (clic entendu = prévu + latence). */
    private fun isClick(timeMs: Double, latency: Double): Boolean =
        clickTimes.any { abs(timeMs - (it + latency)) <= CLICK_GUARD_MS }

    /** Démarre/arrête l'écoute du micro selon l'état du métronome et l'option. */
    private fun syncListener() {
        val want = engine.running.value && currentSettings.metroListen
        if (want && !listenerRunning) {
            grader.reset()
            clickTimes.clear()
            _timing.value = null
            listener.start()
            listenerRunning = true
        } else if (!want && listenerRunning) {
            listener.stop()
            listenerRunning = false
            _timing.value = null
        }
    }

    // --- Lecture -----------------------------------------------------------------------------

    fun toggleRun() {
        if (uiState.value.running) stop() else start()
    }

    fun start() {
        engine.updateConfig(uiState.value.config)
        engine.start()
        syncListener()
    }

    fun stop() {
        engine.stop()
        syncListener()
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

        /** Demi-fenêtre autour d'un clic où une attaque est attribuée au clic, pas au jeu (ms). */
        const val CLICK_GUARD_MS = 40.0
    }
}
