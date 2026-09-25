package com.blenouvel.accordeur.model

import kotlin.math.abs

/**
 * Fonction d'une note dans un accord : intervalle depuis la fondamentale (demi-tons), numéro de
 * degré (fixe la lettre de la note : la tierce de La est un Do, jamais un Si♯) et nom français.
 */
enum class ChordDegree(val semitones: Int, val number: Int, val label: String) {
    ROOT(0, 1, "Fondamentale"),
    FLAT_NINTH(1, 9, "Neuvième mineure"),
    SECOND(2, 2, "Seconde"),
    NINTH(2, 9, "Neuvième"),
    SHARP_NINTH(3, 9, "Neuvième augmentée"),
    MINOR_THIRD(3, 3, "Tierce mineure"),
    MAJOR_THIRD(4, 3, "Tierce majeure"),
    FOURTH(5, 4, "Quarte"),
    ELEVENTH(5, 11, "Onzième"),
    FLAT_FIFTH(6, 5, "Quinte diminuée"),
    SHARP_ELEVENTH(6, 11, "Onzième augmentée"),
    FIFTH(7, 5, "Quinte"),
    SHARP_FIFTH(8, 5, "Quinte augmentée"),
    FLAT_THIRTEENTH(8, 13, "Treizième mineure"),
    SIXTH(9, 6, "Sixte"),
    DIMINISHED_SEVENTH(9, 7, "Septième diminuée"),
    THIRTEENTH(9, 13, "Treizième"),
    MINOR_SEVENTH(10, 7, "Septième mineure"),
    MAJOR_SEVENTH(11, 7, "Septième majeure"),
}

/** Note d'un accord, orthographiée selon son degré. */
data class ChordTone(val degree: ChordDegree, val note: SpelledNote)

/**
 * Accord nommé : fondamentale, suffixe (« m7 », « 7sus4 »…), basse si ce n'est pas la
 * fondamentale (accord renversé : « C/E »), notes par degré croissant.
 */
data class Chord(
    val root: SpelledNote,
    val suffix: String,
    val bass: SpelledNote?,
    val tones: List<ChordTone>,
) {
    /** Symbole en notation anglaise, la plus répandue pour les accords : « Am7 », « F♯m7♭5 », « C/E ». */
    val symbol: String get() = ChordNamer.english(root) + suffix + (bass?.let { "/" + ChordNamer.english(it) } ?: "")
}

/**
 * Nomme un ensemble de notes jouées ensemble. Chaque note présente est essayée comme
 * fondamentale ; on garde le nom le plus simple (table de formules, du plus courant au plus
 * rare), la basse servant de fondamentale à égalité et un renversement (« /basse ») coûtant cher.
 * Deux notes ne font un accord que si elles forment une quinte (« A5 ») : sinon c'est un intervalle.
 */
object ChordNamer {
    private class Formula(
        val suffix: String,
        val cost: Double,
        val required: List<ChordDegree>,
        val optional: List<ChordDegree> = emptyList(),
    )

    private val R = ChordDegree.ROOT
    private val M3 = ChordDegree.MAJOR_THIRD
    private val m3 = ChordDegree.MINOR_THIRD
    private val P5 = ChordDegree.FIFTH

