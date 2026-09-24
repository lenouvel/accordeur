package com.blenouvel.accordeur

import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PolyReading
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2

/**
 * Non-régression sur la banque de sons enregistrée sur le téléphone : chaque son est rejoué dans
 * la chaîne actuelle, avec les réglages de sa fiche, et comparé à ce qui avait été affiché.
 *
 * Dossier : variable d'environnement ACCORDEUR_BANQUE, sinon « testbank » à la racine du projet
 * (archive exportée depuis l'application, décompressée). Sans banque, le test ne fait rien.
 *
 * Le rapport est informatif (l'affichage enregistré venait d'une version peut-être moins bonne).
 * Avec ACCORDEUR_BANQUE_STRICT=1, le test échoue si la version actuelle lit moins de trames que
 * l'enregistrement ou affiche une autre note sur plus de 5 % des trames lues des deux côtés.
 */
class BankReplayTest {
    private fun bankDirectory(): File? {
        val env = System.getenv("ACCORDEUR_BANQUE")
        val candidates = if (env != null) listOf(File(env)) else listOf(File("../testbank"), File("testbank"))
        return candidates.map { if (File(it, "banque").isDirectory) File(it, "banque") else it }.firstOrNull { it.isDirectory }
    }

    private class Score(var frames: Int = 0, var recordedPitch: Int = 0, var replayedPitch: Int = 0, var both: Int = 0, var sameNote: Int = 0, var centsSum: Double = 0.0)

    @Test
    fun replayBank() {
        val directory = bankDirectory()
        if (directory == null) {
            println("Banque de sons absente (ACCORDEUR_BANQUE ou testbank/) : rien à rejouer.")
            return
        }
        val sounds = BankFiles.sounds(directory)
        println("Banque : ${directory.absolutePath} — ${sounds.size} sons")
        val total = Score()
        var worse = 0
        for ((wav, txt) in sounds) {
            val sheet = BankFiles.readSheet(txt)
            val sound = BankFiles.readWav(wav)
            val mode = TunerMode.valueOf(sheet["mode"])
            val a4 = sheet["la4"].toDouble()
            val notes = sheet["accordage"].split('|')[2].split(' ').map { Note.parse(it) }
            val targets = DoubleArray(notes.size) { NoteMapper.frequencyOf(notes[it], a4) }
            val locked = sheet["corde_verrouillee"].toInt()

            val processor = TunerProcessor(sound.sampleRate)
            processor.mode = mode
            processor.targets = TunerTargets(targets, if (mode == TunerMode.MONO) locked else -1)
            val chunk = FloatArray(PitchDetector.HOP)
            val replayed = ArrayList<Double>()
            var lastPoly: PolyReading? = null
            var i = 0
            while (i + chunk.size <= sound.samples.size) {
                sound.samples.copyInto(chunk, 0, i, i + chunk.size)
                val frame = processor.process(chunk)
                replayed += frame?.let { if (it.holding) Double.NaN else it.frequency } ?: Double.NaN
                lastPoly = frame?.poly ?: lastPoly
                i += chunk.size
            }

            val score = Score()
            val recorded = sheet.frames
            for (k in 0 until minOf(recorded.size, replayed.size)) {
                val a = if (recorded[k].holding) Double.NaN else recorded[k].frequency
                val b = replayed[k]
                score.frames++
                if (!a.isNaN()) score.recordedPitch++
                if (!b.isNaN()) score.replayedPitch++
                if (!a.isNaN() && !b.isNaN()) {
                    score.both++
                    if (NoteMapper.nearest(a, a4).note == NoteMapper.nearest(b, a4).note) {
                        score.sameNote++
                        score.centsSum += abs(1200.0 * log2(b / a))
                    }
                }
            }
            total.frames += score.frames
            total.recordedPitch += score.recordedPitch
            total.replayedPitch += score.replayedPitch
            total.both += score.both
            total.sameNote += score.sameNote
            total.centsSum += score.centsSum
            val regression = score.replayedPitch < score.recordedPitch || (score.both > 0 && score.sameNote < 0.95 * score.both)
            if (regression) worse++
            val poly = if (mode == TunerMode.POLY) {
                "  poly enregistré « ${recorded.lastOrNull { it.poly.isNotEmpty() }?.poly ?: "—"} » / rejoué « ${lastPoly?.strings?.joinToString(",") { if (it.detected) String.format(Locale.ROOT, "%+.1f", it.cents) else "--" } ?: "—"} »"
            } else {
                ""
            }
            println(
                String.format(
                    Locale.ROOT,
                    "%s %-5s %s : lu %d → %d trames sur %d, même note %d/%d, écart moyen %.2f ¢%s%s",
                    if (regression) "✗" else "✓", mode, wav.name, score.recordedPitch, score.replayedPitch, score.frames,
                    score.sameNote, score.both, if (score.sameNote > 0) score.centsSum / score.sameNote else 0.0,
                    if (sheet.keys["source"] != null) " [${sheet["source"]}, ${sheet["appareil"]}]" else "", poly,
                ),
            )
        }
        println(
            String.format(
                Locale.ROOT,
                "TOTAL : trames lues %d → %d, même note %d/%d, écart moyen %.2f ¢, sons en recul : %d/%d",
                total.recordedPitch, total.replayedPitch, total.sameNote, total.both,
                if (total.sameNote > 0) total.centsSum / total.sameNote else 0.0, worse, sounds.size,
            ),
        )
        if (System.getenv("ACCORDEUR_BANQUE_STRICT") == "1") {
            assertTrue("$worse son(s) en recul par rapport à l'enregistrement", worse == 0)
        }
    }
}
