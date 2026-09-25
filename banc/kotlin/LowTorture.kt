package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.Biquad
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.TunerFrame
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Corde G♯1 d'une 7-cordes en drop G♯ dans des conditions dégradées réalistes : ronflement secteur
 * 50 Hz (ampli), grondement de pièce, cordes voisines en résonance, frisage, réverbération,
 * partiels non idéaux, longue extinction. Chaîne complète (détecteur + stabilisateur).
 */
object LowString {
    data class P(
        val f1: Double = 51.913,
        val b: Double = 5e-4,
        val alpha: Double = 1.0,
        val pluckPos: Double = 0.15,
        val tau1: Double = 5.0,
        val corner: Double = 700.0,
        val glideCents: Double = 3.0,
        val glideTau: Double = 0.3,
        val jitter: Double = 0.0,
        val polarization: Double = 0.0,
        val seconds: Double = 8.0,
        val seed: Long = 1,
    )

    fun string(p: P): DoubleArray {
        val r = Random(p.seed)
        val f0 = p.f1 / sqrt(1.0 + p.b)
        val n = (p.seconds * SR).toInt()
        val out = DoubleArray(n)
        var h = 1
        while (true) {
            val model = h * f0 * sqrt(1.0 + p.b * h * h)
            if (model > 4000.0) break
            val fh = model * (1.0 + p.jitter * r.nextGaussian())
            val a = sin(h * PI * p.pluckPos) / (h.toDouble() * h) * h.toDouble().pow(p.alpha)
            val tau = p.tau1 / (1.0 + (fh / p.corner).pow(2))
            for (pol in 0..(if (p.polarization > 0) 1 else 0)) {
                val f = fh + pol * p.polarization * h
                val amp = if (p.polarization > 0) a * 0.5 else a
                var ph = r.nextDouble() * 2 * PI
                val decay = exp(-1.0 / (tau * SR))
                var env = amp
                val g = 2.0.pow(p.glideCents / 1200.0) - 1.0
                val glideDecay = exp(-1.0 / (p.glideTau * SR))
                var glide = g
                for (i in 0 until n) {
                    ph += 2 * PI * f * (1.0 + glide) / SR
                    out[i] += env * sin(ph)
                    env *= decay
                    glide *= glideDecay
                }
            }
            h++
        }
        return out
    }

    /** Ronflement d'ampli : 50 Hz et harmoniques (100 Hz dominant), valeur efficace relative. */
    fun hum(n: Int, rms: Double): DoubleArray {
        val out = DoubleArray(n)
        val amps = doubleArrayOf(0.5, 1.0, 0.6, 0.4, 0.3, 0.25, 0.2, 0.15, 0.1, 0.08)
        for (k in amps.indices) {
            val w = 2 * PI * 50.0 * (k + 1) / SR
            val ph = k * 1.3
            for (i in 0 until n) out[i] += amps[k] * sin(w * i + ph)
        }
        val s = rms / TestSignals.rms(out)
        for (i in out.indices) out[i] *= s
        return out
    }

    /** Frisage : bruit 300–3000 Hz, modulé à la période de la corde, qui s'éteint vite. */
    fun buzz(n: Int, f1: Double, rms: Double, tau: Double = 0.8, seed: Long = 5): DoubleArray {
        val r = Random(seed)
        val hp = Biquad.highPass(SR, 300.0)
        val lp = Biquad.lowPass(SR, 3000.0)
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val gate = max(0.0, sin(2 * PI * f1 * t)).pow(8)
            out[i] = lp.process(hp.process(r.nextGaussian())) * gate * exp(-t / tau)
        }
        // Normalise sur la première seconde.
        var s = 0.0
        val m = min(n, SR)
        for (i in 0 until m) s += out[i] * out[i]
        val g = rms / sqrt(s / m)
        for (i in out.indices) out[i] *= g
        return out
    }

    /** Réverbération : bruit à décroissance exponentielle (RT60), mélangé à [wet]. */
    fun reverb(x: DoubleArray, rt60: Double, wet: Double, seed: Long = 9): DoubleArray {
        val r = Random(seed)
        val len = (rt60 * SR).toInt()
        val ir = DoubleArray(len) { r.nextGaussian() * exp(-6.91 * it / len) }
        val norm = sqrt(ir.sumOf { it * it })
        for (i in ir.indices) ir[i] /= norm
        // Convolution par blocs FFT serait mieux ; ici directe mais sous-échantillonnée de l'IR.
        val out = x.copyOf()
        val step = 16
        for (k in 1 until len step step) {
            val c = ir[k] * wet * sqrt(step.toDouble())
            if (abs(c) < 1e-6) continue
            for (i in k until x.size) out[i] += c * x[i - k]
        }
        return out
    }

    fun scale(x: DoubleArray, g: Double) = DoubleArray(x.size) { x[it] * g }
    fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { a[it] + (if (it < b.size) b[it] else 0.0) }
}

