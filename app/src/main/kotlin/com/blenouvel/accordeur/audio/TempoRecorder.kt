package com.blenouvel.accordeur.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Métadonnées d'une prise de suivi de tempo (reportées dans la fiche). */
data class TempoTakeMeta(
    val bpm: Int,
    val beatsPerMeasure: Int,
    val subdivision: String,
    val division: Int,
    val toleranceMs: Double,
    val latencyMs: Int,
    val headphones: Boolean,
)

/**
 * Banque de test du suivi de tempo. Une prise (bouton ●) garde le **signal brut du micro** (WAV) et
 * une fiche listant, alignés sur le WAV, les repères du métronome (temps `B`, clics audibles `C`) et
 * les attaques détectées (`O` = prise en compte avec écart ms, `o` = ignorée car collée à un clic).
 * De quoi régler le détecteur d'attaques hors-ligne, comme la banque de l'accordeur.
 *
 * Écriture sur le fil du [TempoListener] (start/offer/stop) ; les repères arrivent d'autres fils et
 * sont mis dans une liste synchronisée, puis datés à la fermeture. Pas de fil d'écriture séparé :
 * l'écoute ne fait qu'une détection légère et l'écriture est bufferisée.
 */
class TempoRecorder(
    private val directory: File,
    private val maxTakeSeconds: Double = MAX_TAKE_S,
) {
    @Volatile
    var armed = false
        private set

    @Volatile
    var recording = false
        private set

    /** « fabricant modèle | Android … » (rempli par le [TempoListener]). */
    @Volatile
    var deviceInfo: String = ""

    @Volatile
    var onBankChanged: (() -> Unit)? = null

    @Volatile
    private var meta: TempoTakeMeta? = null

    private var sampleRate = 48_000
    private var floatSamples = true
    private var sourceName = "?"
    private var startNanos = 0L
    private var samplesOffered = 0L

    private val events = ArrayList<Event>() // protégé par synchronized(events)

    // Fichier courant.
    private var wav: RandomAccessFile? = null
    private var name = ""
    private var clipStartSample = 0L
    private var samples = 0L
    private var failed = false
    private val buffer = ByteBuffer.allocate(MAX_BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)

    private class Event(val nanos: Long, val type: Char, val value: Double)

    /** Lance une prise (bouton ●) avec les réglages courants. */
    fun startTake(meta: TempoTakeMeta) {
        this.meta = meta
        synchronized(events) { events.clear() }
        armed = true
    }

    /** Termine la prise (bouton ■). */
    fun stopTake() {
        armed = false
    }

    /** Ajoute un repère (n'importe quel fil) : 'B' temps, 'C' clic, 'O' attaque, 'o' attaque ignorée. */
    fun event(type: Char, nanos: Long, value: Double = Double.NaN) {
        if (!armed) return
        synchronized(events) { events.add(Event(nanos, type, value)) }
    }

    /** Début de capture (fil d'écoute) : échantillon 0 ↔ [startNanos]. */
    fun start(sampleRate: Int, floatSamples: Boolean, sourceName: String, startNanos: Long) {
        this.sampleRate = sampleRate
        this.floatSamples = floatSamples
        this.sourceName = sourceName
        this.startNanos = startNanos
        samplesOffered = 0L
    }

    /** Bloc brut du micro (fil d'écoute). */
    fun offer(block: FloatArray, count: Int) {
        val first = samplesOffered
        samplesOffered += count
        if (armed && wav == null) openClip(first)
        if (!armed && wav != null) closeClip()
        val w = wav ?: return
        if (failed) return
        var i = 0
        while (i < count) {
            val chunk = minOf(MAX_BLOCK, count - i)
            buffer.clear()
            if (floatSamples) {
                for (j in 0 until chunk) buffer.putFloat(block[i + j])
            } else {
                for (j in 0 until chunk) buffer.putShort((block[i + j] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            try {
                w.write(buffer.array(), 0, chunk * if (floatSamples) 4 else 2)
            } catch (_: Exception) {
                failed = true
                return
            }
            i += chunk
        }
        samples += count
        if (samples.toDouble() / sampleRate >= maxTakeSeconds) {
            closeClip()
            armed = false
        }
    }

    /** Fin de capture (fil d'écoute). */
    fun stop() {
        if (wav != null) closeClip()
        armed = false
    }

    private fun openClip(firstSample: Long) {
        failed = false
        samples = 0L
        clipStartSample = firstSample
        name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date()) + "_tempo"
        try {
            directory.mkdirs()
            val f = RandomAccessFile(File(directory, "$name.wav"), "rw")
            f.setLength(0)
            f.write(header(0))
            wav = f
            recording = true
        } catch (_: Exception) {
            failed = true
            wav = null
            recording = false
        }
    }

    private fun closeClip() {
        val f = wav ?: return
        wav = null
        recording = false
        try {
            if (!failed) {
                f.seek(0)
                f.write(header(samples))
            }
            f.close()
            if (!failed) writeSheet()
        } catch (_: Exception) {
            failed = true
        }
        if (failed) {
            File(directory, "$name.wav").delete()
            File(directory, "$name.txt").delete()
        } else {
            onBankChanged?.invoke()
        }
    }

    private fun writeSheet() {
        val m = meta
        val clipStartNanos = startNanos + (clipStartSample * 1_000_000_000.0 / sampleRate).toLong()
        val evs = synchronized(events) { events.toList() }
        val sheet = buildString {
            append("# Accordeur — banque tempo. Signal brut du micro + repères du métronome, alignés\n")
            append("# sur le WAV (t_s = secondes depuis le début du fichier).\n")
            append("format=").append(SHEET_FORMAT).append('\n')
            append("fichier=").append(name).append(".wav\n")
            append("date=").append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date())).append('\n')
            append("appareil=").append(deviceInfo).append('\n')
            append("frequence=").append(sampleRate).append('\n')
            append("encodage=").append(if (floatSamples) "float32" else "pcm16").append('\n')
            append("source=").append(sourceName).append('\n')
            append("duree_s=").append(String.format(Locale.ROOT, "%.3f", samples.toDouble() / sampleRate)).append('\n')
            if (m != null) {
                append("bpm=").append(m.bpm).append('\n')
                append("temps_par_mesure=").append(m.beatsPerMeasure).append('\n')
                append("subdivision=").append(m.subdivision).append('\n')
                append("grille_par_temps=").append(m.division).append('\n')
                append("tolerance_ms=").append(String.format(Locale.ROOT, "%.0f", m.toleranceMs)).append('\n')
                append("latence_ms=").append(m.latencyMs).append('\n')
                append("casque=").append(m.headphones).append('\n')
            }
            append("[evenements]\n")
            append("t_s\ttype\tecart_ms  # B=temps C=clic O=attaque o=attaque_ignoree(clic)\n")
            for (e in evs) {
                val t = (e.nanos - clipStartNanos) / 1e9
                if (t < -0.1) continue
                append(String.format(Locale.ROOT, "%.3f\t%c\t", t, e.type))
                append(if (e.value.isNaN()) "-" else String.format(Locale.ROOT, "%+.1f", e.value)).append('\n')
            }
        }
        File(directory, "$name.txt").writeText(sheet)
    }

    /** En-tête WAV (float 32 bits avec bloc « fact », ou PCM 16 bits). */
    private fun header(sampleCount: Long): ByteArray {
        val bytesPerSample = if (floatSamples) 4 else 2
        val headerSize = if (floatSamples) FLOAT_HEADER else PCM_HEADER
        val dataBytes = sampleCount * bytesPerSample
        val b = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((headerSize - 8 + dataBytes).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        if (floatSamples) {
            b.putInt(18).putShort(3).putShort(1).putInt(sampleRate).putInt(sampleRate * 4)
            b.putShort(4).putShort(32).putShort(0)
            b.put("fact".toByteArray(Charsets.US_ASCII)).putInt(4).putInt(sampleCount.toInt())
        } else {
            b.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            b.putShort(2).putShort(16)
        }
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
        return b.array()
    }

    /** Bilan : nombre de prises terminées et taille totale (octets). */
    fun stats(): Stats {
        val wavs = directory.listFiles { f -> f.name.endsWith(".wav") } ?: return Stats(0, 0L)
        val done = wavs.filter { File(directory, it.name.removeSuffix(".wav") + ".txt").exists() }
        val bytes = done.sumOf { it.length() + File(directory, it.name.removeSuffix(".wav") + ".txt").length() }
        return Stats(done.size, bytes)
    }

    data class Stats(val takes: Int, val bytes: Long)

    companion object {
        const val DIRECTORY = "banque-tempo"
        const val SHEET_FORMAT = "tempo-1"
        const val FLOAT_HEADER = 58
        const val PCM_HEADER = 44
        const val MAX_TAKE_S = 300.0
        private const val MAX_BLOCK = 2048
    }
}
