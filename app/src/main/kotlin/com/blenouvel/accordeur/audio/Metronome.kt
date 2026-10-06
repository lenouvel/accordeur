package com.blenouvel.accordeur.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.blenouvel.accordeur.model.ClickSound
import com.blenouvel.accordeur.model.MetronomeConfig
import com.blenouvel.accordeur.model.MetronomeMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Événement émis à chaque clic, pour synchroniser l'affichage (pendule, points). */
data class MetronomeTick(
    /** Temps dans la mesure (0-based). */
    val beatIndex: Int,
    val beatsPerMeasure: Int,
    /** Rang du clic dans le temps (0 = sur le temps, > 0 = subdivision). */
    val subIndex: Int,
    val accent: Boolean,
    /** Faux pendant une mesure silencieuse (le clic n'est pas joué, le visuel continue). */
    val audible: Boolean,
    val countIn: Boolean,
    /** Numéro de mesure (1-based), 0 pendant le décompte. */
    val measure: Int,
    /** Mesures de décompte restantes (0 hors décompte). */
    val countInLeft: Int,
    val bpm: Int,
    /** Incrémenté à chaque clic : force l'animation même quand le reste est identique. */
    val serial: Long,
)

/**
 * Moteur du métronome. Un thread dédié (priorité audio) écrit en continu dans un [AudioTrack] en
 * `MODE_STREAM` : l'intervalle entre deux clics est défini en **nombre d'échantillons** (partie
 * fractionnaire accumulée), donc le tempo ne dérive pas, et l'écriture bloquante cadence la boucle.
 *
 * Les clics sont pré-rendus par [ClickSynth] et simplement recopiés. La configuration est lue à
 * chaque clic (via [config], `@Volatile`) : un changement de BPM, de mesure, de son… prend effet au
 * clic suivant. [tick] publie l'état courant pour l'affichage ; un léger décalage son/visuel (de
 * l'ordre du tampon audio) est possible, sans incidence sur la justesse du tempo.
 */
class Metronome {
    private val _tick = MutableStateFlow<MetronomeTick?>(null)
    val tick: StateFlow<MetronomeTick?> = _tick.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    private var config = MetronomeConfig()

    @Volatile
    private var alive = false

    private var thread: Thread? = null

