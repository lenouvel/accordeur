package com.blenouvel.accordeur

import com.blenouvel.accordeur.TestSignals.SAMPLE_RATE
import com.blenouvel.accordeur.audio.CaptureFormat
import com.blenouvel.accordeur.audio.MicSource
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.RecordingContext
import com.blenouvel.accordeur.audio.SoundRecorder
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/** Banque de sons : un son par période de jeu, pré-roll, sans perte, fiche exploitable pour le rejeu. */
class SoundRecorderTest {
    private val tuning = Presets.STANDARD_6
    private val context = RecordingContext(
        mode = TunerMode.MONO, detection = "AUTO", lockedString = -1, a4 = 440.0,
        tuningId = tuning.id, tuningName = tuning.name, tuningNotes = "E2 A2 D3 G3 B3 E4",
    )

    private fun format(float: Boolean) = CaptureFormat(
        sampleRate = SAMPLE_RATE, floatSamples = float, source = MicSource.VOICE_PERFORMANCE,
        requestedSource = MicSource.AUTO, unprocessedDeclared = false,
        device = "test", android = "JVM", appVersion = "test",
    )

    /** Silence, note, silence, note, silence (bruit de fond faible). */
    private fun session(): DoubleArray {
        val a2 = TestSignals.stiffPluck(110.0, 1e-4, 1.5, amplitude = 0.05, seed = 3)
        val e2 = TestSignals.stiffPluck(82.41, 1.5e-4, 1.2, amplitude = 0.05, seed = 4)
        val signal = TestSignals.concat(
            TestSignals.silence(1.5), a2, TestSignals.silence(3.0), e2, TestSignals.silence(2.5),
        )
        return TestSignals.addNoise(signal, rms = 1e-5, seed = 9)
    }

    /** Capture simulée : chaîne d'analyse + enregistreur, comme le fil audio de l'app. */
    private fun capture(signal: DoubleArray, directory: File, float: Boolean, enabled: Boolean = true): FloatArray {
        val input = FloatArray(signal.size) { i ->
            val x = signal[i].toFloat()
            if (float) x else (Math.round(x * 32768f).coerceIn(-32768, 32767) / 32768f)
        }
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.targets = TunerTargets(tuning.frequencies())
        val recorder = SoundRecorder(directory)
        recorder.enabled = enabled
        recorder.context = context
        recorder.start(format(float))
        val chunk = FloatArray(PitchDetector.HOP)
        var i = 0
        while (i + chunk.size <= input.size) {
            input.copyInto(chunk, 0, i, i + chunk.size)
            val frame = processor.process(chunk)
            recorder.offer(chunk, chunk.size, frame, muted = false)
            i += chunk.size
            // Rythme d'un vrai micro (43 ms par bloc) inutile : la file absorbe, mais on laisse le
            // fil d'écriture respirer pour ne pas saturer les 128 tampons.
            if ((i / chunk.size) % 32 == 0) Thread.sleep(5)
        }
        recorder.stop()
        return input
    }

    private fun tempDir(): File = Files.createTempDirectory("banque").toFile().also { it.deleteOnExit() }

    @Test
    fun oneLosslessSoundPerNoteWithPreroll() {
        for (float in listOf(true, false)) {
            val directory = tempDir()
            val input = capture(session(), directory, float)
            val sounds = BankFiles.sounds(directory)
            assertEquals("float=$float : un son par note", 2, sounds.size)
            for ((wav, txt) in sounds) {
                val sound = BankFiles.readWav(wav)
                val sheet = BankFiles.readSheet(txt)
                assertEquals(float, sound.float)
                assertEquals(SAMPLE_RATE, sound.sampleRate)
                assertEquals(if (float) "float32" else "pcm16", sheet["encodage"])
                assertEquals("aucun bloc perdu", "0", sheet["blocs_perdus"])
                assertEquals("E2 A2 D3 G3 B3 E4", sheet["accordage"].split('|')[2])
                // Sans perte : les échantillons sont exactement ceux du micro.
                val first = sheet["premier_echantillon"].toInt()
                for (k in sound.samples.indices) assertEquals("échantillon $k", input[first + k], sound.samples[k], 0f)
                // Pré-roll : ~1 s de silence avant le son, et le son lui-même a été entendu.
                val heard = sheet.frames.first { it.signal }
                assertTrue("pré-roll ${heard.time} s", heard.time in 0.9..1.3)
                assertTrue(sheet.frames.any { !it.frequency.isNaN() })
                assertEquals(sound.samples.size / SAMPLE_RATE.toDouble(), sheet["duree_s"].toDouble(), 1e-3)
            }
            // Le silence entre les notes n'est pas gardé.
            val total = sounds.sumOf { BankFiles.readWav(it.first).samples.size }
            assertTrue("durée gardée ${total / SAMPLE_RATE.toDouble()} s", total < input.size * 0.8)
        }
    }

