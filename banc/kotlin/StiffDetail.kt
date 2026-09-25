package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PitchEstimate
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import org.junit.Test

class StiffDetail {
    @Test fun detail() {
        for ((note, b, alpha, mic) in listOf(
            listOf("G#1", 8e-4, 2.0, PhoneSim.VOICE_150_4), listOf("G#1", 8e-4, 1.0, PhoneSim.VOICE_150_4),
            listOf("B1", 4e-4, 2.0, PhoneSim.VOICE_120), listOf("D2", 2.5e-4, 2.0, PhoneSim.VOICE_120))) {
            note as String; b as Double; alpha as Double; mic as PhoneSim.Mic
            val f = NoteMapper.frequencyOf(Note.parse(note))
            val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(StiffString.pluck(f, b, alpha = alpha), mic), -60.0)
            val (signal, start) = PhoneSim.scene(tone, -92.0)
            val pre = Preprocessor(SR); val x = DoubleArray(signal.size) { pre.process(signal[it]) }
            val det = PitchDetector(); val est = PitchEstimate()
            println("=== $note B=$b α=$alpha ${mic.name}  (vrai f1 = ${"%.3f".format(f)})")
            var end = start + 4 * PitchDetector.HOP
            while (end < start + (1.6 * SR).toInt()) {
                val ok = det.detect(x.copyOfRange(end - PitchDetector.WINDOW, end), est)
                println(String.format("  t=%.2f MPM %8.3f (%+7.1f¢) clarté %.3f | final %8.3f (%+6.1f¢) B^=%.1e partiels %d harm %.2f", (end - start).toDouble() / SR,
                    est.mpmFrequency, 1200 * Math.log(est.mpmFrequency / f) / Math.log(2.0), est.clarity, est.frequency, 1200 * Math.log(est.frequency / f) / Math.log(2.0), est.inharmonicity, est.partials, est.harmonicity))
                end += 4 * PitchDetector.HOP
            }
        }
    }
}
