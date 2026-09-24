package com.blenouvel.accordeur.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mode poly à affichage maintenu :
 *
 * - un **grattage** (la plupart des cordes attaquées ensemble) met à jour toutes les cordes ;
 * - une **corde jouée seule** ne met à jour qu'elle : les autres gardent leur valeur ;
 * - rien ne s'efface quand le son s'éteint : le tableau reste affiché jusqu'à l'attaque suivante.
 *
 * **Attaques** : saut de niveau ou clic du médiator (signalés par le [TunerProcessor] via [onset]),
 * ou énergie d'une corde qui dépasse de 9 dB son maximum des 1 à 2 s précédentes (corde rejouée
 * pendant que d'autres sonnent ; un battement ne dépasse pas son propre maximum).
 *
 * **Classement**, 0,3 s après l'attaque (fenêtre longue remplie) : une corde est « fraîche » si
 * elle est détectée avec au moins deux partiels (une résonance de caisse n'en a qu'un), si son
 * énergie dépasse de 3 dB son maximum d'avant l'attaque et si elle n'est pas 20 dB sous la plus
 * forte (transitoire, résonances). Au moins 60 % de cordes fraîches : grattage. Sinon, le détecteur
 * mono nomme la note jouée ; on la retient si sa corde est fraîche, si les cordes fraîches ne sont
 * que ses harmoniques (ses partiels les ont fait monter), ou si c'est une corde sans partiel propre
 * (Si3, Mi4 en standard : énergie non mesurable seule). À défaut : les cordes fraîches, moins
 * celles qui ne font que partager les partiels d'une corde plus grave fraîche (Ré3 / Ré2 en Drop D).
 *
 * Tant qu'elles sonnent, les cordes de l'attaque restent suivies (on peut tourner la mécanique) ;
 * une corde seule est mesurée par le détecteur mono (~1 cent) quand il la reconnaît.
 */
class PolyTracker(sampleRate: Int) {
    private val poly = PolyPitchDetector(sampleRate)
    private val mono = PitchDetector(sampleRate)
    private val monoEstimate = PitchEstimate()
    private var monoTime = Double.NaN
    private var monoFound = false

    private var targets = DoubleArray(0)
    private val board = arrayOfNulls<PolyString>(MAX_STRINGS)
    private var published: PolyReading? = null
    private var dirty = false
    private var event = 0
    private var strum = false

    // Énergies par corde des dernières analyses (anneau).
    private val history = Array(MAX_STRINGS) { DoubleArray(HISTORY) }
    private val historyTime = DoubleArray(HISTORY)
    private var historyCount = 0
    private var historyNext = 0

    // Attaque en attente de classement, puis cordes suivies tant qu'elles sonnent.
    private var pending = false
    private var pendingTime = 0.0
    private var lastEventTime = Double.NEGATIVE_INFINITY
    private var currentTime = 0.0
    private val baseline = DoubleArray(MAX_STRINGS)
    private val after = DoubleArray(MAX_STRINGS)
    private val fresh = BooleanArray(MAX_STRINGS)
    private val following = BooleanArray(MAX_STRINGS)
    private val peak = DoubleArray(MAX_STRINGS)

    /** Cordes de l'accordage (grave → aiguë) ; un changement efface le tableau. */
    fun setTargets(stringsHz: DoubleArray) {
        if (stringsHz.contentEquals(targets)) return
        targets = stringsHz.copyOf(min(stringsHz.size, MAX_STRINGS))
        reset()
    }

    fun reset() {
        board.fill(null)
        published = null
        dirty = false
        event = 0
        strum = false
        historyCount = 0
        historyNext = 0
        pending = false
        lastEventTime = Double.NEGATIVE_INFINITY
        following.fill(false)
    }

    /** Silence (gate fermé) : ce qui sonnait avant ne sert plus de référence. */
    fun silence() {
        historyCount = 0
        historyNext = 0
    }

    /** Attaque (saut de niveau ou clic d'attaque) à [time]. */
    fun onset(time: Double) {
        if (!pending && time - lastEventTime >= EVENT_REFRACTORY_S) startEvent(time, lag = 0.0)
    }

