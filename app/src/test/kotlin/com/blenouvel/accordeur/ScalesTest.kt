package com.blenouvel.accordeur

import com.blenouvel.accordeur.model.FretNote
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
    fun harmonicMinorExampleBox() {
        // Sol mineure harmonique, standard 6 cordes, Position 1 : doigté 3 notes/corde standard.
        val positions = FretboardMap.positions(Presets.STANDARD_6, 7, scale("harmonic_minor"), 15)
        val p1 = positions[0] // Position 1 = box de la fondamentale (après rotation)
        fun frets(s: Int) = p1.notes.filter { it.string == s }.map { it.fret }.sorted()
        assertEquals(listOf(3, 5, 6), frets(0)) // Mi : Sol La Si♭    (1 2 ♭3)
        assertEquals(listOf(3, 5, 6), frets(1)) // La : Do Ré Mi♭     (4 5 ♭6)
        assertEquals(listOf(4, 5, 7), frets(2)) // Ré : Fa♯ Sol La    (6 1 2)
        assertEquals(listOf(3, 5, 7), frets(3)) // Sol : Si♭ Do Ré    (♭3 4 5) — étirement 4 cases
        assertEquals(listOf(4, 7, 8), frets(4)) // Si : Mi♭ Fa♯ Sol   (♭6 7 1) — étirement 4 cases
        assertEquals(listOf(5, 6, 8), frets(5)) // Mi : La Si♭ Do     (2 ♭3 4)
        // 3 notes par corde (ou partiel en bout de manche) ; aucune hauteur redondante.
        for (pos in positions) assertEquals(pos.notes.size, pos.notes.map { it.midi }.distinct().size)
    }

    @Test
    fun positionsHaveThreeNotesPerString() {
        // Gamme heptatonique : chaque corde porte 3 notes de la gamme (hors bords du manche).
        for (s in ScaleCatalog.all.filter { it.size == 7 }) {
            for (root in 0 until 12) {
                for (pos in FretboardMap.positions(Presets.STANDARD_6, root, s, 15)) {
                    for (str in 0 until 6) {
                        val count = pos.notes.count { it.string == str }
                        assertTrue("${s.id}/$root box ${pos.index} corde $str : $count notes", count <= 3)
                    }
                }
            }
        }
    }

    @Test
    fun positionOneStartsOnTonic() {
        // Position 1 : la note la plus grave sur la corde la plus grave est la fondamentale.
        for (s in ScaleCatalog.all) {
            for (root in 0 until 12) {
                val positions = FretboardMap.positions(Presets.STANDARD_6, root, s, 15)
                if (positions.isEmpty()) continue
                val low = positions[0].notes.filter { it.string == 0 }
                if (low.isNotEmpty()) assertEquals(
                    "${s.id}/$root : Position 1 ancrée sur la fondamentale",
                    0, low.minByOrNull { it.fret }!!.degree,
                )
            }
        }
    }

    @Test
    fun runRoundTripsEachPosition() {
        val positions = FretboardMap.positions(Presets.STANDARD_6, 7, scale("harmonic_minor"), 15)
        val run = FretboardMap.run(positions)
        assertTrue(run.isNotEmpty())
        // Aller-retour par position : montée jusqu'au sommet, puis retour sur la note la plus grave.
        for (pos in positions) {
            val midis = run.filter { it.position == pos.index }.map { it.midi }
            if (midis.size < 2) continue
            val peak = midis.indexOf(midis.max())
            assertTrue("box ${pos.index} montée", midis.subList(0, peak + 1).zipWithNext().all { (a, b) -> a < b })
            assertTrue("box ${pos.index} descente", midis.subList(peak, midis.size).zipWithNext().all { (a, b) -> a > b })
            assertEquals("box ${pos.index} départ sur la note grave", midis.min(), midis.first())
            assertEquals("box ${pos.index} retour sur la note grave", midis.first(), midis.last())
        }
        // Les positions s'enchaînent dans l'ordre (0, 1, 2, …).
        assertEquals(positions.indices.toList(), run.map { it.position }.distinct())
    }

    /**
     * Simule le guide interactif hors mode positions (manche entier, « case la plus proche »), en
     * supposant que l'on joue chaque case proposée : renvoie la suite des cases éclairées sur une
     * montée + descente complète de la gamme, puis un peu au-delà (pour couvrir le rebouclage).
     */
    private fun guidedCells(root: Int, scaleId: String, frets: Int = 12): List<FretNote> {
        val s = scale(scaleId)
        val notes = FretboardMap.notes(Presets.STANDARD_6, root, s, frets)
        val seq = ScalesViewModel.runSequence(s.size)
        var anchor: FretNote? = null
        val out = ArrayList<FretNote>()
        for (i in 0 until seq.size * 2 + 1) {
            val key = ScalesViewModel.focusCells(notes, seq[i.mod(seq.size)], nearest = true, anchor).single()
            val cell = notes.first { ScalesViewModel.cellKey(it.string, it.fret) == key }
            out += cell
            anchor = cell
        }
        return out
    }

    @Test
    fun interactiveGuideNeverSkipsStrings() {
        // Le bug : hors mode positions, le guide proposait parfois une case à deux cordes d'écart
        // (même hauteur sur plusieurs cordes, départage par ordre de liste). Invariant : deux cases
        // consécutives restent sur la même corde ou une corde voisine, pour toutes les gammes.
        for (s in ScaleCatalog.all) {
            for (root in 0 until 12) {
                val cells = guidedCells(root, s.id)
                cells.zipWithNext().forEach { (a, b) ->
                    assertTrue(
                        "${s.id} sur $root : saut de corde ${a.string}→${b.string}",
                        abs(a.string - b.string) <= 1,
                    )
                }
            }
        }
    }

    @Test
    fun interactiveGuideClimbsThenDescends() {
        // Montée puis descente : la hauteur augmente jusqu'au sommet (fondamentale à l'octave) puis
        // redescend, sans repartir en arrière au milieu.
        val cells = guidedCells(7, "major")
        val midis = cells.map { it.midi }
        val peak = midis.indexOf(midis.max())
        assertTrue("montée", midis.subList(0, peak + 1).zipWithNext().all { (a, b) -> b >= a })
        assertTrue("descente", midis.subList(peak, (cells.size + 2) / 2).zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun interactiveGuideChangesStringEarly() {
        // Doigté « économique » : on change de corde au plus tôt. Sur la montée d'une octave de
        // toutes les gammes et fondamentales, l'invariant exact : si on reste sur la corde alors que
        // la hauteur augmente, c'est que cette note n'était pas jouable sur la corde du dessus.
        for (s in ScaleCatalog.all) {
            for (root in 0 until 12) {
                val notes = FretboardMap.notes(Presets.STANDARD_6, root, s, 12)
                val up = guidedCells(root, s.id).take(s.size + 1) // fondamentale → octave
                val path = up.joinToString(" ") { "${it.string}:${it.fret}" }
                up.zipWithNext().forEach { (a, b) ->
                    if (b.string == a.string && b.midi > a.midi) {
                        assertTrue(
                            "${s.id}/$root : aurait dû changer de corde — $path",
                            notes.none { it.string == a.string + 1 && it.midi == b.midi },
                        )
                    }
                }
            }
        }
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
