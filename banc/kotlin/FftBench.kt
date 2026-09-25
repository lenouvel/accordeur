package com.blenouvel.accordeur

import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.PitchEstimate
import com.blenouvel.accordeur.audio.RealFft
import org.jtransforms.fft.DoubleFFT_1D
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/** FFT maison (Kotlin) contre JTransforms (référence Java), tailles utilisées par l'accordeur. */
class FftBench {
    private fun bench(label: String, runs: Int, block: () -> Unit): Double {
        repeat(runs / 4) { block() } // chauffe du JIT
        val t0 = System.nanoTime()
        repeat(runs) { block() }
        val ms = (System.nanoTime() - t0) / 1e6 / runs
        println(String.format("  %-52s %.4f ms", label, ms))
        return ms
    }

    @Test fun compare() {
        pl.edu.icm.jlargearrays.ConcurrencyUtils.setNumberOfThreads(1) // un seul cœur, comme notre fil audio
        for (n in listOf(16384, 32768)) {
            val r = Random(1)
            val x = DoubleArray(n) { r.nextGaussian() }
            val ours = RealFft(n); val re = DoubleArray(ours.bins); val im = DoubleArray(ours.bins)
            val jt = DoubleFFT_1D(n.toLong()); val buf = DoubleArray(n)
            // Même résultat ?
            ours.forward(x, re, im); x.copyInto(buf); jt.realForward(buf)
            var err = 0.0
            for (k in 1 until n / 2) err = max(err, max(abs(re[k] - buf[2 * k]), abs(im[k] - buf[2 * k + 1])))
            println("=== FFT réelle de $n points (écart max avec JTransforms : ${"%.1e".format(err)})")
            val a = bench("maison (RealFft.forward)", 2000) { ours.forward(x, re, im) }
            val b = bench("JTransforms (DoubleFFT_1D.realForward, 1 thread)", 2000) { x.copyInto(buf); jt.realForward(buf) }
            println(String.format("  → rapport maison / JTransforms : %.2f", a / b))
        }
        val det = PitchDetector(); val est = PitchEstimate()
        val w = StiffString.pluck(82.41, 1.5e-4, seconds = 0.5).copyOfRange(4000, 4000 + PitchDetector.WINDOW)
        println("=== Analyse mono complète (2 FFT de 16384 points + série de partiels)")
        bench("PitchDetector.detect", 2000) { det.detect(w, est) }
        println("  Budget par trame : ${"%.1f".format(PitchDetector.HOP * 1000.0 / PitchDetector.SAMPLE_RATE)} ms")
    }
}
