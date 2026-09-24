package com.blenouvel.accordeur.model

import kotlin.math.abs

/** Familles de gammes, dans l'ordre d'affichage. */
enum class ScaleCategory(val title: String) {
    MAJOR_MODES("Gamme majeure et ses modes"),
    PENTATONIC_BLUES("Pentatoniques et blues"),
    HARMONIC_MINOR("Mineure harmonique et ses modes"),
    MELODIC_MINOR("Mineure mélodique et ses modes"),
    SYMMETRIC("Gammes symétriques"),
    OTHER("Autres gammes"),
}

/** Étiquettes affichées sur le manche. */
enum class FretLabels { NOTES, DEGREES }

/**
 * Gamme ou mode : intervalles en demi-tons depuis la fondamentale (0 inclus, croissants, < 12)
 * et degrés (« 1 », « ♭3 », « ♯4 »…). Pour les gammes de 7 notes, les degrés sont déduits des
 * intervalles (comparaison avec la gamme majeure) ; sinon ils sont donnés explicitement.
 * Le numéro de degré fixe aussi la lettre de chaque note (orthographe correcte : Si♭ en Fa majeur).
 */
class ScaleType(
    val id: String,
    val name: String,
    val category: ScaleCategory,
    val intervals: List<Int>,
    degrees: List<String>? = null,
) {
    val degrees: List<String> = degrees ?: deriveDegrees(intervals)

    init {
        require(intervals.first() == 0 && intervals.zipWithNext().all { (a, b) -> b > a } && intervals.last() < 12) {
            "intervalles invalides pour $id"
        }
        require(this.degrees.size == intervals.size) { "degrés incohérents pour $id" }
    }

    val size: Int get() = intervals.size

    /** Numéro de degré (1…7) de chaque note : fixe la lettre employée. */
    val degreeNumbers: List<Int> get() = degrees.map { parseDegree(it).first }

    /** Écarts successifs en demi-tons, octave comprise (somme = 12). */
    val steps: List<Int> get() = (intervals + 12).zipWithNext { a, b -> b - a }

    /** Formule en tons : « T T ½T T T T ½T ». */
    val formula: String get() = steps.joinToString(" ") { stepLabel(it) }

    override fun toString() = "ScaleType($id)"

    companion object {
        /** Intervalles de la gamme majeure pour les degrés 1…7. */
        val MAJOR_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

        fun accidentalSymbol(accidental: Int): String = when (accidental) {
            -2 -> "♭♭"
            -1 -> "♭"
            0 -> ""
            1 -> "♯"
            2 -> "♯♯"
            else -> error("altération hors bornes : $accidental")
        }

        /** « ♭3 » → (3, −1) ; « ♯♯4 » → (4, 2). */
        fun parseDegree(label: String): Pair<Int, Int> {
            var accidental = 0
            var i = 0
            while (i < label.length && label[i] in "♭♯") {
                accidental += if (label[i] == '♯') 1 else -1
                i++
            }
            return label.substring(i).toInt() to accidental
        }

        private fun deriveDegrees(intervals: List<Int>): List<String> {
            require(intervals.size == 7) { "degrés explicites requis hors gammes heptatoniques" }
            return intervals.mapIndexed { i, semitones -> accidentalSymbol(semitones - MAJOR_OFFSETS[i]) + (i + 1) }
        }

        private fun stepLabel(semitones: Int): String = when (semitones) {
            1 -> "½T"
            2 -> "T"
            3 -> "1½T"
            4 -> "2T"
            else -> "${semitones}½T"
        }
    }
}

/** Note orthographiée : lettre (0 = Do/C … 6 = Si/B) et altération (−2…+2). */
data class SpelledNote(val letter: Int, val accidental: Int) {
    val pitchClass: Int get() = (NATURAL_PITCH_CLASSES[letter] + accidental).mod(12)

    companion object {
        val NATURAL_PITCH_CLASSES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    }
}

/** Catalogue des gammes et modes proposés. */
object ScaleCatalog {
    private fun s(id: String, name: String, category: ScaleCategory, intervals: String, degrees: String? = null) =
        ScaleType(
            id = id,
            name = name,
            category = category,
            intervals = intervals.trim().split(Regex("\\s+")).map(String::toInt),
            degrees = degrees?.trim()?.split(Regex("\\s+")),
        )

    private val M = ScaleCategory.MAJOR_MODES
    private val P = ScaleCategory.PENTATONIC_BLUES
    private val H = ScaleCategory.HARMONIC_MINOR
    private val J = ScaleCategory.MELODIC_MINOR
    private val Y = ScaleCategory.SYMMETRIC
    private val O = ScaleCategory.OTHER

