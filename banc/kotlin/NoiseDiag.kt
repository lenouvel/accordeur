package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.TunerTargets
import com.blenouvel.accordeur.model.NoteMapper
import com.blenouvel.accordeur.model.NoteNames
import com.blenouvel.accordeur.model.Notation
import com.blenouvel.accordeur.model.Presets
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Fausses détections : bruits divers (blanc, rose, grondement, clics, rafales), 6 s chacun. */
class NoiseDiag {
    private fun scaled(x: DoubleArray, db: Double): DoubleArray {
        val rms = sqrt(x.sumOf { it * it } / x.size); val g = 10.0.pow(db / 20) / rms
        return DoubleArray(x.size) { x[it] * g }
    }
    private fun white(n: Int, seed: Long) = Random(seed).let { r -> DoubleArray(n) { r.nextGaussian() } }
    private fun pink(n: Int, seed: Long): DoubleArray { // Voss-McCartney simplifié (filtre de Paul Kellet)
        val r = Random(seed); var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        return DoubleArray(n) { val w = r.nextGaussian(); b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164; b2 = 0.57000 * b2 + w * 1.0526913; b0 + b1 + b2 + w * 0.1848 }
    }
    private fun brown(n: Int, seed: Long): DoubleArray { val r = Random(seed); var y = 0.0; return DoubleArray(n) { y = 0.998 * y + r.nextGaussian(); y } }
    private fun clicks(n: Int, seed: Long): DoubleArray { val r = Random(seed); val x = DoubleArray(n); var i = 5000; while (i < n) { for (k in 0 until 200) if (i + k < n) x[i + k] += (1 - k / 200.0) * r.nextGaussian(); i += 10000 + r.nextInt(20000) }; return x }
    private fun bursts(n: Int, seed: Long): DoubleArray { val w = pink(n, seed); val r = Random(seed + 1); val x = DoubleArray(n); var i = 10000; while (i < n) { val len = 4000 + r.nextInt(20000); for (k in 0 until len) if (i + k < n) x[i + k] = w[i + k] * sin(PI * k / len); i += len + 20000 + r.nextInt(40000) }; return x }
    private fun hum(n: Int): DoubleArray = DoubleArray(n) { val t = it.toDouble() / SR; sin(2 * PI * 50 * t) + 0.5 * sin(2 * PI * 100 * t + 1) + 0.3 * sin(2 * PI * 150 * t + 2) }

    @Test fun noises() {
        val n = 6 * SR
        val cases = ArrayList<Pair<String, DoubleArray>>()
        for (db in listOf(-95.0, -80.0, -60.0, -40.0, -25.0)) cases += "blanc $db dBFS" to scaled(white(n, 1), db)
        for (db in listOf(-80.0, -60.0, -40.0)) cases += "rose $db dBFS" to scaled(pink(n, 2), db)
        for (db in listOf(-70.0, -50.0)) cases += "grondement $db dBFS" to scaled(brown(n, 3), db)
        for (db in listOf(-60.0, -35.0)) cases += "clics $db dBFS" to scaled(clicks(n, 4), db)
        for (db in listOf(-60.0, -40.0)) cases += "rafales $db dBFS" to scaled(bursts(n, 5), db)
        for (seed in 10L..19L) cases += "blanc -50 dBFS graine $seed" to scaled(white(n, seed), -50.0)
        cases += "secteur 50 Hz -60 dBFS" to scaled(hum(n), -60.0)
        for ((name, x) in cases) {
            val noisy = DoubleArray(x.size) { x[it] } // pas de bruit de fond supplémentaire
            val frames = PhoneSim.run(noisy, TunerTargets(Presets.STANDARD_6.frequencies()))
            val hits = frames.filterNotNull().filter { it.hasPitch && !it.holding }
            val gate = frames.filterNotNull().count { it.signal }
            val notes = hits.groupingBy { NoteNames.label(NoteMapper.nearest(it.frequency).note, Notation.ENGLISH) }.eachCount()
            println(String.format("  %-28s gate ouvert %3d/%d trames, lectures %3d %s", name, gate, frames.size, hits.size, notes))
        }
    }
}
