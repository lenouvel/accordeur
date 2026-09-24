package com.blenouvel.accordeur.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Filtre biquad (coefficients RBJ « Audio EQ Cookbook »), structure Direct Form II
 * transposée, calcul en double (stable même pour une coupure très basse à 48 kHz).
 */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        /** Q de Butterworth d'ordre 2. */
        const val Q_BUTTERWORTH = 0.7071067811865476

        fun highPass(sampleRate: Int, cutoff: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w0 = 2.0 * PI * cutoff / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 + cw) / 2.0 / a0,
                b1 = -(1.0 + cw) / a0,
                b2 = (1.0 + cw) / 2.0 / a0,
                a1 = -2.0 * cw / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }

        fun lowPass(sampleRate: Int, cutoff: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w0 = 2.0 * PI * cutoff / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 - cw) / 2.0 / a0,
                b1 = (1.0 - cw) / a0,
                b2 = (1.0 - cw) / 2.0 / a0,
                a1 = -2.0 * cw / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}

/** Suppression de la composante continue : y[n] = x[n] − x[n−1] + r·y[n−1]. */
class DcBlocker(private val r: Double = 0.9995) {
    private var x1 = 0.0
    private var y1 = 0.0

    fun process(x: Double): Double {
        val y = x - x1 + r * y1
        x1 = x
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        y1 = 0.0
    }
}

/**
 * Pré-traitement du flux micro (appliqué en continu, échantillon par échantillon) :
 * DC block → passe-haut 32 Hz (laisse passer G♯1 = 51,9 Hz) → passe-bas 1,3 kHz
 * d'ordre 4 (Butterworth, deux biquads). Coupe grondements, manipulation et souffle.
 */
class Preprocessor(sampleRate: Int, highPassHz: Double = 32.0, lowPassHz: Double = 1300.0) {
    private val dc = DcBlocker()
    private val highPass = Biquad.highPass(sampleRate, highPassHz)
    private val lowPass1 = Biquad.lowPass(sampleRate, lowPassHz, 0.5411961001461969)
    private val lowPass2 = Biquad.lowPass(sampleRate, lowPassHz, 1.3065629648763764)

    fun process(x: Double): Double = lowPass2.process(lowPass1.process(highPass.process(dc.process(x))))

    fun reset() {
        dc.reset()
        highPass.reset()
        lowPass1.reset()
        lowPass2.reset()
    }
}