    val all: List<ScaleType> = listOf(
        // Gamme majeure et ses modes
        s("major", "Majeure (ionien)", M, "0 2 4 5 7 9 11"),
        s("dorian", "Dorien", M, "0 2 3 5 7 9 10"),
        s("phrygian", "Phrygien", M, "0 1 3 5 7 8 10"),
        s("lydian", "Lydien", M, "0 2 4 6 7 9 11"),
        s("mixolydian", "Mixolydien", M, "0 2 4 5 7 9 10"),
        s("minor", "Mineure naturelle (éolien)", M, "0 2 3 5 7 8 10"),
        s("locrian", "Locrien", M, "0 1 3 5 6 8 10"),
        // Pentatoniques et blues
        s("pentatonic_major", "Pentatonique majeure", P, "0 2 4 7 9", "1 2 3 5 6"),
        s("pentatonic_minor", "Pentatonique mineure", P, "0 3 5 7 10", "1 ♭3 4 5 ♭7"),
        s("blues", "Blues (mineure)", P, "0 3 5 6 7 10", "1 ♭3 4 ♭5 5 ♭7"),
        s("blues_major", "Blues majeure", P, "0 2 3 4 7 9", "1 2 ♭3 3 5 6"),
        s("egyptian", "Pentatonique suspendue (égyptienne)", P, "0 2 5 7 10", "1 2 4 5 ♭7"),
        s("hirajoshi", "Hirajoshi (japonaise)", P, "0 2 3 7 8", "1 2 ♭3 5 ♭6"),
        s("in_sen", "In-sen (japonaise)", P, "0 1 5 7 10", "1 ♭2 4 5 ♭7"),
        // Mineure harmonique et ses modes
        s("harmonic_minor", "Mineure harmonique", H, "0 2 3 5 7 8 11"),
        s("locrian_nat6", "Locrien ♮6", H, "0 1 3 5 6 9 10"),
        s("ionian_aug", "Ionien augmenté (♯5)", H, "0 2 4 5 8 9 11"),
        s("dorian_sharp4", "Dorien ♯4 (roumain)", H, "0 2 3 6 7 9 10"),
        s("phrygian_dominant", "Phrygien dominant (espagnole)", H, "0 1 4 5 7 8 10"),
        s("lydian_sharp2", "Lydien ♯2", H, "0 3 4 6 7 9 11"),
        s("ultralocrian", "Superlocrien ♭♭7", H, "0 1 3 4 6 8 9"),
        // Mineure mélodique et ses modes
        s("melodic_minor", "Mineure mélodique (jazz)", J, "0 2 3 5 7 9 11"),
        s("dorian_flat2", "Dorien ♭2", J, "0 1 3 5 7 9 10"),
        s("lydian_aug", "Lydien augmenté (♯5)", J, "0 2 4 6 8 9 11"),
        s("lydian_dominant", "Lydien dominant (♭7)", J, "0 2 4 6 7 9 10"),
        s("mixolydian_flat6", "Mixolydien ♭6", J, "0 2 4 5 7 8 10"),
        s("locrian_nat2", "Locrien ♮2 (semi-diminué)", J, "0 2 3 5 6 8 10"),
        s("altered", "Altérée (superlocrien)", J, "0 1 3 4 6 8 10"),
        // Gammes symétriques
        s("whole_tone", "Par tons", Y, "0 2 4 6 8 10", "1 2 3 ♯4 ♯5 ♭7"),
        s("diminished_wh", "Diminuée ton / demi-ton", Y, "0 2 3 5 6 8 9 11", "1 2 ♭3 4 ♭5 ♭6 6 7"),
        s("diminished_hw", "Diminuée demi-ton / ton", Y, "0 1 3 4 6 7 9 10", "1 ♭2 ♯2 3 ♯4 5 6 ♭7"),
        s("chromatic", "Chromatique", Y, "0 1 2 3 4 5 6 7 8 9 10 11", "1 ♭2 2 ♭3 3 4 ♭5 5 ♭6 6 ♭7 7"),
        // Autres gammes
        s("harmonic_major", "Majeure harmonique", O, "0 2 4 5 7 8 11"),
        s("double_harmonic", "Double harmonique (byzantine)", O, "0 1 4 5 7 8 11"),
        s("hungarian_minor", "Mineure hongroise (tzigane)", O, "0 2 3 6 7 8 11"),
        s("neapolitan_minor", "Napolitaine mineure", O, "0 1 3 5 7 8 11"),
        s("neapolitan_major", "Napolitaine majeure", O, "0 1 3 5 7 9 11"),
        s("persian", "Persane", O, "0 1 4 5 6 8 11"),
        s("enigmatic", "Énigmatique", O, "0 1 4 6 8 10 11"),
        s("bebop_dominant", "Bebop dominante", O, "0 2 4 5 7 9 10 11", "1 2 3 4 5 6 ♭7 7"),
        s("bebop_major", "Bebop majeure", O, "0 2 4 5 7 8 9 11", "1 2 3 4 5 ♭6 6 7"),
    )

