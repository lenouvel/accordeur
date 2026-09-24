package com.blenouvel.accordeur.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Résultat d'une analyse. Objet réutilisé d'une trame à l'autre (aucune allocation). */
class PitchEstimate {
    /** Fréquence retenue (Hz), 0 si aucune hauteur n'a été trouvée. */
    var frequency = 0.0

    /** Fréquence MPM avant raffinement par les partiels (Hz). */
    var mpmFrequency = 0.0

    /** Clarté MPM : valeur de la NSDF au sommet choisi, ∈ [0, 1]. */
    var clarity = 0.0

    /** Coefficient d'inharmonicité B estimé (0 si non estimé). */
    var inharmonicity = 0.0

    /** Nombre de partiels utilisés par le raffinement (0 = MPM seul). */
    var partials = 0

    val isValid: Boolean get() = frequency > 0.0

    fun reset() {
        frequency = 0.0
        mpmFrequency = 0.0
        clarity = 0.0
        inharmonicity = 0.0
        partials = 0
    }
}

/**
 * Détection de hauteur monophonique — McLeod Pitch Method (MPM / NSDF), robuste aux
 * erreurs d'octave.
 *
 * 1. Autocorrélation r(τ) par FFT (Wiener–Khinchin, zéro-padding ×2 → corrélation linéaire)
 *    et m(τ) = Σ x[j]² + x[j+τ]² calculé de façon incrémentale.
 * 2. NSDF n(τ) = 2·r(τ) / m(τ) ∈ [−1, 1].
 * 3. Un maximum « clé » par lobe positif ; on retient le premier ≥ k·max.
 * 4. Interpolation parabolique du sommet (3 points) → période sous-échantillon.
 * 5. Clarté = valeur NSDF au sommet.
 * 6. Raffinement : les partiels sont mesurés dans le spectre (fenêtre de Hann, estimateur
 *    exact à deux bins) puis ajustés au modèle de corde raide f_h = h·f0·√(1 + B·h²).
 *    Sur les cordes filées, les partiels aigus sont trop hauts (inharmonicité) et tirent le
 *    MPM de quelques cents vers l'aigu : le raffinement renvoie la vraie fondamentale.
 *
 * Aucune allocation après construction. Non thread-safe (un détecteur par thread).
 */
