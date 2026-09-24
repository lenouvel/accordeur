package com.blenouvel.accordeur

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
