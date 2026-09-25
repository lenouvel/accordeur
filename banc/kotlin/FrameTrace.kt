package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.dbg.DebugDetector
import com.blenouvel.accordeur.dbg.DebugEstimate
import org.junit.Test

class FrameTrace {
    @Test fun trace() {
        val raw = PhoneSim.load("MusyngKite", "electric_guitar_clean", "Ab1")
        val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(raw, PhoneSim.VOICE_120), -65.0)
        val (signal, start) = PhoneSim.scene(tone, -92.0)
        val pre = Preprocessor(SR); val x = DoubleArray(signal.size) { pre.process(signal[it]) }
        val det = DebugDetector(); val est = DebugEstimate()
        val t = System.getProperty("trace.t", "0.352").toDouble()
        val end = (((start + t * SR) / PitchDetector.HOP).toInt()) * PitchDetector.HOP
        det.trace = true
        det.detect(x.copyOfRange(end - PitchDetector.WINDOW, end), est)
        println("→ f ${est.frequency} harm ${est.harmonicity} p ${est.partials} MPM ${est.mpmFrequency}")
        det.dumpPeaks()
    }
}
