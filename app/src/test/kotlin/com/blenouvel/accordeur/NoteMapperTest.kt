package com.blenouvel.accordeur

import com.blenouvel.accordeur.model.CustomTuningCodec
import com.blenouvel.accordeur.model.GuitarString
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMapperTest {

    @Test
    fun a4IsMidi69() {
        assertEquals(69.0, NoteMapper.midiOf(440.0), 1e-12)
        assertEquals(440.0, NoteMapper.frequencyOf(69), 1e-12)
        assertEquals(Note(9, 4), Note.fromMidi(69))
        assertEquals(60, Note(0, 4).midi)
    }

    @Test
    fun frequencyMidiRoundTrip() {
        var f = 30.0
        while (f < 2000.0) {
            assertEquals(f, NoteMapper.frequencyOf(NoteMapper.midiOf(f, 442.0), 442.0), 1e-9)
            f *= 1.037
        }
    }

    @Test
    fun nearestNoteAndCents() {
        val e2 = NoteMapper.nearest(82.41)
        assertEquals(Note(4, 2), e2.note)
        assertEquals(0.065, e2.cents, 0.01)

        // Un quart de ton au-dessus de La4 : +50 cents au plus.
        val quarter = NoteMapper.nearest(440.0 * Math.pow(2.0, 0.49 / 12))
        assertEquals(Note(9, 4), quarter.note)
        assertEquals(49.0, quarter.cents, 1e-9)

        val flat = NoteMapper.nearest(440.0 * Math.pow(2.0, -0.2 / 12))
        assertEquals(-20.0, flat.cents, 1e-9)
    }

    @Test
    fun centsFromBijection() {
        for (cents in listOf(-50.0, -12.5, -1.0, 0.0, 0.3, 7.0, 49.9)) {
            val f = NoteMapper.frequencyOf(Note(4, 2)) * Math.pow(2.0, cents / 1200.0)
            assertEquals(cents, NoteMapper.centsFrom(f, Note(4, 2)), 1e-9)
            assertEquals(cents, NoteMapper.nearest(f).cents, 1e-9)
        }
    }

    @Test
    fun referencePitchShiftsEverything() {
        assertEquals(442.0, NoteMapper.frequencyOf(Note(9, 4), 442.0), 1e-12)
        assertEquals(82.4069 * 442.0 / 440.0, NoteMapper.frequencyOf(Note(4, 2), 442.0), 1e-3)
        // 440 Hz lu avec La = 442 : un peu moins de 8 cents trop bas.
        val reading = NoteMapper.nearest(440.0, 442.0)
        assertEquals(Note(9, 4), reading.note)
        assertEquals(-7.85, reading.cents, 0.01)
        // Les accordages suivent le La de référence (rien de codé en dur).
        val low = Presets.STANDARD_6.frequencies(435.0)[0]
        assertEquals(82.4069 * 435.0 / 440.0, low, 1e-3)
    }

    @Test
    fun frenchAndEnglishNotation() {
        assertEquals("Mi2", NoteNames.label(Note(4, 2), Notation.FRENCH))
        assertEquals("E2", NoteNames.label(Note(4, 2), Notation.ENGLISH))
        assertEquals("Mi2 / E2", NoteNames.label(Note(4, 2), Notation.BOTH))
        assertEquals("Sol♯1", NoteNames.label(Note(8, 1), Notation.FRENCH))
        assertEquals("G♯1", NoteNames.label(Note(8, 1), Notation.ENGLISH))
        assertEquals("Ré", NoteNames.primary(2, Notation.BOTH))
        assertEquals("D", NoteNames.secondary(2, Notation.BOTH))
        assertEquals(null, NoteNames.secondary(2, Notation.FRENCH))
        val fr = listOf("Do", "Do♯", "Ré", "Ré♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si")
        val en = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        for (pc in 0..11) {
            assertEquals(fr[pc], NoteNames.french(pc))
            assertEquals(en[pc], NoteNames.english(pc))
        }
    }

    @Test
    fun parseNotes() {
        assertEquals(Note(8, 1), Note.parse("G#1"))
        assertEquals(Note(8, 1), Note.parse("G♯1"))
        assertEquals(Note(10, 3), Note.parse("Bb3"))
        assertEquals(Note(10, 3), Note.parse("B♭3"))
        assertEquals(Note(0, 4), Note.parse("B#3"))
        assertEquals(Note(11, 3), Note.parse("Cb4"))
    }

    private fun assertTuning(tuning: Tuning, expectedHz: List<Double>, fr: String, en: String) {
        assertEquals(expectedHz.size, tuning.stringCount)
        val hz = tuning.frequencies()
        for (i in expectedHz.indices) assertEquals("${tuning.name} corde $i", expectedHz[i], hz[i], 0.006)
        assertEquals(fr, NoteNames.sequence(tuning, Notation.FRENCH))
        assertEquals(en, NoteNames.sequence(tuning, Notation.ENGLISH))
    }

    @Test
    fun requiredPresets() {
        assertEquals(5, Presets.required.size)
        assertTuning(
            Presets.STANDARD_6,
            listOf(82.41, 110.00, 146.83, 196.00, 246.94, 329.63),
            "Mi La Ré Sol Si Mi", "E A D G B E",
        )
        assertTuning(
            Presets.DROP_D_6,
            listOf(73.42, 110.00, 146.83, 196.00, 246.94, 329.63),
            "Ré La Ré Sol Si Mi", "D A D G B E",
        )
        assertTuning(
            Presets.STANDARD_7,
            listOf(61.74, 82.41, 110.00, 146.83, 196.00, 246.94, 329.63),
            "Si Mi La Ré Sol Si Mi", "B E A D G B E",
        )
        assertTuning(
            Presets.DROP_A_7,
            listOf(55.00, 82.41, 110.00, 146.83, 196.00, 246.94, 329.63),
            "La Mi La Ré Sol Si Mi", "A E A D G B E",
        )
        assertTuning(
            Presets.DROP_G_SHARP_7,
            listOf(51.91, 77.78, 103.83, 138.59, 185.00, 233.08, 311.13),
            "Sol♯ Ré♯ Sol♯ Do♯ Fa♯ La♯ Ré♯", "G♯ D♯ G♯ C♯ F♯ A♯ D♯",
        )
    }

    @Test
    fun presetIdsAreUnique() {
        val ids = Presets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(Presets.STANDARD_6, Presets.byId("std6"))
        assertTrue(Presets.all.all { it.stringCount in Tuning.MIN_STRINGS..Tuning.MAX_STRINGS })
    }

    @Test
    fun customTuningCodecRoundTrip() {
        val custom = listOf(
            Tuning("custom-a", "Open C | test", listOf("C2", "G2", "C3", "G3", "C4", "E4").map(GuitarString::parse), true),
            Tuning("custom-b", "Basse 4", listOf("E1", "A1", "D2", "G2").map(GuitarString::parse), true),
        )
        val decoded = CustomTuningCodec.decode(CustomTuningCodec.encode(custom))
        assertEquals(2, decoded.size)
        assertEquals("Open C / test", decoded[0].name)
        assertEquals(custom[0].strings.map { it.midi }, decoded[0].strings.map { it.midi })
        assertEquals(custom[1].strings.map { it.midi }, decoded[1].strings.map { it.midi })
        assertTrue(decoded.all { it.isCustom })
        assertEquals(emptyList<Tuning>(), CustomTuningCodec.decode("n'importe quoi\n|x|"))
    }
}