    /**
     * Nouvelle analyse, toutes les ~2 trames tant que ça sonne : [polyWindow] =
     * [PolyPitchDetector.WINDOW] derniers échantillons filtrés, [monoWindow] = [PitchDetector.WINDOW].
     */
    fun analyze(polyWindow: DoubleArray, monoWindow: DoubleArray, time: Double) {
        if (targets.isEmpty()) return
        monoTime = Double.NaN
        currentTime = time
        val reading = poly.analyze(polyWindow, targets, time)
        val energies = poly.energies
        // Corde rejouée pendant que d'autres sonnent (ni saut de niveau ni clic net) : son énergie
        // dépasse nettement son maximum d'avant (un battement ne dépasse pas son propre maximum).
        if (!pending && time - lastEventTime >= EVENT_REFRACTORY_S && rose(reading, energies, time)) {
            startEvent(time, lag = RISE_LAG_S)
        }
        if (pending && time - pendingTime >= CLASSIFY_DELAY_S) {
            // Énergie après l'attaque : maximum de cette analyse et de la précédente (battements).
            for (i in targets.indices) {
                after[i] = energies[i]
                val previous = (historyNext - 1 + HISTORY) % HISTORY
                if (historyCount > 0 && historyTime[previous] >= pendingTime + SETTLE_S) after[i] = max(after[i], history[i][previous])
            }
            push(energies, time)
            classify(reading, after, monoWindow)
        } else {
            push(energies, time)
            if (!pending) sustain(reading, energies, monoWindow)
        }
    }

    /** Tableau courant (même objet tant qu'il ne change pas) ; null avant la première attaque. */
    fun reading(time: Double): PolyReading? {
        if (dirty) {
            dirty = false
            published = PolyReading(List(targets.size) { board[it] ?: EMPTY }, time, event, strum)
        }
        return published
    }

    /**
     * Attaque à [time] ; [lag] : retard de sa détection (la montée d'énergie n'est vue qu'une fois
     * la fenêtre longue en partie remplie). Niveau d'avant l'attaque : maximum de chaque corde sur la
     * seconde qui précède (0 après un silence) — le maximum, pour qu'une corde qui bat ne paraisse
     * pas rejouée.
     */
    private fun startEvent(time: Double, lag: Double) {
        pending = true
        pendingTime = time
        val from = if (lag > 0.0) max(time - lag - RISE_BASELINE_S, lastEventTime + WINDOW_FILL_S) else time - BASELINE_S
        for (i in targets.indices) baseline[i] = recentMax(i, from, time - lag)
        lastEventTime = time
    }

    /** Maximum d'énergie de la corde [i] sur les analyses de [from, to] (0 si aucune). */
    private fun recentMax(i: Int, from: Double, to: Double): Double {
        var highest = 0.0
        for (k in 0 until historyCount) {
            if (historyTime[k] in from..to) highest = max(highest, history[i][k])
        }
        return highest
    }

    /** Une corde vient-elle d'être rejouée alors que d'autres sonnent encore ? */
    private fun rose(reading: PolyReading, energies: DoubleArray, time: Double): Boolean {
        // Référence longue (≤ 2 s, au moins 1 s) : couvre une période de battement. Elle ne commence
        // qu'une fois la fenêtre longue remplie par l'attaque précédente (sinon tout « monte »).
        val from = max(time - RISE_LAG_S - RISE_BASELINE_S, lastEventTime + WINDOW_FILL_S)
        val to = time - RISE_LAG_S
        if (to - from < RISE_MIN_REFERENCE_S) return false
        var loudest = 0.0
        for (i in targets.indices) if (reading.strings[i].detected) loudest = max(loudest, energies[i])
        for (i in targets.indices) {
            if (!reading.strings[i].detected || !harmonic(i) || energies[i] < RELATIVE_TO_LOUDEST * loudest) continue
            val before = recentMax(i, from, to)
            if (before > 0.0 && energies[i] >= RISE_RATIO * before) return true
        }
        return false
    }

    /** Une vraie corde fait entendre ses partiels (une résonance de caisse vers 100 Hz, non). */
    private fun harmonic(i: Int): Boolean = poly.partialsFound[i] >= min(2, poly.partialsExpected[i])

