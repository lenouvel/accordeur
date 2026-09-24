package com.blenouvel.accordeur

import com.blenouvel.accordeur.model.FretboardMap
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.ScaleCatalog
import com.blenouvel.accordeur.model.ScaleCategory
import com.blenouvel.accordeur.model.ScaleSpeller
import com.blenouvel.accordeur.model.ScaleType
import com.blenouvel.accordeur.model.SpelledNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScalesTest {
    private fun scale(id: String) = ScaleCatalog.byId(id).also { assertEquals(id, it.id) }

    private fun spelled(root: Int, id: String, notation: Notation = Notation.FRENCH) =
        ScaleSpeller.spell(root, scale(id)).joinToString(" ") { NoteNames.spelled(it, notation) }

    @Test
    fun catalogIsConsistent() {
        val ids = ScaleCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (s in ScaleCatalog.all) {
            assertEquals("${s.id} : somme des écarts", 12, s.steps.sum())
            // Chaque degré « ♭3 », « ♯4 »… correspond exactement à son intervalle.
            s.degrees.forEachIndexed { i, label ->
                val (degree, accidental) = ScaleType.parseDegree(label)
                assertEquals("${s.id} degré $label", s.intervals[i], (ScaleType.MAJOR_OFFSETS[degree - 1] + accidental).mod(12))
            }
            assertTrue("${s.id} : degrés croissants", s.degreeNumbers.zipWithNext().all { (a, b) -> b >= a })
        }
        assertTrue(ScaleCatalog.byCategory.getValue(ScaleCategory.MAJOR_MODES).size == 7)
        assertEquals(ScaleCatalog.DEFAULT, ScaleCatalog.byId("inconnue"))
    }

    @Test
    fun formulas() {
        assertEquals("T T ½T T T T ½T", scale("major").formula)
        assertEquals("T ½T T T ½T 1½T ½T", scale("harmonic_minor").formula)
        assertEquals("1½T T T 1½T T", scale("pentatonic_minor").formula)
        assertEquals("T T T T T T", scale("whole_tone").formula)
    }

    @Test
    fun degreesOfModes() {
        assertEquals("1 2 3 4 5 6 7", scale("major").degrees.joinToString(" "))
        assertEquals("1 2 ♭3 4 5 6 ♭7", scale("dorian").degrees.joinToString(" "))
        assertEquals("1 ♭2 ♭3 4 5 ♭6 ♭7", scale("phrygian").degrees.joinToString(" "))
        assertEquals("1 2 3 ♯4 5 6 7", scale("lydian").degrees.joinToString(" "))
        assertEquals("1 2 3 4 5 6 ♭7", scale("mixolydian").degrees.joinToString(" "))
        assertEquals("1 2 ♭3 4 5 ♭6 ♭7", scale("minor").degrees.joinToString(" "))
        assertEquals("1 ♭2 ♭3 4 ♭5 ♭6 ♭7", scale("locrian").degrees.joinToString(" "))
        assertEquals("1 ♭2 ♭3 ♭4 ♭5 ♭6 ♭7", scale("altered").degrees.joinToString(" "))
        assertEquals("1 ♭2 ♭3 ♭4 ♭5 ♭6 ♭♭7", scale("ultralocrian").degrees.joinToString(" "))
        assertEquals("1 ♭2 3 4 5 ♭6 ♭7", scale("phrygian_dominant").degrees.joinToString(" "))
    }

    @Test
    fun spellingFollowsKeySignature() {
        assertEquals("Do Ré Mi Fa Sol La Si", spelled(0, "major"))
        assertEquals("Fa Sol La Si♭ Do Ré Mi", spelled(5, "major"))
        assertEquals("D E F♯ G A B C♯", spelled(2, "major", Notation.ENGLISH))
        // Touche noire : l'enharmonie la plus simple.
        assertEquals("Ré♭ Mi♭ Fa Sol♭ La♭ Si♭ Do", spelled(1, "major"))
        assertEquals("Do♯ Ré♯ Mi Fa♯ Sol♯ La Si", spelled(1, "minor"))
        assertEquals("Mi♭ Fa Sol La♭ Si♭ Do Ré", spelled(3, "major"))
        assertEquals("Fa♯ Sol♯ La♯ Si Do♯ Ré♯ Mi♯", spelled(6, "major"))
        assertEquals("Si♭ Do Ré♭ Mi♭ Fa Sol♭ La", spelled(10, "harmonic_minor"))
        // Modes et gammes non heptatoniques : lettres fixées par les degrés.
        assertEquals("Si Do Ré Mi Fa Sol La", spelled(11, "locrian"))
        assertEquals("Mi Fa Sol♯ La Si Do Ré", spelled(4, "phrygian_dominant"))
        assertEquals("La Do Ré Mi♭ Mi Sol", spelled(9, "blues"))
        assertEquals("A C D E G", spelled(9, "pentatonic_minor", Notation.ENGLISH))
        assertEquals("Do Ré Mi Fa♯ Sol♯ Si♭", spelled(0, "whole_tone"))
        assertEquals("Do Ré♭ Ré♯ Mi Fa♯ Sol La Si♭", spelled(0, "diminished_hw"))
    }

    @Test
    fun spellingAlwaysMatchesThePitches() {
        for (s in ScaleCatalog.all) {
            for (root in 0 until 12) {
                val notes = ScaleSpeller.spell(root, s)
                assertEquals(s.size, notes.size)
                notes.forEachIndexed { i, n ->
                    assertEquals("${s.id} sur $root, note $i", (root + s.intervals[i]) % 12, n.pitchClass)
                }
            }
        }
        // Les 7 modes diatoniques n'ont jamais besoin de double altération.
        for (s in ScaleCatalog.byCategory.getValue(ScaleCategory.MAJOR_MODES)) {
            for (root in 0 until 12) {
                assertTrue(ScaleSpeller.spell(root, s).all { abs(it.accidental) <= 1 })
            }
        }
    }

    @Test
    fun spelledNames() {
        assertEquals("Si♭", NoteNames.spelled(SpelledNote(6, -1), Notation.FRENCH))
        assertEquals("B♭", NoteNames.spelled(SpelledNote(6, -1), Notation.ENGLISH))
        assertEquals("Fa♯♯", NoteNames.spelled(SpelledNote(3, 2), Notation.BOTH))
        assertEquals(10, SpelledNote(6, -1).pitchClass)
        assertEquals(11, SpelledNote(0, -1).pitchClass)
    }

    @Test
    fun fretboardPositions() {
        val notes = FretboardMap.notes(Presets.STANDARD_6, 0, scale("major"), 12)
        val lowE = notes.filter { it.string == 0 }.map { it.fret }
        assertEquals(listOf(0, 1, 3, 5, 7, 8, 10, 12), lowE)
        val c = notes.first { it.string == 0 && it.fret == 8 }
        assertEquals(0, c.degree)
        assertEquals(48, c.midi) // Do3
        // Chaque case de 0 à 12 : 13 positions par corde, 7 notes sur 12 → 7 ou 8 positions.
        for (s in 0 until 6) assertTrue(notes.count { it.string == s } in 7..8)
        // 7 cordes Drop G♯, Sol♯ mineur pentatonique : la corde grave à vide est la fondamentale.
        val drop = FretboardMap.notes(Presets.DROP_G_SHARP_7, 8, scale("pentatonic_minor"), 22)
        assertTrue(drop.any { it.string == 0 && it.fret == 0 && it.degree == 0 })
        assertTrue(drop.all { it.fret in 0..22 && it.string in 0..6 })
    }
}
