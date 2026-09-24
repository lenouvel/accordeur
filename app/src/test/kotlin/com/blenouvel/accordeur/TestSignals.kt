package com.blenouvel.accordeur

import com.blenouvel.accordeur.audio.Biquad
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Générateurs de signaux synthétiques pour les tests (déterministes : graine fixe). */
object TestSignals {
    const val SAMPLE_RATE = 48_000

    /** Sinusoïde pure. */
    fun sine(frequency: Double, seconds: Double, amplitude: Double = 0.3, phase: Double = 0.3): DoubleArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        return DoubleArray(n) { amplitude * sin(2.0 * PI * frequency * it / SAMPLE_RATE + phase) }
    }

    /**
     * Corde pincée synthétique : partiels f_h = h·f0·√(1 + B·h²) (corde raide), amplitudes en
     * 1/h^[rolloff], fondamentale atténuée de [fundamentalDb] dB (micro de téléphone), décroissance
     * exponentielle (les aigus s'éteignent plus vite), phases aléatoires.
     * [f1] est la fréquence réelle du premier partiel (ce qu'un accordeur doit afficher).
     */
    fun pluckedString(
        f1: Double,
        seconds: Double,
        inharmonicity: Double = 0.0,
        partials: Int = 12,
        rolloff: Double = 1.0,
        fundamentalDb: Double = 0.0,
        decaySeconds: Double = 2.5,
        amplitude: Double = 0.3,
        seed: Long = 1,
    ): DoubleArray {
        val random = Random(seed)
        val f0 = f1 / sqrt(1.0 + inharmonicity)
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = DoubleArray(n)
        for (h in 1..partials) {
            val fh = h * f0 * sqrt(1.0 + inharmonicity * h * h)
            if (fh > SAMPLE_RATE / 2.0 - 1000) break
            var a = 1.0 / h.toDouble().pow(rolloff)
            if (h == 1) a *= 10.0.pow(fundamentalDb / 20.0)
            val tau = decaySeconds / (1.0 + 0.15 * (h - 1))
            val phase = random.nextDouble() * 2.0 * PI
            val w = 2.0 * PI * fh / SAMPLE_RATE
            for (i in 0 until n) out[i] += a * exp(-i / (tau * SAMPLE_RATE)) * sin(w * i + phase)
        }
        normalize(out, amplitude)
        return out
    }

    /**
     * Corde filée au spectre physiquement plausible : pincement triangulaire à [pluckPosition] de
     * la longueur (amplitudes sin(hπp)/h²), rayonnement ∝ h^[radiation] (1 : force au chevalet,
     * guitare acoustique ; 2 : dipôle, guitare électrique non branchée — les aigus dominent),
     * partiels f_h = h·f0·√(1 + B·h²) jusqu'à 5 kHz, les aigus s'éteignant plus vite.
     */
    fun stiffPluck(
        f1: Double,
        inharmonicity: Double,
        seconds: Double,
        pluckPosition: Double = 0.18,
        radiation: Double = 1.0,
        decaySeconds: Double = 4.0,
        amplitude: Double = 0.3,
        seed: Long = 1,
    ): DoubleArray {
        val random = Random(seed)
        val f0 = f1 / sqrt(1.0 + inharmonicity)
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = DoubleArray(n)
        var h = 1
        while (true) {
            val fh = h * f0 * sqrt(1.0 + inharmonicity * h * h)
            if (fh > 5000.0) break
            val a = sin(h * PI * pluckPosition) / (h.toDouble() * h) * h.toDouble().pow(radiation)
            val tau = decaySeconds / (1.0 + (fh / 700.0).pow(2))
            val phase = random.nextDouble() * 2.0 * PI
            val w = 2.0 * PI * fh / SAMPLE_RATE
            for (i in 0 until n) out[i] += a * exp(-i / (tau * SAMPLE_RATE)) * sin(w * i + phase)
            h++
        }
        normalize(out, amplitude)
        return out
    }

    /** Passe-haut de Butterworth d'ordre 4 : chemin micro « voix » d'un téléphone (coupe les graves). */
    fun highPass(signal: DoubleArray, cutoff: Double): DoubleArray {
        val first = Biquad.highPass(SAMPLE_RATE, cutoff, 0.5411961001461969)
        val second = Biquad.highPass(SAMPLE_RATE, cutoff, 1.3065629648763764)
        return DoubleArray(signal.size) { second.process(first.process(signal[it])) }
    }

    /** Bruit rose (filtre de Paul Kellet) ou « grondement » (bruit brun), de valeur efficace [rms]. */
    fun coloredNoise(n: Int, rms: Double, brown: Boolean, seed: Long = 11): DoubleArray {
        val random = Random(seed)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        val out = DoubleArray(n) {
            val w = random.nextGaussian()
            if (brown) {
                b0 = 0.998 * b0 + w
                b0
            } else {
                b0 = 0.99765 * b0 + w * 0.0990460
                b1 = 0.96300 * b1 + w * 0.2965164
                b2 = 0.57000 * b2 + w * 1.0526913
                b0 + b1 + b2 + w * 0.1848
            }
        }
        val scale = rms / rms(out)
        for (i in out.indices) out[i] *= scale
        return out
    }

    /** Ajoute un bruit blanc gaussien de valeur efficace [rms]. */
    fun addNoise(signal: DoubleArray, rms: Double, seed: Long = 7): DoubleArray {
        val random = Random(seed)
        return DoubleArray(signal.size) { signal[it] + rms * random.nextGaussian() }
    }

    /** Somme de plusieurs signaux (de même longueur). */
    fun mix(vararg signals: DoubleArray): DoubleArray {
        val n = signals.minOf { it.size }
        return DoubleArray(n) { i -> signals.sumOf { it[i] } }
    }

    fun rms(signal: DoubleArray): Double = sqrt(signal.sumOf { it * it } / signal.size)

    /** Mise à l'échelle pour que le crête vaille [peak]. */
    fun normalize(signal: DoubleArray, peak: Double) {
        val max = signal.maxOf { kotlin.math.abs(it) }
        if (max > 0) for (i in signal.indices) signal[i] *= peak / max
    }

    fun toFloat(signal: DoubleArray): FloatArray = FloatArray(signal.size) { signal[it].toFloat() }

    fun silence(seconds: Double): DoubleArray = DoubleArray((seconds * SAMPLE_RATE).toInt())

    fun concat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size })
        var offset = 0
        for (p in parts) {
            p.copyInto(out, offset)
            offset += p.size
        }
        return out
    }

    /** Écart en cents entre deux fréquences. */
    fun cents(measured: Double, expected: Double): Double = 1200.0 * kotlin.math.log2(measured / expected)
}
