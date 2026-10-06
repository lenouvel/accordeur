package com.blenouvel.accordeur

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blenouvel.accordeur.audio.Metronome
import com.blenouvel.accordeur.audio.MetronomeTick
import com.blenouvel.accordeur.data.SettingsStore
import com.blenouvel.accordeur.model.ClickSound
import com.blenouvel.accordeur.model.MetronomeConfig
import com.blenouvel.accordeur.model.SilentMeasures
import com.blenouvel.accordeur.model.Subdivision
import com.blenouvel.accordeur.model.TapTempo
import com.blenouvel.accordeur.model.TempoAutomation
import com.blenouvel.accordeur.model.tempoName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** État de la vue « Métronome ». */
data class MetronomeUiState(
    val config: MetronomeConfig = MetronomeConfig(),
    val running: Boolean = false,
    val tick: MetronomeTick? = null,
    /** Nom de tempo italien du BPM affiché (tempo courant si automation en cours). */
    val tempoName: String = tempoName(MetronomeConfig().bpm),
) {
    /** BPM affiché : celui du clic courant (automation) à l'arrêt on retombe sur le réglage. */
    val displayBpm: Int get() = if (running) tick?.bpm ?: config.bpm else config.bpm
}

class MetronomeViewModel(
    private val settingsStore: SettingsStore,
    private val engine: Metronome,
) : ViewModel() {

    private val tap = TapTempo()

    val uiState: StateFlow<MetronomeUiState> =
        combine(settingsStore.settings, engine.running, engine.tick) { settings, running, tick ->
            val config = settings.metronome
            val bpm = if (running) tick?.bpm ?: config.bpm else config.bpm
            MetronomeUiState(
                config = config,
                running = running,
                tick = tick,
                tempoName = tempoName(bpm),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MetronomeUiState())

    init {
        // La config persistée pilote le moteur : tout changement de réglage est répercuté.
        viewModelScope.launch {
            settingsStore.settings.collect { engine.updateConfig(it.metronome) }
        }
    }

    // --- Lecture -----------------------------------------------------------------------------

    fun toggleRun() {
        if (uiState.value.running) stop() else start()
    }

    fun start() {
        engine.updateConfig(uiState.value.config)
        engine.start()
    }

    fun stop() = engine.stop()

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

    override fun onCleared() {
        engine.stop()
    }

    private inline fun launchSetting(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
