package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.dbg.DebugDetector
import com.blenouvel.accordeur.dbg.DebugEstimate
import org.junit.Test
import kotlin.math.pow

class HumTrace {
    @Test fun trace() {
        val level = -60.0
        val mic = PhoneSim.VOICE_150_4
        val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(LowString.string(LowString.P()), mic), level)
        val g = 10.0.pow(level / 20.0)
        val start = SR
        val total = start + tone.size + SR / 2
        val signal = PhoneSim.noise(total, -92.0)
        for (i in tone.indices) signal[start + i] += tone[i]
        val hum = PhoneSim.applyMic(LowString.hum(total, g * 0.25), mic)
        for (i in 0 until total) signal[i] += hum[i]
        val pre = Preprocessor(SR); val x = DoubleArray(signal.size) { pre.process(signal[it]) }
        val det = DebugDetector(); val est = DebugEstimate()
        for (t in listOf(2.5, 5.0)) {
            val end = ((start + t * SR) / PitchDetector.HOP).toInt() * PitchDetector.HOP
            det.trace = true
            println("=== t = $t s")
            val ok = det.detect(x.copyOfRange(end - PitchDetector.WINDOW, end), est)
            det.dumpPeaks()
            println("→ $ok f ${est.frequency} harm ${est.harmonicity} p ${est.partials} mpm ${est.mpmFrequency} cl ${est.clarity}")
        }
    }
}
