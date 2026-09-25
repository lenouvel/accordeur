package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.dbg.DebugDetector
import com.blenouvel.accordeur.dbg.DebugEstimate
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import org.junit.Test

class StiffTrace {
    @Test fun trace() {
        val f = NoteMapper.frequencyOf(Note.parse("G#1"))
        val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(StiffString.pluck(f, 8e-4, alpha = 2.0), PhoneSim.VOICE_150_4), -55.0)
        val (signal, start) = PhoneSim.scene(tone, -92.0)
        val pre = Preprocessor(SR); val x = DoubleArray(signal.size) { pre.process(signal[it]) }
        val det = DebugDetector(); val est = DebugEstimate()
        var end = ((start + 0.3 * SR) / PitchDetector.HOP).toInt() * PitchDetector.HOP
        det.trace = true
        det.detect(x.copyOfRange(end - PitchDetector.WINDOW, end), est)
        println("→ f ${est.frequency} harm ${est.harmonicity} p ${est.partials}")
    }
}
