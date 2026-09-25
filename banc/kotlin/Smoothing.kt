package com.blenouvel.accordeur

import com.blenouvel.accordeur.TestSignals.SAMPLE_RATE
import com.blenouvel.accordeur.audio.*
import org.junit.Test
import kotlin.math.*

/** Compare retard (glissando) et tremblement (note tenue bruitée) selon β du filtre 1€. */
class Smoothing {
    private fun run(beta: Double, signal: DoubleArray): List<Double> {
        val det = PitchDetector(); val est = PitchEstimate(); val st = PitchStabilizer(smoothingBeta = beta)
        val pre = Preprocessor(SAMPLE_RATE); val filtered = DoubleArray(signal.size) { pre.process(signal[it]) }
        val out = ArrayList<Double>(); var end = PitchDetector.HOP
        while (end <= filtered.size) {
            val w = DoubleArray(PitchDetector.WINDOW) { i -> val k = end - PitchDetector.WINDOW + i; if (k < 0) 0.0 else filtered[k] }
            val t = end.toDouble() / SAMPLE_RATE
            val ms = w.sumOf { it*it } / w.size
            st.updateLevel(10*log10(max(ms,1e-12)), 10*log10(max(ms,1e-12)), t)
            st.updatePitch(if (st.gateOpen && det.detect(w, est)) est else null, t, 0.0, DoubleArray(0))
            out += st.frequency; end += PitchDetector.HOP
        }
        return out
    }
    @Test fun compare() {
        val f0 = 82.41
        // Glissando -40 → +10 cents à 25 c/s
        val n = (2.5*SAMPLE_RATE).toInt(); val g = DoubleArray(n); var ph = 0.0
        val truth = DoubleArray(n)
        for (i in 0 until n) { val t = i.toDouble()/SAMPLE_RATE; val c = if (t < 0.25) -40.0 else min(10.0, -40.0 + 25.0*(t-0.25)); truth[i] = c; ph += 2*PI*f0*2.0.pow(c/1200)/SAMPLE_RATE
            g[i] = 0.3*(sin(ph) + 0.5*sin(2*ph+0.3) + 0.3*sin(3*ph+1.1)) }
        // Note tenue + bruit -20 dB
        val held = TestSignals.addNoise(TestSignals.pluckedString(f0, 3.0, inharmonicity=2e-4, fundamentalDb=-10.0, decaySeconds=6.0), 0.02, seed=5)
        for (beta in listOf(0.007, 0.02, 0.05, 0.1)) {
            val gl = run(beta, g)
            val lags = gl.mapIndexedNotNull { i, f -> val s = (i+1)*PitchDetector.HOP - 1; val t = s.toDouble()/SAMPLE_RATE
                if (f.isNaN() || t < 0.6 || t > 1.9) null else truth[s] - 1200*log2(f/f0) }
            val h = run(beta, held).drop(15).filter { !it.isNaN() }.map { 1200*log2(it/f0) }
            val jitter = h.max() - h.min()
            println("beta=$beta : retard moyen ${"%.2f".format(lags.average())} cents (≈ ${"%.0f".format(lags.average()/25*1000)} ms) ; tremblement crête-crête note tenue ${"%.3f".format(jitter)} cents")
        }
    }
}
