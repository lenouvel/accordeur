package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.Preprocessor
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.dbg.DebugDetector
import com.blenouvel.accordeur.dbg.DebugEstimate
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import org.junit.Test

/** Paramètres : -Dg.sf -Dg.inst -Dg.note -Dg.mic (raw|v120|v150|v200) -Dg.level -Dg.trace (instant à tracer, s). */
class GlitchDebug {
    private fun label(f: Double) = NoteNames.label(NoteMapper.nearest(f).note, Notation.ENGLISH) + String.format("%+.0f", NoteMapper.nearest(f).cents)
    @Test fun debug() {
        val sf = System.getProperty("g.sf", "MusyngKite"); val inst = System.getProperty("g.inst", "acoustic_guitar_steel")
        val note = System.getProperty("g.note", "E4"); val level = System.getProperty("g.level", "-50").toDouble()
        val mic = when (System.getProperty("g.mic", "raw")) { "v120" -> PhoneSim.VOICE_120; "v150" -> PhoneSim.VOICE_150_4; "v200" -> PhoneSim.VOICE_200_4; else -> PhoneSim.RAW }
        val traceAt = System.getProperty("g.trace", "-1").toDouble()
        val raw = PhoneSim.load(sf, inst, note)
        val tone = PhoneSim.scaleToPeakRms(PhoneSim.applyMic(raw, mic), level)
        val (signal, start) = PhoneSim.scene(tone, -92.0)
        val frames = PhoneSim.run(signal, TunerTargets(Presets.STANDARD_6.frequencies()))
        val pre = Preprocessor(SR); val x = DoubleArray(signal.size) { pre.process(signal[it]) }
        val det = DebugDetector(); val est = DebugEstimate()
        for (i in frames.indices) {
            val end = (i + 1) * PitchDetector.HOP
            if (end < start || end > start + 1.7 * SR) continue
            val t = (end - start).toDouble() / SR
            det.trace = traceAt >= 0 && Math.abs(t - traceAt) < 0.021
            det.detect(x.copyOfRange(end - PitchDetector.WINDOW, end), est)
            val fr = frames[i]!!
            println(String.format("t=%.3f niv %.1f | brut %s harm %.2f p %d MPM %s cl %.2f | brutFr %s | affiché %s", t, fr.levelDb,
                if (est.frequency > 0) label(est.frequency) else "-", est.harmonicity, est.partials, if (est.mpmFrequency > 0) label(est.mpmFrequency) else "-", est.clarity,
                if (fr.rawFrequency.isNaN()) "-" else label(fr.rawFrequency), if (fr.hasPitch) label(fr.frequency) + if (fr.holding) "(h)" else "" else "—"))
            if (det.trace) det.dumpPeaks()
        }
    }
}