    @Test
    fun replayReproducesTheRecordedReadings() {
        val directory = tempDir()
        capture(session(), directory, float = true)
        for ((wav, txt) in BankFiles.sounds(directory)) {
            val sound = BankFiles.readWav(wav)
            val sheet = BankFiles.readSheet(txt)
            val processor = TunerProcessor(SAMPLE_RATE)
            processor.targets = TunerTargets(tuning.frequencies())
            val chunk = FloatArray(PitchDetector.HOP)
            val replayed = ArrayList<Double>()
            var i = 0
            while (i + chunk.size <= sound.samples.size) {
                sound.samples.copyInto(chunk, 0, i, i + chunk.size)
                replayed += processor.process(chunk)?.frequency ?: Double.NaN
                i += chunk.size
            }
            // Même note affichée que pendant l'enregistrement, à moins d'un cent.
            val recorded = sheet.frames.map { it.frequency }
            var compared = 0
            for (k in 0 until minOf(recorded.size, replayed.size)) {
                val a = recorded[k]
                val b = replayed[k]
                if (a.isNaN() || b.isNaN()) continue
                compared++
                assertEquals(NoteMapper.nearest(a).note, NoteMapper.nearest(b).note)
                assertTrue("trame $k : $a / $b Hz", abs(1200 * Math.log(a / b) / Math.log(2.0)) < 1.0)
            }
            assertTrue("${wav.name} : $compared trames comparées", compared >= 10)
        }
    }

    @Test
    fun unwritableBankDoesNotBreakTheTuner() {
        // Dossier impossible à créer (un fichier porte déjà ce nom) : aucune exception, aucun son.
        val blocker = Files.createTempFile("banque", ".bloque").toFile().also { it.deleteOnExit() }
        val directory = File(blocker, "banque")
        capture(session(), directory, float = true)
        assertTrue(!directory.exists())
    }

    @Test
    fun nothingIsRecordedWhenDisabled() {
        val directory = tempDir()
        capture(session(), directory, float = true, enabled = false)
        assertTrue(BankFiles.sounds(directory).isEmpty())
    }

    @Test
    fun bankIsCappedOldestFirst() {
        val directory = tempDir()
        // Plafond de 1 Mo : un son (~0,7 Mo) tient, pas deux ; le plus ancien part.
        val input = session()
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.targets = TunerTargets(tuning.frequencies())
        val recorder = SoundRecorder(directory, maxBytes = 1_000_000)
        recorder.enabled = true
        recorder.context = context
        recorder.start(format(true))
        val chunk = FloatArray(PitchDetector.HOP)
        var i = 0
        while (i + chunk.size <= input.size) {
            for (k in chunk.indices) chunk[k] = input[i + k].toFloat()
            recorder.offer(chunk, chunk.size, processor.process(chunk), muted = false)
            i += chunk.size
            if ((i / chunk.size) % 32 == 0) Thread.sleep(5)
        }
        recorder.stop()
        val sounds = BankFiles.sounds(directory)
        assertEquals(1, sounds.size)
        assertTrue("le plus récent reste", BankFiles.readSheet(sounds[0].second)["premier_echantillon"].toLong() > 3 * SAMPLE_RATE)
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 1_000_000)
    }
}
