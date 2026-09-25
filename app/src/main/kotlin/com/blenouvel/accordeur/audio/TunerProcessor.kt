package com.blenouvel.accordeur.audio

import kotlin.math.log10

/**
 * Mode d'accordage : une corde à la fois (précis) ou toutes après un grattage (coup d'œil,
 * tableau maintenu : une corde rejouée seule ne met à jour qu'elle).
 */
enum class TunerMode { MONO, POLY }

/**
 * Cibles fournies par l'interface : fréquences des cordes de l'accordage (grave → aiguë) et
 * corde verrouillée ([lockedIndex] = −1 en mode auto). Objet immuable, publié par un champ volatile.
 */
class TunerTargets(val stringsHz: DoubleArray, val lockedIndex: Int = -1) {
    val lockedHz: Double get() = if (lockedIndex in stringsHz.indices) stringsHz[lockedIndex] else 0.0

    companion object {
        val NONE = TunerTargets(DoubleArray(0))
    }
}

/** Instantané publié à chaque hop (~23 fois par seconde). */
data class TunerFrame(
    val timeSeconds: Double,
    /** Niveau RMS de la fenêtre d'analyse (dBFS). */
    val levelDb: Double,
    /** Signal utile présent (gate ouvert). */
    val signal: Boolean,
    /** Hauteur stabilisée (Hz), NaN si aucune. */
    val frequency: Double,
    /** Dernière mesure brute retenue (Hz), NaN si aucune. */
    val rawFrequency: Double,
    val clarity: Double,
    /** Valeur maintenue après disparition du signal (à afficher atténuée). */
    val holding: Boolean,
    /** Tableau des cordes en mode poly (maintenu entre les attaques), sinon null. */
    val poly: PolyReading?,
    /** Spectre et notes entendues (page Spectre), sinon null. */
    val spectrum: SpectrumFrame? = null,
) {
    val hasPitch: Boolean get() = !frequency.isNaN()
}

/**
 * Chaîne de traitement complète, indépendante d'Android (testable sur JVM) :
 * pré-filtrage continu → tampon circulaire → toutes les [PitchDetector.HOP] trames, analyse de la
 * dernière fenêtre (série de partiels en mono, [PolyTracker] en poly) → stabilisation. Pour la
 * page Spectre, le signal brut (non filtré) passe à la place dans le [SpectrumAnalyzer].
 *
 * [process] est appelé par le thread audio ; les champs de configuration peuvent être modifiés
 * depuis un autre thread. Tampons préalloués : pas d'allocation dans la boucle échantillon.
 */
class TunerProcessor(val sampleRate: Int = PitchDetector.SAMPLE_RATE) {
    @Volatile
    var mode: TunerMode = TunerMode.MONO

    @Volatile
    var targets: TunerTargets = TunerTargets.NONE

    /** Analyse suspendue (ex. pendant la lecture du son de référence). */
    @Volatile
    var muted: Boolean = false

    /** Page Spectre : spectre du signal brut et notes entendues, pas d'accordage. */
    @Volatile
    var spectrum: Boolean = false

    private val preprocessor = Preprocessor(sampleRate)

    /** Aigus du signal brut (> 2 kHz) : le clic du médiator, même pendant qu'un accord sonne. */
    private val attackFilter = Biquad.highPass(sampleRate, ATTACK_BAND_HZ)
    private var attackEnergy = 0.0
    private val attackLevels = DoubleArray(ATTACK_HISTORY)
    private val attackSorted = DoubleArray(ATTACK_HISTORY)
    private var attackCount = 0
    private var attackNext = 0
    private var lastAttackTime = Double.NEGATIVE_INFINITY
    private val ring = DoubleArray(RING_SIZE)
    private var written = 0L
    private val window = DoubleArray(PitchDetector.WINDOW)
    private val polyWindow = DoubleArray(PolyPitchDetector.WINDOW)
    private val detector = PitchDetector(sampleRate)
    private val estimate = PitchEstimate()
    private val stabilizer = PitchStabilizer()
    private val polyTracker = PolyTracker(sampleRate)

