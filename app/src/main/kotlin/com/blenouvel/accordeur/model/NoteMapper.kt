package com.blenouvel.accordeur.model

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Notation des noms de notes. */
enum class Notation { FRENCH, ENGLISH, BOTH }

/** Note la plus proche d'une fréquence et écart en cents (∈ [-50, +50]). */
data class NoteReading(val note: Note, val cents: Double)

/**
 * Conversions fréquence ↔ MIDI ↔ note ↔ cents, avec La de référence réglable.
 * Fonctions pures, sans allocation (hors [nearest]).
 */
object NoteMapper {
    const val DEFAULT_A4 = 440.0
    const val MIN_A4 = 430.0
    const val MAX_A4 = 450.0

    /** Hauteur MIDI continue : 69 + 12·log2(f / a4). */
    fun midiOf(frequency: Double, a4: Double = DEFAULT_A4): Double = 69.0 + 12.0 * log2(frequency / a4)

    /** Fréquence d'une hauteur MIDI (éventuellement fractionnaire). */
    fun frequencyOf(midi: Double, a4: Double = DEFAULT_A4): Double = a4 * 2.0.pow((midi - 69.0) / 12.0)

    fun frequencyOf(midi: Int, a4: Double = DEFAULT_A4): Double = frequencyOf(midi.toDouble(), a4)

    fun frequencyOf(note: Note, a4: Double = DEFAULT_A4): Double = frequencyOf(note.midi, a4)

    /** Écart en cents de [frequency] par rapport à [reference] : 1200·log2(f / fRef). */
    fun cents(frequency: Double, reference: Double): Double = 1200.0 * log2(frequency / reference)

    /** Note chromatique la plus proche (mode auto) et écart en cents. */
    fun nearest(frequency: Double, a4: Double = DEFAULT_A4): NoteReading {
        val midi = midiOf(frequency, a4)
        val nearest = midi.roundToInt()
        return NoteReading(Note.fromMidi(nearest), (midi - nearest) * 100.0)
    }

    /** Écart en cents par rapport à une note cible (mode manuel : corde verrouillée). */
    fun centsFrom(frequency: Double, target: Note, a4: Double = DEFAULT_A4): Double =
        cents(frequency, frequencyOf(target, a4))
}

/** Libellés de notes en français (Do Ré Mi…) ou en anglais (C D E…). */
object NoteNames {
    private val ENGLISH = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val FRENCH = arrayOf("Do", "Do♯", "Ré", "Ré♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si")

    fun english(pitchClass: Int): String = ENGLISH[pitchClass]

    fun french(pitchClass: Int): String = FRENCH[pitchClass]

    /** Nom principal : français pour FRENCH et BOTH, anglais pour ENGLISH. */
    fun primary(pitchClass: Int, notation: Notation): String =
        if (notation == Notation.ENGLISH) english(pitchClass) else french(pitchClass)

    /** Nom secondaire (anglais) affiché en petit quand la notation est BOTH, sinon null. */
    fun secondary(pitchClass: Int, notation: Notation): String? =
        if (notation == Notation.BOTH) english(pitchClass) else null

    /** Libellé complet avec octave, ex. « Mi2 », « E2 » ou « Mi2 / E2 ». */
    fun label(note: Note, notation: Notation): String = when (notation) {
        Notation.FRENCH -> french(note.pitchClass) + note.octave
        Notation.ENGLISH -> english(note.pitchClass) + note.octave
        Notation.BOTH -> "${french(note.pitchClass)}${note.octave} / ${english(note.pitchClass)}${note.octave}"
    }

    private val FRENCH_LETTERS = arrayOf("Do", "Ré", "Mi", "Fa", "Sol", "La", "Si")
    private val ENGLISH_LETTERS = arrayOf("C", "D", "E", "F", "G", "A", "B")

    /** Note orthographiée (gammes) : « Si♭ », « Fa♯♯ », « B♭ ». BOTH → nom français. */
    fun spelled(note: SpelledNote, notation: Notation): String {
        val letters = if (notation == Notation.ENGLISH) ENGLISH_LETTERS else FRENCH_LETTERS
        return letters[note.letter] + ScaleType.accidentalSymbol(note.accidental)
    }

    /** Suite des notes d'un accordage sans octave, ex. « Mi La Ré Sol Si Mi ». */
    fun sequence(tuning: Tuning, notation: Notation): String =
        tuning.strings.joinToString(" ") { primary(it.pitchClass, notation) }
}
