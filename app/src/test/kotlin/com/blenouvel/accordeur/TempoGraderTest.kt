package com.blenouvel.accordeur

import com.blenouvel.accordeur.model.Subdivision
import com.blenouvel.accordeur.model.TempoGrader
import com.blenouvel.accordeur.model.TimingVerdict
import com.blenouvel.accordeur.model.gradingDivision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TempoGraderTest {

    private fun grader() = TempoGrader()

    @Test
    fun noBeatYetGivesNoReading() {
        val g = grader()
        assertNull(g.onOnset(1000.0))
    }

    @Test
    fun onTheBeatIsOnTime() {
        val g = grader()
        g.setDivision(1)
        // 120 BPM : un temps toutes les 500 ms.
        g.onBeat(1000.0, 500.0)
        val a = g.onOnset(1005.0) // +5 ms
        g.onBeat(1500.0, 500.0)
        val b = g.onOnset(1503.0) // +3 ms
        g.onBeat(2000.0, 500.0)
        val r = g.onOnset(2004.0) // +4 ms
        assertTrue(abs(r!!.meanDeviationMs - 4.0) < 1.0)
        assertEquals(TimingVerdict.ON_TIME, r.verdict)
        assertTrue(a != null && b != null)
    }

    @Test
    fun consistentlyEarlyIsRushing() {
        val g = grader()
        g.setDivision(1)
        var r = g.reading()
        for (k in 0..3) {
            g.onBeat(1000.0 + k * 500.0, 500.0)
            r = g.onOnset(1000.0 + k * 500.0 - 60.0)!! // 60 ms en avance
        }
        assertEquals(TimingVerdict.RUSHING, r.verdict)
        assertTrue(r.meanDeviationMs < -45.0)
    }

    @Test
    fun consistentlyLateIsDragging() {
        val g = grader()
        g.setDivision(1)
        var r = g.reading()
        for (k in 0..3) {
            g.onBeat(1000.0 + k * 500.0, 500.0)
            r = g.onOnset(1000.0 + k * 500.0 + 70.0)!! // 70 ms en retard
        }
        assertEquals(TimingVerdict.DRAGGING, r.verdict)
        assertTrue(r.meanDeviationMs > 45.0)
    }

    @Test
    fun belowMinOnsetsHasNoVerdict() {
        val g = grader()
        g.setDivision(1)
        g.onBeat(1000.0, 500.0)
        val r = g.onOnset(1200.0)!!
        assertEquals(TimingVerdict.NONE, r.verdict)
        assertEquals(1, r.matched)
    }

    @Test
    fun eighthNotesSnapToSubdivisionGrid() {
        // 150 BPM : temps = 400 ms, croche = 200 ms.
        val g = grader()
        g.setDivision(2)
        g.onBeat(0.0, 400.0)
        // Une croche jouée à 205 ms (le « et » du temps) : 5 ms de retard, pas 195 ms d'avance.
        val r = g.onOnset(205.0)!!
        assertTrue("écart attendu ~+5 ms, obtenu ${r.lastDeviationMs}", abs(r.lastDeviationMs - 5.0) < 2.0)
    }

    @Test
    fun quarterGridMakesTheEighthAmbiguous() {
        // Même attaque, mais notée à la noire : rattachée au temps suivant (400 ms), écart borné.
        val g = grader()
        g.setDivision(1)
        g.onBeat(0.0, 400.0)
        val r = g.onOnset(205.0)!!
        assertTrue("l'écart doit être grand (ambigu), obtenu ${r.lastDeviationMs}", abs(r.lastDeviationMs) > 150.0)
    }

    @Test
    fun latencyOffsetRecentersZero() {
        val g = grader()
        g.setDivision(1)
        // Toutes les attaques à +40 ms, mais latence 40 ms déclarée : écart ramené à ~0.
        var r = g.reading()
        for (k in 0..3) {
            g.onBeat(1000.0 + k * 500.0, 500.0)
            r = g.onOnset(1000.0 + k * 500.0 + 40.0, latencyMs = 40.0)!!
        }
        assertTrue(abs(r.meanDeviationMs) < 2.0)
        assertEquals(TimingVerdict.ON_TIME, r.verdict)
    }

    @Test
    fun windowForgetsOldOnsets() {
        val g = TempoGrader(window = 4)
        g.setDivision(1)
        // Quatre attaques très en retard puis quatre pile : la fenêtre ne garde que les récentes.
        for (k in 0..3) {
            g.onBeat(k * 500.0, 500.0)
            g.onOnset(k * 500.0 + 120.0)
        }
        var r = g.reading()
        for (k in 4..7) {
            g.onBeat(k * 500.0, 500.0)
            r = g.onOnset(k * 500.0 + 2.0)!!
        }
        assertEquals(4, r.matched)
        assertTrue(abs(r.meanDeviationMs) < 5.0)
    }

    @Test
    fun gradingDivisionMatchesNotation() {
        assertEquals(1, Subdivision.QUARTER.gradingDivision)
        assertEquals(2, Subdivision.EIGHTHS.gradingDivision)
        assertEquals(4, Subdivision.SIXTEENTHS.gradingDivision)
        assertEquals(3, Subdivision.TRIPLET.gradingDivision)
        assertEquals(4, Subdivision.EIGHTH_TWO_SIXTEENTHS.gradingDivision)
        assertEquals(4, Subdivision.DOTTED_EIGHTH_SIXTEENTH.gradingDivision)
        assertEquals(2, Subdivision.EIGHTH_REST_EIGHTH.gradingDivision)
        assertEquals(5, Subdivision.QUINTUPLET.gradingDivision)
        assertEquals(6, Subdivision.SEXTUPLET.gradingDivision)
        assertEquals(7, Subdivision.SEPTUPLET.gradingDivision)
    }
}
