package com.blenouvel.accordeur.model

import kotlin.math.abs
import kotlin.math.sqrt

/** Verdict rythmique du suivi au micro. */
enum class TimingVerdict {
    /** Pas (encore) assez d'attaques pour juger. */
    NONE,

    /** Dans la tolérance : dans le tempo. */
    ON_TIME,

    /** En avance (attaques en moyenne avant le clic). */
    RUSHING,

    /** En retard (attaques en moyenne après le clic). */
    DRAGGING,
}

/**
 * Lecture du suivi de tempo à un instant donné. Les écarts sont **signés** : négatif = en avance,
 * positif = en retard, en millisecondes.
 */
data class TimingReading(
    /** Écart de la dernière attaque (ms), NaN si aucune. */
    val lastDeviationMs: Double = Double.NaN,
    /** Écart moyen lissé sur la fenêtre récente (ms), NaN si aucune. */
    val meanDeviationMs: Double = Double.NaN,
    /** Dispersion (écart-type) des écarts récents (ms), NaN si moins de deux attaques. */
    val spreadMs: Double = Double.NaN,
    /** Nombre d'attaques dans la fenêtre. */
    val matched: Int = 0,
    val verdict: TimingVerdict = TimingVerdict.NONE,
    /** Écarts récents (ms), du plus ancien au plus récent (pour les visuels qui les tracent). */
    val recentMs: List<Double> = emptyList(),
)

/**
 * Compare les attaques entendues au micro à la grille du métronome et en tire un écart de tempo.
 *
 * Logique pure (sans Android ni audio) : on l'alimente avec les repères de temps du moteur
 * ([onBeat], horodatés sur la même horloge que les attaques) et les attaques détectées ([onOnset]).
 * Chaque attaque est rattachée au point le plus proche d'une grille de [division] subdivisions
 * **égales** par temps ; l'écart est borné à la demi-grille (au-delà, le rattachement est ambigu).
 * Un offset de latence (constant : sortie audio + entrée micro) recentre le zéro.
 */
class TempoGrader(
    private val toleranceMs: Double = DEFAULT_TOLERANCE_MS,
    private val window: Int = DEFAULT_WINDOW,
    private val minForVerdict: Int = DEFAULT_MIN_FOR_VERDICT,
) {
    private var beatTimeMs = Double.NaN
    private var beatIntervalMs = Double.NaN

    @Volatile
    private var division = 1

    private val deviations = ArrayDeque<Double>()

    /** Nombre de subdivisions égales par temps sur lesquelles rattacher les attaques (≥ 1). */
    fun setDivision(value: Int) {
        division = value.coerceAtLeast(1)
    }

    /** Repère d'un temps : son instant [timeMs] et l'intervalle courant entre deux temps [intervalMs]. */
    fun onBeat(timeMs: Double, intervalMs: Double) {
        beatTimeMs = timeMs
        if (intervalMs > 0) beatIntervalMs = intervalMs
    }

    /**
     * Enregistre une attaque entendue à [timeMs] ; [latencyMs] recentre le zéro. Renvoie la lecture
     * mise à jour, ou null tant qu'aucun temps n'a encore été vu (rattachement impossible).
     */
    fun onOnset(timeMs: Double, latencyMs: Double = 0.0): TimingReading? {
        if (beatTimeMs.isNaN() || beatIntervalMs.isNaN() || beatIntervalMs <= 0.0) return null
        val gridMs = beatIntervalMs / division
        val adjusted = timeMs - latencyMs
        val k = Math.round((adjusted - beatTimeMs) / gridMs)
        val expected = beatTimeMs + k * gridMs
        val half = gridMs / 2.0
        val deviation = (adjusted - expected).coerceIn(-half, half)
        deviations.addLast(deviation)
        while (deviations.size > window) deviations.removeFirst()
        return reading()
    }

    /** Lecture courante (sans nouvelle attaque). */
    fun reading(): TimingReading {
        val n = deviations.size
        if (n == 0) return TimingReading()
        val mean = deviations.average()
        val spread = if (n >= 2) sqrt(deviations.sumOf { (it - mean) * (it - mean) } / n) else Double.NaN
        val verdict = when {
            n < minForVerdict -> TimingVerdict.NONE
            abs(mean) <= toleranceMs -> TimingVerdict.ON_TIME
            mean < 0.0 -> TimingVerdict.RUSHING
            else -> TimingVerdict.DRAGGING
        }
        return TimingReading(
            lastDeviationMs = deviations.last(),
            meanDeviationMs = mean,
            spreadMs = spread,
            matched = n,
            verdict = verdict,
            recentMs = deviations.toList(),
        )
    }

    /** Oublie les attaques et le repère courant (au (re)démarrage du métronome). */
    fun reset() {
        beatTimeMs = Double.NaN
        beatIntervalMs = Double.NaN
        deviations.clear()
    }

    companion object {
        /** En deçà de cet écart moyen, le jeu est « dans le tempo ». */
        const val DEFAULT_TOLERANCE_MS = 45.0
        const val DEFAULT_WINDOW = 8
        const val DEFAULT_MIN_FOR_VERDICT = 3
    }
}
