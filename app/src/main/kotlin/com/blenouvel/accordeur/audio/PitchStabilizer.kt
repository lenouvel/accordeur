package com.blenouvel.accordeur.audio

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * Stabilisation de la hauteur mesurée trame après trame :
 *
 * - **Gate** adaptatif avec hystérésis : niveau > max(plancher absolu, bruit ambiant + marge).
 *   Le bruit de fond est suivi en continu (descente rapide, remontée lente).
 * - **Clarté** : une mesure n'est retenue que si la clarté MPM ≥ [minClarity].
 * - **Garde d'octave** : repli vers la corde verrouillée ou vers une corde de l'accordage, puis
 *   continuité à l'intérieur d'une attaque (une fois la note établie, un saut d'exactement une
 *   octave est une erreur de détection — typique des cordes graves en fin de note).
 * - **Médian** sur les 5 dernières mesures (rejette les valeurs aberrantes).
 * - **Filtre 1€** sur la hauteur en cents (aiguille fluide, stable près de la justesse).
 * - **Maintien** de la dernière valeur pendant [holdSeconds] quand le signal disparaît.
 *
 * Hauteurs manipulées en cents absolus : 1200·log2(f / 440).
 */
class PitchStabilizer(
    private val minClarity: Double = 0.9,
    private val holdSeconds: Double = 1.2,
    smoothingBeta: Double = SMOOTHING_BETA,
) {
    // --- Sorties (lues après update) -----------------------------------------------------
    /** Gate ouvert : un signal utile est présent. */
    var gateOpen = false
        private set

    /** Nombre d'attaques détectées depuis la création (utile pour le mode poly). */
    var onsetCount = 0
        private set

    /** Instant de la dernière attaque (s). */
    var lastOnsetTime = Double.NEGATIVE_INFINITY
        private set

    /** Hauteur lissée (Hz) ou NaN. */
    var frequency = Double.NaN
        private set

    /** Dernière mesure brute retenue, après correction d'octave (Hz) ou NaN. */
    var rawFrequency = Double.NaN
        private set

    var clarity = 0.0
        private set

    /** Vrai si [frequency] est une valeur maintenue (plus de signal exploitable). */
    var holding = false
        private set

    /** Bruit de fond estimé (dBFS). */
    var noiseFloorDb = INITIAL_NOISE_FLOOR_DB
        private set

    // --- État interne ---------------------------------------------------------------------
    private val oneEuro = OneEuroFilter(minCutoff = 1.0, beta = smoothingBeta, derivativeCutoff = 1.0)
    private val history = DoubleArray(MEDIAN_SIZE)
    private val sorted = DoubleArray(MEDIAN_SIZE)
    private var historyCount = 0
    private var historyNext = 0

    private var reference = Double.NaN
    private var acceptedInSegment = 0
    private var pendingValue = Double.NaN
    private var pendingCount = 0
    private var warmupHops = 0
    private var previousHopLevelDb = SILENCE_DB
    private var previousTime = Double.NaN
    private var startTime = Double.NaN
    private var lastValidTime = Double.NEGATIVE_INFINITY
    private var smoothedCents = Double.NaN

    /**
     * Met à jour le gate et la détection d'attaque. À appeler à chaque hop, avant [updatePitch].
     * [levelDb] : niveau RMS de la fenêtre d'analyse ; [hopLevelDb] : niveau du dernier hop.
     */
    fun updateLevel(levelDb: Double, hopLevelDb: Double, timeSeconds: Double) {
        val dt = if (previousTime.isNaN()) 0.0 else (timeSeconds - previousTime).coerceAtLeast(0.0)
        previousTime = timeSeconds
        if (startTime.isNaN()) startTime = timeSeconds

        // Suivi du bruit de fond : descente rapide, remontée lente (une note tenue ne devient pas
        // « bruit »), sauf juste après le démarrage où l'on apprend vite l'ambiance de la pièce.
        val riseRate = if (timeSeconds - startTime < LEARNING_S) LEARNING_RISE_DB_PER_S else NOISE_RISE_DB_PER_S
        noiseFloorDb = if (levelDb < noiseFloorDb) {
            noiseFloorDb + 0.5 * (levelDb - noiseFloorDb)
        } else {
            (noiseFloorDb + riseRate * dt).coerceAtMost(levelDb)
        }.coerceIn(ABSOLUTE_GATE_DB - 20.0, MAX_NOISE_FLOOR_DB)

        val openThreshold = max(ABSOLUTE_GATE_DB, noiseFloorDb + GATE_MARGIN_DB)
        val wasOpen = gateOpen
        gateOpen = if (gateOpen) levelDb > openThreshold - GATE_HYSTERESIS_DB else levelDb > openThreshold

        val jump = hopLevelDb - previousHopLevelDb
        previousHopLevelDb = hopLevelDb
        val refractory = timeSeconds - lastOnsetTime < ONSET_REFRACTORY_S
        val onset = gateOpen && !refractory && (!wasOpen || jump >= ONSET_JUMP_DB)
        if (onset) {
            onsetCount++
            lastOnsetTime = timeSeconds
            startSegment(wasOpen)
        }
    }

    /**
     * Intègre la mesure de la trame courante. [estimate] peut être invalide (pas de hauteur).
     * [lockedTargetHz] > 0 : corde verrouillée (mode manuel) ; [stringTargetsHz] : cordes de l'accordage.
     */
    fun updatePitch(
        estimate: PitchEstimate?,
        timeSeconds: Double,
        lockedTargetHz: Double,
        stringTargetsHz: DoubleArray,
    ) {
        val valid = gateOpen && estimate != null && estimate.isValid && estimate.clarity >= minClarity
        if (warmupHops > 0) {
            warmupHops--
        } else if (valid) {
            clarity = estimate.clarity
            accept(toCents(estimate.frequency), timeSeconds, lockedTargetHz, stringTargetsHz)
        }

        if (timeSeconds - lastValidTime <= 1e-9) {
            holding = false
        } else if (!smoothedCents.isNaN() && timeSeconds - lastValidTime <= holdSeconds) {
            holding = true
        } else {
            clearOutput()
        }
    }

    /** Oublie tout (changement d'accordage, reprise après pause…). */
    fun reset() {
        gateOpen = false
        noiseFloorDb = INITIAL_NOISE_FLOOR_DB
        previousHopLevelDb = SILENCE_DB
        previousTime = Double.NaN
        startTime = Double.NaN
        startSegment(false)
        clearOutput()
    }

    private fun accept(rawCents: Double, time: Double, lockedTargetHz: Double, strings: DoubleArray) {
        var cents = foldToTargets(rawCents, lockedTargetHz, strings)

        // Continuité d'octave dans l'attaque en cours.
        if (acceptedInSegment >= REFERENCE_READINGS && !reference.isNaN()) {
            if (abs(cents - (reference + 1200.0)) < OCTAVE_TOLERANCE_CENTS) {
                cents -= 1200.0
            } else if (abs(cents - (reference - 1200.0)) < OCTAVE_TOLERANCE_CENTS) {
                cents += 1200.0
            }
            // Saut franc vers une autre note sans nouvelle attaque : on n'y croit qu'après
            // plusieurs mesures concordantes.
            if (abs(cents - reference) > NOTE_CHANGE_CENTS) {
                if (pendingCount > 0 && abs(cents - pendingValue) < NOTE_AGREEMENT_CENTS) {
                    pendingCount++
                } else {
                    pendingValue = cents
                    pendingCount = 1
                }
                if (pendingCount < NOTE_CHANGE_READINGS) return
                resetFilters()
                acceptedInSegment = 0
            }
        }
        pendingCount = 0

        history[historyNext] = cents
        historyNext = (historyNext + 1) % MEDIAN_SIZE
        if (historyCount < MEDIAN_SIZE) historyCount++
        val median = median()
        smoothedCents = oneEuro.filter(median, time)
        acceptedInSegment++
        reference = median
        lastValidTime = time
        frequency = fromCents(smoothedCents)
        rawFrequency = fromCents(cents)
        holding = false
    }

    /**
     * Repli d'octave guidé par l'accordage : une mesure qui tombe exactement une octave au-dessus
     * (ou au-dessous) de la corde visée — et loin de toute autre corde — est ramenée sur celle-ci.
     */
    private fun foldToTargets(cents: Double, lockedTargetHz: Double, strings: DoubleArray): Double {
        if (lockedTargetHz > 0.0) {
            val target = toCents(lockedTargetHz)
            return when {
                abs(cents - 1200.0 - target) < TARGET_OCTAVE_WINDOW_CENTS -> cents - 1200.0
                abs(cents + 1200.0 - target) < TARGET_OCTAVE_WINDOW_CENTS -> cents + 1200.0
                else -> cents
            }
        }
        if (strings.isEmpty() || nearAnyString(cents, strings)) return cents
        return when {
            nearAnyString(cents - 1200.0, strings) -> cents - 1200.0
            nearAnyString(cents + 1200.0, strings) -> cents + 1200.0
            else -> cents
        }
    }

    private fun nearAnyString(cents: Double, strings: DoubleArray): Boolean {
        for (hz in strings) if (abs(cents - toCents(hz)) <= TARGET_OCTAVE_WINDOW_CENTS) return true
        return false
    }

    private fun startSegment(afterSound: Boolean) {
        resetFilters()
        reference = Double.NaN
        acceptedInSegment = 0
        pendingCount = 0
        // Après une nouvelle attaque sur une note qui sonne encore, la fenêtre mélange les deux
        // notes : on ignore la première analyse.
        warmupHops = if (afterSound) 1 else 0
    }

    private fun resetFilters() {
        historyCount = 0
        historyNext = 0
        oneEuro.reset()
    }

    private fun clearOutput() {
        if (!smoothedCents.isNaN()) startSegment(false)
        frequency = Double.NaN
        rawFrequency = Double.NaN
        smoothedCents = Double.NaN
        clarity = 0.0
        holding = false
    }

    private fun median(): Double {
        for (i in 0 until historyCount) sorted[i] = history[i]
        for (i in 1 until historyCount) {
            val v = sorted[i]
            var j = i - 1
            while (j >= 0 && sorted[j] > v) {
                sorted[j + 1] = sorted[j]
                j--
            }
            sorted[j + 1] = v
        }
        return if (historyCount % 2 == 1) {
            sorted[historyCount / 2]
        } else {
            0.5 * (sorted[historyCount / 2 - 1] + sorted[historyCount / 2])
        }
    }

    companion object {
        const val SILENCE_DB = -120.0
        const val ABSOLUTE_GATE_DB = -85.0
        const val GATE_MARGIN_DB = 10.0
        const val GATE_HYSTERESIS_DB = 4.0
        const val INITIAL_NOISE_FLOOR_DB = -95.0
        const val MAX_NOISE_FLOOR_DB = -45.0
        const val NOISE_RISE_DB_PER_S = 1.5
        const val LEARNING_S = 1.5
        const val LEARNING_RISE_DB_PER_S = 15.0

        /**
         * β du filtre 1€. En cents/secondes, 0,05 (au lieu de 0,007) réduit le retard de l'aiguille
         * quand on tourne la mécanique sans augmenter le tremblement sur une note tenue.
         */
        const val SMOOTHING_BETA = 0.05
        const val ONSET_JUMP_DB = 6.0
        const val ONSET_REFRACTORY_S = 0.15

        private const val MEDIAN_SIZE = 5
        private const val REFERENCE_READINGS = 3
        private const val OCTAVE_TOLERANCE_CENTS = 70.0
        private const val TARGET_OCTAVE_WINDOW_CENTS = 100.0
        private const val NOTE_CHANGE_CENTS = 60.0
        private const val NOTE_AGREEMENT_CENTS = 30.0
        private const val NOTE_CHANGE_READINGS = 3

        fun toCents(frequency: Double): Double = 1200.0 * log2(frequency / 440.0)
        fun fromCents(cents: Double): Double = 440.0 * 2.0.pow(cents / 1200.0)
    }
}
