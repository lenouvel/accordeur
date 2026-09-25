package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.SpectrumAnalyzer
import com.blenouvel.accordeur.model.ChordNamer
import com.blenouvel.accordeur.model.Note
import org.junit.Test
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Banc des accords : notes réelles (soundfonts) mêlées en grattage, micro de téléphone simulé. */
class ChordBench {
    companion object {
        private val FLATS = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
        fun fileName(note: Note) = FLATS[note.pitchClass] + note.octave

        val CHORDS = linkedMapOf(
            "E" to "E2 B2 E3 G#3 B3 E4", "A" to "A2 E3 A3 C#4 E4", "D" to "D3 A3 D4 F#4",
            "G" to "G2 B2 D3 G3 B3 G4", "C" to "C3 E3 G3 C4 E4", "Am" to "A2 E3 A3 C4 E4",
            "Em" to "E2 B2 E3 G3 B3 E4", "Dm" to "D3 A3 D4 F4", "E7" to "E2 B2 D3 G#3 B3 E4",
            "A7" to "A2 E3 G3 C#4 E4", "D7" to "D3 A3 C4 F#4", "G7" to "G2 B2 D3 G3 B3 F4",
            "C7" to "C3 E3 A#3 C4 E4", "Cmaj7" to "C3 E3 G3 B3 E4", "Am7" to "A2 E3 G3 C4 E4",
            "Dm7" to "D3 A3 C4 F4", "Em7" to "E2 B2 E3 G3 D4 E4", "Fmaj7" to "F3 A3 C4 E4",
            "F" to "F2 C3 F3 A3 C4 F4", "Bm" to "B2 F#3 B3 D4 F#4", "B♭" to "A#2 F3 A#3 D4 F4",
            "F♯m" to "F#2 C#3 F#3 A3 C#4 F#4", "E5" to "E2 B2 E3", "A5" to "A2 E3 A3",
            "Dsus4" to "D3 A3 D4 G4", "Dsus2" to "D3 A3 D4 E4", "Asus2" to "A2 E3 A3 B3 E4",
            "Asus4" to "A2 E3 A3 D4 E4", "Cadd9" to "C3 E3 G3 D4 E4", "Bm7♭5" to "B2 F3 A3 D4",
            "A7sus4" to "A2 E3 G3 D4 E4", "G♯11" to "G#1 D#2 G#2 C#3 F#3 A#3 D#4", "G♯5" to "G#1 D#2 G#2",
        )

        /** Grattage : chaque note décalée de [strumMs], gain aléatoire ±[spreadDb]. */
        fun strum(sf: String, inst: String, notes: List<Note>, strumMs: Double = 15.0, spreadDb: Double = 3.0, seed: Long = 1): DoubleArray {
            val r = Random(seed)
            val parts = notes.map { PhoneSim.load(sf, inst, fileName(it)) }
            val length = parts.maxOf { it.size } + (notes.size * strumMs / 1000 * SR).toInt()
            val out = DoubleArray(length)
            parts.forEachIndexed { i, x ->
                val offset = (i * strumMs / 1000 * SR).toInt()
                // Chaque note ramenée au même RMS crête, puis ±spread dB.
                val scaled = PhoneSim.scaleToPeakRms(x, -30.0 + (r.nextDouble() * 2 - 1) * spreadDb)
                for (k in scaled.indices) out[offset + k] += scaled[k]
            }
            return out
        }

        /** Résultat d'une analyse : pour chaque trame (hop), notes publiées. */
        fun analyze(signal: DoubleArray, lead: Int, lowest: Double = 46.25): List<List<Int>> {
            val an = SpectrumAnalyzer(SR)
            an.lowestNoteHz = lowest
            val w = FloatArray(SpectrumAnalyzer.WINDOW)
            val out = ArrayList<List<Int>>()
            var end = PitchDetector.HOP
            while (end <= signal.size) {
                for (i in 0 until w.size) { val k = end - w.size + i; w[i] = if (k >= 0) signal[k].toFloat() else 0f }
                val frame = an.analyze(w, sound = end > lead)
                out += frame.notes.map { it.midi }
                end += PitchDetector.HOP
            }
            return out
        }

        fun chordName(midis: List<Int>): String {
            if (midis.isEmpty()) return "—"
            val pcs = midis.map { it.mod(12) }.toSet()
            if (pcs.size == 1) return "note"
            return ChordNamer.name(pcs, midis.min().mod(12))?.symbol ?: "?"
        }
    }

    private val sources = listOf("FluidR3_GM", "MusyngKite", "FatBoy").flatMap { sf ->
        listOf("acoustic_guitar_steel", "acoustic_guitar_nylon", "electric_guitar_clean").map { sf to it }
    }

    private class ChordResult(val expected: String, val rate: Double, val voted: Double, val errors: Map<String, Int>, val sample: String)

