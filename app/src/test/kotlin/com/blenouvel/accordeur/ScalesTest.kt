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
    fun positionsHaveConstantNotesPerString() {
        // Sol mineur pentatonique : la box « racine » est 1 & ♭3 sur Mi, 4 & 5 sur La.
        val gm = FretboardMap.positions(Presets.STANDARD_6, 7, scale("pentatonic_minor"), 12)
        val gRoot = gm[FretboardMap.rootPosition(gm)]
        assertEquals(listOf(0, 1), gRoot.notes.filter { it.string == 0 }.sortedBy { it.fret }.map { it.degree }) // 1, ♭3
        assertEquals(listOf(2, 3), gRoot.notes.filter { it.string == 1 }.sortedBy { it.fret }.map { it.degree }) // 4, 5

        // Fa mineur pentatonique, box racine : la corde de Sol tient La♭ (♭3) et Si♭ (4), et PAS Do.
        val fm = FretboardMap.positions(Presets.STANDARD_6, 5, scale("pentatonic_minor"), 12)
        val fRoot = fm[FretboardMap.rootPosition(fm)]
        for (s in 0 until 6) assertEquals("corde $s : 2 notes", 2, fRoot.notes.count { it.string == s })
        val gString = fRoot.notes.filter { it.string == 3 }.sortedBy { it.fret }
        assertEquals(listOf(1, 3), gString.map { it.fret })
        assertEquals(listOf(1, 2), gString.map { it.degree }) // ♭3 (La♭) et 4 (Si♭), le Do (case 5) est exclu

        // Invariant général : jamais plus de notes par corde que prévu (2, ou 3 au-delà de 6 notes).
        for (s in ScaleCatalog.all) {
            val per = if (s.size <= 6) 2 else 3
            for (root in 0 until 12) {
                for (pos in FretboardMap.positions(Presets.STANDARD_6, root, s, 15)) {
                    for (str in 0 until 6) {
                        assertTrue("${s.id} sur $root, box ${pos.index}, corde $str", pos.notes.count { it.string == str } <= per)
                    }
                }
            }
        }
    }

    @Test
    fun runChainsBoxesUpThenDown() {
        val positions = FretboardMap.positions(Presets.STANDARD_6, 7, scale("pentatonic_minor"), 12)
        val run = FretboardMap.run(positions)
        assertTrue(run.isNotEmpty())
        // Le parcours monte les box (indice croissant) jusqu'au sommet puis les redescend.
        val boxes = run.map { it.position }
        val peak = boxes.indexOf(boxes.max())
        assertTrue("montée jusqu'au sommet", boxes.subList(0, peak + 1).zipWithNext().all { (a, b) -> b >= a })
        assertTrue("redescente ensuite", boxes.subList(peak, boxes.size).zipWithNext().all { (a, b) -> b <= a })
        assertEquals(0, boxes.first())
        assertEquals(0, boxes.last())
        assertEquals(positions.lastIndex, boxes.max())
        // Une même case n'est jamais donnée deux fois de suite (jonctions dédoublonnées).
        assertTrue(run.zipWithNext().none { (a, b) -> a.string == b.string && a.fret == b.fret })
        // À l'intérieur d'une box en montée, on va de la corde grave vers l'aiguë.
        val firstBox = run.takeWhile { it.position == 0 }
        assertTrue(firstBox.zipWithNext().all { (a, b) -> a.string <= b.string })
    }

    @Test
    fun runSnakesAcrossPositions() {
        val positions = FretboardMap.positions(Presets.STANDARD_6, 7, scale("pentatonic_minor"), 12)
        val run = FretboardMap.run(positions)
        val upLen = (run.size + 2) / 2 // run = montée + retour (montée inversée, sans les extrémités)
        val up = run.subList(0, upLen)
        // Chaque box est parcourue dans un sens monotone : rang pair en degrés croissants (hauteur
        // croissante), rang impair en degrés décroissants — d'où le retour « 4 ♭3 1 ♭7 5 … » en position 2.
        for (p in positions.indices) {
            val midis = up.filter { it.position == p }.map { it.midi }
            if (midis.size < 2) continue
            val ok = if (p % 2 == 0) midis.zipWithNext().all { (a, b) -> a < b }
            else midis.zipWithNext().all { (a, b) -> a > b }
            assertTrue("box $p : hauteurs monotones", ok)
        }
        // On ne revient pas à la corde grave à la jonction : la box 0 finit dans l'aigu, la box 1 y démarre.
        assertEquals(0, up.first { it.position == 0 }.string)
        assertEquals(5, up.first { it.position == 1 }.string)
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