    val DEFAULT: ScaleType = all.first()

    fun byId(id: String?): ScaleType = all.firstOrNull { it.id == id } ?: DEFAULT

    val byCategory: Map<ScaleCategory, List<ScaleType>> =
        ScaleCategory.entries.associateWith { c -> all.filter { it.category == c } }
}

/**
 * Orthographe d'une gamme : chaque degré n utilise la lettre (fondamentale + n − 1), l'altération
 * découle de la hauteur. Pour une fondamentale sur touche noire, on retient l'enharmonie qui
 * donne le moins d'altérations (Ré♭ majeur plutôt que Do♯ majeur, Do♯ mineur plutôt que Ré♭ mineur).
 */
object ScaleSpeller {
    /** Notes de la gamme [scale] sur la fondamentale [rootPitchClass], fondamentale en premier. */
    fun spell(rootPitchClass: Int, scale: ScaleType): List<SpelledNote> {
        val root = rootPitchClass.mod(12)
        return rootCandidates(root)
            .map { candidate -> spellFrom(candidate, root, scale) }
            .minWith(compareBy<List<SpelledNote>> { cost(it) }.thenBy { if (it.first().accidental < 0) 1 else 0 })
    }

    /** Orthographe de la fondamentale seule (dans le contexte de la gamme). */
    fun root(rootPitchClass: Int, scale: ScaleType): SpelledNote = spell(rootPitchClass, scale).first()

    private fun rootCandidates(pitchClass: Int): List<SpelledNote> {
        val natural = SpelledNote.NATURAL_PITCH_CLASSES.indexOf(pitchClass)
        if (natural >= 0) return listOf(SpelledNote(natural, 0))
        val below = SpelledNote.NATURAL_PITCH_CLASSES.indexOf((pitchClass - 1).mod(12))
        val above = SpelledNote.NATURAL_PITCH_CLASSES.indexOf((pitchClass + 1).mod(12))
        return listOf(SpelledNote(below, 1), SpelledNote(above, -1))
    }

    private fun spellFrom(root: SpelledNote, rootPitchClass: Int, scale: ScaleType): List<SpelledNote> =
        scale.intervals.zip(scale.degreeNumbers) { semitones, degree ->
            val letter = (root.letter + degree - 1).mod(7)
            val target = (rootPitchClass + semitones).mod(12)
            var accidental = (target - SpelledNote.NATURAL_PITCH_CLASSES[letter]).mod(12)
            if (accidental > 6) accidental -= 12
            if (abs(accidental) > 2) fallback(target) else SpelledNote(letter, accidental)
        }

    /** Orthographe simple (dièse) pour un cas pathologique (altération > 2). */
    private fun fallback(pitchClass: Int): SpelledNote {
        val natural = SpelledNote.NATURAL_PITCH_CLASSES.indexOf(pitchClass)
        return if (natural >= 0) SpelledNote(natural, 0)
        else SpelledNote(SpelledNote.NATURAL_PITCH_CLASSES.indexOf(pitchClass - 1), 1)
    }

    /** Nombre total d'altérations, les doubles altérations étant fortement pénalisées. */
    private fun cost(notes: List<SpelledNote>): Int = notes.sumOf { abs(it.accidental) + if (abs(it.accidental) == 2) 4 else 0 }
}

/** Position d'une note de la gamme sur le manche. */
data class FretNote(
    /** Indice de la corde (0 = la plus grave). */
    val string: Int,
    /** Case (0 = à vide). */
    val fret: Int,
    /** Indice du degré dans la gamme (0 = fondamentale). */
    val degree: Int,
    /** Hauteur MIDI de la note jouée. */
    val midi: Int,
)

/** Notes de la gamme sur tout le manche. */
object FretboardMap {
    fun notes(tuning: Tuning, rootPitchClass: Int, scale: ScaleType, frets: Int): List<FretNote> {
        val degreeOf = IntArray(12) { -1 }
        scale.intervals.forEachIndexed { index, semitones -> degreeOf[(rootPitchClass + semitones).mod(12)] = index }
        val result = ArrayList<FretNote>()
        tuning.strings.forEachIndexed { s, string ->
            for (fret in 0..frets) {
                val midi = string.midi + fret
                val degree = degreeOf[midi.mod(12)]
                if (degree >= 0) result += FretNote(s, fret, degree, midi)
            }
        }
        return result
    }

    /** Nombres de cases proposés. */
    val FRET_COUNTS = listOf(12, 15, 22)

    /** Cases marquées d'un repère (double repère à 12 et 24). */
    val INLAYS = setOf(3, 5, 7, 9, 12, 15, 17, 19, 21, 24)
}