    @Test
    fun chords() {
        val mic = PhoneSim.VOICE_150_4
        val jobs = sources.flatMap { src -> CHORDS.entries.map { src to it } }
        val results = jobs.parallelStream().map { (src, chord) ->
            val (sf, inst) = src
            val (expected, voicing) = chord
            val notes = voicing.split(' ').map { Note.parse(it) }
            val lead = SR / 2
            val raw = strum(sf, inst, notes, seed = expected.hashCode().toLong())
            val sig = DoubleArray(lead + raw.size) { if (it >= lead) raw[it - lead] else 0.0 }
            val phone = PhoneSim.applyMic(sig, mic)
            val n = PhoneSim.noise(phone.size, -90.0, seed = 5); for (i in phone.indices) phone[i] += n[i]
            val lowest = if (notes.any { it.midi < 40 }) 46.25 else 73.42
            val frames = analyze(phone, lead, lowest)
            val from = (lead + 0.4 * SR).toInt() / PitchDetector.HOP
            val to = min(frames.size, (lead + 1.6 * SR).toInt() / PitchDetector.HOP)
            var ok = 0
            val errs = LinkedHashMap<String, Int>()
            val sets = LinkedHashMap<String, Int>()
            for (i in from until to) {
                val name = chordName(frames[i])
                if (name == expected) ok++ else {
                    errs.merge(name, 1, Int::plus)
                    sets.merge(frames[i].joinToString(" ") { m -> fileName(Note.fromMidi(m)) }, 1, Int::plus)
                }
            }
            val sample = sets.maxByOrNull { it.value }?.let { "${sf.take(5)}/${inst.substringAfter('_').substringAfter('_')}: ${it.key} ×${it.value}" } ?: ""
            // Ce que l'écran montrerait : nom majoritaire sur les 8 dernières trames.
            var votedOk = 0
            for (i in from until to) {
                val names = (maxOf(0, i - 7)..i).map { chordName(frames[it]) }.filter { it != "—" }
                val shown = names.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "—"
                if (shown == expected) votedOk++
            }
            ChordResult(expected, ok.toDouble() / (to - from), votedOk.toDouble() / (to - from), errs, sample)
        }.toList()
        println("=== Accords (micro ${mic.name}) : taux de trames au bon nom, 0,4–1,6 s après le grattage")
        for ((name, list) in results.groupBy { it.expected }) {
            val errs = LinkedHashMap<String, Int>()
            for (r in list) for ((k, v) in r.errors) errs.merge(k, v, Int::plus)
            val top = errs.entries.sortedByDescending { it.value }.take(4).joinToString(" ") { "${it.key}×${it.value}" }
            val sample = list.firstOrNull { it.sample.isNotEmpty() }?.sample ?: ""
            println(String.format("  %-7s %5.1f %% (affiché %5.1f %%)  %-40s | ex. %s", name, 100 * list.map { it.rate }.average(), 100 * list.map { it.voted }.average(), top, sample))
        }
        println(String.format("TOTAL accords : %.1f %% par trame, %.1f %% affichés (vote sur 8 trames)", 100 * results.map { it.rate }.average(), 100 * results.map { it.voted }.average()))
    }

    private class NoteResult(val frames: Int, val single: Int, val right: Int, val wrong: Map<String, Int>)

    @Test
    fun singleNotes() {
        val mic = PhoneSim.VOICE_150_4
        val jobs = sources.flatMap { src -> (32..76).map { src to it } }
        val results = jobs.parallelStream().map { (src, midi) ->
            val (sf, inst) = src
            val note = Note.fromMidi(midi)
            val raw = PhoneSim.scaleToPeakRms(PhoneSim.load(sf, inst, fileName(note)), -30.0)
            val lead = SR / 2
            val sig = DoubleArray(lead + raw.size) { if (it >= lead) raw[it - lead] else 0.0 }
            val phone = PhoneSim.applyMic(sig, mic)
            val n = PhoneSim.noise(phone.size, -90.0, seed = 5); for (i in phone.indices) phone[i] += n[i]
            val res = analyze(phone, lead)
            val from = (lead + 0.4 * SR).toInt() / PitchDetector.HOP
            val to = min(res.size, (lead + 1.6 * SR).toInt() / PitchDetector.HOP)
            var frames = 0; var single = 0; var right = 0
            val wrong = LinkedHashMap<String, Int>()
            for (i in from until to) {
                frames++
                val notes = res[i]
                if (notes.size == 1) { single++; if (notes[0] == midi) right++ else wrong.merge("${fileName(note)}→${fileName(Note.fromMidi(notes[0]))}", 1, Int::plus) }
                else wrong.merge("${fileName(note)}:" + notes.joinToString("+") { fileName(Note.fromMidi(it)) }, 1, Int::plus)
            }
            NoteResult(frames, single, right, wrong)
        }.toList()
        val frames = results.sumOf { it.frames }
        val wrong = LinkedHashMap<String, Int>()
        for (r in results) for ((k, v) in r.wrong) wrong.merge(k, v, Int::plus)
        println(String.format("=== Notes seules : %d trames, une seule note %.1f %%, la bonne %.1f %%", frames, 100.0 * results.sumOf { it.single } / frames, 100.0 * results.sumOf { it.right } / frames))
        println("   erreurs : " + wrong.entries.sortedByDescending { it.value }.take(30).joinToString(" ") { "${it.key}×${it.value}" })
    }
}
