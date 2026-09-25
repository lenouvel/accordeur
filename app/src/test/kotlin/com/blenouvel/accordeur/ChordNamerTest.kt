package com.blenouvel.accordeur

import com.blenouvel.accordeur.model.ChordDegree
import com.blenouvel.accordeur.model.ChordNamer
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nom des accords (notation anglaise) et décomposition par degrés, orthographe comprise. */
class ChordNamerTest {
    /** Nom de l'accord formé par des notes (la première est la basse), ex. « A2 D3 E3 G3 ». */
    private fun symbol(notes: String): String? {
        val parsed = notes.split(' ').map { Note.parse(it) }
        return ChordNamer.name(parsed.map { it.pitchClass }.toSet(), parsed.minBy { it.midi }.pitchClass)?.symbol
    }

    @Test
    fun commonGuitarChords() {
        val cases = mapOf(
            "E2 B2 E3 G#3 B3 E4" to "E",
            "A2 E3 A3 C#4 E4" to "A",
            "D3 A3 D4 F#4" to "D",
            "G2 B2 D3 G3 B3 G4" to "G",
            "C3 E3 G3 C4 E4" to "C",
            "A2 E3 A3 C4 E4" to "Am",
            "E2 B2 E3 G3 B3 E4" to "Em",
            "D3 A3 D4 F4" to "Dm",
            "E2 B2 D3 G#3 B3 E4" to "E7",
            "A2 E3 G3 C#4 E4" to "A7",
            "D3 A3 C4 F#4" to "D7",
            "G2 B2 D3 G3 B3 F4" to "G7",
            "C3 E3 G3 B3 E4" to "Cmaj7",
            "A2 E3 G3 C4 E4" to "Am7",
            "D3 A3 C4 F4" to "Dm7",
            "F2 C3 E3 A3 C4 F4" to "Fmaj7",
            "B2 F#3 B3 D4 F#4" to "Bm",
            "F2 C3 F3 A3 C4 F4" to "F",
            "E2 B2 E3" to "E5",
            "A2 E3 A3" to "A5",
            "D3 A3 D4 G4" to "Dsus4",
            "A2 E3 A3 B3 E4" to "Asus2",
            "C3 E3 G3 D4 E4" to "Cadd9",
            "B2 F3 A3 D4" to "Bm7♭5",
            "C3 D#3 F#3 A3" to "Cdim7",
            "C3 E3 A#3 D#4" to "C7♯9",
            "G#2 B2 D#3 G#3" to "G♯m",
            "D#3 G3 A#3 D#4" to "E♭",
            "A#2 F3 A#3 D4 F4" to "B♭",
            "F#2 C#3 F#3 A#3 C#4 F#4" to "F♯",
            "C#3 G#3 C#4 E4" to "C♯m",
        )
        for ((notes, expected) in cases) assertEquals(notes, expected, symbol(notes))
    }

    @Test
    fun inversionsKeepTheSimplestName() {
        assertEquals("C/E", symbol("E2 C3 G3 C4 E4"))
        assertEquals("D/F♯", symbol("F#2 A2 D3 A3 D4"))
        assertEquals("C6", symbol("C3 E3 A3 G4"))
        assertEquals("Am7", symbol("A2 C3 E3 G3"))
    }

    @Test
    fun openStringsOfCommonTunings() {
        assertEquals("Em11", symbol("E2 A2 D3 G3 B3 E4"))
        // Accordage de la 7-cordes en drop G♯.
        assertEquals("G♯11", symbol("G#1 D#2 G#2 C#3 F#3 A#3 D#4"))
    }

    @Test
    fun decompositionSpellsEachDegree() {
        // Exemple de la demande : La (Fondamentale), Ré (Quarte), Mi (Quinte), Sol (Septième mineure).
        val chord = ChordNamer.name(setOf(9, 2, 4, 7), 9)!!
        assertEquals("A7sus4", chord.symbol)
        assertEquals(
            listOf("La" to "Fondamentale", "Ré" to "Quarte", "Mi" to "Quinte", "Sol" to "Septième mineure"),
            chord.tones.map { NoteNames.spelled(it.note, Notation.FRENCH) to it.degree.label },
        )
        // Tierce mineure de Do : Mi♭, pas Ré♯ ; septième diminuée : Si♭♭.
        val cm = ChordNamer.name(setOf(0, 3, 6, 9), 0)!!
        assertEquals(listOf("Do", "Mi♭", "Sol♭", "Si♭♭"), cm.tones.map { NoteNames.spelled(it.note, Notation.FRENCH) })
        assertEquals(ChordDegree.DIMINISHED_SEVENTH, cm.tones.last().degree)
        // Neuvième augmentée de Do : Ré♯.
        val hendrix = ChordNamer.name(setOf(0, 4, 7, 10, 3), 0)!!
        assertEquals("Ré♯", NoteNames.spelled(hendrix.tones.first { it.degree == ChordDegree.SHARP_NINTH }.note, Notation.FRENCH))
    }

    @Test
    fun notAChord() {
        assertNull(ChordNamer.name(setOf(9), 9))
        assertNull("tierce seule : un intervalle", ChordNamer.name(setOf(9, 0), 9))
        assertNull("amas chromatique", ChordNamer.name(setOf(0, 1, 2), 0))
        assertEquals("Tierce mineure", ChordNamer.intervalName(3))
    }
}