class PitchDetector(
    val sampleRate: Int = SAMPLE_RATE,
    val windowSize: Int = WINDOW,
    minFrequency: Double = MIN_FREQUENCY,
    maxFrequency: Double = MAX_FREQUENCY,
    private val peakThreshold: Double = PEAK_THRESHOLD,
    private val refine: Boolean = true,
) {
    /** Plus petit décalage analysé (fréquence max). */
    val tauMin: Int = max(2, floor(sampleRate / maxFrequency).toInt())

    /** Plus grand décalage analysé (fréquence min). */
    val tauMax: Int = ceil(sampleRate / minFrequency).toInt()

    init {
        require((windowSize and (windowSize - 1)) == 0) { "fenêtre non puissance de 2 : $windowSize" }
        require(tauMax + 1 < windowSize / 2) { "fenêtre trop courte pour $minFrequency Hz" }
    }

    private val fftSize = windowSize * 2
    private val fft = RealFft(fftSize)
    private val padded = DoubleArray(fftSize)
    private val specRe = DoubleArray(fft.bins)
    private val specIm = DoubleArray(fft.bins)
    private val power = DoubleArray(fft.bins)
    private val zeroIm = DoubleArray(fft.bins)
    private val acf = DoubleArray(fftSize)
    private val nsdf = DoubleArray(tauMax + 2)
    private val keyTau = IntArray(tauMax + 2)
    private val keyValue = DoubleArray(tauMax + 2)

    /** Largeur d'un bin du spectre non zéro-paddé (Hz). */
    private val binHz = sampleRate.toDouble() / windowSize
    private val maxBin = windowSize / 2 - 2

    private val partialHarmonic = IntArray(MAX_PARTIALS)
    private val partialFrequency = DoubleArray(MAX_PARTIALS)
    private val partialMagnitude = DoubleArray(MAX_PARTIALS)
    private val partialSnr = DoubleArray(MAX_PARTIALS)

    // Résultats de la dernière recherche de pic / du dernier ajustement.
    private var peakFrequency = 0.0
    private var peakMagnitude = 0.0
    private var fitF0 = 0.0
    private var fitB = 0.0

    /**
     * Analyse [window] (au moins [windowSize] échantillons filtrés, du plus ancien au plus récent).
     * Renvoie true et remplit [out] si une hauteur a été trouvée.
     */
    fun detect(window: DoubleArray, out: PitchEstimate): Boolean {
        out.reset()
        var energy = 0.0
        for (i in 0 until windowSize) {
            val v = window[i]
            padded[i] = v
            energy += v * v
        }
        if (energy < MIN_ENERGY) return false
        java.util.Arrays.fill(padded, windowSize, fftSize, 0.0)

        // Autocorrélation linéaire par FFT : r = IFFT(|X|²).
        fft.forward(padded, specRe, specIm)
        for (k in 0 until fft.bins) power[k] = specRe[k] * specRe[k] + specIm[k] * specIm[k]
        fft.inverse(power, zeroIm, acf)

        // NSDF avec m(τ) incrémental.
        var m = 2.0 * energy
        nsdf[0] = 1.0
        for (tau in 1..tauMax + 1) {
            val a = window[tau - 1]
            val b = window[windowSize - tau]
            m -= a * a + b * b
            nsdf[tau] = if (m > MIN_ENERGY) 2.0 * acf[tau] / m else 0.0
        }

        // Maxima clés : on saute le lobe initial (autour de τ = 0) puis un maximum par lobe positif.
        var tau = 1
        while (tau <= tauMax && nsdf[tau] > 0.0) tau++
        var count = 0
        var best = 0.0
        while (tau <= tauMax) {
            while (tau <= tauMax && nsdf[tau] <= 0.0) tau++
            if (tau > tauMax) break
            var peakTau = tau
            var peakValue = nsdf[tau]
            while (tau <= tauMax && nsdf[tau] > 0.0) {
                if (nsdf[tau] > peakValue) {
                    peakValue = nsdf[tau]
                    peakTau = tau
                }
                tau++
            }
            if (peakTau in tauMin until tauMax) {
                keyTau[count] = peakTau
                keyValue[count] = peakValue
                count++
                if (peakValue > best) best = peakValue
            }
        }
        if (count == 0 || best <= 0.0) return false

        val threshold = peakThreshold * best
        var chosen = 0
        while (keyValue[chosen] < threshold) chosen++

        // Interpolation parabolique : δ = ½·(a − c) / (a − 2b + c).
        val t = keyTau[chosen]
        val a = nsdf[t - 1]
        val b = nsdf[t]
        val c = nsdf[t + 1]
        val denominator = a - 2.0 * b + c
        val delta = if (denominator < 0.0) (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5) else 0.0
        val period = t + delta
        val frequency = sampleRate / period

        out.mpmFrequency = frequency
        out.frequency = frequency
        out.clarity = (b - 0.25 * (a - c) * delta).coerceIn(0.0, 1.0)
        if (refine) refineWithPartials(frequency, out)
        return true
    }

    // --- Raffinement par les partiels ------------------------------------------------------

    private fun refineWithPartials(mpmFrequency: Double, out: PitchEstimate) {
        val maxHarmonic = min(MAX_PARTIALS, floor(MAX_PARTIAL_HZ / mpmFrequency).toInt())
        if (maxHarmonic < 1) return

        // Recherche itérative : chaque partiel trouvé affine la prédiction des suivants.
        fitF0 = mpmFrequency
        fitB = 0.0
        var count = 0
        var strongest = 0.0
        for (h in 1..maxHarmonic) {
            val predicted = h * fitF0 * sqrt(1.0 + fitB * h * h)
            val tolerance = max(1.5 * binHz, predicted * SEARCH_TOLERANCE)
            if (!findPeak(predicted - tolerance, predicted + tolerance)) continue
            val noise = noiseAround(predicted, mpmFrequency)
            val snr = if (noise > 0.0) peakMagnitude / noise else MAX_SNR
            if (snr < MIN_PARTIAL_SNR) continue
            partialHarmonic[count] = h
            partialFrequency[count] = peakFrequency
            partialMagnitude[count] = peakMagnitude
            partialSnr[count] = min(snr, MAX_SNR)
            if (peakMagnitude > strongest) strongest = peakMagnitude
            count++
            fit(count, strongest)
        }
        if (count == 0) return

        // Ajustement final sans les partiels trop faibles par rapport au plus fort.
        val used = fit(count, strongest)
        if (used == 0) return
        val fundamental = fitF0 * sqrt(1.0 + fitB)
        val deviation = 1200.0 * log2(fundamental / mpmFrequency)
        if (abs(deviation) > MAX_REFINEMENT_CENTS) return
        out.frequency = fundamental
        out.inharmonicity = fitB
        out.partials = used
    }

    /**
     * Moindres carrés pondérés sur y = (f_h / h)² = F + F·B·h² (linéaire en h²).
     * Met à jour [fitF0] et [fitB] ; renvoie le nombre de partiels utilisés.
     */
    private fun fit(count: Int, strongest: Double): Int {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        var used = 0
        for (i in 0 until count) {
            if (partialMagnitude[i] < strongest * MIN_RELATIVE_MAGNITUDE) continue
            val h = partialHarmonic[i].toDouble()
            val x = h * h
            val ratio = partialFrequency[i] / h
            val y = ratio * ratio
            // Écart-type de y ∝ 1 / (h · SNR) → poids ∝ (h · SNR)².
            val w = x * partialSnr[i] * partialSnr[i]
            sw += w
            sx += w * x
            sy += w * y
            sxx += w * x * x
            sxy += w * x * y
            used++
        }
        if (used == 0 || sw <= 0.0) return 0
        var slope = 0.0
        var intercept = sy / sw
        val determinant = sw * sxx - sx * sx
        if (used >= 3 && determinant > 1e-9 * sw * sxx) {
            slope = (sw * sxy - sx * sy) / determinant
            intercept = (sy - slope * sx) / sw
        }
        var b = if (intercept > 0.0) slope / intercept else 0.0
        if (b < 0.0 || b > MAX_INHARMONICITY) {
            // Hors du domaine physique : on refait l'ajustement avec B borné.
            b = b.coerceIn(0.0, MAX_INHARMONICITY)
            var num = 0.0
            for (i in 0 until count) {
                if (partialMagnitude[i] < strongest * MIN_RELATIVE_MAGNITUDE) continue
                val h = partialHarmonic[i].toDouble()
                val ratio = partialFrequency[i] / h
                val w = h * h * partialSnr[i] * partialSnr[i]
                num += w * ratio * ratio / (1.0 + b * h * h)
            }
            intercept = num / sw
        }
        if (intercept <= 0.0) return 0
        fitF0 = sqrt(intercept)
        fitB = b
        return used
    }

    /**
     * Cherche le pic de plus forte amplitude entre [loHz] et [hiHz] dans le spectre de Hann et
     * estime sa fréquence exacte (estimateur à deux bins, exact pour une sinusoïde pure).
     */
    private fun findPeak(loHz: Double, hiHz: Double): Boolean {
        val lo = max(2, ceil(loHz / binHz).toInt())
        val hi = min(maxBin, floor(hiHz / binHz).toInt())
        if (hi < lo) return false
        var bestBin = -1
        var bestMagnitude = 0.0
        for (m in lo..hi) {
            val magnitude = hannMagnitude(m)
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestBin = m
            }
        }
        if (bestBin < 0) return false
        val left = hannMagnitude(bestBin - 1)
        val right = hannMagnitude(bestBin + 1)
        if (left > bestMagnitude || right > bestMagnitude) return false // pas un maximum local
        // Fenêtre de Hann : |X[m+1]| / |X[m]| = (1 + δ) / (2 − δ) → δ = (2α − 1) / (α + 1).
        val offset = if (right >= left) {
            val alpha = right / bestMagnitude
            (2.0 * alpha - 1.0) / (alpha + 1.0)
        } else {
            val alpha = left / bestMagnitude
            -(2.0 * alpha - 1.0) / (alpha + 1.0)
        }
        peakFrequency = (bestBin + offset) * binHz
        peakMagnitude = bestMagnitude
        return true
    }

    /** Bruit local : amplitude moyenne à mi-chemin entre le partiel et ses voisins. */
    private fun noiseAround(partialHz: Double, spacingHz: Double): Double {
        val below = hannMagnitude(((partialHz - 0.5 * spacingHz) / binHz).roundToInt().coerceIn(2, maxBin))
        val above = hannMagnitude(((partialHz + 0.5 * spacingHz) / binHz).roundToInt().coerceIn(2, maxBin))
        return 0.5 * (below + above)
    }

    /**
     * Amplitude du spectre fenêtré par Hann au bin m (résolution de la fenêtre, fs/W),
     * déduite du spectre rectangulaire zéro-paddé : Xh[m] = ½X[2m] − ¼(X[2m−2] + X[2m+2]).
     */
    private fun hannMagnitude(m: Int): Double {
        val k = 2 * m
        val re = 0.5 * specRe[k] - 0.25 * (specRe[k - 2] + specRe[k + 2])
        val im = 0.5 * specIm[k] - 0.25 * (specIm[k - 2] + specIm[k + 2])
        return sqrt(re * re + im * im)
    }

    companion object {
        const val SAMPLE_RATE = 48_000

        /** 8192 échantillons ≈ 171 ms : ≥ 4 périodes même à G♯1 (51,9 Hz). */
        const val WINDOW = 8192

        /** 2048 échantillons ≈ 43 ms → ~23 analyses par seconde. */
        const val HOP = 2048

        const val MIN_FREQUENCY = 35.0
        const val MAX_FREQUENCY = 1370.0

        /** Seuil k du MPM : premier maximum clé ≥ k·max. */
        const val PEAK_THRESHOLD = 0.9

        private const val MIN_ENERGY = 1e-10
        private const val MAX_PARTIALS = 12
        private const val MAX_PARTIAL_HZ = 1250.0
        private const val SEARCH_TOLERANCE = 0.012
        private const val MIN_PARTIAL_SNR = 6.0
        private const val MAX_SNR = 100.0
        private const val MIN_RELATIVE_MAGNITUDE = 0.02
        private const val MAX_INHARMONICITY = 0.002
        private const val MAX_REFINEMENT_CENTS = 25.0
    }
}
