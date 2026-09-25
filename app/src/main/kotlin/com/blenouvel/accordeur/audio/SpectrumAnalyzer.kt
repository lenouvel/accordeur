package com.blenouvel.accordeur.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pic du spectre : fréquence (Hz) et niveau (dBFS). */
class SpectrumPeak(val frequency: Double, val db: Float)

/** Note entendue : fréquence de son premier partiel (Hz), numéro MIDI le plus proche (La4 = 69). */
class HeardNote(val frequency: Double, val midi: Int)

/** Instantané de la page Spectre (~23 fois par seconde). */
class SpectrumFrame(
    /** Niveaux (dBFS) aux [SpectrumAnalyzer.DISPLAY_POINTS] points de l'axe (log, 20 Hz → 20 kHz). */
    val levels: FloatArray,
    /** Pics marquants (≤ [SpectrumAnalyzer.DISPLAY_PEAKS]), du plus fort au plus faible. */
    val peaks: List<SpectrumPeak>,
    /** Notes entendues (confirmées sur plusieurs trames), de la plus grave à la plus aiguë. */
    val notes: List<HeardNote>,
)

/**
 * Spectre du signal brut du micro et notes qui le composent (une corde ou un accord).
 *
 * 1. **Spectre** : fenêtre de Hann de [WINDOW] échantillons (~0,34 s : un demi-ton est résolu dès
 *    ~80 Hz), FFT zéro-paddée ; niveaux en dBFS (sinus pleine échelle = 0 dB) ramenés sur un axe
 *    logarithmique de 20 Hz à 20 kHz (maximum des bins de chaque point, interpolation en bas).
 * 2. **Pics** : maxima locaux nettement au-dessus du bruit local (20ᵉ centile des bins par sixième
 *    d'octave : les creux entre partiels, même dans un accord dense), lobes secondaires des pics
 *    forts écartés, fréquence affinée par interpolation parabolique.
 * 3. **Séries de partiels** : chaque pic fort, vu comme partiel 1 à [MAX_DIVISOR], propose une
 *    fondamentale ; comme dans l'accordeur, on y ajuste la série d'une corde raide (partiels forts
 *    d'abord, raideur choisie sur une grille, extension à toute la bande). On compte ses **trous**
 *    parmi ses 12 premiers partiels (au-dessus de la coupure des graves du micro) : partiels
 *    attendus sans pic. Une corde n'en a presque pas ; une sous-harmonique commune (un La1 qui
 *    « expliquerait » un accord La5) en a beaucoup (partiels 5, 7, 11) : elle est écartée.
 * 4. **Notes** (choix glouton) : on retient la série qui explique la plus grande part de l'énergie
 *    des pics encore inexpliqués, puis on recommence tant qu'une série en apporte assez. Une note
 *    dont tous les partiels sont déjà expliqués n'apporte rien : les partiels d'une corde ne
 *    deviennent pas des notes fantômes, et un pic faible (bruit, résonance) ne pèse presque rien.
 * 5. **Stabilité** : une note n'est publiée qu'une fois entendue sur 2 des 3 dernières trames.
 *
 * Aucune allocation après construction, hors l'instantané publié. Non thread-safe.
 */
class SpectrumAnalyzer(val sampleRate: Int) {
    /** Note la plus grave cherchée (Hz) : corde la plus grave de l'accordage, un ton plus bas. */
    var lowestNoteHz = DEFAULT_LOWEST_NOTE_HZ