    // Page Spectre : signal brut (pas de filtrage), analyseur créé à la première utilisation.
    private val rawRing = FloatArray(RING_SIZE)
    private var spectrumAnalyzer: SpectrumAnalyzer? = null
    private var spectrumWindow: FloatArray? = null
    private var spectrumActive = false

    private var hopFill = 0
    private var hopEnergy = 0.0
    private var hopIndex = 0L

    private var polyActive = false
    private var polyOnsets = 0
    private var lastPolyHop = Long.MIN_VALUE / 2

    /** Traite [count] échantillons ; renvoie le dernier instantané produit, ou null. */
    fun process(input: FloatArray, count: Int = input.size): TunerFrame? {
        var frame: TunerFrame? = null
        for (i in 0 until count) {
            val x = input[i].toDouble()
            val a = attackFilter.process(x)
            attackEnergy += a * a
            val y = preprocessor.process(x)
            rawRing[(written and RING_MASK).toInt()] = input[i]
            ring[(written and RING_MASK).toInt()] = y
            written++
            hopEnergy += y * y
            if (++hopFill == PitchDetector.HOP) {
                frame = analyze(hopEnergy / hopFill, attackEnergy / hopFill)
                hopFill = 0
                hopEnergy = 0.0
                attackEnergy = 0.0
            }
        }
        return frame
    }

    /** Remet la chaîne à zéro (à la reprise de l'écoute). */
    fun reset() {
        preprocessor.reset()
        attackFilter.reset()
        attackEnergy = 0.0
        attackCount = 0
        attackNext = 0
        lastAttackTime = Double.NEGATIVE_INFINITY
        java.util.Arrays.fill(ring, 0.0)
        written = 0
        hopFill = 0
        hopEnergy = 0.0
        hopIndex = 0
        stabilizer.reset()
        polyTracker.reset()
        polyActive = false
        lastPolyHop = Long.MIN_VALUE / 2
        spectrumActive = false
        java.util.Arrays.fill(rawRing, 0f)
    }

    private fun analyze(hopMeanSquare: Double, attackMeanSquare: Double): TunerFrame {
        hopIndex++
        val time = written.toDouble() / sampleRate
        copyLatest(window)
        var sum = 0.0
        for (v in window) sum += v * v
        val levelDb = toDb(sum / window.size)

        if (muted) {
            return TunerFrame(time, levelDb, false, Double.NaN, Double.NaN, 0.0, false, null)
        }

        stabilizer.updateLevel(levelDb, toDb(hopMeanSquare), time)
        val attack = attackOnset(toDb(attackMeanSquare), time)
        val currentTargets = targets
        if (spectrum) return analyzeSpectrum(time, levelDb, currentTargets)
        spectrumActive = false
        val poly: PolyReading?
        if (mode == TunerMode.MONO) {
            polyActive = false
            // Court-circuit sur silence : pas de détection tant que le gate est fermé.
            val found = stabilizer.gateOpen && detector.detect(window, estimate)
            stabilizer.updatePitch(
                if (found) estimate else null,
                time,
                currentTargets.lockedHz,
                currentTargets.stringsHz,
            )
            poly = null
        } else {
            stabilizer.updatePitch(null, time, 0.0, currentTargets.stringsHz)
            poly = updatePoly(time, currentTargets.stringsHz, attack)
        }
        return TunerFrame(
            timeSeconds = time,
            levelDb = levelDb,
            signal = stabilizer.gateOpen,
            frequency = stabilizer.frequency,
            rawFrequency = stabilizer.rawFrequency,
            clarity = stabilizer.clarity,
            holding = stabilizer.holding,
            poly = poly,
        )
    }

