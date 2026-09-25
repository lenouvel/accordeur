package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.SpectrumAnalyzer
import com.blenouvel.accordeur.model.Note
import org.junit.Test

class SpectrumTiming {
    @Test fun timing() {
        val notes = "E2 B2 E3 G#3 B3 E4".split(' ').map { Note.parse(it) }
        val raw = ChordBench.strum("FluidR3_GM", "acoustic_guitar_steel", notes)
        val phone = PhoneSim.applyMic(raw, PhoneSim.VOICE_150_4)
        val an = SpectrumAnalyzer(SR)
        val w = FloatArray(SpectrumAnalyzer.WINDOW)
        fun pass(): Double {
            val t0 = System.nanoTime(); var n = 0
            var end = SpectrumAnalyzer.WINDOW
            while (end <= phone.size) {
                for (i in w.indices) w[i] = phone[end - w.size + i].toFloat()
                an.analyze(w, true); n++
                end += PitchDetector.HOP
            }
            return (System.nanoTime() - t0) / 1e6 / n
        }
        repeat(3) { pass() }
        println(String.format("SpectrumAnalyzer.analyze (accord de 6 cordes) : %.2f ms par trame (JVM, chaud)", pass()))
    }
}
