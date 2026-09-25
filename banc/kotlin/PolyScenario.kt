package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PolyReading
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerProcessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Presets
import org.junit.Test

/** Grattage réel (échantillons, décalage de 15 ms entre cordes), puis une corde rejouée. */
class PolyScenario {
    private val notes = listOf("E2", "A2", "D3", "G3", "B3", "E4")

    private fun place(out: DoubleArray, x: DoubleArray, at: Double, gain: Double) {
        val o = (at * SR).toInt(); for (i in x.indices) if (o + i < out.size) out[o + i] += gain * x[i]
    }

    @Test fun scenarios() {
        val tuning = Presets.STANDARD_6
        var ok = 0; var total = 0
        for (sf in listOf("FluidR3_GM", "MusyngKite")) for (inst in listOf("acoustic_guitar_steel", "electric_guitar_clean", "acoustic_guitar_nylon")) for (mic in listOf(PhoneSim.RAW, PhoneSim.VOICE_150_4)) for (replayAt in listOf(2.0, 4.5)) for (replayed in listOf(0, 3, 5)) {
            val out = DoubleArray((8.0 * SR).toInt())
            for ((i, n) in notes.withIndex()) place(out, PhoneSim.load(sf, inst, n), 0.5 + 0.015 * i, 1.0)
            place(out, PhoneSim.load(sf, inst, notes[replayed]), replayAt, 1.5)
            val signal = PhoneSim.applyMic(PhoneSim.scaleToPeakRms(out, -55.0), mic)
            val noisy = DoubleArray(signal.size) { signal[it] } .also { val n = PhoneSim.noise(it.size, -92.0); for (i in it.indices) it[i] += n[i] }
            val p = TunerProcessor(SR); p.mode = TunerMode.POLY; p.targets = TunerTargets(tuning.frequencies())
            val input = FloatArray(noisy.size) { noisy[it].toFloat() }; val chunk = FloatArray(PitchDetector.HOP)
            val frames = ArrayList<PolyReading?>(); var i = 0
            while (i + chunk.size <= input.size) { input.copyInto(chunk, 0, i, i + chunk.size); frames += p.process(chunk)?.poly; i += chunk.size }
            fun at(t: Double) = frames[(t * SR / PitchDetector.HOP).toInt()]
            val s1 = at(replayAt - 0.05); val s2 = at(replayAt + 1.2); val end = frames.last()
            val strumOk = s1 != null && s1.strum && s1.strings.count { it.detected } >= 5
            val singleOk = s2 != null && !s2.strum && s2.event == (s1?.event ?: -1) + 1 && s2.strings[replayed].fresh && s2.strings.withIndex().all { (k, v) -> k == replayed || !v.fresh }
            val holdOk = end != null && end.event == s2?.event
            total++; if (strumOk && singleOk && holdOk) ok++
            if (!(strumOk && singleOk && holdOk)) println(String.format("  ÉCHEC %s/%s %s rejouée %s à %.1f s : grattage %s (%s) | ensuite ev=%s strum=%s fraîches=%s | fin ev=%s",
                sf, inst, mic.name, notes[replayed], replayAt, strumOk, s1?.strings?.map { if (it.detected) "%+.0f".format(it.cents) else "--" },
                s2?.event, s2?.strum, s2?.strings?.withIndex()?.filter { it.value.fresh }?.map { notes[it.index] }, end?.event))
        }
        println("=== Scénarios poly réussis : $ok / $total")
    }
}
