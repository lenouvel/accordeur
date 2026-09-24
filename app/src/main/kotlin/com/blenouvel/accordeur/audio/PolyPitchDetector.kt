package com.blenouvel.accordeur.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Mesure d'une corde en mode polyphonique. */
data class PolyString(
    /** Corde repérée dans le spectre. */
    val detected: Boolean,
    /** Écart à la cible en cents (NaN si non détectée). */
    val cents: Double,
    /** Faux si la mesure repose sur un partiel partagé avec une autre corde (moins fiable). */
    val reliable: Boolean,
    /** Mise à jour par la dernière attaque (grattage ou corde jouée seule). */
    val fresh: Boolean = false,
)

/**
 * Résultat polyphonique : une entrée par corde (grave → aiguë). [event] compte les attaques
 * prises en compte ; [strum] : la dernière était un grattage (sinon une ou deux cordes seules).
 */
data class PolyReading(
    val strings: List<PolyString>,
    val timeSeconds: Double,
    val event: Int = 0,
    val strum: Boolean = false,
)

/**
 * Estimation multi-hauteurs **contrainte** (type PolyTune) : on connaît les fréquences cibles
 * de l'accordage, on mesure seulement l'écart de chaque corde.
 *
 * Fenêtre longue (16384 éch. ≈ 0,34 s) × Hann, zéro-padding ×2, FFT réelle. Pour chaque corde,
 * on choisit les partiels (h = 1…3) les moins recouverts par ceux des autres cordes (accordage
 * en quartes/quintes → recouvrements fréquents), on cherche le pic à ±½ ton, on estime sa
 * fréquence exacte (estimateur Hann à deux bins) et on combine les partiels retenus.
 * Mesurer h·f puis diviser par h améliore d'autant la résolution en cents sur les graves.
 *
 * Aucune allocation hors de la construction du [PolyReading] renvoyé.
 */
