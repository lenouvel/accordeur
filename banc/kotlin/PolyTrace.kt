package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.TunerMode
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.Presets
import org.junit.Test

class PolyTrace {
    @Test fun trace() {
        val sf = System.getProperty("g.sf", "FluidR3_GM"); val inst = System.getProperty("g.inst", "electric_guitar_clean")
        val replayed = System.getProperty("g.note", "E4"); val replayAt = System.getProperty("g.trace", "4.5").toDouble()
        val mic = if (System.getProperty("g.mic", "raw") == "v150") PhoneSim.VOICE_150_4 else PhoneSim.RAW
        val notes = listOf("E2", "A2", "D3", "G3", "B3", "E4")
        val out = DoubleArray((8.0 * SR).toInt())
        fun place(x: DoubleArray, at: Double, gain: Double) { val o = (at * SR).toInt(); for (i in x.indices) if (o + i < out.size) out[o + i] += gain * x[i] }
        for ((i, n) in notes.withIndex()) place(PhoneSim.load(sf, inst, n), 0.5 + 0.015 * i, 1.0)
        place(PhoneSim.load(sf, inst, replayed), replayAt, 1.5)
        val signal = PhoneSim.applyMic(PhoneSim.scaleToPeakRms(out, -55.0), mic)
        val noise = PhoneSim.noise(signal.size, -92.0)
        val p = com.blenouvel.accordeur.dbg.TunerProcessor(SR); p.mode = TunerMode.POLY; p.targets = TunerTargets(Presets.STANDARD_6.frequencies())
        println("  cordes : " + notes.joinToString(" "))
        val input = FloatArray(signal.size) { (signal[it] + noise[it]).toFloat() }; val chunk = FloatArray(PitchDetector.HOP); var i = 0
        var last: com.blenouvel.accordeur.audio.PolyReading? = null
        while (i + chunk.size <= input.size) { input.copyInto(chunk, 0, i, i + chunk.size); last = p.process(chunk)?.poly ?: last; i += chunk.size }
        println("  fin : ev=${last?.event} strum=${last?.strum} " + last?.strings?.map { if (it.detected) "%+.0f%s".format(it.cents, if (it.fresh) "!" else "") else "--" })
    }
}