    private val FORMULAS = listOf(
        Formula("", 0.0, listOf(R, M3, P5)),
        Formula("m", 0.1, listOf(R, m3, P5)),
        Formula("5", 0.3, listOf(R, P5)),
        Formula("sus4", 1.0, listOf(R, ChordDegree.FOURTH, P5)),
        Formula("sus2", 1.1, listOf(R, ChordDegree.SECOND, P5)),
        Formula("dim", 1.5, listOf(R, m3, ChordDegree.FLAT_FIFTH)),
        Formula("aug", 1.5, listOf(R, M3, ChordDegree.SHARP_FIFTH)),
        Formula("6", 1.0, listOf(R, M3, ChordDegree.SIXTH), listOf(P5)),
        Formula("m6", 1.1, listOf(R, m3, ChordDegree.SIXTH), listOf(P5)),
        Formula("6/9", 1.8, listOf(R, M3, ChordDegree.SIXTH, ChordDegree.NINTH), listOf(P5)),
        Formula("m6/9", 1.9, listOf(R, m3, ChordDegree.SIXTH, ChordDegree.NINTH), listOf(P5)),
        Formula("7", 1.0, listOf(R, M3, ChordDegree.MINOR_SEVENTH), listOf(P5)),
        Formula("maj7", 1.0, listOf(R, M3, ChordDegree.MAJOR_SEVENTH), listOf(P5)),
        Formula("m7", 1.0, listOf(R, m3, ChordDegree.MINOR_SEVENTH), listOf(P5)),
        Formula("m(maj7)", 2.0, listOf(R, m3, ChordDegree.MAJOR_SEVENTH), listOf(P5)),
        Formula("m7♭5", 1.6, listOf(R, m3, ChordDegree.FLAT_FIFTH, ChordDegree.MINOR_SEVENTH)),
        Formula("dim7", 1.6, listOf(R, m3, ChordDegree.FLAT_FIFTH, ChordDegree.DIMINISHED_SEVENTH)),
        Formula("7♭5", 2.5, listOf(R, M3, ChordDegree.FLAT_FIFTH, ChordDegree.MINOR_SEVENTH)),
        Formula("7♯5", 2.3, listOf(R, M3, ChordDegree.SHARP_FIFTH, ChordDegree.MINOR_SEVENTH)),
        Formula("maj7♯5", 2.6, listOf(R, M3, ChordDegree.SHARP_FIFTH, ChordDegree.MAJOR_SEVENTH)),
        Formula("7sus4", 1.6, listOf(R, ChordDegree.FOURTH, ChordDegree.MINOR_SEVENTH), listOf(P5)),
        Formula("7sus2", 2.0, listOf(R, ChordDegree.SECOND, ChordDegree.MINOR_SEVENTH), listOf(P5)),
        Formula("9", 1.6, listOf(R, M3, ChordDegree.MINOR_SEVENTH, ChordDegree.NINTH), listOf(P5)),
        Formula("maj9", 1.7, listOf(R, M3, ChordDegree.MAJOR_SEVENTH, ChordDegree.NINTH), listOf(P5)),
        Formula("m9", 1.7, listOf(R, m3, ChordDegree.MINOR_SEVENTH, ChordDegree.NINTH), listOf(P5)),
        Formula("add9", 1.3, listOf(R, M3, ChordDegree.NINTH), listOf(P5)),
        Formula("m(add9)", 1.4, listOf(R, m3, ChordDegree.NINTH), listOf(P5)),
        Formula("7♭9", 2.4, listOf(R, M3, ChordDegree.MINOR_SEVENTH, ChordDegree.FLAT_NINTH), listOf(P5)),
        Formula("7♯9", 2.4, listOf(R, M3, ChordDegree.MINOR_SEVENTH, ChordDegree.SHARP_NINTH), listOf(P5)),
        Formula("11", 2.0, listOf(R, ChordDegree.NINTH, ChordDegree.ELEVENTH, ChordDegree.MINOR_SEVENTH), listOf(P5)),
        Formula("m11", 2.0, listOf(R, m3, ChordDegree.ELEVENTH, ChordDegree.MINOR_SEVENTH), listOf(P5, ChordDegree.NINTH)),
        Formula("add11", 2.0, listOf(R, M3, ChordDegree.ELEVENTH), listOf(P5)),
        Formula("m(add11)", 2.0, listOf(R, m3, ChordDegree.ELEVENTH), listOf(P5)),
        Formula("7♯11", 2.5, listOf(R, M3, P5, ChordDegree.SHARP_ELEVENTH, ChordDegree.MINOR_SEVENTH), listOf(ChordDegree.NINTH)),
        Formula("maj7♯11", 2.4, listOf(R, M3, P5, ChordDegree.SHARP_ELEVENTH, ChordDegree.MAJOR_SEVENTH), listOf(ChordDegree.NINTH)),
        Formula("13", 2.0, listOf(R, M3, ChordDegree.MINOR_SEVENTH, ChordDegree.THIRTEENTH), listOf(P5, ChordDegree.NINTH)),
        Formula("maj13", 2.2, listOf(R, M3, ChordDegree.MAJOR_SEVENTH, ChordDegree.THIRTEENTH), listOf(P5, ChordDegree.NINTH)),
        Formula("m13", 2.2, listOf(R, m3, ChordDegree.MINOR_SEVENTH, ChordDegree.THIRTEENTH), listOf(P5, ChordDegree.NINTH)),
        Formula("7♭13", 2.6, listOf(R, M3, P5, ChordDegree.FLAT_THIRTEENTH, ChordDegree.MINOR_SEVENTH)),
    )

    /** Pénalité par note facultative absente (la quinte, souvent omise à la guitare). */
    private const val MISSING_OPTIONAL = 0.2

    /** Pénalité d'un renversement (fondamentale ailleurs qu'à la basse). */
    private const val INVERSION = 2.5

    /**
     * Accord formé par les classes de hauteur [pitchClasses] (0 = Do … 11 = Si), la plus grave
     * étant [bass]. Null s'il n'y a pas d'accord connu (une seule note, intervalle, amas).
     */
    fun name(pitchClasses: Set<Int>, bass: Int): Chord? {
        if (pitchClasses.size < 2 || bass !in pitchClasses) return null
        var best: Formula? = null
        var bestRoot = -1
        var bestCost = Double.MAX_VALUE
        // La basse d'abord : à égalité, c'est elle la fondamentale.
        for (root in listOf(bass) + pitchClasses.filter { it != bass }.sorted()) {
            val intervals = pitchClasses.map { (it - root).mod(12) }.toSet()
            for (formula in FORMULAS) {
                val required = formula.required.map { it.semitones }
                if (!intervals.containsAll(required)) continue
                val allowed = required + formula.optional.map { it.semitones }
                if (!allowed.containsAll(intervals)) continue
                val missing = formula.optional.count { it.semitones !in intervals }
                val cost = formula.cost + MISSING_OPTIONAL * missing + if (root != bass) INVERSION else 0.0
                if (cost < bestCost - 1e-9) {
                    best = formula
                    bestRoot = root
                    bestCost = cost
                }
            }
        }
        val formula = best ?: return null
        val degrees = (formula.required + formula.optional).filter { (bestRoot + it.semitones).mod(12) in pitchClasses }
        return spell(bestRoot, formula.suffix, degrees, bass)
    }