    /** Page Spectre : analyse du signal brut ; notes cherchées dès la corde la plus grave (− 1 ton). */
    private fun analyzeSpectrum(time: Double, levelDb: Double, targets: TunerTargets): TunerFrame {
        val analyzer = spectrumAnalyzer ?: SpectrumAnalyzer(sampleRate).also { spectrumAnalyzer = it }
        val window = spectrumWindow ?: FloatArray(SpectrumAnalyzer.WINDOW).also { spectrumWindow = it }
        if (!spectrumActive) {
            spectrumActive = true
            analyzer.reset()
        }
        var lowest = Double.MAX_VALUE
        for (hz in targets.stringsHz) if (hz < lowest) lowest = hz
        analyzer.lowestNoteHz = if (lowest == Double.MAX_VALUE) {
            SpectrumAnalyzer.DEFAULT_LOWEST_NOTE_HZ
        } else {
            lowest * LOWEST_NOTE_MARGIN
        }
        val start = written - window.size
        for (i in window.indices) {
            val index = start + i
            window[i] = if (index < 0) 0f else rawRing[(index and RING_MASK).toInt()]
        }
        stabilizer.updatePitch(null, time, 0.0, targets.stringsHz)
        val gate = stabilizer.gateOpen
        return TunerFrame(time, levelDb, gate, Double.NaN, Double.NaN, 0.0, false, null, analyzer.analyze(window, gate))
    }

    /**
     * Mode poly : les attaques signalées par le gate et une analyse spectrale longue toutes les
     * [POLY_EVERY_HOPS] trames (tant que ça sonne) alimentent le [PolyTracker], qui tient le tableau.
     */
    private fun updatePoly(time: Double, strings: DoubleArray, attack: Boolean): PolyReading? {
        if (strings.isEmpty()) return null
        polyTracker.setTargets(strings)
        if (!polyActive) {
            // Entrée en mode poly : les attaques comptées en mono ne concernent pas le tableau.
            polyActive = true
            polyOnsets = stabilizer.onsetCount
        }
        if (stabilizer.onsetCount != polyOnsets) {
            polyOnsets = stabilizer.onsetCount
            polyTracker.onset(stabilizer.lastOnsetTime)
        } else if (attack && stabilizer.gateOpen) {
            polyTracker.onset(time)
        }
        if (!stabilizer.gateOpen) {
            polyTracker.silence()
        } else if (hopIndex - lastPolyHop >= POLY_EVERY_HOPS) {
            lastPolyHop = hopIndex
            copyLatest(polyWindow)
            polyTracker.analyze(polyWindow, window, time)
        }
        return polyTracker.reading(time)
    }

    /**
     * Clic d'attaque : l'énergie des aigus du dernier bloc dépasse de [ATTACK_JUMP_DB] la médiane
     * des ~0,5 s précédentes (les aigus d'une corde qui sonne s'éteignent vite, pas le clic).
     */
    private fun attackOnset(levelDb: Double, time: Double): Boolean {
        var median = Double.NaN
        if (attackCount >= ATTACK_HISTORY / 2) {
            for (i in 0 until attackCount) attackSorted[i] = attackLevels[i]
            java.util.Arrays.sort(attackSorted, 0, attackCount)
            median = attackSorted[attackCount / 2]
        }
        attackLevels[attackNext] = levelDb
        attackNext = (attackNext + 1) % ATTACK_HISTORY
        if (attackCount < ATTACK_HISTORY) attackCount++
        if (median.isNaN() || levelDb < median + ATTACK_JUMP_DB || time - lastAttackTime < ATTACK_REFRACTORY_S) return false
        lastAttackTime = time
        return true
    }

    private fun copyLatest(destination: DoubleArray) {
        val n = destination.size
        val start = written - n
        for (i in 0 until n) {
            val index = start + i
            destination[i] = if (index < 0) 0.0 else ring[(index and RING_MASK).toInt()]
        }
    }

    private fun toDb(meanSquare: Double): Double =
        if (meanSquare <= 1e-12) PitchStabilizer.SILENCE_DB else 10.0 * log10(meanSquare)

    companion object {
        private const val RING_SIZE = 32768
        private const val RING_MASK = (RING_SIZE - 1).toLong()
        private const val POLY_EVERY_HOPS = 2
        private const val ATTACK_BAND_HZ = 2000.0
        private const val ATTACK_HISTORY = 12
        private const val ATTACK_JUMP_DB = 6.0
        private const val ATTACK_REFRACTORY_S = 0.25

        /** Un ton sous la corde la plus grave : 2^(−2/12). */
        private const val LOWEST_NOTE_MARGIN = 0.8908987181403393
    }
}
