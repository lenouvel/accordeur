package com.blenouvel.accordeur

import com.blenouvel.accordeur.TestSignals.SAMPLE_RATE
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PolyReading
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Mode poly à affichage maintenu : un grattage met tout à jour, une corde rejouée seule ne met à
 * jour qu'elle, et le tableau reste affiché après l'extinction du son.
 */
class PolyTrackerTest {

    /** Une corde pincée à [atSeconds], désaccordée de [cents], sur une durée totale [total]. */
    private fun string(tuning: Tuning, index: Int, cents: Double, atSeconds: Double, total: Double, amplitude: Double = 0.1): DoubleArray {
        val f = tuning.frequencies()[index] * 2.0.pow(cents / 1200.0)
        val b = if (f < 100) 2e-4 else 5e-5
        val tone = TestSignals.pluckedString(
            f, total - atSeconds, inharmonicity = b, partials = 10, fundamentalDb = -6.0,
            decaySeconds = 2.0, amplitude = amplitude, seed = 31L + index,
        )
        return TestSignals.concat(TestSignals.silence(atSeconds), tone).copyOf((total * SAMPLE_RATE).toInt())
    }

    /** Toutes les cordes grattées ensemble à [atSeconds]. */
    private fun strum(tuning: Tuning, detune: DoubleArray, atSeconds: Double, total: Double): DoubleArray =
        TestSignals.mix(*tuning.strings.indices.map { string(tuning, it, detune[it], atSeconds, total) }.toTypedArray())

    /** Fait passer [signal] dans la chaîne en mode poly ; renvoie le tableau publié à chaque trame. */
    private fun run(signal: DoubleArray, tuning: Tuning): List<PolyReading?> {
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.mode = TunerMode.POLY
        processor.targets = TunerTargets(tuning.frequencies())
        val input = TestSignals.toFloat(TestSignals.addNoise(signal, rms = 1e-4, seed = 5))
        val chunk = FloatArray(PitchDetector.HOP)
        val out = ArrayList<PolyReading?>()
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            out += processor.process(chunk)?.poly
            i += chunk.size
        }
        return out
    }

    private fun at(readings: List<PolyReading?>, seconds: Double): PolyReading? =
        readings[(seconds * SAMPLE_RATE / PitchDetector.HOP).toInt().coerceAtMost(readings.size - 1)]

    private fun assertCents(label: String, expected: Double, reading: PolyReading, index: Int, tolerance: Double) {
        val s = reading.strings[index]
        assertTrue("$label : corde $index non détectée", s.detected)
        val allowed = if (s.reliable) tolerance else 6.0
        assertTrue("$label : corde $index attendue $expected, lue ${s.cents}", abs(s.cents - expected) < allowed)
    }

    @Test
    fun strumThenSingleStringUpdatesOnlyThatString() {
        val tuning = Presets.STANDARD_6
        val strummed = doubleArrayOf(-12.0, 7.0, 0.0, 20.0, -5.0, 3.0)
        val total = 13.0
        // Grattage à 0,5 s ; la corde 3 (Sol, index 3) retendue puis rejouée seule à 5 s ; silence.
        val signal = TestSignals.mix(
            strum(tuning, strummed, 0.5, total),
            string(tuning, 3, 2.0, 5.0, total, amplitude = 0.15),
        )
        val readings = run(signal, tuning)

        assertNull("rien avant la première attaque", at(readings, 0.4))
        val afterStrum = at(readings, 1.5)
        assertNotNull("pas de tableau après le grattage", afterStrum)
        afterStrum!!
        assertTrue("grattage reconnu", afterStrum.strum)
        for (i in strummed.indices) assertCents("après le grattage", strummed[i], afterStrum, i, 3.5)
        assertTrue(afterStrum.strings.all { it.fresh })

        val afterSingle = at(readings, 6.5)!!
        assertFalse("corde seule : pas un grattage", afterSingle.strum)
        assertEquals(afterStrum.event + 1, afterSingle.event)
        assertCents("corde rejouée", 2.0, afterSingle, 3, 1.5)
        assertTrue(afterSingle.strings[3].fresh)
        for (i in strummed.indices) {
            if (i == 3) continue
            assertFalse("corde $i marquée fraîche", afterSingle.strings[i].fresh)
            assertCents("corde $i conservée", strummed[i], afterSingle, i, 3.5)
        }

        // Longtemps après l'extinction du son, le tableau est toujours là, inchangé.
        val end = readings.last()!!
        assertEquals(afterSingle.event, end.event)
        for (i in strummed.indices) assertEquals(afterSingle.strings[i].detected, end.strings[i].detected)
        assertCents("fin, corde rejouée", 2.0, end, 3, 1.5)
    }

    @Test
    fun lowStringAloneDoesNotUpdateItsOctave() {
        // Drop D : Ré2 rejoué seul fait sonner les partiels pairs de Ré3 (même notes) ; seul Ré2 change.
        val tuning = Presets.DROP_D_6
        val strummed = doubleArrayOf(15.0, -8.0, 4.0, -20.0, 10.0, -3.0)
        val total = 9.0
        val signal = TestSignals.mix(
            strum(tuning, strummed, 0.5, total),
            string(tuning, 0, -2.0, 5.0, total, amplitude = 0.2),
        )
        val readings = run(signal, tuning)
        val before = at(readings, 4.5)!!
        val after = at(readings, 6.5)!!
        assertEquals(before.event + 1, after.event)
        assertCents("Ré2 rejoué", -2.0, after, 0, 1.5)
        assertFalse("Ré3 ne doit pas être mis à jour", after.strings[2].fresh)
        assertEquals(before.strings[2].cents, after.strings[2].cents, 0.5)
    }

    @Test
    fun lowEAloneDoesNotUpdateBAndHighE() {
        // Standard : Si3 et Mi4 n'ont que des partiels communs avec Mi2 (et La2).
        val tuning = Presets.STANDARD_6
        val strummed = doubleArrayOf(-12.0, 7.0, 0.0, 20.0, -5.0, 3.0)
        val total = 9.0
        val signal = TestSignals.mix(
            strum(tuning, strummed, 0.5, total),
            string(tuning, 0, 1.0, 5.0, total, amplitude = 0.2),
        )
        val readings = run(signal, tuning)
        val after = at(readings, 6.5)!!
        assertCents("Mi2 rejoué", 1.0, after, 0, 1.5)
        assertTrue(after.strings[0].fresh)
        for (i in 1 until 6) assertFalse("corde $i ne doit pas être mise à jour", after.strings[i].fresh)
    }

    @Test
    fun changingTuningClearsTheBoard() {
        val tuning = Presets.STANDARD_6
        val signal = strum(tuning, DoubleArray(6), 0.5, 3.0)
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.mode = TunerMode.POLY
        processor.targets = TunerTargets(tuning.frequencies())
        val input = TestSignals.toFloat(signal)
        val chunk = FloatArray(PitchDetector.HOP)
        var last: PolyReading? = null
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            last = processor.process(chunk)?.poly ?: last
            i += chunk.size
        }
        assertNotNull(last)
        processor.targets = TunerTargets(Presets.DROP_D_6.frequencies())
        assertNull(processor.process(FloatArray(PitchDetector.HOP))?.poly)
    }
}
