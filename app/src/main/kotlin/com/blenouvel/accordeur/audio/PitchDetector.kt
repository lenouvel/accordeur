package com.blenouvel.accordeur.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Résultat d'une analyse. Objet réutilisé d'une trame à l'autre (aucune allocation). */
class PitchEstimate {
    /** Fréquence retenue (Hz) : premier partiel de la corde. 0 si aucune hauteur fiable. */
    var frequency = 0.0

    /** Fréquence MPM (Hz), 0 si le MPM n'a rien trouvé. */
    var mpmFrequency = 0.0

    /** Clarté MPM : valeur de la NSDF au sommet choisi, ∈ [0, 1]. */
    var clarity = 0.0

    /** Part de l'énergie du spectre (30–1450 Hz) contenue dans la série de partiels retenue, ∈ [0, 1]. */
    var harmonicity = 0.0

    /** Coefficient d'inharmonicité B estimé (0 si non estimé). */
    var inharmonicity = 0.0

    /** Nombre de partiels de la série retenue (0 = MPM seul). */
    var partials = 0

    val isValid: Boolean get() = frequency > 0.0

    fun reset() {
        frequency = 0.0
        mpmFrequency = 0.0
        clarity = 0.0
        harmonicity = 0.0
        inharmonicity = 0.0
        partials = 0
    }
}

/**
 * Détection de hauteur monophonique, pensée pour les cordes graves captées par un téléphone :
 * fondamentale souvent absente (micro, chemin « voix » qui coupe sous 100–200 Hz) et partiels
 * **étirés** par la raideur des cordes filées (f_h = h·f0·√(1 + B·h²), B ≈ 1e-4…1e-3).
 *
 * 1. **MPM** (NSDF par autocorrélation FFT, maxima clés, interpolation parabolique) : une première
 *    estimation de période et sa clarté. Sur une corde raide sans fondamentale, le MPM suit
 *    l'écart entre partiels, plus grand que f0 : il lit 20 à 50 cents trop haut, clarté 0,5–0,85.
 * 2. **Spectre de Hann** (déduit du spectre zéro-paddé, sans seconde FFT) : pics au-dessus du
 *    bruit, fréquence exacte par l'estimateur à deux bins.
 * 3. **Candidats** f0 : le MPM et ses sous-multiples, et chaque pic fort divisé par h = 1…12.
 * 4. Pour chaque candidat, on suit la série de partiels d'une corde raide (chaque partiel trouvé
 *    affine f0 et B par moindres carrés sur (f_h/h)² = F + F·B·h²), puis on mesure la part de
 *    l'énergie du spectre (bruit compris) contenue dans ses partiels. Un candidat plus grave ne
 *    l'emporte que s'il explique nettement plus d'énergie (partiels impairs réels) avec une série
 *    assez complète : pas de fausse sous-octave ni de sous-harmonique « fourre-tout ». Un partiel
 *    isolé (note aiguë) doit être confirmé par le MPM.
 * 5. La fréquence affichée est celle du **premier partiel** de la série ajustée, même quand il est
 *    inaudible : elle ne dépend ni du micro ni de l'étirement des partiels aigus.
 *
 * Aucune allocation après construction. Non thread-safe (un détecteur par thread).
 */