    /** Applique une nouvelle configuration (prise en compte au clic suivant). */
    fun updateConfig(c: MetronomeConfig) {
        config = c.sanitized()
    }

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        alive = true
        _running.value = true
        thread = Thread(::run, "accordeur-metronome").also { it.start() }
    }

    @Synchronized
    fun stop() {
        alive = false
        val t = thread
        thread = null
        if (t != null) {
            try {
                t.join(STOP_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        _running.value = false
        _tick.value = null
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            _running.value = false
            return
        }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Tampon volontairement petit : limite le décalage son/visuel.
                .setBufferSizeInBytes(max(minBuf, MIN_BUFFER_BYTES))
                .build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "AudioTrack indisponible", e)
            _running.value = false
            return
        }

        val silence = ShortArray(CHUNK)
        // Clics en cache pour le son courant.
        var cachedSound: ClickSound? = null
        var accentBuf = ShortArray(0)
        var beatBuf = ShortArray(0)
        var subBuf = ShortArray(0)

        var tickInBeat = 0
        var beatInMeasure = 0
        var measuresDone = 0 // mesures complètes depuis le départ (décompte compris)
        var barsForAuto = 0 // mesures écoulées comptant pour l'automation
        var frac = 0.0 // accumulateur fractionnaire d'échantillons (anti-dérive)
        var serial = 0L

        try {
            track.play()
            while (alive) {
                val cfg = config
                val sub = cfg.subdivision
                val beats = cfg.beatsPerMeasure
                val grid = sub.grid
                val onsets = sub.onsets

                // La mesure ou la subdivision a pu rétrécir en cours de route : on recadre.
                if (beatInMeasure >= beats) {
                    beatInMeasure = 0
                    tickInBeat = 0
                }
                if (tickInBeat >= grid) tickInBeat = 0

                if (cfg.sound != cachedSound) {
                    accentBuf = ClickSynth.render(cfg.sound, ClickSynth.Tick.ACCENT)
                    beatBuf = ClickSynth.render(cfg.sound, ClickSynth.Tick.BEAT)
                    subBuf = ClickSynth.render(cfg.sound, ClickSynth.Tick.SUB)
                    cachedSound = cfg.sound
                }

                val bpm = if (cfg.automation.enabled) {
                    MetronomeMath.automatedBpm(cfg.automation, barsForAuto)
                } else {
                    cfg.bpm
                }

                val inCountIn = measuresDone < cfg.countInBars
                val realMeasure = measuresDone - cfg.countInBars // < 0 pendant le décompte
                val muted = !inCountIn && MetronomeMath.isMuted(realMeasure, cfg.silent)

                val onBeat = tickInBeat == 0
                val sounded = tickInBeat in onsets
                val accent = onBeat && beatInMeasure == 0 && cfg.accentFirst
                val audible = sounded && !muted
                val variant = when {
                    !onBeat -> subBuf
                    accent -> accentBuf
                    else -> beatBuf
                }

                serial++
                _tick.value = MetronomeTick(
                    beatIndex = beatInMeasure,
                    beatsPerMeasure = beats,
                    subIndex = tickInBeat,
                    accent = accent,
                    audible = audible,
                    countIn = inCountIn,
                    measure = if (inCountIn) 0 else realMeasure + 1,
                    countInLeft = if (inCountIn) cfg.countInBars - measuresDone else 0,
                    bpm = bpm,
                    serial = serial,
                )

                frac += MetronomeMath.samplesPerTick(bpm, grid, SAMPLE_RATE)
                var remaining = frac.toInt()
                frac -= remaining

                if (audible && variant.isNotEmpty() && remaining > 0) {
                    val clickLen = minOf(variant.size, remaining)
                    if (!writeFully(track, variant, clickLen)) break
                    remaining -= clickLen
                }
                while (remaining > 0 && alive) {
                    val len = minOf(remaining, CHUNK)
                    if (!writeFully(track, silence, len)) {
                        remaining = 0
                        break
                    }
                    remaining -= len
                }

                tickInBeat++
                if (tickInBeat >= grid) {
                    tickInBeat = 0
                    beatInMeasure++
                    if (beatInMeasure >= beats) {
                        beatInMeasure = 0
                        measuresDone++
                        if (cfg.automation.enabled) {
                            barsForAuto++
                            val total = MetronomeMath.totalBars(cfg.automation)
                            if (barsForAuto >= total) {
                                barsForAuto = if (cfg.automation.loop) 0 else total
                            }
                        }
                    }
                }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Métronome interrompu", e)
        } finally {
            try {
                track.pause()
                track.flush()
            } catch (_: IllegalStateException) {
            }
            try {
                track.stop()
            } catch (_: IllegalStateException) {
            }
            track.release()
            _running.value = false
        }
    }

    /** Écrit exactement [len] échantillons (write bloquant) ; false sur erreur. */
    private fun writeFully(track: AudioTrack, data: ShortArray, len: Int): Boolean {
        var off = 0
        while (off < len && alive) {
            val n = track.write(data, off, len - off, AudioTrack.WRITE_BLOCKING)
            if (n < 0) {
                Log.w(TAG, "AudioTrack.write : $n")
                return false
            }
            off += n
        }
        return true
    }

    private companion object {
        const val TAG = "Metronome"
        const val SAMPLE_RATE = ClickSynth.SAMPLE_RATE
        const val CHUNK = 4096
        const val STOP_TIMEOUT_MS = 400L
        const val MIN_BUFFER_BYTES = 3072
    }
}