class LowTorture {
    private fun label(f: Double) = NoteNames.label(NoteMapper.nearest(f).note, Notation.ENGLISH)
    private val tuning = Presets.DROP_G_SHARP_7
    private val expected = Note.parse("G#1")

    data class Stats(val rate: DoubleArray, val correct: Double, val cents: Double, val spread: Double, val first: Int, val notes: String)

    private val spans = listOf(0.2 to 1.5, 1.5 to 4.0, 4.0 to 7.5)

    private fun evaluate(tone: DoubleArray, lead: Double = 1.0, noiseDb: Double = -92.0, extra: DoubleArray? = null): Stats {
        val start = (lead * SR).toInt()
        val total = start + tone.size + SR / 2
        val signal = PhoneSim.noise(total, noiseDb)
        for (i in tone.indices) signal[start + i] += tone[i]
        if (extra != null) for (i in 0 until min(total, extra.size)) signal[i] += extra[i]
        val frames = PhoneSim.run(signal, TunerTargets(tuning.frequencies()))
        return stats(frames, start)
    }

    fun stats(frames: List<TunerFrame?>, start: Int): Stats {
        val hop = PitchDetector.HOP
        val rates = DoubleArray(spans.size)
        var hits = 0; var good = 0; var first = -1
        val cents = ArrayList<Double>()
        val seen = LinkedHashMap<String, Int>()
        for ((s, span) in spans.withIndex()) {
            val from = (start + span.first * SR).toInt() / hop
            val to = min(frames.size, (start + span.second * SR).toInt() / hop)
            var n = 0; var h = 0
            for (i in from until to) {
                val f = frames[i] ?: continue
                n++
                if (f.hasPitch && !f.holding) {
                    h++; hits++
                    if (NoteMapper.nearest(f.frequency).note == expected) { good++; cents += NoteMapper.centsFrom(f.frequency, expected) }
                    val k = label(f.frequency); seen[k] = (seen[k] ?: 0) + 1
                }
            }
            rates[s] = if (n == 0) 0.0 else h.toDouble() / n
        }
        for (i in frames.indices) { val f = frames[i]; if (f != null && f.hasPitch && !f.holding && i * hop >= start) { first = ((i + 1) * hop - start) * 1000 / SR; break } }
        val mean = if (cents.isEmpty()) Double.NaN else cents.average()
        val sd = if (cents.size < 2) Double.NaN else sqrt(cents.sumOf { (it - mean) * (it - mean) } / (cents.size - 1))
        return Stats(rates, if (hits == 0) 0.0 else good.toDouble() / hits, mean, sd, first, seen.entries.joinToString(" ") { "${it.key}×${it.value}" })
    }

    private fun fmt(s: Stats) = String.format("%3.0f%% %3.0f%% %3.0f%% | ok %3.0f%% %+5.1f¢ ±%4.1f | 1re %4d ms | %s",
        100 * s.rate[0], 100 * s.rate[1], 100 * s.rate[2], 100 * s.correct, s.cents, s.spread, s.first, s.notes.take(40))

    private fun phone(x: DoubleArray, mic: PhoneSim.Mic) = PhoneSim.applyMic(x, mic)

