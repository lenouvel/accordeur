package com.blenouvel.accordeur

import com.blenouvel.accordeur.TestSignals.SAMPLE_RATE
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.SpectrumAnalyzer
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Harmony
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Page Spectre : une corde = une note (pas de partiels fantômes), un accord = ses notes et son nom. */
class SpectrumAnalyzerTest {
    /** Corde raide pincée, passée par le coupe-bas d'un micro de téléphone. */
    private fun string(note: String, seconds: Double = 2.0, amplitude: Double = 0.05, seed: Long = 1): DoubleArray {
        val n = Note.parse(note)
        val f = NoteMapper.frequencyOf(n)
        val b = when {
            n.midi < 40 -> 4e-4
            n.midi < 50 -> 1.5e-4
            else -> 6e-5
        }
        return TestSignals.stiffPluck(f, b, seconds, amplitude = amplitude, seed = seed)
    }

    /** Grattage : cordes décalées de 15 ms, du grave à l'aigu. */
    private fun strum(notes: String): DoubleArray {
        val parts = notes.split(' ').mapIndexed { i, name ->
            TestSignals.concat(TestSignals.silence(0.015 * i), string(name, seed = 10L + i))
        }
        val n = parts.maxOf { it.size }
        return DoubleArray(n) { k -> parts.sumOf { if (k < it.size) it[k] else 0.0 } }
    }

    /** Notes publiées à chaque trame, de [from] à [to] s (le son commence à 0,3 s). */
    private fun heard(signal: DoubleArray, lowestHz: Double, from: Double = 0.6, to: Double = 1.8): List<List<Int>> {
        val lead = TestSignals.silence(0.3)
        val input = TestSignals.addNoise(TestSignals.highPass(TestSignals.concat(lead, signal), 150.0), rms = 1e-5, seed = 3)
        val analyzer = SpectrumAnalyzer(SAMPLE_RATE)
        analyzer.lowestNoteHz = lowestHz
        val window = FloatArray(SpectrumAnalyzer.WINDOW)
        val out = ArrayList<List<Int>>()
        var end = PitchDetector.HOP
        while (end <= input.size) {
            for (i in window.indices) {
                val k = end - window.size + i
                window[i] = if (k >= 0) input[k].toFloat() else 0f
            }
            val frame = analyzer.analyze(window, sound = true)
            val t = end.toDouble() / SAMPLE_RATE
            if (t in from..to) out += frame.notes.map { it.midi }
            end += PitchDetector.HOP
        }
        return out
    }

    @Test
    fun singleStringIsOneNote() {
        for (name in listOf("G#1", "E2", "A2", "D3", "G3", "B3", "E4", "A4")) {
            val midi = Note.parse(name).midi
            val frames = heard(string(name), lowestHz = 46.0)
            val right = frames.count { it == listOf(midi) }
            assertTrue("$name : une seule note, la bonne, sur ${right}/${frames.size} trames ; ex. ${frames.firstOrNull { it != listOf(midi) }}", right >= 0.9 * frames.size)
        }
    }

    /** Nom affiché (comme à l'écran : le plus fréquent des 8 dernières trames) pour chaque trame. */
    private fun shownNames(voicing: String): List<String?> {
        val standard = Presets.STANDARD_6.frequencies().min() * 0.89
        val names = heard(strum(voicing), lowestHz = standard).map { notes -> Harmony.of(notes.map { Note.fromMidi(it) })?.chord?.symbol }
        return names.indices.map { i ->
            names.subList(maxOf(0, i - 7), i + 1).filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
    }

    private fun checkChords(cases: Map<String, String>) {
        val failures = ArrayList<String>()
        for ((voicing, expected) in cases) {
            val shown = shownNames(voicing)
            val right = shown.count { it == expected }
            println("$voicing → $expected : $right/${shown.size}")
            if (right < 0.8 * shown.size) failures += "$voicing → $expected affiché sur $right/${shown.size} trames (ex. ${shown.firstOrNull { it != expected }})"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun chordsAreNamed() {
        checkChords(
            mapOf(
                "A2 E3 A3 C4 E4" to "Am",
                "D3 A3 D4 F#4" to "D",
                "C3 E3 G3 C4 E4" to "C",
                "G2 B2 D3 G3 B3 F4" to "G7",
                "A2 E3 G3 C4 E4" to "Am7",
                "A2 E3 G3 D4 E4" to "A7sus4",
                "E2 B2 E3" to "E5",
            ),
        )
    }

    /**
     * Limite connue (voir README, « Reste à faire ») : dans ce Mi majeur synthétique, presque tous
     * les partiels de Mi2 sont partagés avec Mi3, Si3 et Mi4 ; la basse est perdue une trame sur
     * deux (« E/B »).
     */
    @Ignore("Limite connue : basse Mi2 perdue dans le Mi majeur synthétique, voir README")
    @Test
    fun rootInTheBassOfEMajor() {
        checkChords(mapOf("E2 B2 E3 G#3 B3 E4" to "E"))
    }

    @Test
    fun noiseAloneGivesNoNote() {
        val n = (2.0 * SAMPLE_RATE).toInt()
        for (noise in listOf(TestSignals.coloredNoise(n, 0.01, brown = false), TestSignals.coloredNoise(n, 0.01, brown = true))) {
            val frames = heard(noise, lowestHz = 38.0)
            assertTrue("notes dans le bruit : ${frames.filter { it.isNotEmpty() }.take(3)}", frames.count { it.isNotEmpty() } <= frames.size / 20)
        }
    }

    @Test
    fun processorPublishesTheSpectrumOnItsPage() {
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.targets = TunerTargets(Presets.STANDARD_6.frequencies())
        processor.spectrum = true
        val input = TestSignals.toFloat(TestSignals.concat(TestSignals.silence(0.5), string("A2")))
        val chunk = FloatArray(PitchDetector.HOP)
        var last = processor.process(chunk)
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            last = processor.process(chunk) ?: last
            i += chunk.size
        }
        val spectrum = last!!.spectrum!!
        assertEquals(SpectrumAnalyzer.DISPLAY_POINTS, spectrum.levels.size)
        assertEquals(listOf(Note.parse("A2").midi), spectrum.notes.map { it.midi })
        // Retour à l'accordeur : plus de spectre, la hauteur est de nouveau mesurée.
        processor.spectrum = false
        input.copyInto(chunk, 0, input.size - 2 * chunk.size, input.size - chunk.size)
        assertEquals(null, processor.process(chunk)!!.spectrum)
    }
}
