package com.blenouvel.accordeur.audio

import kotlin.math.PI
import kotlin.math.abs

/**
 * Filtre « 1€ » (Casiez et al., 2012) : passe-bas dont la coupure augmente avec la vitesse
 * du signal. Aiguille stable quand la note est tenue, réactive quand on tourne la mécanique.
 * Unités : le signal est en cents, le temps en secondes.
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.007,
    private val derivativeCutoff: Double = 1.0,
) {
    private var initialized = false
    private var previousValue = 0.0
    private var previousDerivative = 0.0
    private var previousTime = 0.0

    fun filter(value: Double, timeSeconds: Double): Double {
        if (!initialized) {
            initialized = true
            previousValue = value
            previousDerivative = 0.0
            previousTime = timeSeconds
            return value
        }
        val dt = (timeSeconds - previousTime).coerceAtLeast(1e-4)
        val derivative = (value - previousValue) / dt
        val smoothedDerivative = lerp(previousDerivative, derivative, alpha(dt, derivativeCutoff))
        val cutoff = minCutoff + beta * abs(smoothedDerivative)
        val smoothed = lerp(previousValue, value, alpha(dt, cutoff))
        previousValue = smoothed
        previousDerivative = smoothedDerivative
        previousTime = timeSeconds
        return smoothed
    }

    fun reset() {
        initialized = false
    }

    private fun alpha(dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    private fun lerp(from: Double, to: Double, alpha: Double) = from + alpha * (to - from)
}
