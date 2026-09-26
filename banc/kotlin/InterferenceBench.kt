package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.Presets
import com.blenouvel.accordeur.model.Tuning
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.min

/**
 * Banc d'interférences : la corde à accorder (verrouillée, mode Manuel) est mêlée à une autre
 * source musicale — **une autre guitare** qui joue un accord ailleurs, une **basse**, une
 * **batterie** — à divers niveaux, pour mesurer si l'accordeur suit toujours la bonne corde ou se
 * laisse détourner. Signaux réels (soundfonts) via un micro de téléphone simulé ; batterie
 * synthétique (bursts large bande). Diagnostic seulement.
 *
 * Colonnes : part des trames (0,3–1,6 s) où l'accordeur affiche **la corde visée** (juste), une
 * **autre note** (détourné) ou **rien**.
 */
class InterferenceBench {
    private val sources = listOf("FluidR3_GM", "MusyngKite").flatMap { sf ->
        listOf("acoustic_guitar_steel", "electric_guitar_clean").map { sf to it }
    }

    /** Corde visée (note + accordage), et une interférence harmonique qui évite sa classe de hauteur. */
    private data class Target(val note: String, val tuning: Tuning, val chord: String, val bass: String)

    private val targets = listOf(
        // Corde grave verrouillée ; l'accord/basse interférents évitent la note visée et ses octaves.
        Target("E2", Presets.STANDARD_6, chord = "A#2 D3 F3 A#3", bass = "A#1"),
        Target("D2", Presets.DROP_D_6, chord = "G#2 C3 D#3 G#3", bass = "G#1"),
        Target("G#1", Presets.DROP_G_SHARP_7, chord = "D2 F#2 A2 D3", bass = "D2"),
    )

    /** Batterie synthétique : coups larges bande périodiques (attaque raide, décroissance ~0,12 s). */
    private fun drums(n: Int, seed: Long): DoubleArray {
        val r = Random(seed)
        val out = DoubleArray(n)
        var next = (0.05 * SR).toInt()
        var hit = 0
        while (next < n) {
            val tau = 0.12
            val len = (0.35 * SR).toInt()
            val loud = if (hit % 2 == 0) 1.0 else 0.55 // grosse caisse / caisse claire alternées
            for (i in 0 until min(len, n - next)) {
                out[next + i] += loud * r.nextGaussian() * exp(-i.toDouble() / SR / tau)
            }
            next += (0.42 * SR).toInt()
            hit++
        }
        return out
    }

    private fun mix(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(maxOf(a.size, b.size))
        for (i in a.indices) out[i] += a[i]
        for (i in b.indices) out[i] += b[i]
        return out
    }

    private class Score(var ok: Int = 0, var fooled: Int = 0, var silent: Int = 0) {
        val total get() = ok + fooled + silent
    }

    private fun evaluate(target: Target, sf: String, inst: String, interference: String, level: Double): Score {
        val expected = Note.parse(target.note)
        val raw = PhoneSim.scaleToPeakRms(PhoneSim.load(sf, inst, ChordBench.fileName(expected)), -30.0)
        // L'interférence démarre avant et dure au-delà de la corde (elle « joue » en continu).
        val jam = when (interference) {
            "propre" -> DoubleArray(0)
            "guitare" -> ChordBench.strum(sf, inst, target.chord.split(' ').map { Note.parse(it) }, seed = 7)
            "basse" -> PhoneSim.load(sf, "electric_guitar_clean", ChordBench.fileName(Note.parse(target.bass)))
            "batterie" -> drums(raw.size + SR, seed = 11)
            else -> DoubleArray(0)
        }.let { if (it.isEmpty()) it else PhoneSim.scaleToPeakRms(it, -30.0 + level) }

        val lead = SR / 2
        val tone = DoubleArray(lead + raw.size + lead / 2)
        for (i in raw.indices) tone[lead + i] += raw[i]
        for (i in jam.indices) if (i < tone.size) tone[i] += jam[i] // l'interférence couvre toute la scène
        val mic = PhoneSim.applyMic(tone, PhoneSim.VOICE_150_4)
        val (signal, start) = PhoneSim.scene(mic, -88.0, lead = 0.3)

        val freqs = target.tuning.frequencies()
        val targetHz = NoteMapper.frequencyOf(expected)
        val idx = freqs.indices.minByOrNull { kotlin.math.abs(freqs[it] - targetHz) } ?: 0
        val frames = PhoneSim.run(signal, TunerTargets(freqs, lockedIndex = idx))
        val hop = PitchDetector.HOP
        val from = (start + lead + 0.3 * SR).toInt() / hop
        val to = min(frames.size, (start + lead + 1.6 * SR).toInt() / hop)
        val score = Score()
        for (i in from until to) {
            val f = frames[i]
            if (f == null || !f.hasPitch || f.holding) { score.silent++; continue }
            if (NoteMapper.nearest(f.frequency).note == expected) score.ok++ else score.fooled++
        }
        return score
    }

    @Test
    fun interference() {
        val cases = listOf("propre" to 0.0, "guitare" to -6.0, "guitare" to 0.0, "basse" to 0.0, "batterie" to 0.0)
        println("=== Interférences : part des trames où la corde visée (verrouillée) est affichée")
        println(String.format("  %-14s %-16s %8s %8s %8s", "corde", "interférence", "juste", "détourné", "rien"))
        for (target in targets) {
            for ((interference, level) in cases) {
                val total = Score()
                for ((sf, inst) in sources) {
                    val s = evaluate(target, sf, inst, interference, level)
                    total.ok += s.ok; total.fooled += s.fooled; total.silent += s.silent
                }
                val n = maxOf(1, total.total)
                val tag = if (interference == "propre") "propre" else "$interference ${level.toInt()}dB"
                println(String.format("  %-14s %-16s %7.0f%% %7.0f%% %7.0f%%", "${target.note} (${target.tuning.name})", tag, 100.0 * total.ok / n, 100.0 * total.fooled / n, 100.0 * total.silent / n))
            }
        }
    }
}