    /**
     * Orthographe : la fondamentale d'une touche noire prend l'enharmonie qui donne le moins
     * d'altérations à l'accord (Mi♭ majeur plutôt que Ré♯ majeur, Sol♯ mineur plutôt que La♭
     * mineur) ; chaque autre note prend la lettre de son degré.
     */
    private fun spell(root: Int, suffix: String, degrees: List<ChordDegree>, bass: Int): Chord {
        val sorted = degrees.sortedWith(compareBy<ChordDegree> { it.number }.thenBy { it.semitones })
        val options = rootSpellings(root).map { spelledRoot ->
            sorted.map { degree -> ChordTone(degree, toneOf(spelledRoot, root, degree)) }
        }
        val tones = options.minWith(
            compareBy<List<ChordTone>> { tones -> tones.sumOf { cost(it.note) } }
                .thenBy { if (it.first().note.accidental < 0) 1 else 0 },
        )
        val rootNote = tones.first().note
        val bassNote = if (bass == root) null else tones.first { it.note.pitchClass == bass }.note
        return Chord(rootNote, suffix, bassNote, tones)
    }

    private fun toneOf(root: SpelledNote, rootPitchClass: Int, degree: ChordDegree): SpelledNote {
        val letter = (root.letter + degree.number - 1).mod(7)
        val target = (rootPitchClass + degree.semitones).mod(12)
        var accidental = (target - SpelledNote.NATURAL_PITCH_CLASSES[letter]).mod(12)
        if (accidental > 6) accidental -= 12
        return SpelledNote(letter, accidental.coerceIn(-2, 2))
    }

    private fun cost(note: SpelledNote): Int = abs(note.accidental) + if (abs(note.accidental) == 2) 4 else 0

    private fun rootSpellings(pitchClass: Int): List<SpelledNote> {
        val natural = SpelledNote.NATURAL_PITCH_CLASSES.indexOf(pitchClass)
        if (natural >= 0) return listOf(SpelledNote(natural, 0))
        val below = SpelledNote.NATURAL_PITCH_CLASSES.indexOf((pitchClass - 1).mod(12))
        val above = SpelledNote.NATURAL_PITCH_CLASSES.indexOf((pitchClass + 1).mod(12))
        return listOf(SpelledNote(below, 1), SpelledNote(above, -1))
    }

    private val LETTERS = arrayOf("C", "D", "E", "F", "G", "A", "B")

    /** Nom anglais d'une note orthographiée : « F♯ », « B♭ ». */
    fun english(note: SpelledNote): String = LETTERS[note.letter] + ScaleType.accidentalSymbol(note.accidental)

    /** Nom d'un intervalle simple (demi-tons, 1…11), pour deux notes qui ne forment pas d'accord. */
    fun intervalName(semitones: Int): String = INTERVALS[semitones.mod(12)]

    private val INTERVALS = arrayOf(
        "Unisson", "Seconde mineure", "Seconde majeure", "Tierce mineure", "Tierce majeure", "Quarte",
        "Triton", "Quinte", "Sixte mineure", "Sixte majeure", "Septième mineure", "Septième majeure",
    )
}

/**
 * Ce qui sonne : notes distinctes, de la plus grave à la plus aiguë, et l'accord qu'elles forment
 * (null pour une note seule, ses octaves, ou un intervalle qui n'est pas une quinte).
 */
data class Harmony(val notes: List<Note>, val chord: Chord?) {
    /** Classes de hauteur présentes. */
    val pitchClasses: Set<Int> get() = notes.mapTo(LinkedHashSet()) { it.pitchClass }

    /** Une seule note (éventuellement doublée à l'octave). */
    val single: Boolean get() = pitchClasses.size == 1

    /** Identité pour la stabilité de l'affichage : même accord (ou mêmes notes) = même clé. */
    val key: String get() = chord?.symbol ?: notes.joinToString(" ") { it.midi.toString() }

    companion object {
        fun of(notes: List<Note>): Harmony? {
            if (notes.isEmpty()) return null
            val sorted = notes.distinctBy { it.midi }.sortedBy { it.midi }
            val pitchClasses = sorted.mapTo(LinkedHashSet()) { it.pitchClass }
            val chord = if (pitchClasses.size >= 2) ChordNamer.name(pitchClasses, sorted.first().pitchClass) else null
            return Harmony(sorted, chord)
        }
    }
}
