package com.blenouvel.accordeur.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Logique pure du métronome (sans Android ni audio) : configuration, bornes, calcul du nombre
 * d'échantillons par clic, tap tempo, progression de l'automation, mesures silencieuses et codec
 * des mémoires. Tout ici est testable sans appareil.
 */

/** Une note (ou un silence) d'un schéma rythmique, mesurée en unités de la grille du temps. */
data class RhythmCell(val units: Int, val rest: Boolean = false)

/**
 * Schéma de division du temps, décrit en notation : une suite de notes/silences dont la somme des
 * durées fait un temps. [tuplet] vaut 0 pour les schémas binaires (grille = la double-croche, soit
 * 4 unités par temps) ou le chiffre du n-olet (3, 5, 6, 7). [beamGroup] découpe la ligature en
 * paquets (ex. sextolet 3+3 ou 2+2+2) ; 0 = une seule ligature. [grid] est le nombre de sous-temps,
 * [onsets] les positions (0 = sur le temps) où un clic sonne.
 *
 * L'ordre suit la grille 4×4 du site (lue de gauche à droite, de haut en bas).
 */
enum class Subdivision(val tuplet: Int, val cells: List<RhythmCell>, val beamGroup: Int = 0) {
    // Rangée 1 : divisions simples.
    QUARTER(0, listOf(RhythmCell(4))),
    EIGHTHS(0, listOf(RhythmCell(2), RhythmCell(2))),
    TRIPLET(3, listOf(RhythmCell(1), RhythmCell(1), RhythmCell(1))),
    SIXTEENTHS(0, listOf(RhythmCell(1), RhythmCell(1), RhythmCell(1), RhythmCell(1))),

    // Rangée 2 : combinaisons de doubles-croches (« galops ») et triolet long.
    EIGHTH_TWO_SIXTEENTHS(0, listOf(RhythmCell(2), RhythmCell(1), RhythmCell(1))),
    TWO_SIXTEENTHS_EIGHTH(0, listOf(RhythmCell(1), RhythmCell(1), RhythmCell(2))),
    TRIPLET_QUARTER_EIGHTH(3, listOf(RhythmCell(2), RhythmCell(1))),
    SIXTEENTH_EIGHTH_SIXTEENTH(0, listOf(RhythmCell(1), RhythmCell(2), RhythmCell(1))),

    // Rangée 3 : pointés, silences, syncopes.
    DOTTED_EIGHTH_SIXTEENTH(0, listOf(RhythmCell(3), RhythmCell(1))),
    EIGHTH_REST_EIGHTH(0, listOf(RhythmCell(2, rest = true), RhythmCell(2))),
    TRIPLET_EIGHTH_REST_EIGHTH(3, listOf(RhythmCell(1), RhythmCell(1, rest = true), RhythmCell(1))),
    SIXTEENTH_DOTTED_EIGHTH(0, listOf(RhythmCell(1), RhythmCell(3))),

    // Rangée 4 : n-olets.
    QUINTUPLET(5, List(5) { RhythmCell(1) }),
    SEPTUPLET(7, List(7) { RhythmCell(1) }),
    SEXTUPLET(6, List(6) { RhythmCell(1) }, beamGroup = 3),
    SEXTUPLET_DUPLE(6, List(6) { RhythmCell(1) }, beamGroup = 2),
    ;

    /** Nombre de sous-temps par temps (somme des durées). */
    val grid: Int get() = cells.sumOf { it.units }

    /** Positions (0-based sur la grille) où un clic doit sonner ; 0 = sur le temps. */
    val onsets: Set<Int>
        get() {
            val set = LinkedHashSet<Int>()
            var pos = 0
            for (c in cells) {
                if (!c.rest) set.add(pos)
                pos += c.units
            }
            return set
        }
}

/** Timbre du clic (synthétisé, voir ClickSynth). */
enum class ClickSound { CLICK, WOOD, BEEP, CLAVE, COWBELL }

/** Loi de variation du tempo pendant l'automation (forme de la courbe tempo → temps). */
enum class VariationLaw {
    LINEAR, // montée régulière
    ACCEL, // lente puis rapide (convexe)
    DECEL, // rapide puis lente (concave)
    SINE, // en S (lente, rapide, lente)
    TRIANGLE, // aller-retour : initial → final → initial
    STEP, // par paliers marqués (escalier)
    ;

    /** Transforme l'avancement p∈[0,1] en part e∈[0,1] du trajet initial→final. */
    fun ease(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return when (this) {
            LINEAR, STEP -> x
            ACCEL -> x * x
            DECEL -> 1f - (1f - x) * (1f - x)
            SINE -> ((1.0 - cos(PI * x)) / 2.0).toFloat()
            TRIANGLE -> if (x < 0.5f) 2f * x else 2f * (1f - x)
        }
    }
}

/**
 * Automation du tempo : va de [startBpm] (tempo initial) à [targetBpm] (tempo final), par paliers
 * de [stepBpm] BPM durant [stepBars] mesures chacun, selon la loi [law]. S'arrête à la fin, ou
 * recommence si [loop].
 */
data class TempoAutomation(
    val enabled: Boolean = false,
    val startBpm: Int = 60,
    val targetBpm: Int = 120,
    val stepBpm: Int = 5,
    val stepBars: Int = 4,
    val loop: Boolean = false,
    val law: VariationLaw = VariationLaw.LINEAR,
)

/** Entraînement : [playBars] mesures audibles puis [muteBars] muettes, en boucle. */
data class SilentMeasures(
    val enabled: Boolean = false,
    val playBars: Int = 1,
    val muteBars: Int = 1,
)

