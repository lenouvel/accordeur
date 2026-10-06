package com.blenouvel.accordeur

import com.blenouvel.accordeur.model.MetronomeConfig
import com.blenouvel.accordeur.model.MetronomeMath
import com.blenouvel.accordeur.model.SilentMeasures
import com.blenouvel.accordeur.model.Subdivision
import com.blenouvel.accordeur.model.TapTempo
import com.blenouvel.accordeur.model.TempoAutomation
import com.blenouvel.accordeur.model.VariationLaw
import com.blenouvel.accordeur.model.tempoName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MetronomeTest {

    @Test
    fun samplesPerTickIsExact() {
        assertEquals(6000.0, MetronomeMath.samplesPerTick(120, 4, 48_000), 1e-6) // grille de doubles-croches
        assertEquals(8000.0, MetronomeMath.samplesPerTick(120, 3, 48_000), 1e-6) // triolet
        assertEquals(4000.0, MetronomeMath.samplesPerTick(120, 6, 48_000), 1e-6) // sextolet
    }

    @Test
    fun fractionalAccumulationDoesNotDrift() {
        // À un tempo dont l'intervalle n'est pas entier, l'accumulation de la fraction garde
        // l'erreur cumulée sous un échantillon, même après des milliers de clics.
        val spt = MetronomeMath.samplesPerTick(133, 4, 48_000)
        var frac = 0.0
        var total = 0L
        val ticks = 5_000
        repeat(ticks) {
            frac += spt
            val n = frac.toInt()
            frac -= n
            total += n
        }
        assertTrue("dérive = ${abs(total - ticks * spt)}", abs(total - ticks * spt) < 1.0)
    }

    @Test
    fun subdivisionsHaveExpectedGridAndOnsets() {
        assertEquals(16, Subdivision.entries.size)
        assertEquals(4, Subdivision.QUARTER.grid)
        assertEquals(setOf(0), Subdivision.QUARTER.onsets)
        assertEquals(setOf(0, 2), Subdivision.EIGHTHS.onsets)
        assertEquals(setOf(0, 1, 2, 3), Subdivision.SIXTEENTHS.onsets)
        assertEquals(setOf(0, 3), Subdivision.DOTTED_EIGHTH_SIXTEENTH.onsets)
        assertEquals(setOf(2), Subdivision.EIGHTH_REST_EIGHTH.onsets) // temps silencieux, « et » joué
        assertEquals(3, Subdivision.TRIPLET.grid)
        assertEquals(setOf(0, 1, 2), Subdivision.TRIPLET.onsets)
        assertEquals(setOf(0, 2), Subdivision.TRIPLET_EIGHTH_REST_EIGHTH.onsets)
        assertEquals(7, Subdivision.SEPTUPLET.grid)
        assertEquals(setOf(0, 1, 2, 3, 4, 5), Subdivision.SEXTUPLET.onsets)
        // Chaque schéma : somme des durées = grille, et le 1er onset (s'il existe) tombe sur 0.
        for (sub in Subdivision.entries) {
            assertEquals(sub.cells.sumOf { it.units }, sub.grid)
            assertTrue(sub.onsets.all { it in 0 until sub.grid })
        }
    }

    @Test
    fun bpmFromEvenTaps() {
        assertEquals(120, MetronomeMath.bpmFromTaps(listOf(0, 500, 1000, 1500)))
        assertEquals(100, MetronomeMath.bpmFromTaps(listOf(0, 600)))
        assertNull(MetronomeMath.bpmFromTaps(listOf(0)))
    }

    @Test
    fun tapTempoResetsAfterGap() {
        val tap = TapTempo()
        assertNull(tap.tap(0))
        assertEquals(120, tap.tap(500))
        assertEquals(120, tap.tap(1000))
        // Un trop long silence repart de zéro.
        assertNull(tap.tap(5_000))
        assertEquals(120, tap.tap(5_500))
    }

    @Test
    fun automationLinearProgression() {
        val a = TempoAutomation(enabled = true, startBpm = 60, targetBpm = 120, stepBpm = 5, stepBars = 4, law = VariationLaw.LINEAR)
        assertEquals(48, MetronomeMath.totalBars(a)) // 12 paliers × 4 mesures
        assertEquals(60, MetronomeMath.automatedBpm(a, 0))
        assertEquals(90, MetronomeMath.automatedBpm(a, 24)) // mi-parcours
        assertEquals(120, MetronomeMath.automatedBpm(a, 48))
        assertEquals(120, MetronomeMath.automatedBpm(a, 999)) // borné à la fin
    }

    @Test
    fun automationLawsAndClamps() {
        val base = TempoAutomation(enabled = true, startBpm = 60, targetBpm = 120, stepBpm = 5, stepBars = 4)
        // Accélération sous la droite au début, décélération au-dessus.
        assertEquals(75, MetronomeMath.automatedBpm(base.copy(law = VariationLaw.ACCEL), 24))
        assertEquals(105, MetronomeMath.automatedBpm(base.copy(law = VariationLaw.DECEL), 24))
        // Aller-retour : durée doublée, retour au tempo initial à la fin.
        val tri = base.copy(law = VariationLaw.TRIANGLE)
        assertEquals(96, MetronomeMath.totalBars(tri))
        assertEquals(120, MetronomeMath.automatedBpm(tri, 48)) // sommet
        assertEquals(60, MetronomeMath.automatedBpm(tri, 96)) // retour
        // Désactivée : reste au tempo initial.
        assertEquals(60, MetronomeMath.automatedBpm(base.copy(enabled = false), 24))
    }

    @Test
    fun silentMeasuresPattern() {
        val silent = SilentMeasures(enabled = true, playBars = 2, muteBars = 1)
        val muted = (0..5).map { MetronomeMath.isMuted(it, silent) }
        assertEquals(listOf(false, false, true, false, false, true), muted)
        // Désactivé : jamais muet.
        assertTrue((0..5).none { MetronomeMath.isMuted(it, silent.copy(enabled = false)) })
    }

    @Test
    fun tempoNamesAtBoundaries() {
        assertEquals("Larghissimo", tempoName(10))
        assertEquals("Andante", tempoName(90))
        assertEquals("Allegro", tempoName(120))
        assertEquals("Prestissimo", tempoName(300))
    }

    @Test
    fun configSanitizedClamps() {
        val c = MetronomeConfig(bpm = 500, beatsPerMeasure = 0, countInBars = 99).sanitized()
        assertEquals(300, c.bpm)
        assertEquals(1, c.beatsPerMeasure)
        assertEquals(8, c.countInBars)
    }
}
