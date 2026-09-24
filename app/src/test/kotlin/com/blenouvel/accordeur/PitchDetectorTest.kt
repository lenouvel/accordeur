package com.blenouvel.accordeur

import com.blenouvel.accordeur.TestSignals.SAMPLE_RATE
import com.blenouvel.accordeur.TestSignals.cents
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PitchEstimate
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Précision et robustesse du mode mono, hors appareil : sinusoïdes et cordes synthétiques
 * (graves compris : G♯1 51,91 Hz · A1 55 Hz · B1 61,74 Hz), avec bruit blanc.
 */
class PitchDetectorTest {
    private val guitarFrequencies = listOf(51.91, 55.0, 61.74, 82.41, 110.0, 146.83, 196.0, 246.94, 329.63)

    private fun detectWindow(signal: DoubleArray, offset: Int = SAMPLE_RATE / 2): PitchEstimate {
        val detector = PitchDetector()
        val window = signal.copyOfRange(offset, offset + PitchDetector.WINDOW)
        val estimate = PitchEstimate()
        assertTrue("aucune hauteur détectée", detector.detect(window, estimate))
        return estimate
    }

    /** Fait passer [signal] dans toute la chaîne et renvoie la dernière hauteur stabilisée. */
    private fun runPipeline(signal: DoubleArray, targets: TunerTargets = TunerTargets.NONE): List<Double> {
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.targets = targets
        val input = TestSignals.toFloat(signal)
        val chunk = FloatArray(PitchDetector.HOP)
        val readings = ArrayList<Double>()
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            val frame = processor.process(chunk)
            readings += frame?.frequency ?: Double.NaN
            i += chunk.size
        }
        return readings
    }

    @Test
    fun pureSinesWithWhiteNoise() {
        for (f in guitarFrequencies) {
            // Bruit blanc à −20 dB sous le signal.
            val signal = TestSignals.addNoise(TestSignals.sine(f, 1.0, amplitude = 0.3), rms = 0.3 / Math.sqrt(2.0) * 0.1)
            val estimate = detectWindow(signal)
            val error = cents(estimate.frequency, f)
            assertTrue("$f Hz : écart $error cents", abs(error) < 0.5)
            assertTrue("$f Hz : clarté ${estimate.clarity}", estimate.clarity > 0.9)
            val reading = NoteMapper.nearest(estimate.frequency)
            assertEquals(NoteMapper.nearest(f).note, reading.note)
        }
    }

    @Test
    fun lowStringsHoldInHeavyNoise() {
        // G♯1, A1, B1 avec un bruit à −10 dB (conditions difficiles) : bonne octave, ±2 cents.
        for (f in listOf(51.91, 55.0, 61.74)) {
            val signal = TestSignals.addNoise(TestSignals.sine(f, 1.0, amplitude = 0.3), rms = 0.3 / Math.sqrt(2.0) * 0.316, seed = f.toLong())
            val estimate = detectWindow(signal)
            val error = cents(estimate.frequency, f)
            assertTrue("$f Hz : écart $error cents", abs(error) < 2.0)
            assertEquals(NoteMapper.nearest(f).note, NoteMapper.nearest(estimate.frequency).note)
        }
    }

    @Test
    fun weakFundamentalKeepsTheOctave() {
        // Micro de téléphone : fondamentale atténuée de 20 dB sur les cordes graves.
        for (f in listOf(51.91, 55.0, 61.74, 82.41)) {
            val signal = TestSignals.pluckedString(f, 1.0, partials = 14, fundamentalDb = -20.0, seed = 3)
            val estimate = detectWindow(signal)
            val error = cents(estimate.frequency, f)
            assertTrue("$f Hz : écart $error cents (octave ?)", abs(error) < 1.0)
        }
    }

    @Test
    fun inharmonicityIsCorrectedByPartialFit() {
        // Cordes filées : B ≈ 1e-4…6e-4. Le MPM seul est tiré vers l'aigu, le raffinement corrige.
        val cases = listOf(51.91 to 6e-4, 61.74 to 4e-4, 82.41 to 2e-4, 110.0 to 1.5e-4)
        for ((f, b) in cases) {
            val signal = TestSignals.pluckedString(f, 1.0, inharmonicity = b, partials = 16, rolloff = 0.8, fundamentalDb = -6.0, seed = 5)
            val estimate = detectWindow(signal)
            val mpmError = cents(estimate.mpmFrequency, f)
            val error = cents(estimate.frequency, f)
            assertTrue("$f Hz B=$b : écart raffiné $error cents (MPM $mpmError)", abs(error) < 0.5)
            assertTrue("$f Hz : le raffinement doit améliorer ($error vs $mpmError)", abs(error) <= abs(mpmError) + 0.05)
            assertTrue("$f Hz : partiels utilisés ${estimate.partials}", estimate.partials >= 3)
        }
    }

    @Test
    fun noiseAloneGivesNoPitch() {
        val noise = TestSignals.addNoise(TestSignals.silence(2.0), rms = 0.05, seed = 99)
        val readings = runPipeline(noise)
        assertTrue("du bruit ne doit pas produire de note", readings.all { it.isNaN() })
    }

    @Test
    fun silenceGivesNoPitch() {
        val readings = runPipeline(TestSignals.silence(1.0))
        assertTrue(readings.all { it.isNaN() })
    }

    @Test
    fun pipelineIsStableOnEveryStringOfEveryPreset() {
        for (tuning in Presets.required) {
            val targets = tuning.frequencies()
            for ((index, f) in targets.withIndex()) {
                val b = if (f < 100) 3e-4 else 1e-4
                val tone = TestSignals.pluckedString(f, 2.0, inharmonicity = b, partials = 14, fundamentalDb = -10.0, seed = index.toLong())
                val signal = TestSignals.addNoise(TestSignals.concat(TestSignals.silence(0.2), tone), rms = 0.003, seed = 17)
                val readings = runPipeline(signal, TunerTargets(targets))
                val settled = readings.drop(readings.size / 2).filter { !it.isNaN() }
                assertTrue("${tuning.name} corde $index : pas de lecture", settled.size > 10)
                val worst = settled.maxOf { abs(cents(it, f)) }
                assertTrue("${tuning.name} corde $index ($f Hz) : écart max $worst cents", worst < 1.0)
                val spread = settled.maxOf { cents(it, f) } - settled.minOf { cents(it, f) }
                assertTrue("${tuning.name} corde $index : instabilité $spread cents", spread < 0.5)
            }
        }
    }

    @Test
    fun octaveErrorAtEndOfLowNoteIsCorrected() {
        // Attaque riche (fondamentale présente), puis fin de note où seul le 2e partiel subsiste :
        // la détection brute bascule à l'octave, la garde d'octave la ramène.
        val f = 51.91
        val attack = TestSignals.pluckedString(f, 1.0, partials = 12, fundamentalDb = -6.0, decaySeconds = 10.0, seed = 8)
        val tail = TestSignals.mix(
            TestSignals.sine(f, 1.5, amplitude = 0.3 * 0.03, phase = 0.1),
            TestSignals.sine(2 * f, 1.5, amplitude = 0.3, phase = 0.7),
            TestSignals.sine(3 * f, 1.5, amplitude = 0.3 * 0.05, phase = 1.3),
        )
        val signal = TestSignals.concat(TestSignals.silence(0.2), attack, tail)
        val readings = runPipeline(signal)
        val late = readings.drop((readings.size * 0.8).toInt()).filter { !it.isNaN() }
        assertTrue("pas de lecture en fin de note", late.isNotEmpty())
        for (r in late) assertEquals("octave perdue : $r Hz", Note(8, 1), NoteMapper.nearest(r).note)
    }

    @Test
    fun lockedStringFoldsOctaveErrors() {
        // Signal ne contenant que le 2e partiel de G♯1 (fondamentale absente) : en mode manuel sur
        // la 7e corde (Drop G♯), la lecture est ramenée sur G♯1.
        val f = 51.91
        val signal = TestSignals.concat(TestSignals.silence(0.2), TestSignals.sine(2 * f, 1.5))
        val targets = Presets.DROP_G_SHARP_7.frequencies()
        val readings = runPipeline(signal, TunerTargets(targets, lockedIndex = 0))
        val settled = readings.drop(readings.size / 2).filter { !it.isNaN() }
        assertTrue(settled.isNotEmpty())
        for (r in settled) assertEquals(Note(8, 1), NoteMapper.nearest(r).note)
        // Sans verrou, G♯2 est une corde de l'accordage (5e) : la lecture reste sur G♯2.
        val auto = runPipeline(signal, TunerTargets(targets)).drop(readings.size / 2).filter { !it.isNaN() }
        for (r in auto) assertEquals(Note(8, 2), NoteMapper.nearest(r).note)
    }

    @Test
    fun holdsThenReleasesAfterSilence() {
        val tone = TestSignals.sine(110.0, 1.0)
        val signal = TestSignals.concat(TestSignals.silence(0.2), tone, TestSignals.silence(2.5))
        val processor = TunerProcessor(SAMPLE_RATE)
        val input = TestSignals.toFloat(signal)
        val chunk = FloatArray(PitchDetector.HOP)
        var sawHolding = false
        var last = processor.process(FloatArray(0))
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            last = processor.process(chunk) ?: last
            if (last?.holding == true) sawHolding = true
            i += chunk.size
        }
        assertTrue("la valeur doit être maintenue un moment", sawHolding)
        assertFalse("puis relâchée", last!!.hasPitch)
    }
}
