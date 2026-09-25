package com.blenouvel.accordeur.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Format de la capture en cours (fixé à l'ouverture du micro). */
data class CaptureFormat(
    val sampleRate: Int,
    /** Échantillons float du téléphone (sinon 16 bits : enregistrés en 16 bits, sans perte). */
    val floatSamples: Boolean,
    val source: MicSource,
    val requestedSource: MicSource,
    val unprocessedDeclared: Boolean,
    /** « fabricant modèle », « 15 (API 35) », « 1.0 (1) ». */
    val device: String,
    val android: String,
    val appVersion: String,
)

/** Réglages de l'accordeur au moment de l'enregistrement (fiche de chaque son). */
data class RecordingContext(
    val mode: TunerMode,
    val detection: String,
    val lockedString: Int,
    val a4: Double,
    val tuningId: String,
    val tuningName: String,
    /** Notes des cordes, grave → aiguë : « E2 A2 D3 G3 B3 E4 ». */
    val tuningNotes: String,
)

/**
 * Banque de sons de test : une **prise** commence quand on appuie sur le bouton ● de l'accordeur et
 * finit au second appui (ou en quittant l'accordeur, ou au bout de [MAX_TAKE_S]). Tout ce que le
 * micro livre pendant la prise est gardé **tel quel** (avant tout filtrage, silences compris), sans
 * perte — WAV float 32 bits, ou 16 bits si le téléphone ne fournit que du 16 bits — avec une fiche
 * texte (téléphone, source, réglages) et ce que l'accordeur a affiché à chaque trame. De quoi
 * rejouer la banque dans l'accordeur sur ordinateur (test de non-régression).
 *
 * La prise démarre ~1 s avant l'appui (pré-enregistrement glissant, en mémoire seulement : une
 * corde pincée juste avant l'appui est gardée entière). Changer de réglage pendant une prise la
 * coupe en deux fichiers (chaque fiche reste juste) ; le son de référence n'est pas gardé.
 * Le fil audio ne fait que copier chaque bloc dans un tampon recyclé (aucune allocation, aucune
 * écriture disque) ; un fil d'écriture dédié fait le reste. La banque est plafonnée à
 * [maxBytes] : les sons les plus anciens partent d'abord.
 */
class SoundRecorder(
    private val directory: File,
    private val maxBytes: Long = MAX_BANK_BYTES,
    private val maxTakeSeconds: Double = MAX_TAKE_S,
) {
    /** Fonction activée (réglage « bouton d'enregistrement ») : sinon rien ne quitte le fil audio. */
    @Volatile
    var enabled = false

    /** Prise demandée (bouton ●). Remis à faux en fin de capture ou au bout de [maxTakeSeconds]. */
    @Volatile
    var armed = false
        private set

    /** Numéro de la prise en cours (ou de la dernière). */
    @Volatile
    private var take = 0

    /** Réglages courants ; un changement ferme le son en cours (la fiche resterait fausse). */
    @Volatile
    var context: RecordingContext? = null

    /** Appelé (fil d'écriture) après l'ajout ou la suppression de sons. */
    @Volatile
    var onBankChanged: (() -> Unit)? = null

    /** Vrai pendant qu'un fichier de la prise est ouvert. */
    @Volatile
    var recording = false
        private set

    /** Lance une prise (bouton ●). */
    fun startTake() {
        take++
        armed = true
    }

    /** Termine la prise en cours (bouton ■, sortie de l'accordeur). */
    fun stopTake() {
        armed = false
    }

    private class Chunk(size: Int) {
        val samples = FloatArray(size)
        var count = 0
        var firstSample = 0L
        var frame: TunerFrame? = null
        var muted = false
        var context: RecordingContext? = null
        var armed = false
        var take = 0

        /** Bloc spécial : fin de capture, ou enregistrement désactivé. */
        var close = false
    }

    private val pool = ArrayBlockingQueue<Chunk>(POOL_SIZE)
    private val queue = ArrayBlockingQueue<Chunk>(POOL_SIZE)
    private var writer: Thread? = null
    private var format: CaptureFormat? = null

    /** Fin de capture demandée : le fil d'écriture vide la file puis s'arrête. */
    @Volatile
    private var finished = false

    // Fil audio.
    private var samplesOffered = 0L
    private var wasEnabled = false

    @Volatile
    private var droppedChunks = 0

    init {
        repeat(POOL_SIZE) { pool.add(Chunk(PitchDetector.HOP)) }
    }

    /** Début de capture (fil audio) : lance le fil d'écriture. */
    fun start(format: CaptureFormat) {
        this.format = format
        samplesOffered = 0L
        wasEnabled = false
        droppedChunks = 0
        finished = false
        writer = Thread({ writeLoop(format) }, "accordeur-banque").also {
            it.priority = Thread.MIN_PRIORITY
            it.start()
        }
    }

    /**
     * Bloc de [count] échantillons tel que lu au micro, avec la trame produite (fil audio, non
     * bloquant). Si le fil d'écriture prend du retard, le bloc est perdu et le son en cours fermé.
     */
    fun offer(samples: FloatArray, count: Int, frame: TunerFrame?, muted: Boolean) {
        val first = samplesOffered
        samplesOffered += count
        val on = enabled
        if (!on) {
            if (wasEnabled) sendClose()
            wasEnabled = false
            return
        }
        wasEnabled = true
        val chunk = pool.poll()
        if (chunk == null) {
            droppedChunks++
            return
        }
        System.arraycopy(samples, 0, chunk.samples, 0, count)
        chunk.count = count
        chunk.firstSample = first
        chunk.frame = frame
        chunk.muted = muted
        chunk.context = context
        chunk.armed = armed
        chunk.take = take
        chunk.close = false
        queue.offer(chunk)
    }

    /** Fin de capture (fil audio) : termine la prise en cours et attend le fil d'écriture. */
    fun stop() {
        armed = false
        val thread = writer ?: return
        writer = null
        sendClose(final = true)
        try {
            thread.join(STOP_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun sendClose(final: Boolean = false) {
        if (final) finished = true
        val chunk = pool.poll() ?: return // le fil d'écriture fermera sur la fin de capture
        chunk.close = true
        chunk.frame = null
        chunk.context = null
        queue.offer(chunk)
    }

    // --- Fil d'écriture -----------------------------------------------------------------------

    private val preroll = ArrayDeque<Chunk>()
    private var clip: ClipWriter? = null

    /** Prise arrêtée par la limite de durée : ses blocs encore en file ne la rouvrent pas. */
    private var endedTake = -1

    private fun writeLoop(format: CaptureFormat) {
        try {
            while (true) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (finished) break
                    continue
                }
                try {
                    handle(chunk, format)
                } catch (_: RuntimeException) {
                    // Imprévu : on abandonne le son en cours plutôt que de faire tomber l'application.
                    clip = null
                    recording = false
                }
                if (finished && queue.isEmpty()) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            closeClip()
            clearPreroll()
            recording = false
        }
    }

    private fun handle(chunk: Chunk, format: CaptureFormat) {
        if (chunk.close) {
            closeClip()
            clearPreroll()
            recycle(chunk)
            return
        }
        val context = chunk.context
        val wanted = chunk.armed && !chunk.muted && context != null && chunk.take != endedTake
        val open = clip
        if (open != null) {
            val continuous = chunk.firstSample == open.nextSample
            if (!wanted || !continuous || context != open.context || chunk.take != open.take) {
                // Fin de prise, bloc perdu ou réglage changé (la prise continue dans un autre fichier).
                closeClip()
            } else if (open.seconds >= maxTakeSeconds) {
                closeClip()
                endedTake = open.take
                if (take == open.take) armed = false
            }
        }
        val current = clip
        if (current != null) {
            current.append(chunk)
            recycle(chunk)
            return
        }
        if (chunk.muted) {
            // Son de référence : ni gardé, ni en pré-enregistrement.
            clearPreroll()
            recycle(chunk)
            return
        }
        // Hors prise : pré-enregistrement glissant (~1 s), ouverture dès que la prise est demandée.
        val last = preroll.lastOrNull()
        if (last != null && chunk.firstSample != last.firstSample + last.count) clearPreroll() // bloc perdu
        preroll.addLast(chunk)
        while (preroll.size > PREROLL_CHUNKS) recycle(preroll.removeFirst())
        if (wanted && chunk.take != endedTake && context != null) {
            val first = preroll.first().firstSample
            val writer = ClipWriter(format, context, chunk.take, first, chunk.firstSample - first, droppedChunks)
            clip = writer
            recording = true
            while (preroll.isNotEmpty()) {
                val c = preroll.removeFirst()
                writer.append(c)
                recycle(c)
            }
        }
    }

    private fun clearPreroll() {
        while (preroll.isNotEmpty()) recycle(preroll.removeFirst())
    }

    private fun recycle(chunk: Chunk) {
        chunk.frame = null
        chunk.context = null
        pool.offer(chunk)
    }

    private fun closeClip() {
        val writer = clip ?: return
        clip = null
        recording = false
        writer.finish(droppedChunks)
        enforceCap()
        onBankChanged?.invoke()
    }

    /** Plafond de la banque : on supprime les sons les plus anciens (noms chronologiques). */
    private fun enforceCap() {
        val files = directory.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return
        var total = directory.listFiles()?.sumOf { it.length() } ?: 0L
        for (wav in files) {
            if (total <= maxBytes) break
            val sheet = File(directory, wav.name.removeSuffix(".wav") + ".txt")
            total -= wav.length() + sheet.length()
            wav.delete()
            sheet.delete()
        }
    }

    /**
     * Un son : WAV (en-tête complété à la fermeture) + fiche texte écrite à la fermeture. Une erreur
     * d'écriture (stockage plein…) abandonne ce son et efface ses fichiers : l'accordeur continue.
     */
    private inner class ClipWriter(
        private val format: CaptureFormat,
        val context: RecordingContext,
        val take: Int,
        private val firstSample: Long,
        private val prerollSamples: Long,
        private val droppedAtStart: Int,
    ) {
        private val name: String
        private var wav: RandomAccessFile? = null
        private var failed = false
        private val bytesPerSample = if (format.floatSamples) 4 else 2
        private val headerSize = if (format.floatSamples) FLOAT_HEADER else PCM_HEADER
        private val buffer = ByteBuffer.allocate(PitchDetector.HOP * 4).order(ByteOrder.LITTLE_ENDIAN)
        private val log = StringBuilder()
        private val startedAt = System.currentTimeMillis()
        var nextSample = firstSample
            private set
        private var samples = 0L

        val seconds: Double get() = samples.toDouble() / format.sampleRate

        init {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(startedAt))
            name = "${stamp}_${context.mode.name.lowercase(Locale.ROOT)}_${safe(context.tuningId)}"
            try {
                directory.mkdirs()
                val file = RandomAccessFile(File(directory, "$name.wav"), "rw")
                wav = file
                file.setLength(0)
                file.write(header(0))
            } catch (_: Exception) {
                failed = true
            }
        }

        fun append(chunk: Chunk) {
            nextSample = chunk.firstSample + chunk.count
            val frame = chunk.frame
            if (failed) return
            buffer.clear()
            if (format.floatSamples) {
                for (i in 0 until chunk.count) buffer.putFloat(chunk.samples[i])
            } else {
                for (i in 0 until chunk.count) {
                    buffer.putShort((chunk.samples[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            try {
                wav?.write(buffer.array(), 0, chunk.count * bytesPerSample)
            } catch (_: Exception) {
                failed = true
                return
            }
            samples += chunk.count
            if (frame != null) logFrame(frame)
        }

        private fun logFrame(frame: TunerFrame) {
            val t = frame.timeSeconds - firstSample.toDouble() / format.sampleRate
            log.append(String.format(Locale.ROOT, "%.3f\t%.1f\t%d\t", t, frame.levelDb, if (frame.signal) 1 else 0))
            log.append(if (frame.frequency.isNaN()) "-" else String.format(Locale.ROOT, "%.4f", frame.frequency))
            log.append('\t').append(if (frame.holding) 1 else 0)
            log.append('\t').append(String.format(Locale.ROOT, "%.3f", frame.clarity))
            log.append('\t')
            val poly = frame.poly
            if (poly != null) {
                log.append('e').append(poly.event).append(if (poly.strum) 'g' else 'c').append(':')
                poly.strings.forEachIndexed { i, s ->
                    if (i > 0) log.append(',')
                    if (!s.detected) {
                        log.append("--")
                    } else {
                        log.append(String.format(Locale.ROOT, "%+.1f", s.cents))
                        if (!s.reliable) log.append('~')
                        if (s.fresh) log.append('*')
                    }
                }
            }
            log.append('\n')
        }

        fun finish(droppedNow: Int) {
            try {
                if (!failed) {
                    wav?.seek(0)
                    wav?.write(header(samples))
                }
                wav?.close()
                if (!failed) writeSheet(droppedNow)
            } catch (_: Exception) {
                failed = true
            }
            if (failed) {
                // Son inexploitable : on n'en garde rien.
                File(directory, "$name.wav").delete()
                File(directory, "$name.txt").delete()
            }
        }

        private fun writeSheet(droppedNow: Int) {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date(startedAt))
            val sheet = buildString {
                append("# Accordeur — banque de sons de test. Prise lancée au bouton : signal brut du micro\n")
                append("# (avant tout filtrage, silences compris), puis ce que l'accordeur a affiché à chaque\n")
                append("# trame (~43 ms).\n")
                append("format=").append(SHEET_FORMAT).append('\n')
                append("fichier=").append(name).append(".wav\n")
                append("date=").append(date).append('\n')
                append("appareil=").append(format.device).append('\n')
                append("android=").append(format.android).append('\n')
                append("application=").append(format.appVersion).append('\n')
                append("source=").append(format.source.name).append('\n')
                append("source_demandee=").append(format.requestedSource.name).append('\n')
                append("unprocessed_annonce=").append(format.unprocessedDeclared).append('\n')
                append("frequence=").append(format.sampleRate).append('\n')
                append("encodage=").append(if (format.floatSamples) "float32" else "pcm16").append('\n')
                append("duree_s=").append(String.format(Locale.ROOT, "%.3f", seconds)).append('\n')
                append("prise=").append(take).append('\n')
                append("preroll_s=").append(String.format(Locale.ROOT, "%.3f", prerollSamples.toDouble() / format.sampleRate)).append('\n')
                append("premier_echantillon=").append(firstSample).append('\n')
                append("mode=").append(context.mode.name).append('\n')
                append("detection=").append(context.detection).append('\n')
                append("corde_verrouillee=").append(context.lockedString).append('\n')
                append("la4=").append(context.a4).append('\n')
                append("accordage=").append(context.tuningId).append('|').append(context.tuningName).append('|').append(context.tuningNotes).append('\n')
                append("blocs_perdus=").append(droppedNow - droppedAtStart).append('\n')
                append("[trames]\n")
                append("t_s\tniveau_db\tson\tfreq_hz\tmaintenu\tclarte\tpoly\n")
                append(log)
            }
            File(directory, "$name.txt").writeText(sheet)
        }

        /** En-tête WAV : PCM 16 bits, ou float 32 bits (avec bloc « fact », exigé hors PCM). */
        private fun header(sampleCount: Long): ByteArray {
            val dataBytes = sampleCount * bytesPerSample
            val b = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((headerSize - 8 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            if (format.floatSamples) {
                b.putInt(18).putShort(3).putShort(1).putInt(format.sampleRate).putInt(format.sampleRate * 4)
                b.putShort(4).putShort(32).putShort(0)
                b.put("fact".toByteArray(Charsets.US_ASCII)).putInt(4).putInt(sampleCount.toInt())
            } else {
                b.putInt(16).putShort(1).putShort(1).putInt(format.sampleRate).putInt(format.sampleRate * 2)
                b.putShort(2).putShort(16)
            }
            b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
            return b.array()
        }
    }

    companion object {
        /** Dossier de la banque, dans le stockage privé de l'application. */
        const val DIRECTORY = "banque"

        /** Plafond de la banque : ~90 min de son en float 48 kHz. */
        const val MAX_BANK_BYTES = 1L shl 30

        /** 2 : prises au bouton (continues). 1 : sons découpés automatiquement (versions précédentes). */
        const val SHEET_FORMAT = 2
        const val FLOAT_HEADER = 58
        const val PCM_HEADER = 44

        /** ~5,5 s de tampon entre le fil audio et le fil d'écriture. */
        private const val POOL_SIZE = 128

        /** ~1 s avant l'appui sur ●. */
        private const val PREROLL_CHUNKS = 24

        /** Une prise oubliée s'arrête seule (~55 Mo en float 48 kHz). */
        const val MAX_TAKE_S = 300.0
        private const val STOP_TIMEOUT_MS = 2000L

        private fun safe(text: String): String = text.replace(Regex("[^A-Za-z0-9_-]"), "")
    }
}
