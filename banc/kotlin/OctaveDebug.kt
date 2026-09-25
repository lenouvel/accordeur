package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.dbg.DebugDetector
import com.blenouvel.accordeur.dbg.DebugEstimate
import org.junit.Test

class OctaveDebug {
    @Test fun debug() {
        for ((note, mic) in listOf("Ab1" to PhoneSim.VOICE_150_4)) {
            val raw = PhoneSim.load("MusyngKite", "electric_guitar_clean", note)
            val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(raw, mic), -65.0)
            val (signal, start) = PhoneSim.scene(tone, -92.0)
            val pre = Preprocessor(SR); val x = DoubleArray(signal.size) { pre.process(signal[it]) }
            val det = DebugDetector(); val est = DebugEstimate()
            // Trames alignées sur celles de la chaîne (fin de fenêtre = multiple du hop).
            var end = ((start / PitchDetector.HOP) + 1) * PitchDetector.HOP
            repeat(8) {
                det.trace = it in 2..3
                println("=== $note t=${"%.3f".format((end - start).toDouble() / SR)}")
                det.detect(x.copyOfRange(end - PitchDetector.WINDOW, end), est)
                println("  → f ${"%.3f".format(est.frequency)} MPM ${"%.3f".format(est.mpmFrequency)} clarté ${"%.3f".format(est.clarity)} harm ${"%.3f".format(est.harmonicity)} partiels ${est.partials}")
                if (it in 2..3) det.dumpPeaks()
                end += PitchDetector.HOP
            }
        }
    }
}
