package com.blenouvel.accordeur.model

/**
 * Note chromatique stockée de façon neutre : classe de hauteur (0 = Do/C … 11 = Si/B)
 * + octave en notation scientifique (La4 = 440 Hz, Mi2 = corde grave de la guitare).
 * Le libellé affiché (FR/EN) est calculé à part par [NoteNames].
 */
data class Note(val pitchClass: Int, val octave: Int) {
    init {
        require(pitchClass in 0..11) { "pitchClass hors bornes : $pitchClass" }
    }

    /** Numéro MIDI (La4 = 69, Do4 = 60). */
    val midi: Int get() = (octave + 1) * 12 + pitchClass

    fun transpose(semitones: Int): Note = fromMidi(midi + semitones)

    companion object {
        fun fromMidi(midi: Int): Note = Note(midi.mod(12), Math.floorDiv(midi, 12) - 1)

        private val EN_LETTERS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        /** Analyse une note anglo-saxonne : « E2 », « G#1 », « G♯1 », « Bb3 », « B♭3 ». */
        fun parse(text: String): Note {
            val s = text.trim()
            require(s.isNotEmpty()) { "note vide" }
            var pc = EN_LETTERS[s[0].uppercaseChar()] ?: throw IllegalArgumentException("note invalide : $text")
            var i = 1
            while (i < s.length && s[i] in "#♯b♭") {
                pc += if (s[i] == '#' || s[i] == '♯') 1 else -1
                i++
            }
            val octave = s.substring(i).toIntOrNull() ?: throw IllegalArgumentException("octave invalide : $text")
            // Un altéré qui franchit la frontière Si/Do change d'octave (ex. B#3 = C4).
            return fromMidi((octave + 1) * 12 + pc)
        }
    }
}

/** Corde d'un accordage ; la fréquence est toujours dérivée du La de référence. */
data class GuitarString(val pitchClass: Int, val octave: Int, val label: String? = null) {
    init {
        require(pitchClass in 0..11) { "pitchClass hors bornes : $pitchClass" }
    }

    val note: Note get() = Note(pitchClass, octave)
    val midi: Int get() = note.midi

    fun frequency(a4: Double = NoteMapper.DEFAULT_A4): Double = NoteMapper.frequencyOf(midi, a4)

    companion object {
        fun of(note: Note, label: String? = null) = GuitarString(note.pitchClass, note.octave, label)
        fun parse(text: String) = of(Note.parse(text))
    }
}

/** Accordage : cordes de la plus grave à la plus aiguë. */
data class Tuning(
    val id: String,
    val name: String,
    val strings: List<GuitarString>,
    val isCustom: Boolean = false,
) {
    init {
        require(strings.size in MIN_STRINGS..MAX_STRINGS) { "nombre de cordes invalide : ${strings.size}" }
    }

    val stringCount: Int get() = strings.size

    fun frequencies(a4: Double = NoteMapper.DEFAULT_A4): DoubleArray =
        DoubleArray(strings.size) { strings[it].frequency(a4) }

    companion object {
        const val MIN_STRINGS = 4
        const val MAX_STRINGS = 8
    }
}

/** Accordages fournis. Les 5 premiers sont ceux exigés par la spécification. */
object Presets {
    private fun tuning(id: String, name: String, notes: String) =
        Tuning(id, name, notes.trim().split(Regex("\\s+")).map(GuitarString::parse))

    // 6 cordes
    val STANDARD_6 = tuning("std6", "Standard", "E2 A2 D3 G3 B3 E4")
    val DROP_D_6 = tuning("dropd6", "Drop D", "D2 A2 D3 G3 B3 E4")

    // 7 cordes
    val STANDARD_7 = tuning("std7", "Standard", "B1 E2 A2 D3 G3 B3 E4")
    val DROP_A_7 = tuning("dropa7", "Drop A", "A1 E2 A2 D3 G3 B3 E4")

    /** G#D#G#C#F#A#D# : cordes 6→1 en Mi♭ standard, 7e corde droppée à G♯1 ≈ 51,91 Hz. */
    val DROP_G_SHARP_7 = tuning("dropgs7", "Drop G♯", "G#1 D#2 G#2 C#3 F#3 A#3 D#4")

    /** Les 5 accordages exigés (§6). */
    val required: List<Tuning> = listOf(STANDARD_6, DROP_D_6, STANDARD_7, DROP_A_7, DROP_G_SHARP_7)

    /** Accordages supplémentaires courants (même modèle, faciles à étendre). */
    val extras: List<Tuning> = listOf(
        tuning("eb6", "Mi♭ standard", "D#2 G#2 C#3 F#3 A#3 D#4"),
        tuning("d6", "Ré standard", "D2 G2 C3 F3 A3 D4"),
        tuning("c6", "Do standard", "C2 F2 A#2 D#3 G3 C4"),
        tuning("b6", "Si standard", "B1 E2 A2 D3 F#3 B3"),
        tuning("dropcs6", "Drop C♯", "C#2 G#2 C#3 F#3 A#3 D#4"),
        tuning("dropc6", "Drop C", "C2 G2 C3 F3 A3 D4"),
        tuning("dropb6", "Drop B", "B1 F#2 B2 E3 G#3 C#4"),
        tuning("doubledropd6", "Double Drop D", "D2 A2 D3 G3 B3 D4"),
        tuning("dadgad6", "DADGAD", "D2 A2 D3 G3 A3 D4"),
        tuning("openg6", "Open G", "D2 G2 D3 G3 B3 D4"),
        tuning("opend6", "Open D", "D2 A2 D3 F#3 A3 D4"),
        tuning("opene6", "Open E", "E2 B2 E3 G#3 B3 E4"),
        tuning("opena6", "Open A", "E2 A2 E3 A3 C#4 E4"),
        tuning("openc6", "Open C", "C2 G2 C3 G3 C4 E4"),
        tuning("eb7", "Mi♭ standard", "A#1 D#2 G#2 C#3 F#3 A#3 D#4"),
        tuning("a7", "La standard", "A1 D2 G2 C3 F3 A3 D4"),
        tuning("dropg7", "Drop G", "G1 D2 G2 C3 F3 A3 D4"),
    )

    val all: List<Tuning> = required + extras

    val DEFAULT: Tuning = STANDARD_6

    fun byId(id: String): Tuning? = all.firstOrNull { it.id == id }
}

/**
 * Sérialisation texte des accordages personnalisés (une ligne par accordage :
 * `id|nom|midi,midi,…`). Volontairement simple : pas de dépendance JSON.
 */
object CustomTuningCodec {
    const val ID_PREFIX = "custom-"

    fun encode(tunings: List<Tuning>): String = tunings.joinToString("\n") { t ->
        val name = sanitize(t.name)
        "${t.id}|$name|${t.strings.joinToString(",") { it.midi.toString() }}"
    }

    fun decode(text: String?): List<Tuning> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val midis = parts[2].split(',').mapNotNull { it.trim().toIntOrNull() }
            if (midis.size !in Tuning.MIN_STRINGS..Tuning.MAX_STRINGS) return@mapNotNull null
            runCatching {
                Tuning(
                    id = parts[0],
                    name = parts[1].ifBlank { "Personnalisé" },
                    strings = midis.map { GuitarString.of(Note.fromMidi(it)) },
                    isCustom = true,
                )
            }.getOrNull()
        }.toList()
    }

    fun newId(nowMillis: Long): String = ID_PREFIX + nowMillis.toString(36)

    fun sanitize(name: String): String =
        name.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim().take(40)
}
