package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PitchEstimate
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Corde raide synthétique au spectre physiquement plausible : pincement triangulaire à la position
 * p, rayonnement « acoustique » (force au chevalet, α = 1) ou « électrique débranchée »
 * (dipôle, α = 2), partiels aigus amortis plus vite, léger glissement de hauteur à l'attaque.
 */
object StiffString {
    fun pluck(
        f1: Double,
        b: Double,
        seconds: Double = 3.0,
        pluckPos: Double = 0.18,
        alpha: Double = 1.0,
        tau1: Double = 4.0,
        cornerHz: Double = 700.0,
        glideCents: Double = 3.0,
        maxHz: Double = 5000.0,
        seed: Long = 1,
    ): DoubleArray {
        val r = Random(seed)
        val f0 = f1 / sqrt(1.0 + b)
        val n = (seconds * SR).toInt()
        val out = DoubleArray(n)
        var h = 1
        while (true) {
            val fh = h * f0 * sqrt(1.0 + b * h * h)
            if (fh > maxHz) break
            val a = sin(h * PI * pluckPos) / (h.toDouble() * h) * h.toDouble().pow(alpha)
            val tau = tau1 / (1.0 + (fh / cornerHz).pow(2))
            val phase = r.nextDouble() * 2 * PI
            var ph = phase
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val glide = 1.0 + (2.0.pow(glideCents / 1200.0) - 1.0) * exp(-t / 0.3)
                ph += 2 * PI * fh * glide / SR
                out[i] += a * exp(-t / tau) * sin(ph)
            }
            h++
        }
        return out
    }
}

class StiffStringDiag {
    private fun label(f: Double) = NoteNames.label(NoteMapper.nearest(f).note, Notation.ENGLISH)

    data class Case(val note: String, val b: Double, val tuning: Tuning)

    private val cases = listOf(
        Case("G#1", 8e-4, Presets.DROP_G_SHARP_7),
        Case("A1", 6e-4, Presets.DROP_A_7),
        Case("B1", 4e-4, Presets.STANDARD_7),
        Case("D2", 2.5e-4, Presets.DROP_D_6),
        Case("E2", 1.5e-4, Presets.STANDARD_6),
    )

    @Test
    fun summary() {
        for ((radiation, alpha) in listOf("acoustique α=1" to 1.0, "électrique débranchée α=2" to 2.0)) {
            println("=== $radiation (taux de trames avec lecture 0,15–1,6 s · part correcte · notes · 1re lecture · clarté médiane)")
            for (c in cases) {
                val f = NoteMapper.frequencyOf(Note.parse(c.note))
                for (mic in listOf(PhoneSim.RAW, PhoneSim.VOICE_120, PhoneSim.VOICE_150_4, PhoneSim.VOICE_200_4)) {
                    val line = StringBuilder("  ${c.note.padEnd(3)} B=${"%.1e".format(c.b)} ${mic.name.padEnd(13)}")
                    for (level in listOf(-55.0, -70.0)) {
                        val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(StiffString.pluck(f, c.b, alpha = alpha), mic), level)
                        line.append(" | " + evaluate(tone, Note.parse(c.note), c.tuning, level))
                    }
                    println(line)
                }
            }
        }
    }

    private fun evaluate(tone: DoubleArray, expected: Note, tuning: Tuning, level: Double): String {
        val (signal, start) = PhoneSim.scene(tone, -92.0)
        val frames = PhoneSim.run(signal, TunerTargets(tuning.frequencies()))
        val hop = PitchDetector.HOP
        val from = (start + 0.15 * SR).toInt() / hop
        val to = min(frames.size, (start + 1.6 * SR).toInt() / hop)
        var n = 0; var hits = 0; var good = 0; var first = -1
        val seen = LinkedHashMap<String, Int>()
        val centsList = ArrayList<Double>()
        for (i in from until to) {
            val fr = frames[i] ?: continue
            n++
            if (fr.hasPitch && !fr.holding) {
                hits++
                if (first < 0) first = i
                val note = NoteMapper.nearest(fr.frequency).note
                if (note == expected) { good++; centsList += NoteMapper.centsFrom(fr.frequency, expected) }
                val k = label(fr.frequency); seen[k] = (seen[k] ?: 0) + 1
            }
        }
        // Clarté MPM médiane sur la même plage.
        val pre = Preprocessor(SR)
        val filtered = DoubleArray(signal.size) { pre.process(signal[it]) }
        val det = PitchDetector(); val est = PitchEstimate()
        val clar = ArrayList<Double>()
        for (i in from until to) {
            val end = (i + 1) * hop
            if (det.detect(filtered.copyOfRange(end - PitchDetector.WINDOW, end), est)) clar += est.clarity
        }
        clar.sort()
        val med = if (clar.isEmpty()) 0.0 else clar[clar.size / 2]
        val firstMs = if (first < 0) -1 else (((first + 1) * hop - start) * 1000.0 / SR).toInt()
        val cents = if (centsList.isEmpty()) "   -  " else "%+5.1f".format(centsList.average())
        return String.format("%3.0f dB %3.0f%% ok%3.0f%% %s¢ %-16s %4dms cl %.2f", level, 100.0 * hits / n, if (hits == 0) 0.0 else 100.0 * good / hits, cents, seen.entries.joinToString(" ") { "${it.key}×${it.value}" }.take(16), firstMs, med)
    }
}