    private val fftSize = WINDOW * 2
    private val fft = RealFft(fftSize)
    private val padded = DoubleArray(fftSize)
    private val re = DoubleArray(fft.bins)
    private val im = DoubleArray(fft.bins)
    private val powerDb = DoubleArray(fft.bins)
    private val hann = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / WINDOW) }
    private val binHz = sampleRate.toDouble() / fftSize

    /** Largeur d'un bin de la fenêtre (Hz) = 2 bins du spectre zéro-paddé. */
    private val windowBinHz = sampleRate.toDouble() / WINDOW

    /** dB d'un bin : sinus d'amplitude 1 → 0 dBFS (somme de la fenêtre de Hann = N/2). */
    private val normDb = 20.0 * log10(4.0 / WINDOW)

    // Axe d'affichage : bins couverts par chaque point.
    private val pointLow = IntArray(DISPLAY_POINTS)
    private val pointHigh = IntArray(DISPLAY_POINTS)
    private val pointBin = DoubleArray(DISPLAY_POINTS)
    private val levels = DoubleArray(DISPLAY_POINTS)

    // Bruit de fond par bandes d'un sixième d'octave (au moins [FLOOR_MIN_BINS] bins).
    private val bandFrom = IntArray(MAX_BANDS)
    private val bandTo = IntArray(MAX_BANDS)
    private val bandCenter = DoubleArray(MAX_BANDS)
    private val bandFloor = DoubleArray(MAX_BANDS)
    private var bandCount = 0
    private val scratch = DoubleArray(fft.bins)

    // Pics, par fréquence croissante.
    private val peakHz = DoubleArray(MAX_PEAKS)
    private val peakDb = DoubleArray(MAX_PEAKS)
    private val peakSnr = DoubleArray(MAX_PEAKS)
    private var peakCount = 0
    private val order = IntArray(MAX_PEAKS)

    // Séries candidates.
    private val candidateHz = DoubleArray(MAX_CANDIDATES)
    private var candidateCount = 0
    private val seriesF1 = DoubleArray(MAX_CANDIDATES)
    private val seriesHoles = IntArray(MAX_CANDIDATES)
    private val seriesSpan = IntArray(MAX_CANDIDATES)
    private val seriesCount = IntArray(MAX_CANDIDATES)
    private val seriesStrongestDb = DoubleArray(MAX_CANDIDATES)
    private val seriesPeaks = Array(MAX_CANDIDATES) { IntArray(MAX_PARTIALS) }
    private val seriesValid = BooleanArray(MAX_CANDIDATES)
    private var seriesTotal = 0

    // Évaluation d'une série (tampons de travail).
    private val roughHarmonic = IntArray(ANCHOR_HARMONICS)
    private val roughPeak = IntArray(ANCHOR_HARMONICS)
    private val matchHarmonic = IntArray(MAX_PARTIALS)
    private val matchPeak = IntArray(MAX_PARTIALS)
    private val usedPeak = IntArray(MAX_PEAKS)
    private val claimedPeak = IntArray(MAX_HARMONIC + 1)
    private var evaluation = 0
    private var fitF0 = 0.0
    private var fitB = 0.0

    // Choix des notes.
    private val power = DoubleArray(MAX_PEAKS)
    private var totalPower = 0.0
    private var strongestDb = SILENCE_DB
    private val explained = BooleanArray(MAX_PEAKS)
    private val noteHz = DoubleArray(MAX_NOTES)
    private var noteCount = 0

    // Stabilité : notes des 3 dernières trames (numéros MIDI).
    private val history = Array(HISTORY) { IntArray(MAX_NOTES) }
    private val historyCount = IntArray(HISTORY)
    private val historyHz = Array(HISTORY) { DoubleArray(MAX_NOTES) }
    private var historyNext = 0

    init {
        for (i in 0 until DISPLAY_POINTS) {
            val center = frequencyAt(i.toDouble())
            val low = frequencyAt(i - 0.5)
            val high = frequencyAt(i + 0.5)
            pointBin[i] = center / binHz
            pointLow[i] = ceil(low / binHz).toInt().coerceIn(1, fft.bins - 1)
            pointHigh[i] = floor(high / binHz).toInt().coerceIn(1, fft.bins - 1)
        }
        var from = max(2, (PEAK_MIN_HZ / binHz).toInt() - FLOOR_MIN_BINS / 2)
        val last = min(fft.bins - 3, (PEAK_MAX_HZ / binHz).toInt() + FLOOR_MIN_BINS)
        while (from <= last && bandCount < MAX_BANDS) {
            val to = min(last, max(from + FLOOR_MIN_BINS - 1, (from * BAND_RATIO).toInt()))
            bandFrom[bandCount] = from
            bandTo[bandCount] = to
            bandCenter[bandCount] = ln(sqrt(from.toDouble() * to) * binHz)
            bandCount++
            from = to + 1
        }
    }

    /** Oublie les notes des trames précédentes. */
    fun reset() {
        java.util.Arrays.fill(historyCount, 0)
    }

    /**
     * Analyse les [WINDOW] derniers échantillons bruts de [window] (du plus ancien au plus récent).
     * Les notes ne sont cherchées que si [sound] (un son est présent : gate ouvert).
     */
    fun analyze(window: FloatArray, sound: Boolean): SpectrumFrame {
        for (i in 0 until WINDOW) padded[i] = window[i] * hann[i]
        java.util.Arrays.fill(padded, WINDOW, fftSize, 0.0)
        fft.forward(padded, re, im)
        for (k in 0 until fft.bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            powerDb[k] = if (p > 1e-30) 10.0 * log10(p) + normDb else SILENCE_DB
        }
        displayLevels()
        noiseFloor()
        findPeaks()
        noteCount = 0
        if (sound && peakCount > 0) findNotes()
        return publish()
    }

    // --- Axe d'affichage -----------------------------------------------------------------

    private fun displayLevels() {
        for (i in 0 until DISPLAY_POINTS) {
            val lo = pointLow[i]
            val hi = pointHigh[i]
            levels[i] = if (hi >= lo) {
                var m = SILENCE_DB
                for (k in lo..hi) if (powerDb[k] > m) m = powerDb[k]
                m
            } else {
                // Points plus serrés que les bins (graves) : interpolation linéaire en dB.
                val x = pointBin[i]
                val k = x.toInt().coerceIn(0, fft.bins - 2)
                val t = x - k
                powerDb[k] * (1 - t) + powerDb[k + 1] * t
            }
        }
    }

    /** Bruit local : 20ᵉ centile des bins de chaque bande (les creux entre les partiels). */
    private fun noiseFloor() {
        for (b in 0 until bandCount) {
            var n = 0
            for (k in bandFrom[b]..bandTo[b]) scratch[n++] = powerDb[k]
            bandFloor[b] = select(scratch, n, (n * FLOOR_PERCENTILE).toInt())
        }
    }

    /** Bruit à [hz] : interpolation (en log-fréquence) entre les centres des bandes. */
    private fun floorAtHz(hz: Double): Double {
        val x = ln(hz)
        if (x <= bandCenter[0]) return bandFloor[0]
        for (b in 1 until bandCount) {
            if (x <= bandCenter[b]) {
                val t = (x - bandCenter[b - 1]) / (bandCenter[b] - bandCenter[b - 1])
                return bandFloor[b - 1] + t * (bandFloor[b] - bandFloor[b - 1])
            }
        }
        return bandFloor[bandCount - 1]
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

    // --- Pics ------------------------------------------------------------------------------

    private fun findPeaks() {
        peakCount = 0
        val first = max(2, (PEAK_MIN_HZ / binHz).toInt())
        val last = min(fft.bins - 3, (PEAK_MAX_HZ / binHz).toInt())
        var globalMax = SILENCE_DB
        for (k in first..last) if (powerDb[k] > globalMax) globalMax = powerDb[k]
        val minimum = globalMax - PEAK_RANGE_DB
        for (k in first..last) {
            val m = powerDb[k]
            if (m < minimum || m < powerDb[k - 1] || m <= powerDb[k + 1]) continue
            val noise = floorAtHz(k * binHz)
            if (m < noise + PEAK_SNR_DB) continue
            // Interpolation parabolique sur les dB : δ = ½(a − c)/(a − 2b + c).
            val a = powerDb[k - 1]
            val c = powerDb[k + 1]
            val den = a - 2 * m + c
            val delta = if (den < 0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            val db = m - 0.25 * (a - c) * delta
            add((k + delta) * binHz, db, db - noise)
        }
        // Lobes secondaires d'un pic beaucoup plus fort (Hann : −31 dB à ±2,5 bins de la fenêtre).
        var kept = 0
        for (i in 0 until peakCount) {
            var sidelobe = false
            for (j in max(0, i - 4)..min(peakCount - 1, i + 4)) {
                if (j != i && abs(peakHz[j] - peakHz[i]) < SIDELOBE_BINS * windowBinHz && peakDb[j] - peakDb[i] > SIDELOBE_DB) {
                    sidelobe = true
                    break
                }
            }
            if (sidelobe) continue
            peakHz[kept] = peakHz[i]
            peakDb[kept] = peakDb[i]
            peakSnr[kept] = peakSnr[i]
            kept++
        }
        peakCount = kept
    }

    /** Ajoute un pic ; tableau plein : remplace le plus faible s'il l'est plus que celui-ci. */
    private fun add(hz: Double, db: Double, snr: Double) {
        if (peakCount == MAX_PEAKS) {
            var weakest = 0
            for (j in 1 until peakCount) if (peakDb[j] < peakDb[weakest]) weakest = j
            if (peakDb[weakest] >= db) return
            // Décalage pour garder l'ordre des fréquences.
            for (j in weakest until peakCount - 1) {
                peakHz[j] = peakHz[j + 1]
                peakDb[j] = peakDb[j + 1]
                peakSnr[j] = peakSnr[j + 1]
            }
            peakCount--
        }
        peakHz[peakCount] = hz
        peakDb[peakCount] = db
        peakSnr[peakCount] = snr
        peakCount++
    }

    // --- Notes -----------------------------------------------------------------------------

    private fun findNotes() {
        noteCount = 0
        totalPower = 0.0
        strongestDb = SILENCE_DB
        for (j in 0 until peakCount) {
            power[j] = 10.0.pow(peakDb[j] / 10.0)
            explained[j] = false
            if (peakHz[j] <= NOTE_MAX_HZ) {
                totalPower += power[j]
                strongestDb = max(strongestDb, peakDb[j])
            }
        }
        if (totalPower <= 0.0) return
        collectCandidates()
        seriesTotal = 0
        for (c in 0 until candidateCount) {
            if (!evaluate(candidateHz[c])) continue
            // Même série trouvée depuis un autre pic : on ne la garde qu'une fois.
            val f1 = fitF0 * sqrt(1.0 + fitB)
            var duplicate = false
            for (s in 0 until seriesTotal) if (abs(seriesF1[s] - f1) < SAME_SERIES * f1) duplicate = true
            if (!duplicate) storeSeries(f1)
        }
        greedy()
        completeBass()
        // Du bruit ou un son sans hauteur : les « notes » trouvées n'expliquent presque rien.
        var explainedPower = 0.0
        for (j in 0 until peakCount) if (explained[j] && peakHz[j] <= NOTE_MAX_HZ) explainedPower += power[j]
        if (explainedPower < MIN_EXPLAINED * totalPower) noteCount = 0
    }

    /**
     * Fondamentale grave de l'accord dont presque tous les partiels sont partagés avec les autres
     * notes (Mi2 sous Mi3, Si3 et Mi4) : elle n'apporte presque rien en propre et le choix glouton
     * l'oublie. Une série plus grave, complète (quasiment sans trou) et forte, est ajoutée comme
     * basse — une sous-harmonique fantôme, elle, est trouée.
     */
    private fun completeBass() {
        if (noteCount == 0 || noteCount >= MAX_NOTES) return
        var lowest = noteHz[0]
        for (n in 1 until noteCount) lowest = min(lowest, noteHz[n])
        var best = -1
        var bestPower = 0.0
        for (s in 0 until seriesTotal) {
            if (!seriesValid[s] || seriesF1[s] >= lowest * BASS_BELOW || seriesSpan[s] < BASS_MIN_SPAN) continue
            if (seriesHoles[s] > BASS_HOLE_RATIO * seriesSpan[s] || seriesStrongestDb[s] < strongestDb - BASS_RANGE_DB) continue
            var claimed = 0.0
            for (k in 0 until seriesCount[s]) claimed += power[seriesPeaks[s][k]]
            if (claimed > bestPower) {
                bestPower = claimed
                best = s
            }
        }
        if (best >= 0) choose(best)
    }

    /**
     * Choix glouton des notes. Le pic le plus fort doit appartenir à une note : sinon on retient
     * la série la moins trouée qui l'explique.
     */
    private fun greedy() {
        java.util.Arrays.fill(explained, 0, peakCount, false)
        while (noteCount < MAX_NOTES) {
            var best = -1
            var bestGain = MIN_SHARE
            for (s in 0 until seriesTotal) {
                if (!seriesValid[s] || nearNote(seriesF1[s])) continue
                var gain = 0.0
                var own = SILENCE_DB
                val peaks = seriesPeaks[s]
                for (k in 0 until seriesCount[s]) {
                    val j = peaks[k]
                    if (explained[j]) continue
                    gain += power[j]
                    own = max(own, peakDb[j])
                }
                gain /= totalPower
                // Il lui faut au moins un partiel à elle, net (pas un pic faible de bruit ou de caisse).
                if (own < strongestDb - OWN_RANGE_DB) continue
                if (gain > bestGain) {
                    bestGain = gain
                    best = s
                }
            }
            if (best < 0) break
            choose(best)
        }
        var strongest = -1
        for (j in 0 until peakCount) if (peakHz[j] <= NOTE_MAX_HZ && (strongest < 0 || peakDb[j] > peakDb[strongest])) strongest = j
        if (strongest >= 0 && !explained[strongest] && noteCount < MAX_NOTES) {
            var best = -1
            for (s in 0 until seriesTotal) {
                if (nearNote(seriesF1[s]) || seriesF1[s] < lowestNoteHz || seriesF1[s] > MAX_NOTE_HZ) continue
                if (!claims(s, strongest)) continue
                val ratio = seriesHoles[s].toDouble() / max(1, seriesSpan[s])
                val bestRatio = if (best < 0) Double.MAX_VALUE else seriesHoles[best].toDouble() / max(1, seriesSpan[best])
                if (ratio < bestRatio - 1e-9 || (abs(ratio - bestRatio) < 1e-9 && seriesF1[s] > seriesF1[best])) best = s
            }
            if (best >= 0) choose(best)
        }
    }

    private fun choose(s: Int) {
        noteHz[noteCount++] = seriesF1[s]
        val peaks = seriesPeaks[s]
        for (k in 0 until seriesCount[s]) explained[peaks[k]] = true
    }

    private fun claims(s: Int, peak: Int): Boolean {
        val peaks = seriesPeaks[s]
        for (k in 0 until seriesCount[s]) if (peaks[k] == peak) return true
        return false
    }


    /** Fondamentales candidates : chaque pic fort vu comme partiel 1…[MAX_DIVISOR]. */
    private fun collectCandidates() {
        candidateCount = 0
        for (i in 0 until peakCount) order[i] = i
        val strong = min(peakCount, CANDIDATE_PEAKS)
        for (i in 0 until strong) {
            var best = i
            for (j in i + 1 until peakCount) if (peakDb[order[j]] > peakDb[order[best]]) best = j
            val t = order[i]
            order[i] = order[best]
            order[best] = t
            val hz = peakHz[order[i]]
            for (h in 1..MAX_DIVISOR) {
                val f0 = hz / h
                if (f0 < lowestNoteHz || f0 > MAX_NOTE_HZ) continue
                var duplicate = false
                for (c in 0 until candidateCount) if (abs(candidateHz[c] - f0) < SAME_SERIES * f0) duplicate = true
                if (!duplicate && candidateCount < MAX_CANDIDATES) candidateHz[candidateCount++] = f0
            }
        }
    }

    /**
     * Garde la série ajustée : pics qu'elle explique (un par partiel, tolérance d'appariement),
     * trous parmi ses [HOLE_HARMONICS] premiers partiels au-dessus de [HOLE_MIN_HZ] (pas de pic, ou
     * un pic trop faible pour en être un partiel), validité.
     */
    private fun storeSeries(f1: Double) {
        val s = seriesTotal++
        seriesF1[s] = f1
        var count = 0
        var strongest = SILENCE_DB
        var highest = 0
        var h = 1
        while (h <= MAX_HARMONIC) {
            val predicted = h * fitF0 * sqrt(1.0 + fitB * h * h)
            if (predicted > NOTE_MAX_HZ) break
            val j = nearestPeak(predicted, max(CLAIM_BINS * windowBinHz, CLAIM_RELATIVE * predicted))
            claimedPeak[h] = j
            if (j >= 0 && count < MAX_PARTIALS && (count == 0 || seriesPeaks[s][count - 1] != j)) {
                seriesPeaks[s][count++] = j
                strongest = max(strongest, peakDb[j])
                highest = h
            }
            h++
        }
        seriesCount[s] = count
        seriesStrongestDb[s] = strongest
        // Trous comptés jusqu'au dernier partiel net : une note qui s'éteint perd d'abord ses aigus.
        val from = max(1, ceil(HOLE_MIN_HZ / fitF0).toInt())
        var lastStrong = 0
        var strong = 0
        for (k in 1..min(HOLE_HARMONICS, highest)) {
            val j = claimedPeak[k]
            if (j >= 0 && peakDb[j] >= strongest - HOLE_RANGE_DB) lastStrong = k
            // Partiel franc (bien au-dessus du bruit) : le bruit seul n'en produit pas deux alignés.
            if (j >= 0 && peakSnr[j] >= NOTE_SNR_DB) strong++
        }
        // Un trou n'est comblé que par un pic bien à sa place : dans un accord dense, un partiel
        // d'une autre note tombe souvent à moins de 1 % d'un partiel attendu.
        var holes = 0
        var span = 0
        for (k in from..lastStrong) {
            span++
            val j = claimedPeak[k]
            val predicted = k * fitF0 * sqrt(1.0 + fitB * k * k)
            if (j < 0 || peakDb[j] < strongest - HOLE_RANGE_DB ||
                abs(peakHz[j] - predicted) > max(TIGHT_BINS * windowBinHz, TIGHT_RELATIVE * predicted)
            ) {
                holes++
            }
        }
        seriesHoles[s] = holes
        seriesSpan[s] = span
        seriesValid[s] = strong >= MIN_PARTIALS && f1 >= lowestNoteHz && f1 <= MAX_NOTE_HZ &&
            strongest >= strongestDb - NOTE_RANGE_DB && holes <= MAX_HOLE_RATIO * span
    }

    private fun nearNote(f1: Double): Boolean {
        for (n in 0 until noteCount) if (abs(ln(f1 / noteHz[n])) < SAME_NOTE) return true
        return false
    }

    private var matchCount = 0

    /**
     * Série de partiels d'une corde raide pour [candidate] (même méthode que [PitchDetector]) :
     * appariement grossier des 8 premiers rangs, ancrage sur les partiels forts, raideur choisie
     * sur une grille, extension à toute la bande avec réajustement, partiels éloignés écartés.
     * Remplit [matchHarmonic]/[matchPeak]/[matchCount], [fitF0] et [fitB].
     */
    private fun evaluate(candidate: Double): Boolean {
        evaluation++
        fitF0 = candidate
        fitB = 0.0
        var rough = 0
        val roughMax = min(ANCHOR_HARMONICS, floor(NOTE_MAX_HZ / candidate).toInt())
        for (h in 1..roughMax) {
            val predicted = h * candidate
            val j = nearestPeak(predicted, max(LOOSE_BINS * windowBinHz, LOOSE_RELATIVE * predicted))
            if (j < 0) continue
            roughHarmonic[rough] = h
            roughPeak[rough] = j
            rough++
        }
        if (rough < MIN_PARTIALS) return false
        // Par amplitude décroissante.
        for (i in 1 until rough) {
            val h = roughHarmonic[i]
            val j = roughPeak[i]
            var k = i - 1
            while (k >= 0 && peakDb[roughPeak[k]] < peakDb[j]) {
                roughHarmonic[k + 1] = roughHarmonic[k]
                roughPeak[k + 1] = roughPeak[k]
                k--
            }
            roughHarmonic[k + 1] = h
            roughPeak[k + 1] = j
        }
        var count = 0
        for (r in 0 until rough) {
            val h = roughHarmonic[r]
            val j = roughPeak[r]
            if (usedPeak[j] == evaluation || harmonicUsed(h, count)) continue
            if (count > 0) {
                val predicted = h * fitF0 * sqrt(1.0 + fitB * h * h)
                val tolerance = if (count < 2) {
                    max(LOOSE_BINS * windowBinHz, LOOSE_RELATIVE * predicted)
                } else {
                    max(TIGHT_BINS * windowBinHz, TIGHT_RELATIVE * predicted)
                }
                if (abs(peakHz[j] - predicted) > tolerance) continue
            }
            addPartial(h, j, count++)
            if (count == 1) fitF0 = peakHz[j] / h else fit(count)
        }
        if (NOTE_MAX_HZ / fitF0 >= STIFFNESS_MIN_HARMONICS) chooseStiffness(count)
        var h = 1
        while (h <= MAX_HARMONIC && count < MAX_PARTIALS) {
            val predicted = h * fitF0 * sqrt(1.0 + fitB * h * h)
            if (predicted > NOTE_MAX_HZ) break
            if (!harmonicUsed(h, count)) {
                val j = nearestPeak(predicted, max(TIGHT_BINS * windowBinHz, TIGHT_RELATIVE * predicted))
                if (j >= 0 && usedPeak[j] != evaluation) {
                    addPartial(h, j, count++)
                    fit(count)
                }
            }
            h++
        }
        fit(count)
        // Partiels restés loin du modèle final : écartés, puis réajustement.
        var kept = 0
        for (i in 0 until count) {
            val harmonic = matchHarmonic[i]
            val j = matchPeak[i]
            val predicted = harmonic * fitF0 * sqrt(1.0 + fitB * harmonic * harmonic)
            if (abs(peakHz[j] - predicted) > max(TIGHT_BINS * windowBinHz, TIGHT_RELATIVE * predicted)) continue
            matchHarmonic[kept] = harmonic
            matchPeak[kept] = j
            kept++
        }
        if (kept >= 2 && kept != count) {
            count = kept
            fit(count)
        }
        matchCount = count
        return count >= MIN_PARTIALS
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

    /** Raideur de départ de l'extension : celle de la grille qui aligne le plus de partiels. */
    private fun chooseStiffness(count: Int) {
        var bestB = fitB
        var bestF0 = fitF0
        var bestScore = stiffnessScore(fitF0, fitB)
        for (b in STIFFNESS_GRID) {
            var num = 0.0
            var den = 0.0
            for (i in 0 until count) {
                val h = matchHarmonic[i].toDouble()
                val ratio = peakHz[matchPeak[i]] / h
                val w = h * h
                num += w * ratio * ratio / (1.0 + b * h * h)
                den += w
            }
            if (den <= 0.0) continue
            val f0 = sqrt(num / den)
            val score = stiffnessScore(f0, b)
            if (score > bestScore + 1e-9) {
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
            if (predicted > NOTE_MAX_HZ) break
            val j = nearestPeak(predicted, max(TIGHT_BINS * windowBinHz, TIGHT_RELATIVE * predicted))
            if (j >= 0) score += ln(1.0 + min(peakSnr[j], MAX_SNR_DB))
            h++
        }
        return score
    }

    /** Moindres carrés pondérés sur (f_h / h)² = F + F·B·h² ; B borné à [0, MAX_INHARMONICITY]. */
    private fun fit(count: Int) {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until count) {
            val h = matchHarmonic[i].toDouble()
            val x = h * h
            val ratio = peakHz[matchPeak[i]] / h
            val y = ratio * ratio
            val snr = min(peakSnr[matchPeak[i]], MAX_SNR_DB)
            val w = x * (1.0 + snr)
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
            b = b.coerceIn(0.0, MAX_INHARMONICITY)
            var num = 0.0
            for (i in 0 until count) {
                val h = matchHarmonic[i].toDouble()
                val ratio = peakHz[matchPeak[i]] / h
                val snr = min(peakSnr[matchPeak[i]], MAX_SNR_DB)
                num += h * h * (1.0 + snr) * ratio * ratio / (1.0 + b * h * h)
            }
            intercept = num / sw
        }
        if (intercept <= 0.0) return
        fitF0 = sqrt(intercept)
        fitB = b
    }

    /** Pic le plus proche de [hz] à [tolerance] près (pics triés par fréquence) ; −1 si aucun. */
    private fun nearestPeak(hz: Double, tolerance: Double): Int {
        var lo = 0
        var hi = peakCount
        val from = hz - tolerance
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (peakHz[mid] < from) lo = mid + 1 else hi = mid
        }
        var best = -1
        var bestDistance = tolerance
        var i = lo
        while (i < peakCount) {
            val d = peakHz[i] - hz
            if (d > tolerance) break
            if (abs(d) <= bestDistance) {
                bestDistance = abs(d)
                best = i
            }
            i++
        }
        return best
    }

    // --- Publication -----------------------------------------------------------------------

    private fun publish(): SpectrumFrame {
        val out = FloatArray(DISPLAY_POINTS) { levels[it].toFloat() }

        // Pics affichables : les plus forts.
        for (i in 0 until peakCount) order[i] = i
        val shown = min(peakCount, DISPLAY_PEAKS)
        for (i in 0 until shown) {
            var best = i
            for (j in i + 1 until peakCount) if (peakDb[order[j]] > peakDb[order[best]]) best = j
            val t = order[i]
            order[i] = order[best]
            order[best] = t
        }
        val peaks = List(shown) { SpectrumPeak(peakHz[order[it]], peakDb[order[it]].toFloat()) }

        // Notes confirmées : entendues sur au moins 2 des 3 dernières trames (fréquence la plus récente).
        val slot = historyNext
        historyNext = (historyNext + 1) % HISTORY
        historyCount[slot] = noteCount
        for (n in 0 until noteCount) {
            history[slot][n] = midiOf(noteHz[n])
            historyHz[slot][n] = noteHz[n]
        }
        val notes = ArrayList<HeardNote>()
        for (age in 0 until HISTORY) {
            val f = (slot - age).mod(HISTORY)
            for (m in 0 until historyCount[f]) {
                val midi = history[f][m]
                if (notes.any { it.midi == midi }) continue
                var seen = 0
                for (g in 0 until HISTORY) {
                    for (k in 0 until historyCount[g]) if (history[g][k] == midi) {
                        seen++
                        break
                    }
                }
                if (seen >= CONFIRM_FRAMES) notes += HeardNote(historyHz[f][m], midi)
            }
        }
        notes.sortBy { it.frequency }
        return SpectrumFrame(out, peaks, notes)
    }

    private fun midiOf(hz: Double): Int = (69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)).roundToInt()

    companion object {
        /** 16384 échantillons ≈ 0,34 s à 48 kHz. */
        const val WINDOW = 16384
        const val DISPLAY_POINTS = 480
        const val MIN_HZ = 20.0
        const val MAX_HZ = 20_000.0
        const val DISPLAY_PEAKS = 16

        /** Sans accordage : un peu sous le Mi1 d'une basse… ou d'une 8-cordes. */
        const val DEFAULT_LOWEST_NOTE_HZ = 38.0
        private const val SILENCE_DB = -160.0

        /** Fréquence du point [position] (0 … DISPLAY_POINTS − 1, fractionnaire) de l'axe. */
        fun frequencyAt(position: Double): Double = MIN_HZ * (MAX_HZ / MIN_HZ).pow(position / (DISPLAY_POINTS - 1))

        /** Position de [hz] sur l'axe, de 0 (20 Hz) à 1 (20 kHz). */
        fun positionOf(hz: Double): Double = ln(hz / MIN_HZ) / ln(MAX_HZ / MIN_HZ)

        private const val FLOOR_MIN_BINS = 24
        private const val FLOOR_PERCENTILE = 0.2
        private val BAND_RATIO = 2.0.pow(1.0 / 6.0)
        private const val MAX_BANDS = 96
        private const val PEAK_MIN_HZ = 30.0
        private const val PEAK_MAX_HZ = 5000.0
        private const val PEAK_RANGE_DB = 70.0
        private const val PEAK_SNR_DB = 10.0
        private const val SIDELOBE_BINS = 3.5
        private const val SIDELOBE_DB = 25.0
        private const val MAX_PEAKS = 192

        private const val NOTE_MAX_HZ = 4000.0
        private const val MAX_NOTE_HZ = 1400.0
        private const val CANDIDATE_PEAKS = 24
        private const val MAX_DIVISOR = 6
        private const val MAX_CANDIDATES = CANDIDATE_PEAKS * MAX_DIVISOR
        private const val SAME_SERIES = 0.004
        private const val MAX_PARTIALS = 64
        private const val MAX_HARMONIC = 96
        private const val ANCHOR_HARMONICS = 8
        private const val MIN_PARTIALS = 2
        private const val LOOSE_BINS = 1.2
        private const val LOOSE_RELATIVE = 0.03
        private const val TIGHT_BINS = 0.6
        private const val TIGHT_RELATIVE = 0.006
        private const val MAX_INHARMONICITY = 0.002
        private val STIFFNESS_GRID = doubleArrayOf(0.0, 5e-5, 1e-4, 2e-4, 3e-4, 5e-4, 8e-4, 1.2e-3)
        private const val STIFFNESS_MIN_HARMONICS = 10.0
        private const val MAX_SNR_DB = 50.0

        /** En dessous, les partiels manquent souvent (coupe-bas du micro) : pas comptés comme trous. */
        private const val HOLE_MIN_HZ = 150.0
        private const val HOLE_HARMONICS = 12
        private const val HOLE_RANGE_DB = 50.0
        private const val MAX_HOLE_RATIO = 0.25
        private const val CLAIM_BINS = 1.0
        private const val CLAIM_RELATIVE = 0.008

        /** Une note doit avoir un partiel à moins de 35 dB du pic le plus fort. */
        private const val NOTE_RANGE_DB = 35.0

        /** …et au moins deux partiels francs, à 20 dB au-dessus du bruit. */
        private const val NOTE_SNR_DB = 20.0

        /** Les notes retenues doivent expliquer au moins la moitié de l'énergie des pics. */
        private const val MIN_EXPLAINED = 0.5

        // Basse retrouvée : sous la note la plus grave, 10 % de trous au plus sur au moins 6 partiels.
        private const val BASS_BELOW = 0.97
        private const val BASS_MIN_SPAN = 6
        private const val BASS_HOLE_RATIO = 0.1
        private const val BASS_RANGE_DB = 30.0

        /** Part minimale de l'énergie des pics qu'une note doit expliquer en propre. */
        private const val MIN_SHARE = 0.005

        /** Son partiel propre le plus fort doit être à moins de 20 dB du pic le plus fort. */
        private const val OWN_RANGE_DB = 20.0
        private const val MAX_NOTES = 7
        private val SAME_NOTE = ln(2.0) / 24.0
        private const val HISTORY = 3
        private const val CONFIRM_FRAMES = 2
    }
}