/** Configuration complète du métronome (ce que le moteur doit connaître). */
data class MetronomeConfig(
    val bpm: Int = MetronomeRange.DEFAULT_BPM,
    val beatsPerMeasure: Int = 4,
    val subdivision: Subdivision = Subdivision.QUARTER,
    val sound: ClickSound = ClickSound.CLICK,
    val accentFirst: Boolean = true,
    /** Mesures de décompte avant le départ (0 = aucun). */
    val countInBars: Int = 0,
    val silent: SilentMeasures = SilentMeasures(),
    val automation: TempoAutomation = TempoAutomation(),
) {
    fun sanitized(): MetronomeConfig = copy(
        bpm = bpm.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM),
        beatsPerMeasure = beatsPerMeasure.coerceIn(MetronomeRange.MIN_BEATS, MetronomeRange.MAX_BEATS),
        countInBars = countInBars.coerceIn(0, MetronomeRange.MAX_COUNT_IN),
        silent = silent.copy(
            playBars = silent.playBars.coerceIn(1, MetronomeRange.MAX_SILENT_BARS),
            muteBars = silent.muteBars.coerceIn(1, MetronomeRange.MAX_SILENT_BARS),
        ),
        automation = automation.copy(
            startBpm = automation.startBpm.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM),
            targetBpm = automation.targetBpm.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM),
            stepBpm = automation.stepBpm.coerceIn(1, 60),
            stepBars = automation.stepBars.coerceIn(1, 32),
        ),
    )
}

/** Bornes et valeurs par défaut du métronome. */
object MetronomeRange {
    const val MIN_BPM = 10
    const val MAX_BPM = 300
    const val DEFAULT_BPM = 120
    const val MIN_BEATS = 1
    const val MAX_BEATS = 16
    const val MAX_COUNT_IN = 8
    const val MAX_SILENT_BARS = 16
}

/** Calculs temps réel du métronome, isolés pour être testés. */
object MetronomeMath {

    /**
     * Nombre d'échantillons (fractionnaire) entre deux clics consécutifs, subdivision comprise.
     * Le moteur accumule la partie fractionnaire pour ne jamais dériver.
     */
    fun samplesPerTick(bpm: Int, gridPerBeat: Int, sampleRate: Int): Double {
        val ticksPerMinute = bpm.toDouble() * gridPerBeat
        return sampleRate.toDouble() * 60.0 / ticksPerMinute
    }

    /** BPM estimé à partir des intervalles de taps (ms), ou null si moins de 2 taps. */
    fun bpmFromTaps(tapsMs: List<Long>): Int? {
        if (tapsMs.size < 2) return null
        val span = tapsMs.last() - tapsMs.first()
        if (span <= 0) return null
        val intervals = tapsMs.size - 1
        val bpm = (60_000.0 * intervals / span).roundToInt()
        return bpm.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM)
    }

    /** Nombre total de mesures du trajet d'automation (doublé pour un aller-retour). */
    fun totalBars(a: TempoAutomation): Int {
        val span = abs(a.targetBpm - a.startBpm)
        val steps = if (a.stepBpm <= 0) 1 else max(1, ceil(span.toDouble() / a.stepBpm).toInt())
        val bars = steps * max(1, a.stepBars)
        return if (a.law == VariationLaw.TRIANGLE) bars * 2 else bars
    }

    /** BPM courant de l'automation après [barsElapsed] mesures, quantifié en paliers de [stepBpm]. */
    fun automatedBpm(a: TempoAutomation, barsElapsed: Int): Int {
        if (!a.enabled) return a.startBpm.coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM)
        val total = max(1, totalBars(a))
        val p = (barsElapsed.toFloat() / total).coerceIn(0f, 1f)
        val e = a.law.ease(p)
        val raw = a.startBpm + (a.targetBpm - a.startBpm) * e
        val step = max(1, a.stepBpm)
        val quantized = a.startBpm + Math.round((raw - a.startBpm) / step) * step
        val lo = min(a.startBpm, a.targetBpm)
        val hi = max(a.startBpm, a.targetBpm)
        return quantized.coerceIn(lo, hi).coerceIn(MetronomeRange.MIN_BPM, MetronomeRange.MAX_BPM)
    }

    /** Vrai si la mesure [realMeasure] (0-based, hors décompte) doit être muette. */
    fun isMuted(realMeasure: Int, silent: SilentMeasures): Boolean {
        if (!silent.enabled || realMeasure < 0) return false
        val cycle = silent.playBars + silent.muteBars
        if (cycle <= 0) return false
        return (realMeasure % cycle) >= silent.playBars
    }
}

/** Nom de tempo italien usuel pour un BPM donné. */
fun tempoName(bpm: Int): String = when {
    bpm < 24 -> "Larghissimo"
    bpm < 40 -> "Grave"
    bpm < 60 -> "Largo"
    bpm < 66 -> "Larghetto"
    bpm < 76 -> "Adagio"
    bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"
    bpm < 156 -> "Allegro"
    bpm < 176 -> "Vivace"
    bpm < 200 -> "Presto"
    else -> "Prestissimo"
}

/**
 * Suivi des taps pour le « tap tempo » : garde les derniers taps, repart à zéro après un trou.
 * Non thread-safe : à utiliser depuis un seul fil (le ViewModel).
 */
class TapTempo(
    private val maxGapMs: Long = 2_000L,
    private val maxTaps: Int = 5,
) {
    private val taps = ArrayDeque<Long>()

    /** Enregistre un tap à [nowMs] et renvoie le BPM estimé (null si pas encore mesurable). */
    fun tap(nowMs: Long): Int? {
        if (taps.isNotEmpty() && nowMs - taps.last() > maxGapMs) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > maxTaps) taps.removeFirst()
        return MetronomeMath.bpmFromTaps(taps.toList())
    }

    fun reset() = taps.clear()
}