class PitchDetector(
    val sampleRate: Int = SAMPLE_RATE,
    val windowSize: Int = WINDOW,
    private val minFrequency: Double = MIN_FREQUENCY,
    private val maxFrequency: Double = MAX_FREQUENCY,
    private val peakThreshold: Double = PEAK_THRESHOLD,
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

    /** Largeur d'un bin de la fenêtre (Hz) = 2 bins du spectre zéro-paddé. */
    private val binHz = sampleRate.toDouble() / windowSize
    private val paddedBinHz = sampleRate.toDouble() / fftSize
    private val firstBin = max(3, ceil(PEAK_MIN_HZ / paddedBinHz).toInt())
    private val lastBin = min(fft.bins - 4, floor(MAX_PARTIAL_HZ / paddedBinHz).toInt())
    private val hann = DoubleArray(fft.bins)
    private val scratch = DoubleArray(fft.bins)

    /** Énergie du spectre de Hann sur toute la bande analysée (partiels, bruit, autres sons). */
    private var bandEnergy = 0.0

    /** Marque des bins déjà comptés dans l'énergie expliquée (numéro d'évaluation). */
    private val counted = IntArray(fft.bins)
    private var evaluation = 0

    // Pics du spectre, par fréquence croissante.
    private val peakFrequency = DoubleArray(MAX_PEAKS)
    private val peakMagnitude = DoubleArray(MAX_PEAKS)
    private val peakBin = IntArray(MAX_PEAKS)
    private val peakSnr = DoubleArray(MAX_PEAKS)
    private var peakCount = 0
    private val floorBlocks = (lastBin - firstBin + FLOOR_BLOCK) / FLOOR_BLOCK
    private val blockFloor = DoubleArray(floorBlocks)
    private val rawFrequency = DoubleArray(MAX_RAW_PEAKS)
    private val rawMagnitude = DoubleArray(MAX_RAW_PEAKS)
    private val rawBin = IntArray(MAX_RAW_PEAKS)
    private val rawSnr = DoubleArray(MAX_RAW_PEAKS)
    private val strongest = IntArray(STRONG_PEAKS)

    private val candidates = DoubleArray(MAX_CANDIDATES)
    private var candidateCount = 0

    // Série de partiels du candidat en cours d'évaluation.
    private val roughHarmonic = IntArray(MAX_PARTIALS)
    private val roughPeak = IntArray(MAX_PARTIALS)
    private val matchHarmonic = IntArray(MAX_PARTIALS)
    private val matchPeak = IntArray(MAX_PARTIALS)
    private val usedPeak = IntArray(MAX_PEAKS)
    private var fitF0 = 0.0
    private var fitB = 0.0
    private var evalExplained = 0.0
    private var evalPartials = 0
    private var evalDensity = 0.0

    // Meilleure mesure de la MPM (dernier appel).
    private var mpmClarity = 0.0

    /**
     * Analyse [window] (au moins [windowSize] échantillons filtrés, du plus ancien au plus récent).
     * Renvoie true et remplit [out] si une hauteur fiable a été trouvée.
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
        fft.forward(padded, specRe, specIm)

        val mpm = mpm(window, energy)
        out.mpmFrequency = mpm
        out.clarity = mpmClarity

        findPeaks()
        if (peakCount > 0) {
            collectCandidates(mpm)
            var bestF0 = 0.0
            var bestB = 0.0
            var bestExplained = 0.0
            var bestPartials = 0
            var bestDensity = 0.0
            // Du plus aigu au plus grave : un candidat plus grave doit expliquer nettement plus,
            // avec une série assez complète (un sous-harmonique très grave « explique » aussi des
            // sons sans rapport, mais avec beaucoup d'harmoniques manquantes).
            for (i in 0 until candidateCount) {
                if (!evaluate(candidates[i])) continue
                val better = bestPartials == 0 || (
                    evalExplained > bestExplained + LOWER_CANDIDATE_MARGIN &&
                        evalDensity >= LOWER_CANDIDATE_DENSITY * bestDensity
                    )
                if (better) {
                    bestF0 = fitF0
                    bestB = fitB
                    bestExplained = evalExplained
                    bestPartials = evalPartials
                    bestDensity = evalDensity
                }
            }
            val first = bestF0 * sqrt(1.0 + bestB)
            val agreesWithMpm = mpm > 0.0 && abs(ln(first / mpm)) < SINGLE_PARTIAL_AGREEMENT
            val series = bestPartials > 0 && bestExplained >= MIN_HARMONICITY && bestDensity >= MIN_DENSITY &&
                first in minFrequency..maxFrequency
            // Un partiel isolé (notes aiguës) est une preuve faible : le MPM doit concorder.
            val confirmed = bestPartials >= 2 || (agreesWithMpm && mpmClarity >= SINGLE_PARTIAL_CLARITY)
            if (series && confirmed && bestPartials >= minPartials(first)) {
                out.frequency = first
                out.harmonicity = bestExplained
                out.inharmonicity = bestB
                out.partials = bestPartials
                return true
            }
            // MPM très net (son pur, peu de partiels) : la série, si elle concorde, reste plus précise.
            if (series && agreesWithMpm && mpmClarity >= MPM_ONLY_CLARITY) {
                out.frequency = first
                out.harmonicity = bestExplained
                out.partials = bestPartials
                return true
            }
        }
        // Repli : son très pur au-delà de la bande analysée, ou spectre inexploitable.
        if (mpm > 0.0 && mpmClarity >= MPM_ONLY_CLARITY) {
            out.frequency = mpm
            return true
        }
        return false
    }

    // --- MPM ------------------------------------------------------------------------------

    /** Fréquence MPM (0 si rien) ; [mpmClarity] = NSDF au sommet. Utilise [specRe]/[specIm]. */
    private fun mpm(window: DoubleArray, energy: Double): Double {
        mpmClarity = 0.0
        // Autocorrélation linéaire par FFT : r = IFFT(|X|²).
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
        if (count == 0 || best <= 0.0) return 0.0

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
        mpmClarity = (b - 0.25 * (a - c) * delta).coerceIn(0.0, 1.0)
        return sampleRate / (t + delta)
    }

    // --- Pics du spectre --------------------------------------------------------------------

    /**
     * Pics du spectre de Hann entre [PEAK_MIN_HZ] et [MAX_PARTIAL_HZ] : maxima locaux nettement
     * au-dessus du bruit local (18 dB au-dessus du 20e centile du bloc), lobes secondaires des pics
     * forts écartés.
     * Calcule aussi [bandEnergy], l'énergie de toute la bande.
     */
    private fun findPeaks() {
        peakCount = 0
        bandEnergy = 0.0
        for (k in firstBin - LOBE_BINS..lastBin + LOBE_BINS) hann[k] = hannMagnitude(k)
        var globalMax = 0.0
        for (k in firstBin..lastBin) {
            bandEnergy += hann[k] * hann[k]
            if (hann[k] > globalMax) globalMax = hann[k]
        }
        if (globalMax <= 0.0) return
        // Plancher de bruit local (20e centile par blocs de ~94 Hz) : un bruit coloré (grondement,
        // souffle) ne doit pas produire de faux pics là où il est fort.
        for (b in 0 until floorBlocks) {
            val from = firstBin + b * FLOOR_BLOCK
            val to = min(lastBin, from + FLOOR_BLOCK - 1)
            var n = 0
            for (k in from..to) scratch[n++] = hann[k]
            blockFloor[b] = select(scratch, n, (n * NOISE_PERCENTILE).toInt())
        }
        val minimum = globalMax * PEAK_MIN_RELATIVE

        var raw = 0
        for (k in firstBin..lastBin) {
            val m = hann[k]
            if (m < minimum || m <= hann[k - 1] || m < hann[k + 1]) continue
            val floor = floorAt(k)
            if (m < floor * PEAK_OVER_FLOOR) continue
            // Estimateur exact de la fenêtre de Hann sur les bins voisins de la fenêtre (k ± 2) :
            // |X[m+1]| / |X[m]| = (1 + δ) / (2 − δ) → δ = (2α − 1) / (α + 1).
            val left = hann[k - 2]
            val right = hann[k + 2]
            val offset = if (right >= left) {
                val alpha = right / m
                (2.0 * alpha - 1.0) / (alpha + 1.0)
            } else {
                val alpha = left / m
                -(2.0 * alpha - 1.0) / (alpha + 1.0)
            }
            if (raw == MAX_RAW_PEAKS) break
            rawFrequency[raw] = (k + 2.0 * offset.coerceIn(-0.5, 0.5)) * paddedBinHz
            rawMagnitude[raw] = m
            rawBin[raw] = k
            rawSnr[raw] = m / floor
            raw++
        }

        // Lobes secondaires (−31 dB à ±2,5 bins) d'un pic beaucoup plus fort : écartés.
        for (i in 0 until raw) {
            var sidelobe = false
            for (j in max(0, i - 3)..min(raw - 1, i + 3)) {
                if (j != i && abs(rawFrequency[j] - rawFrequency[i]) < SIDELOBE_BINS * binHz &&
                    rawMagnitude[i] < rawMagnitude[j] * SIDELOBE_RATIO
                ) {
                    sidelobe = true
                }
            }
            if (sidelobe) continue
            if (peakCount == MAX_PEAKS) {
                // Tableau plein : on remplace le plus faible s'il est plus faible que celui-ci.
                var weakest = 0
                for (j in 1 until peakCount) if (peakMagnitude[j] < peakMagnitude[weakest]) weakest = j
                if (peakMagnitude[weakest] >= rawMagnitude[i]) continue
                for (j in weakest until peakCount - 1) {
                    peakFrequency[j] = peakFrequency[j + 1]
                    peakMagnitude[j] = peakMagnitude[j + 1]
                    peakBin[j] = peakBin[j + 1]
                    peakSnr[j] = peakSnr[j + 1]
                }
                peakCount--
            }
            peakFrequency[peakCount] = rawFrequency[i]
            peakMagnitude[peakCount] = rawMagnitude[i]
            peakBin[peakCount] = rawBin[i]
            peakSnr[peakCount] = rawSnr[i]
            peakCount++
        }
    }

    /** Plancher de bruit au bin [k] : interpolation (géométrique) entre les centres des blocs. */
    private fun floorAt(k: Int): Double {
        val position = (k - firstBin - 0.5 * (FLOOR_BLOCK - 1)) / FLOOR_BLOCK
        if (position <= 0.0) return blockFloor[0]
        if (position >= floorBlocks - 1) return blockFloor[floorBlocks - 1]
        val b = position.toInt()
        val t = position - b
        val lo = max(blockFloor[b], 1e-30)
        val hi = max(blockFloor[b + 1], 1e-30)
        return lo * Math.pow(hi / lo, t)
    }

    /**
     * Amplitude du spectre fenêtré par Hann au bin k du spectre zéro-paddé, déduite du spectre
     * rectangulaire : Xh[k] = ½X[k] − ¼(X[k−2] + X[k+2]).
     */
    private fun hannMagnitude(k: Int): Double {
        val re = 0.5 * specRe[k] - 0.25 * (specRe[k - 2] + specRe[k + 2])
        val im = 0.5 * specIm[k] - 0.25 * (specIm[k - 2] + specIm[k + 2])
        return sqrt(re * re + im * im)
    }

    // --- Candidats ------------------------------------------------------------------------

    /** Candidats f0, triés du plus aigu au plus grave, sans doublons (< 0,3 %). */
    private fun collectCandidates(mpm: Double) {
        candidateCount = 0
        if (mpm > 0.0) {
            addCandidate(mpm)
            addCandidate(mpm / 2.0)
            addCandidate(mpm / 3.0)
        }
        // Les pics les plus forts, chacun vu comme partiel h = 1…12 d'une corde.
        val count = min(STRONG_PEAKS, peakCount)
        for (s in 0 until count) {
            var best = -1
            for (i in 0 until peakCount) {
                var taken = false
                for (t in 0 until s) if (strongest[t] == i) taken = true
                if (!taken && (best < 0 || peakMagnitude[i] > peakMagnitude[best])) best = i
            }
            strongest[s] = best
            for (h in 1..MAX_DIVISOR) addCandidate(peakFrequency[best] / h)
        }
        // Tri décroissant (insertion, ≤ 80 valeurs).
        for (i in 1 until candidateCount) {
            val v = candidates[i]
            var j = i - 1
            while (j >= 0 && candidates[j] < v) {
                candidates[j + 1] = candidates[j]
                j--
            }
            candidates[j + 1] = v
        }
    }

    private fun addCandidate(frequency: Double) {
        if (frequency < minFrequency * 0.97 || frequency > maxFrequency * 1.03) return
        for (i in 0 until candidateCount) {
            if (abs(candidates[i] - frequency) < DUPLICATE_RELATIVE * frequency) return
        }
        if (candidateCount < MAX_CANDIDATES) candidates[candidateCount++] = frequency
    }

    // --- Série de partiels d'une corde raide -------------------------------------------------

    /**
     * Série de partiels d'une corde raide pour le candidat [candidate] :
     * 1. appariement grossier (B = 0, tolérance large) des harmoniques h·candidat (h ≤ 8) aux pics ;
     * 2. **ancrage** : on ajoute ces paires par amplitude décroissante, chacune seulement si elle
     *    concorde avec le modèle (f0, B) ajusté sur les précédentes — les partiels forts fixent
     *    le modèle, un pic faible parasite ne peut pas le dévier ;
     * 3. **extension** : toutes les harmoniques, dans l'ordre, avec le modèle ancré (tolérance
     *    serrée, réajustement à chaque ajout) — suit l'étirement des partiels aigus.
     * Remplit [fitF0], [fitB], [evalExplained] (énergie des lobes des partiels confirmés /
     * énergie de la bande) et [evalPartials].
     */
    private fun evaluate(candidate: Double): Boolean {
        fitF0 = candidate
        fitB = 0.0
        evalExplained = 0.0
        evalPartials = 0
        evaluation++

        // 1. Appariement grossier, limité aux premiers rangs : plus haut, l'étirement d'une corde
        //    raide dépasse la tolérance et fausserait la numérotation des partiels.
        var rough = 0
        val maxHarmonic = min(ANCHOR_HARMONICS, floor(MAX_PARTIAL_HZ / candidate).toInt())
        for (h in 1..maxHarmonic) {
            val predicted = h * candidate
            val j = nearestPeak(predicted, max(LOOSE_BINS * binHz, LOOSE_RELATIVE * predicted))
            if (j < 0) continue
            roughHarmonic[rough] = h
            roughPeak[rough] = j
            rough++
        }
        if (rough == 0) return false
        // Tri par amplitude décroissante (insertion, ≤ 32 paires).
        for (i in 1 until rough) {
            val h = roughHarmonic[i]
            val j = roughPeak[i]
            var k = i - 1
            while (k >= 0 && peakMagnitude[roughPeak[k]] < peakMagnitude[j]) {
                roughHarmonic[k + 1] = roughHarmonic[k]
                roughPeak[k + 1] = roughPeak[k]
                k--
            }
            roughHarmonic[k + 1] = h
            roughPeak[k + 1] = j
        }

        // 2. Ancrage sur les partiels forts.
        var count = 0
        for (r in 0 until rough) {
            val h = roughHarmonic[r]
            val j = roughPeak[r]
            if (usedPeak[j] == evaluation || harmonicUsed(h, count)) continue
            if (count > 0 && !fitsModel(h, j, count)) continue
            addPartial(h, j, count)
            count++
            if (count == 1) fitF0 = peakFrequency[j] / h else fit(count)
        }

        // 2 bis. Raideur : les ancres, toutes graves, la fixent mal (un partiel décalé de 1 Hz par
        //        un ronflement voisin suffit à la fausser) ; on la choisit sur une grille.
        if (MAX_PARTIAL_HZ / fitF0 >= STIFFNESS_MIN_HARMONICS) chooseStiffness(count)

        // 3. Extension à toutes les harmoniques avec le modèle ancré.
        var h = 1
        while (h <= MAX_HARMONIC) {
            val predicted = h * fitF0 * sqrt(1.0 + fitB * h * h)
            if (predicted > MAX_PARTIAL_HZ) break
            if (count < MAX_PARTIALS && !harmonicUsed(h, count)) {
                val j = nearestPeak(predicted, max(TIGHT_BINS * binHz, TIGHT_RELATIVE * predicted))
                if (j >= 0 && usedPeak[j] != evaluation) {
                    addPartial(h, j, count)
                    count++
                    fit(count)
                }
            }
            h++
        }
        fit(count)
        // Un partiel resté loin du modèle final (apparié avant que la raideur soit connue) ne
        // doit pas le tirer : on l'écarte et on réajuste.
        val kept = pruneOutliers(count)
        if (kept != count) {
            count = kept
            fit(count)
        }

        // Seuls les partiels proches du modèle final comptent (pas de rapprochement fortuit) ;
        // chacun explique l'énergie de son lobe principal (±2 bins de la fenêtre).
        var explained = 0.0
        var confirmed = 0
        var lowest = Int.MAX_VALUE
        var highest = 0
        for (i in 0 until count) {
            val harmonic = matchHarmonic[i]
            val j = matchPeak[i]
            val predicted = harmonic * fitF0 * sqrt(1.0 + fitB * harmonic * harmonic)
            if (abs(peakFrequency[j] - predicted) > max(STRICT_BINS * binHz, STRICT_RELATIVE * predicted)) continue
            confirmed++
            lowest = min(lowest, harmonic)
            highest = max(highest, harmonic)
            for (k in max(firstBin, peakBin[j] - LOBE_BINS)..min(lastBin, peakBin[j] + LOBE_BINS)) {
                if (counted[k] == evaluation) continue
                counted[k] = evaluation
                explained += hann[k] * hann[k]
            }
        }
        evalExplained = if (bandEnergy > 0.0) explained / bandEnergy else 0.0
        evalPartials = confirmed
        evalDensity = if (confirmed > 0) confirmed.toDouble() / (highest - lowest + 1) else 0.0
        return confirmed > 0
    }

    private fun addPartial(harmonic: Int, peak: Int, index: Int) {
        matchHarmonic[index] = harmonic
        matchPeak[index] = peak
        usedPeak[peak] = evaluation
    }

    private fun harmonicUsed(harmonic: Int, count: Int): Boolean {
        for (i in 0 until count) if (matchHarmonic[i] == harmonic) return true
        return false
    }

    /** Le pic [peak] est-il le partiel [harmonic] du modèle ajusté sur les [count] premiers ? */
    private fun fitsModel(harmonic: Int, peak: Int, count: Int): Boolean {
        val predicted = harmonic * fitF0 * sqrt(1.0 + fitB * harmonic * harmonic)
        val tolerance = if (count < 2) {
            max(LOOSE_BINS * binHz, LOOSE_RELATIVE * predicted)
        } else {
            max(TIGHT_BINS * binHz, TIGHT_RELATIVE * predicted)
        }
        return abs(peakFrequency[peak] - predicted) <= tolerance
    }

    /** Pic le plus proche de [hz] à [tolerance] près ; −1 si aucun (pics triés par fréquence). */
    private fun nearestPeak(hz: Double, tolerance: Double): Int {
        // Premier pic ≥ hz − tolerance (recherche dichotomique).
        var lo = 0
        var hi = peakCount
        val from = hz - tolerance
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (peakFrequency[mid] < from) lo = mid + 1 else hi = mid
        }
        var best = -1
        var bestDistance = tolerance
        var i = lo
        while (i < peakCount) {
            val d = peakFrequency[i] - hz
            if (d > tolerance) break
            if (abs(d) <= bestDistance) {
                bestDistance = abs(d)
                best = i
            }
            i++
        }
        return best
    }

    /**
     * Choisit la raideur B de départ de l'extension : pour chaque B de la grille (et celui des
     * ancres), f0 est réajusté sur les ancres, puis on compte les partiels du spectre qui tombent
     * sur la série prédite, pondérés par leur netteté (log du rapport au bruit). Les partiels
     * aigus, très étirés, départagent les valeurs de B que les ancres graves ne distinguent pas.
     */
    private fun chooseStiffness(count: Int) {
        var bestB = fitB
        var bestF0 = fitF0
        var bestScore = stiffnessScore(fitF0, fitB)
        for (b in STIFFNESS_GRID) {
            var num = 0.0
            var den = 0.0
            for (i in 0 until count) {
                val j = matchPeak[i]
                val h = matchHarmonic[i].toDouble()
                val ratio = peakFrequency[j] / h
                val snr = min(peakSnr[j], MAX_SNR)
                val w = h * h * snr * snr
                num += w * ratio * ratio / (1.0 + b * h * h)
                den += w
            }
            if (den <= 0.0) continue
            val f0 = sqrt(num / den)
            val score = stiffnessScore(f0, b)
            if (score > bestScore + STIFFNESS_SCORE_MARGIN) {
                bestScore = score
                bestB = b
                bestF0 = f0
            }
        }
        fitF0 = bestF0
        fitB = bestB
    }

    private fun stiffnessScore(f0: Double, b: Double): Double {
        var score = 0.0
        var h = 1
        while (h <= MAX_HARMONIC) {
            val predicted = h * f0 * sqrt(1.0 + b * h * h)
            if (predicted > MAX_PARTIAL_HZ) break
            val j = nearestPeak(predicted, max(TIGHT_BINS * binHz, TIGHT_RELATIVE * predicted))
            if (j >= 0) score += ln(1.0 + min(peakSnr[j], MAX_SNR))
            h++
        }
        return score
    }

    /** Retire les partiels hors tolérance du modèle ajusté ; renvoie le nombre restant. */
    private fun pruneOutliers(count: Int): Int {
        var kept = 0
        for (i in 0 until count) {
            val harmonic = matchHarmonic[i]
            val j = matchPeak[i]
            val predicted = harmonic * fitF0 * sqrt(1.0 + fitB * harmonic * harmonic)
            if (abs(peakFrequency[j] - predicted) > max(TIGHT_BINS * binHz, TIGHT_RELATIVE * predicted)) continue
            matchHarmonic[kept] = harmonic
            matchPeak[kept] = j
            kept++
        }
        return if (kept >= 2) kept else count
    }

    /**
     * Moindres carrés pondérés sur y = (f_h / h)² = F + F·B·h² (linéaire en h²), sur les
     * [count] premiers partiels appariés. Met à jour [fitF0] et [fitB].
     */
    private fun fit(count: Int) {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until count) {
            val j = matchPeak[i]
            val h = matchHarmonic[i].toDouble()
            val x = h * h
            val ratio = peakFrequency[j] / h
            val y = ratio * ratio
            // Écart-type de y ∝ 1 / (h · SNR) → poids ∝ (h · SNR)².
            val snr = min(peakSnr[j], MAX_SNR)
            val w = x * snr * snr
            sw += w
            sx += w * x
            sy += w * y
            sxx += w * x * x
            sxy += w * x * y
        }
        if (sw <= 0.0) return
        var slope = 0.0
        var intercept = sy / sw
        val determinant = sw * sxx - sx * sx
        if (count >= 3 && determinant > 1e-9 * sw * sxx) {
            slope = (sw * sxy - sx * sy) / determinant
            intercept = (sy - slope * sx) / sw
        }
        var b = if (intercept > 0.0) slope / intercept else 0.0
        if (b < 0.0 || b > MAX_INHARMONICITY) {
            // Hors du domaine physique : on refait l'ajustement avec B borné.
            b = b.coerceIn(0.0, MAX_INHARMONICITY)
            var num = 0.0
            for (i in 0 until count) {
                val j = matchPeak[i]
                val h = matchHarmonic[i].toDouble()
                val ratio = peakFrequency[j] / h
                val snr = min(peakSnr[j], MAX_SNR)
                num += h * h * snr * snr * ratio * ratio / (1.0 + b * h * h)
            }
            intercept = num / sw
        }
        if (intercept <= 0.0) return
        fitF0 = sqrt(intercept)
        fitB = b
    }

    /** Partiels confirmés exigés : plus la note est grave, plus la série doit être complète. */
    private fun minPartials(frequency: Double): Int {
        val inBand = MAX_PARTIAL_HZ / frequency
        return when {
            inBand >= 12 -> 4
            inBand >= 6 -> 3
            inBand >= 3 -> 2
            else -> 1
        }
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
        const val SAMPLE_RATE = 48_000

        /** 8192 échantillons ≈ 171 ms : ≥ 4 périodes même à G♯1 (51,9 Hz). */
        const val WINDOW = 8192

        /** 2048 échantillons ≈ 43 ms → ~23 analyses par seconde. */
        const val HOP = 2048

        const val MIN_FREQUENCY = 35.0
        const val MAX_FREQUENCY = 1370.0

        /** Seuil k du MPM : premier maximum clé ≥ k·max. */
        const val PEAK_THRESHOLD = 0.9

        /** Part minimale de l'énergie spectrale expliquée par la série de partiels. */
        const val MIN_HARMONICITY = 0.5

        /** Sans série de partiels exploitable, le MPM seul doit être très net. */
        const val MPM_ONLY_CLARITY = 0.9

        private const val MIN_ENERGY = 1e-10
        private const val PEAK_MIN_HZ = 30.0
        private const val MAX_PARTIAL_HZ = 1450.0
        private const val NOISE_PERCENTILE = 0.2
        private const val FLOOR_BLOCK = 32
        private const val PEAK_OVER_FLOOR = 8.0
        private const val LOBE_BINS = 4
        private const val PEAK_MIN_RELATIVE = 0.003
        private const val SIDELOBE_BINS = 3.5
        private const val SIDELOBE_RATIO = 0.05
        private const val MAX_RAW_PEAKS = 256
        private const val MAX_PEAKS = 64
        private const val STRONG_PEAKS = 6
        private const val MAX_DIVISOR = 12
        private const val MAX_CANDIDATES = 3 + STRONG_PEAKS * MAX_DIVISOR
        private const val DUPLICATE_RELATIVE = 0.003
        private const val MAX_PARTIALS = 32
        private const val MAX_HARMONIC = 48
        private const val ANCHOR_HARMONICS = 8
        private const val LOOSE_BINS = 1.2
        private const val LOOSE_RELATIVE = 0.03
        private const val TIGHT_BINS = 0.6
        private const val TIGHT_RELATIVE = 0.006
        private const val STRICT_BINS = 0.35
        private const val STRICT_RELATIVE = 0.003
        private const val LOWER_CANDIDATE_MARGIN = 0.015
        private const val LOWER_CANDIDATE_DENSITY = 0.45
        private const val MIN_DENSITY = 0.35
        private const val MAX_SNR = 300.0
        private const val SINGLE_PARTIAL_CLARITY = 0.8
        private val SINGLE_PARTIAL_AGREEMENT = ln(2.0) / 24.0 // ½ demi-ton
        private const val MAX_INHARMONICITY = 0.002

        /** Grille de raideurs essayées avant l'extension (cordes filées : 1e-4…1e-3). */
        private val STIFFNESS_GRID = doubleArrayOf(
            0.0, 5e-5, 1e-4, 1.5e-4, 2e-4, 3e-4, 4e-4, 5e-4, 6.5e-4, 8e-4, 1e-3, 1.25e-3, 1.6e-3, 2e-3,
        )

        /** En dessous de ce nombre d'harmoniques dans la bande, l'étirement est négligeable. */
        private const val STIFFNESS_MIN_HARMONICS = 10.0
        private const val STIFFNESS_SCORE_MARGIN = 1e-9
    }
}
