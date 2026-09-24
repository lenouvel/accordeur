package com.blenouvel.accordeur.audio

import kotlin.math.log10

/** Mode d'accordage : une corde à la fois (précis) ou toutes après un grattage (coup d'œil). */
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
    /** Mesures par corde en mode poly, sinon null. */
    val poly: PolyReading?,
) {
    val hasPitch: Boolean get() = !frequency.isNaN()
}

/**
 * Chaîne de traitement complète, indépendante d'Android (testable sur JVM) :
 * pré-filtrage continu → tampon circulaire → toutes les [PitchDetector.HOP] trames, analyse de la
 * dernière fenêtre (MPM en mono, spectre haute résolution en poly) → stabilisation.
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

    private val preprocessor = Preprocessor(sampleRate)
    private val ring = DoubleArray(RING_SIZE)
    private var written = 0L
    private val window = DoubleArray(PitchDetector.WINDOW)
    private val polyWindow = DoubleArray(PolyPitchDetector.WINDOW)
    private val detector = PitchDetector(sampleRate)
    private val estimate = PitchEstimate()
    private val stabilizer = PitchStabilizer()
    private val polyDetector = PolyPitchDetector(sampleRate)

    private var hopFill = 0
    private var hopEnergy = 0.0
    private var hopIndex = 0L

    private var polyReading: PolyReading? = null
    private var polyOnset = -1
    private var lastPolyHop = Long.MIN_VALUE / 2
    private var lastSignalTime = Double.NEGATIVE_INFINITY

    /** Traite [count] échantillons ; renvoie le dernier instantané produit, ou null. */
    fun process(input: FloatArray, count: Int = input.size): TunerFrame? {
        var frame: TunerFrame? = null
        for (i in 0 until count) {
            val y = preprocessor.process(input[i].toDouble())
            ring[(written and RING_MASK).toInt()] = y
            written++
            hopEnergy += y * y
            if (++hopFill == PitchDetector.HOP) {
                frame = analyze(hopEnergy / hopFill)
                hopFill = 0
                hopEnergy = 0.0
            }
        }
        return frame
    }

    /** Remet la chaîne à zéro (à la reprise de l'écoute). */
    fun reset() {
        preprocessor.reset()
        java.util.Arrays.fill(ring, 0.0)
        written = 0
        hopFill = 0
        hopEnergy = 0.0
        hopIndex = 0
        stabilizer.reset()
        polyReading = null
        polyOnset = -1
        lastPolyHop = Long.MIN_VALUE / 2
        lastSignalTime = Double.NEGATIVE_INFINITY
    }

    private fun analyze(hopMeanSquare: Double): TunerFrame {
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
        val currentTargets = targets
        val poly: PolyReading?
        if (mode == TunerMode.MONO) {
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
            poly = updatePoly(time, currentTargets.stringsHz)
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

    /**
     * Mode poly : après un grattage (attaque), on attend que la fenêtre longue soit remplie par la
     * nouvelle note, puis on analyse toutes les [POLY_EVERY_HOPS] trames tant que ça sonne.
     * Les mesures successives d'un même grattage sont moyennées (affichage plus calme).
     */
    private fun updatePoly(time: Double, strings: DoubleArray): PolyReading? {
        if (stabilizer.gateOpen) lastSignalTime = time
        if (strings.isEmpty()) return null
        val sinceOnset = time - stabilizer.lastOnsetTime
        val due = hopIndex - lastPolyHop >= POLY_EVERY_HOPS
        if (stabilizer.gateOpen && sinceOnset >= POLY_DELAY_S && due) {
            lastPolyHop = hopIndex
            copyLatest(polyWindow)
            val fresh = polyDetector.analyze(polyWindow, strings, time)
            val previous = polyReading
            if (fresh.strings.any { it.detected }) {
                polyReading = if (previous != null && polyOnset == stabilizer.onsetCount &&
                    previous.strings.size == fresh.strings.size
                ) {
                    blend(previous, fresh)
                } else {
                    fresh
                }
                polyOnset = stabilizer.onsetCount
            }
        } else if (time - lastSignalTime > POLY_HOLD_S) {
            polyReading = null
        }
        val reading = polyReading
        return if (reading != null && reading.strings.size == strings.size) reading else null
    }

    private fun blend(previous: PolyReading, fresh: PolyReading): PolyReading {
        val strings = fresh.strings.mapIndexed { i, now ->
            val before = previous.strings[i]
            when {
                now.detected && before.detected -> now.copy(cents = 0.5 * (now.cents + before.cents))
                now.detected -> now
                else -> before
            }
        }
        return PolyReading(strings, fresh.timeSeconds)
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
        private const val POLY_EVERY_HOPS = 4
        private const val POLY_DELAY_S = 0.25
        private const val POLY_HOLD_S = 4.0
    }
}
