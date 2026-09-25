package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.SpectrumAnalyzer
import com.blenouvel.accordeur.model.Harmony
import com.blenouvel.accordeur.model.Note
import org.junit.Test
import java.io.File
import java.util.Locale

/** Instantanés réels de l'analyseur (pour les rendus d'écran de la page Spectre). */
class SpectrumSnapshot {
    private val out = File("/tmp/claude-0/-home-user-accordeur/02c0f76a-3ff2-55ad-afc2-406e7976f715/scratchpad/screens/data").also { it.mkdirs() }

    private fun snapshot(name: String, signal: DoubleArray, lowest: Double, at: Double) {
        val phone = PhoneSim.applyMic(signal, PhoneSim.VOICE_150_4)
        val n = PhoneSim.noise(phone.size, -95.0, seed = 5); for (i in phone.indices) phone[i] += n[i]
        val an = SpectrumAnalyzer(SR); an.lowestNoteHz = lowest
        val w = FloatArray(SpectrumAnalyzer.WINDOW)
        var end = PitchDetector.HOP
        var last: com.blenouvel.accordeur.audio.SpectrumFrame? = null
        while (end <= (at * SR).toInt()) {
            for (i in w.indices) { val k = end - w.size + i; w[i] = if (k >= 0) phone[k].toFloat() else 0f }
            last = an.analyze(w, true)
            end += PitchDetector.HOP
        }
        val f = last!!
        val text = buildString {
            append(f.levels.joinToString(" ") { String.format(Locale.ROOT, "%.2f", it) }).append('\n')
            append(f.peaks.joinToString(";") { String.format(Locale.ROOT, "%.3f %.2f", it.frequency, it.db) }).append('\n')
            append(f.notes.joinToString(";") { String.format(Locale.ROOT, "%.3f %d", it.frequency, it.midi) }).append('\n')
        }
        File(out, "$name.txt").writeText(text)
        val h = Harmony.of(f.notes.map { Note.fromMidi(it.midi) })
        println("$name : notes ${f.notes.map { it.midi }} → ${h?.chord?.symbol ?: h?.notes}")
    }

    @Test fun snapshots() {
        val a2 = PhoneSim.scaleToPeakRms(PhoneSim.load("FluidR3_GM", "acoustic_guitar_steel", "A2"), -24.0)
        snapshot("note_a2", DoubleArray(SR / 2 + a2.size) { if (it >= SR / 2) a2[it - SR / 2] else 0.0 }, 73.42, 1.4)
        for ((name, voicing) in listOf("A7sus4" to "A2 E3 G3 D4 E4", "Am7" to "A2 E3 G3 C4 E4", "E" to "E2 B2 E3 G#3 B3 E4")) {
            val notes = voicing.split(' ').map { Note.parse(it) }
            for (src in listOf("FluidR3_GM" to "acoustic_guitar_steel", "MusyngKite" to "acoustic_guitar_steel", "FatBoy" to "acoustic_guitar_steel")) {
                val raw = ChordBench.strum(src.first, src.second, notes, seed = name.hashCode().toLong())
                val scaled = DoubleArray(raw.size) { raw[it] * 2.0 }
                snapshot("chord_${name}_${src.first}", DoubleArray(SR / 2 + scaled.size) { if (it >= SR / 2) scaled[it - SR / 2] else 0.0 }, 73.42, 1.3)
            }
        }
    }
}