    private fun push(energies: DoubleArray, time: Double) {
        for (i in targets.indices) history[i][historyNext] = energies[i]
        historyTime[historyNext] = time
        historyNext = (historyNext + 1) % HISTORY
        if (historyCount < HISTORY) historyCount++
    }

    private fun classify(reading: PolyReading, energies: DoubleArray, monoWindow: DoubleArray) {
        pending = false
        val n = targets.size
        var loudest = 0.0
        for (i in 0 until n) if (reading.strings[i].detected) loudest = max(loudest, energies[i])
        var count = 0
        for (i in 0 until n) {
            fresh[i] = reading.strings[i].detected && harmonic(i) &&
                energies[i] >= FRESH_RATIO * max(baseline[i], ENERGY_FLOOR) &&
                energies[i] >= RELATIVE_TO_LOUDEST * loudest
            if (fresh[i]) count++
        }
        val isStrum = count >= max(MIN_STRUM_STRINGS, ceil(STRUM_FRACTION * n).toInt())
        if (!isStrum) {
            // Corde seule : le détecteur mono dit quelle note vient d'être jouée (une résonance de
            // caisse ou les partiels communs d'une autre corde ne la trompent pas). À défaut, les
            // cordes fraîches, sans celles qui ne font que partager les partiels d'une plus grave.
            val played = monoString(monoWindow)
            if (played >= 0 && confirmsMono(played, count)) {
                for (i in 0 until n) fresh[i] = i == played
                count = 1
            } else {
                excludeSharedPartials()
            }
        }
        if (count == 0) return // choc, bruit : rien de nouveau, le tableau reste tel quel
        var single = -1
        var played = 0
        for (i in 0 until n) {
            if (fresh[i]) {
                played++
                single = i
            }
        }
        event++
        strum = isStrum
        dirty = true
        for (i in 0 until n) {
            val detected = reading.strings[i].detected
            following[i] = if (isStrum) detected else fresh[i]
            peak[i] = energies[i]
            board[i] = when {
                isStrum -> if (detected) reading.strings[i].copy(fresh = true) else EMPTY.copy(fresh = true)
                fresh[i] -> measure(i, reading, monoWindow, alone = played == 1 && single == i).copy(fresh = true)
                else -> board[i]?.copy(fresh = false)
            }
        }
    }

    /** Les cordes de l'attaque sont suivies tant qu'elles sonnent (mécanique qu'on tourne). */
    private fun sustain(reading: PolyReading, energies: DoubleArray, monoWindow: DoubleArray) {
        var followed = 0
        var single = -1
        for (i in targets.indices) {
            if (following[i]) {
                followed++
                single = i
            }
        }
        for (i in targets.indices) {
            if (!following[i]) continue
            if (energies[i] < peak[i] * RELEASE_RATIO) {
                following[i] = false // la corde s'éteint : on fige sa dernière valeur
                continue
            }
            peak[i] = max(peak[i], energies[i])
            val now = measure(i, reading, monoWindow, alone = followed == 1 && single == i)
            if (!now.detected) continue
            val before = board[i]
            board[i] = if (before != null && before.detected) {
                now.copy(cents = before.cents + SMOOTHING * (now.cents - before.cents), fresh = before.fresh)
            } else {
                now.copy(fresh = before?.fresh ?: true)
            }
            dirty = true
        }
    }

    /**
     * La note du mono (corde [k]) désigne-t-elle bien la corde jouée ? Oui si elle est fraîche, si
     * toutes les cordes fraîches ne sont que ses harmoniques (ses partiels les ont fait monter), ou
     * si son énergie n'est pas mesurable seule (partiels tous communs avec d'autres cordes : Si3, Mi4
     * en standard) alors qu'aucune corde propre n'est fraîche.
     */
    private fun confirmsMono(k: Int, freshCount: Int): Boolean {
        if (fresh[k]) return true
        if (freshCount == 0) return !poly.clean[k]
        for (j in targets.indices) {
            if (!fresh[j]) continue
            val ratio = targets[j] / targets[k]
            val whole = ratio.roundToInt()
            if (whole < 2 || abs(ratio - whole) >= HARMONIC_TOLERANCE * ratio) return false
        }
        return true
    }