class PolyPitchDetector(
    val sampleRate: Int = PitchDetector.SAMPLE_RATE,
    val windowSize: Int = WINDOW,
) {
    init {
        require((windowSize and (windowSize - 1)) == 0) { "fenêtre non puissance de 2 : $windowSize" }
    }

    private val fftSize = windowSize * 2
    private val fft = RealFft(fftSize)
    private val hann = DoubleArray(windowSize) { 0.5 - 0.5 * cos(2.0 * PI * it / windowSize) }
    private val buffer = DoubleArray(fftSize)
    private val re = DoubleArray(fft.bins)
    private val im = DoubleArray(fft.bins)
    private val magnitude = DoubleArray(fft.bins)
    private val scratch = DoubleArray(fft.bins)

    /** Pas du spectre zéro-paddé (Hz). */
    private val paddedBinHz = sampleRate.toDouble() / fftSize

    /** Résolution de la fenêtre (Hz) : un bin du spectre non paddé = 2 bins paddés. */
    private val windowBinHz = sampleRate.toDouble() / windowSize
    private val collisionHz = COLLISION_BINS * windowBinHz

    private var peakFrequency = 0.0
    private var peakMagnitude = 0.0

    /** Énergie de chaque corde à la dernière analyse (partiels retenus, détectée ou non). */
    val energies = DoubleArray(MAX_STRINGS)

    /** La corde a au moins un partiel propre (non partagé avec une autre corde). */
    val clean = BooleanArray(MAX_STRINGS)

    /** Partiels mesurables (h ≤ 3, dans la bande) et partiels effectivement trouvés, par corde. */
    val partialsExpected = IntArray(MAX_STRINGS)
    val partialsFound = IntArray(MAX_STRINGS)

    /**
     * Analyse [window] ([windowSize] échantillons filtrés) pour les cordes [targetsHz].
     * Remplit aussi [energies] et [clean] pour chaque corde.
     */
    fun analyze(window: DoubleArray, targetsHz: DoubleArray, timeSeconds: Double): PolyReading {
        for (i in 0 until windowSize) buffer[i] = window[i] * hann[i]
        java.util.Arrays.fill(buffer, windowSize, fftSize, 0.0)
        fft.forward(buffer, re, im)
        val lastBin = min(fft.bins - 3, ceil(MAX_ANALYSIS_HZ / paddedBinHz).toInt())
        var globalMax = 0.0
        for (k in 0..lastBin + 2) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            magnitude[k] = m
            if (k <= lastBin && m > globalMax) globalMax = m
        }
        val noiseFloor = noiseFloor(lastBin)

        val results = ArrayList<PolyString>(targetsHz.size)
        for (i in targetsHz.indices) {
            results += measureString(i, targetsHz, noiseFloor, globalMax)
        }
        return PolyReading(results, timeSeconds)
    }

    private fun measureString(index: Int, targets: DoubleArray, noiseFloor: Double, globalMax: Double): PolyString {
        val target = targets[index]
        var weightSum = 0.0
        var centsSum = 0.0
        var used = 0
        var considered = 0
        var reliable = false
        var energy = 0.0
        var bestScore = Double.MAX_VALUE
        // Meilleur score de recouvrement parmi les partiels disponibles.
        for (h in 1..MAX_HARMONIC) {
            if (h * target > MAX_ANALYSIS_HZ) break
            bestScore = min(bestScore, overlapScore(index, h, targets))
        }
        for (h in 1..MAX_HARMONIC) {
            val expected = h * target
            if (expected > MAX_ANALYSIS_HZ) break
            val score = overlapScore(index, h, targets)
            // On garde les partiels propres, ou à défaut le moins recouvert.
            if (score > max(RELIABLE_SCORE, bestScore + 1e-9)) continue
            if (!searchBounds(index, h, targets)) continue
            considered++
            energy += maxSquared(searchLo, searchHi)
            if (!findPeak(searchLo, searchHi)) continue
            if (peakMagnitude < noiseFloor * MIN_SNR || peakMagnitude < globalMax * MIN_RELATIVE) continue
            val cents = 1200.0 * log2(peakFrequency / expected)
            if (abs(cents) > MAX_CENTS) continue
            // Précision en cents ∝ h · amplitude (le partiel h divise l'erreur par h).
            val weight = h.toDouble() * h * peakMagnitude * peakMagnitude
            centsSum += weight * cents
            weightSum += weight
            used++
            if (score <= RELIABLE_SCORE) reliable = true
        }
        if (index < MAX_STRINGS) {
            energies[index] = energy
            clean[index] = bestScore <= RELIABLE_SCORE
            partialsExpected[index] = considered
            partialsFound[index] = used
        }
        return if (used == 0) {
            PolyString(detected = false, cents = Double.NaN, reliable = false)
        } else {
            PolyString(detected = true, cents = centsSum / weightSum, reliable = reliable)
        }
    }

    private var searchLo = 0.0
    private var searchHi = 0.0

    /**
     * Fenêtre de recherche du partiel h de la corde [index] : ±½ ton, rognée à mi-chemin des
     * partiels des autres cordes qui y tombent (sinon une corde muette « emprunterait » le pic
     * d'une voisine). Les partiels confondus (recouvrement) sont gérés par [overlapScore].
     */
    private fun searchBounds(index: Int, h: Int, targets: DoubleArray): Boolean {
        val expected = h * targets[index]
        var lo = expected * CENTS_50_DOWN
        var hi = expected * CENTS_50_UP
        for (j in targets.indices) {
            if (j == index) continue
            for (m in 1..OVERLAP_HARMONICS) {
                val other = m * targets[j]
                if (abs(other - expected) < collisionHz) continue
                if (other in lo..expected) lo = max(lo, 0.5 * (other + expected))
                if (other in expected..hi) hi = min(hi, 0.5 * (other + expected))
            }
        }
        searchLo = lo
        searchHi = hi
        return hi - lo > 1.5 * paddedBinHz
    }

    /**
     * Recouvrement du partiel h de la corde [index] par les partiels des autres cordes, pondéré
     * par l'amplitude attendue (∝ 1/m pour le partiel m) relative à celle du partiel mesuré.
     */
    private fun overlapScore(index: Int, h: Int, targets: DoubleArray): Double {
        val f = h * targets[index]
        var score = 0.0
        for (j in targets.indices) {
            if (j == index) continue
            for (m in 1..OVERLAP_HARMONICS) {
                if (abs(m * targets[j] - f) < collisionHz) score += h.toDouble() / m
            }
        }
        return score
    }

    /** Carré de l'amplitude maximale entre [loHz] et [hiHz] (énergie du partiel, même faible). */
    private fun maxSquared(loHz: Double, hiHz: Double): Double {
        val lo = max(4, ceil(loHz / paddedBinHz).toInt())
        val hi = min(fft.bins - 5, floor(hiHz / paddedBinHz).toInt())
        var best = 0.0
        for (k in lo..hi) if (magnitude[k] > best) best = magnitude[k]
        return best * best
    }

    /** Pic le plus fort entre [loHz] et [hiHz] ; fréquence estimée sur les bins non paddés. */
    private fun findPeak(loHz: Double, hiHz: Double): Boolean {
        val lo = max(4, ceil(loHz / paddedBinHz).toInt())
        val hi = min(fft.bins - 5, floor(hiHz / paddedBinHz).toInt())
        if (hi < lo) return false
        var best = -1
        var bestValue = 0.0
        for (k in lo..hi) {
            if (magnitude[k] > bestValue) {
                bestValue = magnitude[k]
                best = k
            }
        }
        if (best < 0 || magnitude[best - 1] > bestValue || magnitude[best + 1] > bestValue) return false
        // Les bins pairs du spectre paddé sont les bins de la fenêtre de Hann : on prend le plus
        // fort des deux qui encadrent le pic, puis l'estimateur exact à deux bins.
        val below = best / 2
        val above = (best + 1) / 2
        val center = if (magnitude[2 * below] >= magnitude[2 * above]) below else above
        val left = magnitude[2 * center - 2]
        val mid = magnitude[2 * center]
        val right = magnitude[2 * center + 2]
        if (mid <= 0.0) return false
        val offset = if (right >= left) {
            val alpha = right / mid
            (2.0 * alpha - 1.0) / (alpha + 1.0)
        } else {
            val alpha = left / mid
            -(2.0 * alpha - 1.0) / (alpha + 1.0)
        }
        peakFrequency = (center + offset) * windowBinHz
        peakMagnitude = bestValue
        return true
    }

    /**
     * Plancher de bruit : 20e centile des amplitudes entre 40 Hz et [lastBin] (un grattage
     * remplit une bonne partie du spectre de lobes principaux : la médiane serait trop haute).
     */
    private fun noiseFloor(lastBin: Int): Double {
        val first = ceil(40.0 / paddedBinHz).toInt()
        var n = 0
        for (k in first..lastBin) scratch[n++] = magnitude[k]
        if (n == 0) return 0.0
        return select(scratch, n, n / 5)
    }

    /** k-ième plus petite valeur (quickselect en place sur les n premiers éléments). */
    private fun select(a: DoubleArray, n: Int, k: Int): Double {
        var left = 0
        var right = n - 1
        while (left < right) {
            val pivot = a[(left + right) ushr 1]
            var i = left
            var j = right
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]; a[i] = a[j]; a[j] = t
                    i++
                    j--
                }
            }
            if (k <= j) right = j else if (k >= i) left = i else return a[k]
        }
        return a[k]
    }

    companion object {
        /** 16384 échantillons ≈ 0,34 s à 48 kHz. */
        const val WINDOW = 16384

        /** Nombre maximal de cordes suivies (accordages de 4 à 8 cordes). */
        const val MAX_STRINGS = 8

        private const val MAX_HARMONIC = 3
        private const val OVERLAP_HARMONICS = 8
        private const val MAX_ANALYSIS_HZ = 1250.0
        private const val COLLISION_BINS = 2.5
        private const val RELIABLE_SCORE = 0.3
        private const val MIN_SNR = 8.0
        private const val MIN_RELATIVE = 0.004
        private const val MAX_CENTS = 48.0
        private val CENTS_50_DOWN = Math.pow(2.0, -50.0 / 1200.0)
        private val CENTS_50_UP = Math.pow(2.0, 50.0 / 1200.0)
    }
}
