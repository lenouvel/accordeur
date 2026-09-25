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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/** Banque de sons : prises lancées au bouton, sans perte, silences compris, fiche exploitable pour le rejeu. */
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

    /** Silence, note, silence, note, silence (bruit de fond faible) : ~9,7 s. */
    private fun session(): DoubleArray {
        val a2 = TestSignals.stiffPluck(110.0, 1e-4, 1.5, amplitude = 0.05, seed = 3)
        val e2 = TestSignals.stiffPluck(82.41, 1.5e-4, 1.2, amplitude = 0.05, seed = 4)
        val signal = TestSignals.concat(
            TestSignals.silence(1.5), a2, TestSignals.silence(3.0), e2, TestSignals.silence(2.5),
        )
        return TestSignals.addNoise(signal, rms = 1e-5, seed = 9)
    }

    /** Actions au cours de la capture, à un instant donné (s). */
    private class Script(val at: Double, val action: (SoundRecorder) -> Unit)

    /** Capture simulée : chaîne d'analyse + enregistreur, comme le fil audio de l'app. */
    private fun capture(
        signal: DoubleArray,
        directory: File,
        float: Boolean,
        enabled: Boolean = true,
        script: List<Script> = emptyList(),
        recorder: SoundRecorder = SoundRecorder(directory),
    ): FloatArray {
        val input = FloatArray(signal.size) { i ->
            val x = signal[i].toFloat()
            if (float) x else (Math.round(x * 32768f).coerceIn(-32768, 32767) / 32768f)
        }
        val processor = TunerProcessor(SAMPLE_RATE)
        processor.targets = TunerTargets(tuning.frequencies())
        recorder.enabled = enabled
        recorder.context = context
        recorder.start(format(float))
        val pending = script.sortedBy { it.at }.toMutableList()
        val chunk = FloatArray(PitchDetector.HOP)
        var i = 0
        while (i + chunk.size <= input.size) {
            while (pending.isNotEmpty() && pending.first().at * SAMPLE_RATE <= i) pending.removeAt(0).action(recorder)
            input.copyInto(chunk, 0, i, i + chunk.size)
            val frame = processor.process(chunk)
            recorder.offer(chunk, chunk.size, frame, muted = false)
            i += chunk.size
            // La file absorbe l'écart de rythme avec un vrai micro ; on laisse le fil d'écriture
            // respirer pour ne pas saturer les 128 tampons.
            if ((i / chunk.size) % 32 == 0) Thread.sleep(5)
        }
        recorder.stop()
        return input
    }

    private fun press(at: Double) = Script(at) { it.startTake() }
    private fun release(at: Double) = Script(at) { it.stopTake() }

    private fun tempDir(): File = Files.createTempDirectory("banque").toFile().also { it.deleteOnExit() }

    @Test
    fun aTakeKeepsEverythingFromPressToStopLosslessly() {
        for (float in listOf(true, false)) {
            val directory = tempDir()
            val input = capture(session(), directory, float, script = listOf(press(2.0), release(8.0)))
            val sounds = BankFiles.sounds(directory)
            assertEquals("float=$float : une prise, un fichier", 1, sounds.size)
            val (wav, txt) = sounds[0]
            val sound = BankFiles.readWav(wav)
            val sheet = BankFiles.readSheet(txt)
            assertEquals(float, sound.float)
            assertEquals(SAMPLE_RATE, sound.sampleRate)
            assertEquals(if (float) "float32" else "pcm16", sheet["encodage"])
            assertEquals("2", sheet["format"])
            assertEquals("1", sheet["prise"])
            assertEquals("aucun bloc perdu", "0", sheet["blocs_perdus"])
            assertEquals("E2 A2 D3 G3 B3 E4", sheet["accordage"].split('|')[2])
            // Sans perte : les échantillons sont exactement ceux du micro.
            val first = sheet["premier_echantillon"].toInt()
            for (k in sound.samples.indices) assertEquals("échantillon $k", input[first + k], sound.samples[k], 0f)
            // ~1 s avant l'appui, puis tout jusqu'à l'arrêt, silences compris (les deux notes).
            val preroll = sheet["preroll_s"].toDouble()
            assertTrue("pré-enregistrement $preroll s", preroll in 0.9..1.1)
            assertEquals("début ~1 s avant l'appui", 2.0 - preroll, first / SAMPLE_RATE.toDouble(), 0.05)
            val seconds = sound.samples.size / SAMPLE_RATE.toDouble()
            assertEquals("fin à l'arrêt", 8.0, first / SAMPLE_RATE.toDouble() + seconds, 0.05)
            assertEquals(seconds, sheet["duree_s"].toDouble(), 1e-3)
            assertTrue("le silence entre les notes est gardé", sheet.frames.count { !it.signal } > 20)
            assertTrue("les deux notes sont dans la prise", sheet.frames.count { !it.frequency.isNaN() } > 20)
        }
    }

    @Test
    fun nothingIsRecordedWithoutPressingTheButton() {
        val directory = tempDir()
        capture(session(), directory, float = true)
        assertTrue(BankFiles.sounds(directory).isEmpty())
    }

    @Test
    fun nothingIsRecordedWhenTheButtonIsDisabled() {
        val directory = tempDir()
        capture(session(), directory, float = true, enabled = false, script = listOf(press(1.0)))
        assertTrue(BankFiles.sounds(directory).isEmpty())
    }

    @Test
    fun changingSettingsSplitsTheTake() {
        val directory = tempDir()
        val drop = context.copy(tuningId = Presets.DROP_D_6.id, tuningName = Presets.DROP_D_6.name, tuningNotes = "D2 A2 D3 G3 B3 E4")
        capture(session(), directory, float = true, script = listOf(press(1.0), Script(4.0) { it.context = drop }, release(7.0)))
        val sheets = BankFiles.sounds(directory).map { BankFiles.readSheet(it.second) }.sortedBy { it["premier_echantillon"].toLong() }
        assertEquals("deux fichiers pour la même prise", 2, sheets.size)
        assertEquals(sheets[0]["prise"], sheets[1]["prise"])
        assertEquals(Presets.STANDARD_6.id, sheets[0]["accordage"].split('|')[0])
        assertEquals(Presets.DROP_D_6.id, sheets[1]["accordage"].split('|')[0])
        // Contigus : le second reprend exactement où le premier s'arrête, sans pré-enregistrement.
        val end = sheets[0]["premier_echantillon"].toLong() + Math.round(sheets[0]["duree_s"].toDouble() * SAMPLE_RATE)
        assertEquals(end.toDouble(), sheets[1]["premier_echantillon"].toDouble(), 1.0)
        assertEquals(0.0, sheets[1]["preroll_s"].toDouble(), 1e-9)
    }

    @Test
    fun aForgottenTakeStopsByItself() {
        val directory = tempDir()
        val recorder = SoundRecorder(directory, maxTakeSeconds = 3.0)
        capture(session(), directory, float = true, script = listOf(press(1.0)), recorder = recorder)
        val sounds = BankFiles.sounds(directory)
        assertEquals(1, sounds.size)
        val seconds = BankFiles.readWav(sounds[0].first).samples.size / SAMPLE_RATE.toDouble()
        assertTrue("prise limitée : $seconds s", seconds in 3.0..3.2)
        assertFalse("le bouton revient au repos", recorder.armed)
    }

    @Test
    fun replayReproducesTheRecordedReadings() {
        val directory = tempDir()
        capture(session(), directory, float = true, script = listOf(press(0.5), release(9.5)))
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
            assertTrue("${wav.name} : $compared trames comparées", compared >= 20)
        }
    }

    @Test
    fun unwritableBankDoesNotBreakTheTuner() {
        // Dossier impossible à créer (un fichier porte déjà ce nom) : aucune exception, aucun son.
        val blocker = Files.createTempFile("banque", ".bloque").toFile().also { it.deleteOnExit() }
        val directory = File(blocker, "banque")
        capture(session(), directory, float = true, script = listOf(press(1.0), release(8.0)))
        assertTrue(!directory.exists())
    }

    @Test
    fun bankIsCappedOldestFirst() {
        val directory = tempDir()
        // Plafond de 1,3 Mo : une prise de 2 s + 1 s de pré-enregistrement (~0,58 Mo) ; deux tiennent,
        // pas trois : la plus ancienne part.
        capture(
            session(), directory, float = true,
            script = listOf(press(1.0), release(3.0), press(4.0), release(6.0), press(7.0), release(9.0)),
            recorder = SoundRecorder(directory, maxBytes = 1_300_000),
        )
        val sheets = BankFiles.sounds(directory).map { BankFiles.readSheet(it.second) }
        assertEquals(2, sheets.size)
        assertEquals("les plus récentes restent", setOf("2", "3"), sheets.map { it["prise"] }.toSet())
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 1_300_000)
    }
}
