package com.blenouvel.accordeur

import com.blenouvel.accordeur.audio.Biquad
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.TunerFrame
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.File
import java.util.Random
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Échantillons réels (soundfonts) + modèles de micro de téléphone, pour les diagnostics. */
object PhoneSim {
    const val SR = 48_000
    val dir = File("/tmp/claude-0/-home-user-accordeur/02c0f76a-3ff2-55ad-afc2-406e7976f715/scratchpad/samples")

    private val cache = java.util.concurrent.ConcurrentHashMap<String, DoubleArray>()

    /** Échantillon décodé, mono, rééchantillonné à 48 kHz (interpolation linéaire : hauteur exacte). */
    fun load(soundfont: String, instrument: String, note: String): DoubleArray = cache.getOrPut("$soundfont/$instrument/$note") {
        val file = File(dir, "$soundfont/$instrument/$note.mp3")
        val bitstream = Bitstream(file.inputStream().buffered())
        val decoder = Decoder()
        var data = DoubleArray(1 shl 16)
        var n = 0
        var rate = 44_100
        while (true) {
            val header = bitstream.readFrame() ?: break
            val buffer = decoder.decodeFrame(header, bitstream) as SampleBuffer
            rate = buffer.sampleFrequency
            val channels = buffer.channelCount
            val pcm = buffer.buffer
            val length = buffer.bufferLength
            var i = 0
            while (i + channels - 1 < length) {
                var s = 0.0
                for (c in 0 until channels) s += pcm[i + c]
                if (n == data.size) data = data.copyOf(n * 2)
                data[n++] = s / channels / 32768.0
                i += channels
            }
            bitstream.closeFrame()
        }
        resample(data.copyOf(n), rate, SR)
    }

    fun resample(x: DoubleArray, from: Int, to: Int): DoubleArray {
        if (from == to) return x
        val n = ((x.size - 1).toLong() * to / from).toInt()
        return DoubleArray(n) { i ->
            val t = i.toDouble() * from / to
            val k = t.toInt()
            val f = t - k
            x[k] * (1 - f) + x[minOf(k + 1, x.size - 1)] * f
        }
    }

    /** Réponse du micro : passe-haut d'ordre [order] (2 ou 4) à [cutoff] Hz, + pente « guitare électrique débranchée ». */
    data class Mic(val name: String, val cutoff: Double, val order: Int, val tiltHz: Double = 0.0)

    val RAW = Mic("brut 30Hz/2", 30.0, 2)
    val VOICE_120 = Mic("voix 120Hz/2", 120.0, 2)
    val VOICE_150_4 = Mic("voix 150Hz/4", 150.0, 4)
    val VOICE_200_4 = Mic("voix 200Hz/4", 200.0, 4)

    fun applyMic(x: DoubleArray, mic: Mic): DoubleArray {
        val filters = ArrayList<Biquad>()
        if (mic.order == 2) {
            filters += Biquad.highPass(SR, mic.cutoff)
        } else {
            filters += Biquad.highPass(SR, mic.cutoff, 0.5411961001461969)
            filters += Biquad.highPass(SR, mic.cutoff, 1.3065629648763764)
        }
        // Pente +12 dB/oct sous tiltHz (rayonnement dipolaire d'une électrique non branchée).
        if (mic.tiltHz > 0) {
            filters += Biquad.highPass(SR, mic.tiltHz, 0.5)
        }
        return DoubleArray(x.size) { i -> var v = x[i]; for (f in filters) v = f.process(v); v }
    }

    /** Met à l'échelle pour que le RMS court terme maximal (85 ms) vaille [db] dBFS. */
    fun scaleToPeakRms(x: DoubleArray, db: Double): DoubleArray {
        val w = 4096
        var best = 0.0
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i] * x[i]
            if (i >= w) sum -= x[i - w] * x[i - w]
            if (i >= w - 1) best = max(best, sum / w)
        }
        val g = 10.0.pow(db / 20.0) / sqrt(best)
        return DoubleArray(x.size) { x[it] * g }
    }

    fun noise(n: Int, db: Double, seed: Long = 3): DoubleArray {
        val r = Random(seed)
        val a = 10.0.pow(db / 20.0)
        return DoubleArray(n) { a * r.nextGaussian() }
    }

    /** Silence bruité [lead] s, puis le son, puis [tail] s ; renvoie aussi l'indice de l'attaque. */
    fun scene(tone: DoubleArray, noiseDb: Double, lead: Double = 0.8, tail: Double = 0.5, seed: Long = 3): Pair<DoubleArray, Int> {
        val start = (lead * SR).toInt()
        val total = start + tone.size + (tail * SR).toInt()
        val out = noise(total, noiseDb, seed)
        for (i in tone.indices) out[start + i] += tone[i]
        return out to start
    }

    fun run(signal: DoubleArray, targets: TunerTargets, mode: TunerMode = TunerMode.MONO): List<TunerFrame?> {
        val p = TunerProcessor(SR)
        p.targets = targets
        p.mode = mode
        val input = FloatArray(signal.size) { signal[it].toFloat() }
        val chunk = FloatArray(PitchDetector.HOP)
        val out = ArrayList<TunerFrame?>()
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            out += p.process(chunk)
            i += chunk.size
        }
        return out
    }

    fun db(x: Double) = 20 * log10(max(x, 1e-12))
}
