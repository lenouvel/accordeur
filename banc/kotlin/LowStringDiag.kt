package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PitchEstimate
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * Corde grave captée par un téléphone : taux de détection et justesse de l'octave, sur de vrais
 * échantillons de guitare, selon la réponse du micro (brut / chemin « voix ») et le niveau.
 */
class LowStringDiag {
    data class Case(val note: String, val tuning: Tuning)

    private val cases = listOf(
        Case("Ab1", Presets.DROP_G_SHARP_7),
        Case("A1", Presets.DROP_A_7),
        Case("B1", Presets.STANDARD_7),
        Case("D2", Presets.DROP_D_6),
        Case("E2", Presets.STANDARD_6),
        Case("A2", Presets.STANDARD_6),
        Case("E4", Presets.STANDARD_6),
    )

    private fun label(f: Double) = NoteNames.label(NoteMapper.nearest(f).note, Notation.ENGLISH)

    data class Result(val rate: Double, val correct: Double, val notes: String, val firstMs: Int)

    private fun evaluate(tone: DoubleArray, expected: Note, tuning: Tuning, noiseDb: Double): Result {
        val (signal, start) = PhoneSim.scene(tone, noiseDb)
        val frames = PhoneSim.run(signal, TunerTargets(tuning.frequencies()))
        val hop = PitchDetector.HOP
        val from = (start + 0.15 * SR).toInt() / hop
        val to = min(frames.size, (start + min(tone.size.toDouble(), 1.6 * SR)).toInt() / hop)
        var n = 0
        var hits = 0
        var good = 0
        var first = -1
        val seen = LinkedHashMap<String, Int>()
        for (i in from until to) {
            val f = frames[i] ?: continue
            n++
            if (f.hasPitch && !f.holding) {
                hits++
                if (first < 0) first = i
                val note = NoteMapper.nearest(f.frequency).note
                if (note == expected) good++
                val k = label(f.frequency)
                seen[k] = (seen[k] ?: 0) + 1
            }
        }
        val firstMs = if (first < 0) -1 else (((first + 1) * hop - start) * 1000.0 / SR).toInt()
        return Result(hits.toDouble() / n, if (hits == 0) 0.0 else good.toDouble() / hits, seen.entries.joinToString(" ") { "${it.key}×${it.value}" }, firstMs)
    }

    @Test
    fun lowStrings() {
        val mics = listOf(PhoneSim.RAW, PhoneSim.VOICE_120, PhoneSim.VOICE_150_4, PhoneSim.VOICE_200_4)
        val levels = listOf(-50.0, -65.0, -75.0)
        val notes = listOf("Ab1" to Presets.DROP_G_SHARP_7, "A1" to Presets.DROP_A_7, "B1" to Presets.STANDARD_7, "D2" to Presets.DROP_D_6,
            "E2" to Presets.STANDARD_6, "A2" to Presets.STANDARD_6, "D3" to Presets.STANDARD_6, "G3" to Presets.STANDARD_6,
            "B3" to Presets.STANDARD_6, "E4" to Presets.STANDARD_6)
        var totalFrames = 0.0; var totalHits = 0.0; var totalWrong = 0
        for (sf in listOf("FluidR3_GM", "MusyngKite")) for (inst in listOf("acoustic_guitar_steel", "electric_guitar_clean", "acoustic_guitar_nylon")) {
            var frames = 0.0; var hits = 0.0; var wrong = 0
            val problems = ArrayList<String>()
            for ((note, tuning) in notes) {
                val raw = PhoneSim.load(sf, inst, note)
                val expected = Note.parse(note)
                for (mic in mics) for (level in levels) {
                    val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(raw, mic), level)
                    val r = evaluate(tone, expected, tuning, noiseDb = -92.0)
                    frames += 1; hits += r.rate
                    val bad = Math.round((1 - r.correct) * r.rate * 34).toInt()
                    wrong += bad
                    if (r.correct < 1.0 || (level > -70 && r.rate < 0.85)) problems += String.format("%s %s %.0f dB : %3.0f%% ok %3.0f%% %s", note, mic.name, level, 100 * r.rate, 100 * r.correct, r.notes)
                }
            }
            totalFrames += frames; totalHits += hits; totalWrong += wrong
            println(String.format("=== %s / %s : détection moyenne %.1f %%, trames fausses ≈ %d", sf, inst, 100 * hits / frames, wrong))
            for (p in problems) println("   " + p)
        }
        println(String.format("TOTAL : détection moyenne %.1f %%, trames fausses ≈ %d", 100 * totalHits / totalFrames, totalWrong))
    }

    /** Détail trame par trame : MPM brut (fréquence, clarté) et sortie stabilisée. */
    @Test
    fun frameDetail() {
        for ((note, mic) in listOf("Ab1" to PhoneSim.VOICE_150_4, "E2" to PhoneSim.VOICE_150_4, "B1" to PhoneSim.VOICE_120)) {
            val raw = PhoneSim.load("FluidR3_GM", "acoustic_guitar_steel", note)
            val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(raw, mic), -65.0)
            val (signal, start) = PhoneSim.scene(tone, -92.0)
            val tuning = if (note == "Ab1") Presets.DROP_G_SHARP_7 else if (note == "B1") Presets.STANDARD_7 else Presets.STANDARD_6
            val frames = PhoneSim.run(signal, TunerTargets(tuning.frequencies()))
            val pre = Preprocessor(SR)
            val filtered = DoubleArray(signal.size) { pre.process(signal[it]) }
            val det = PitchDetector()
            val est = PitchEstimate()
            println("=== $note ${mic.name} -65 dBFS (durée ${"%.2f".format(tone.size.toDouble() / SR)} s)")
            val hop = PitchDetector.HOP
            for (i in frames.indices) {
                val end = (i + 1) * hop
                if (end < start || end > start + tone.size + hop * 4) continue
                val w = filtered.copyOfRange(end - PitchDetector.WINDOW, end)
                val found = det.detect(w, est)
                val fr = frames[i]
                println(String.format("  t=%5.2f niv %6.1f gate %-5s | MPM %8.2f (%s) clarté %.3f → %8.2f (%s) partiels %d | affiché %s",
                    (end - start).toDouble() / SR, fr?.levelDb ?: 0.0, fr?.signal,
                    if (found) est.mpmFrequency else 0.0, if (found) label(est.mpmFrequency) else "-", est.clarity,
                    if (found) est.frequency else 0.0, if (found) label(est.frequency) else "-", est.partials,
                    if (fr != null && fr.hasPitch) label(fr.frequency) + (if (fr.holding) "(h)" else "") + String.format(" %+.1f", NoteMapper.nearest(fr.frequency).cents) else "—"))
            }
        }
    }
}