    @Test
    fun torture() {
        val mics = listOf(PhoneSim.VOICE_150_4, PhoneSim.VOICE_200_4)
        println("colonnes : taux de lecture 0,2–1,5 s | 1,5–4 s | 4–7,5 s ; part correcte ; justesse ; 1re lecture ; notes")
        for (mic in mics) for (level in listOf(-60.0, -72.0)) {
            println("=== ${mic.name}, crête ${level} dBFS")
            fun base(p: LowString.P) = PhoneSim.scaleToPeakRms(phone(LowString.string(p), mic), level)
            val g = 10.0.pow(level / 20.0)
            val cases = linkedMapOf<String, () -> Stats>(
                "référence B=5e-4" to { evaluate(base(LowString.P())) },
                "B=1e-4" to { evaluate(base(LowString.P(b = 1e-4))) },
                "B=1.2e-3" to { evaluate(base(LowString.P(b = 1.2e-3))) },
                "B=2.5e-3" to { evaluate(base(LowString.P(b = 2.5e-3))) },
                "électrique α=2" to { evaluate(base(LowString.P(alpha = 2.0))) },
                "pincé au 1/10" to { evaluate(base(LowString.P(pluckPos = 0.1))) },
                "pincé au 1/4" to { evaluate(base(LowString.P(pluckPos = 0.25))) },
                "glissement 30¢" to { evaluate(base(LowString.P(glideCents = 30.0, glideTau = 0.6))) },
                "partiels ±0,15 %" to { evaluate(base(LowString.P(jitter = 0.0015))) },
                "partiels ±0,4 %" to { evaluate(base(LowString.P(jitter = 0.004))) },
                "2 polarisations" to { evaluate(base(LowString.P(polarization = 0.25))) },
                "extinction rapide τ=2" to { evaluate(base(LowString.P(tau1 = 2.0))) },
                "ronflement −12 dB" to {
                    val t = base(LowString.P())
                    evaluate(t, extra = phone(LowString.hum(t.size + 2 * SR, g * 0.25), mic))
                },
                "ronflement −6 dB" to {
                    val t = base(LowString.P())
                    evaluate(t, extra = phone(LowString.hum(t.size + 2 * SR, g * 0.5), mic))
                },
                "grondement −15 dB" to {
                    val t = base(LowString.P())
                    evaluate(t, extra = phone(TestSignals.coloredNoise(t.size + 2 * SR, g * 0.18, brown = true), mic))
                },
                "bruit rose −25 dB" to {
                    val t = base(LowString.P())
                    evaluate(t, extra = phone(TestSignals.coloredNoise(t.size + 2 * SR, g * 0.056, brown = false), mic))
                },
                "frisage −10 dB" to {
                    val p = LowString.P()
                    val t = base(p)
                    evaluate(LowString.add(t, LowString.buzz(t.size, p.f1, g * 0.3)))
                },
                "sympathiques G♯2/D♯2" to {
                    val s = LowString.string(LowString.P())
                    val gs2 = LowString.string(LowString.P(f1 = 103.83 * 2.0.pow(4.0 / 1200), b = 1.5e-4, pluckPos = 0.5, seed = 7))
                    val ds2 = LowString.string(LowString.P(f1 = 77.78 * 2.0.pow(-6.0 / 1200), b = 2.5e-4, pluckPos = 0.5, seed = 8))
                    val mix = DoubleArray(s.size) { s[it] + 0.12 * gs2[it] + 0.08 * ds2[it] }
                    evaluate(PhoneSim.scaleToPeakRms(phone(mix, mic), level))
                },
                "réverbération 0,6 s" to { evaluate(PhoneSim.scaleToPeakRms(phone(LowString.reverb(LowString.string(LowString.P()), 0.6, 0.5), mic), level)) },
                "tout ensemble" to {
                    val p = LowString.P(alpha = 1.5, glideCents = 15.0, jitter = 0.0015, polarization = 0.2, b = 8e-4)
                    val s = LowString.string(p)
                    val gs2 = LowString.string(LowString.P(f1 = 103.83 * 2.0.pow(4.0 / 1200), b = 1.5e-4, pluckPos = 0.5, seed = 7))
                    val mix = DoubleArray(s.size) { s[it] + 0.1 * gs2[it] }
                    val t = PhoneSim.scaleToPeakRms(phone(LowString.reverb(mix, 0.5, 0.4), mic), level)
                    val extra = LowString.add(phone(LowString.hum(t.size + 2 * SR, g * 0.2), mic), phone(TestSignals.coloredNoise(t.size + 2 * SR, g * 0.1, brown = true), mic))
                    evaluate(LowString.add(t, LowString.buzz(t.size, p.f1, g * 0.2)), extra = extra)
                },
            )
            for ((name, run) in cases) println("  ${name.padEnd(22)} " + fmt(run()))
        }
    }
}