    /** Détection mono de la fenêtre courante, calculée une seule fois par analyse. */
    private fun monoDetect(monoWindow: DoubleArray): Boolean {
        if (monoTime != currentTime) {
            monoFound = mono.detect(monoWindow, monoEstimate)
            monoTime = currentTime
        }
        return monoFound
    }

    /** Corde la plus proche (≤ ±150 cents) de la note du détecteur mono, −1 si aucune. */
    private fun monoString(monoWindow: DoubleArray): Int {
        if (!monoDetect(monoWindow)) return -1
        var best = -1
        var bestCents = MONO_MATCH_CENTS
        for (i in targets.indices) {
            val cents = abs(1200.0 * log2(monoEstimate.frequency / targets[i]))
            if (cents <= bestCents) {
                bestCents = cents
                best = i
            }
        }
        return best
    }

    /**
     * Mesure de la corde [i]. Jouée seule, elle passe par le détecteur mono (bien plus précis que la
     * mesure polyphonique) s'il la reconnaît : à ±150 cents de la cible, au besoin à l'octave près,
     * et d'accord avec la mesure poly quand elle existe.
     */
    private fun measure(i: Int, reading: PolyReading, monoWindow: DoubleArray, alone: Boolean): PolyString {
        val polyMeasure = reading.strings[i]
        if (!alone || !monoDetect(monoWindow)) return polyMeasure
        var cents = 1200.0 * log2(monoEstimate.frequency / targets[i])
        if (cents > 600.0) cents -= 1200.0 else if (cents < -600.0) cents += 1200.0
        if (abs(cents) > MONO_MATCH_CENTS) return polyMeasure
        if (polyMeasure.detected && abs(cents - polyMeasure.cents) > MONO_AGREEMENT_CENTS) return polyMeasure
        return PolyString(detected = true, cents = cents, reliable = true)
    }

    /**
     * Corde « fraîche » dont tous les partiels coïncident avec ceux d'une corde plus grave, elle aussi
     * fraîche et mesurée sur des partiels propres (rapport de fréquences entier) : c'est la grave qui
     * a été jouée, l'aiguë n'a fait que « récupérer » ses partiels.
     */
    private fun excludeSharedPartials() {
        for (j in targets.indices) {
            if (!fresh[j] || poly.clean[j]) continue
            for (i in targets.indices) {
                if (i == j || !fresh[i] || !poly.clean[i] || targets[i] >= targets[j]) continue
                val ratio = targets[j] / targets[i]
                val whole = ratio.roundToInt()
                if (whole >= 2 && abs(ratio - whole) < HARMONIC_TOLERANCE * ratio) {
                    fresh[j] = false
                    break
                }
            }
        }
    }

    private companion object {
        const val MAX_STRINGS = PolyPitchDetector.MAX_STRINGS
        val EMPTY = PolyString(detected = false, cents = Double.NaN, reliable = false)

        /** ~2,7 s d'historique à une analyse toutes les 2 trames (85 ms). */
        const val HISTORY = 32
        const val BASELINE_S = 1.0
        const val CLASSIFY_DELAY_S = 0.3
        const val SETTLE_S = 0.15
        const val EVENT_REFRACTORY_S = 0.6
        const val FRESH_RATIO = 2.0
        const val RELATIVE_TO_LOUDEST = 0.01
        const val ENERGY_FLOOR = 1e-12
        const val MIN_STRUM_STRINGS = 3
        const val STRUM_FRACTION = 0.6
        const val RELEASE_RATIO = 0.01
        const val SMOOTHING = 0.5
        const val MONO_MATCH_CENTS = 150.0
        const val MONO_AGREEMENT_CENTS = 30.0
        const val HARMONIC_TOLERANCE = 0.02
        const val RISE_LAG_S = 0.35
        const val WINDOW_FILL_S = 0.35
        const val RISE_BASELINE_S = 2.0
        const val RISE_MIN_REFERENCE_S = 1.0
        const val RISE_RATIO = 8.0
    }
}
