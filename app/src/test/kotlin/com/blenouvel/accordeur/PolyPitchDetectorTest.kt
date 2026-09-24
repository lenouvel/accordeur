package com.blenouvel.accordeur

import com.blenouvel.accordeur.TestSignals.SAMPLE_RATE
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PolyPitchDetector
import com.blenouvel.accordeur.audio.PolyString
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** Mode poly : grattage synthétique de toutes les cordes, légèrement désaccordées. */
class PolyPitchDetectorTest {

    private fun strum(tuning: Tuning, detuneCents: DoubleArray, seconds: Double = 1.0, mute: Set<Int> = emptySet()): DoubleArray {
        val targets = tuning.frequencies()
        val parts = targets.indices.filter { it !in mute }.map { i ->
            val f = targets[i] * 2.0.pow(detuneCents[i] / 1200.0)
            val b = if (f < 100) 2e-4 else 5e-5
            TestSignals.pluckedString(f, seconds, inharmonicity = b, partials = 10, fundamentalDb = -6.0, amplitude = 0.1, seed = 31L + i)
        }
        return TestSignals.addNoise(TestSignals.mix(*parts.toTypedArray()), rms = 0.001, seed = 5)
    }

    private fun analyze(signal: DoubleArray, tuning: Tuning): List<PolyString> {
        // Même pré-filtrage que dans l'app, puis une fenêtre prise 0,4 s après l'attaque.
        val pre = Preprocessor(SAMPLE_RATE)
        val filtered = DoubleArray(signal.size) { pre.process(signal[it]) }
        val start = (0.4 * SAMPLE_RATE).toInt()
        val window = filtered.copyOfRange(start, start + PolyPitchDetector.WINDOW)
        val reading = PolyPitchDetector(SAMPLE_RATE).analyze(window, tuning.frequencies(), 0.0)
        assertEquals(tuning.stringCount, reading.strings.size)
        return reading.strings
    }

    /**
     * Cordes « fiables » (partiels propres) : ±[tolerance] cents. Cordes dont tous les partiels
     * sont partagés (octaves : D2/D3, G♯1/G♯2…) : précision plus grossière, ±6 cents.
     */
    private fun check(tuning: Tuning, detune: DoubleArray, tolerance: Double): List<PolyString> {
        val measured = analyze(strum(tuning, detune), tuning)
        for (i in detune.indices) {
            val s = measured[i]
            assertTrue("${tuning.name} corde $i non détectée", s.detected)
            val allowed = if (s.reliable) tolerance else 6.0
            assertTrue(
                "${tuning.name} corde $i : attendu ${detune[i]}, mesuré ${s.cents} (fiable=${s.reliable})",
                abs(s.cents - detune[i]) < allowed,
            )
            // Le sens (trop bas / trop haut) doit être juste dès que l'écart est net.
            if (abs(detune[i]) >= allowed) assertEquals(Math.signum(detune[i]), Math.signum(s.cents), 0.0)
        }
        return measured
    }

    @Test
    fun standardSixStrings() {
        val measured = check(Presets.STANDARD_6, doubleArrayOf(-12.0, 7.0, 0.0, 20.0, -5.0, 3.0), tolerance = 3.0)
        // Les quatre cordes graves ont toutes un partiel propre.
        for (i in 0..3) assertTrue("corde $i", measured[i].reliable)
    }

    @Test
    fun dropDSixStrings() {
        val measured = check(Presets.DROP_D_6, doubleArrayOf(15.0, -8.0, 4.0, -20.0, 10.0, -3.0), tolerance = 3.0)
        // D3 est à l'octave de D2 : tous ses partiels sont partagés.
        assertFalse(measured[2].reliable)
    }

    @Test
    fun standardSevenStrings() {
        check(Presets.STANDARD_7, doubleArrayOf(-18.0, 6.0, -9.0, 12.0, 0.0, -14.0, 8.0), tolerance = 3.5)
    }

    @Test
    fun dropGSharpSevenStrings() {
        val measured = check(Presets.DROP_G_SHARP_7, doubleArrayOf(-10.0, 5.0, 12.0, -7.0, 0.0, 9.0, -15.0), tolerance = 3.5)
        // G♯2 (5e corde) est à l'octave de G♯1 (7e).
        assertFalse(measured[2].reliable)
    }

    @Test
    fun mutedStringIsReportedMissing() {
        val tuning = Presets.STANDARD_6
        val detune = DoubleArray(6)
        // Cordes ayant un partiel propre. Si (3e partiel de Mi2) et Mi4 (4e partiel de Mi2, 3e de
        // La2) coïncident avec des partiels d'autres cordes : muettes, elles restent « vues »
        // (lecture signalée comme approximative) — limite physique du grattage global.
        for (muted in 0..3) {
            val measured = analyze(strum(tuning, detune, mute = setOf(muted)), tuning)
            assertFalse("corde muette $muted détectée (${measured[muted].cents})", measured[muted].detected)
            for (i in 0 until 6) {
                if (i == muted) continue
                assertTrue("corde $i (muette : $muted)", measured[i].detected && abs(measured[i].cents) < 4.0)
            }
        }
    }

    @Test
    fun processorPublishesPolyReadings() {
        val tuning = Presets.STANDARD_6
        val detune = doubleArrayOf(-12.0, 7.0, 0.0, 20.0, -5.0, 3.0)
        val signal = TestSignals.concat(TestSignals.silence(0.3), strum(tuning, detune, seconds = 1.5))
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.mode = TunerMode.POLY
        processor.targets = TunerTargets(tuning.frequencies())
        val input = TestSignals.toFloat(signal)
        val chunk = FloatArray(PitchDetector.HOP)
        var last = processor.process(FloatArray(0))
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            last = processor.process(chunk) ?: last
            i += chunk.size
        }
        val poly = last?.poly
        assertNotNull("aucune lecture poly", poly)
        for (s in poly!!.strings.indices) {
            assertTrue("corde $s", poly.strings[s].detected)
            assertTrue("corde $s : ${poly.strings[s].cents}", abs(poly.strings[s].cents - detune[s]) < 3.0)
        }
    }
}
