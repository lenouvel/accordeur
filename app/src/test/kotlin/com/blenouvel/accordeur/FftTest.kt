package com.blenouvel.accordeur

import com.blenouvel.accordeur.audio.Fft
import com.blenouvel.accordeur.audio.RealFft
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FftTest {
    private fun naiveDft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0 until n) {
            for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                re[k] += x[t] * cos(a)
                im[k] += x[t] * sin(a)
            }
        }
        return re to im
    }

    @Test
    fun complexFftMatchesNaiveDft() {
        val n = 64
        val random = Random(3)
        val x = DoubleArray(n) { random.nextGaussian() }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        val (expRe, expIm) = naiveDft(x)
        for (k in 0 until n) {
            assertEquals(expRe[k], re[k], 1e-9)
            assertEquals(expIm[k], im[k], 1e-9)
        }
        Fft(n).inverse(re, im)
        for (t in 0 until n) assertEquals(x[t], re[t] / n, 1e-12)
    }

    @Test
    fun realFftMatchesNaiveDftAndInverts() {
        for (n in listOf(4, 8, 256, 1024)) {
            val random = Random(n.toLong())
            val x = DoubleArray(n) { random.nextGaussian() }
            val fft = RealFft(n)
            val re = DoubleArray(fft.bins)
            val im = DoubleArray(fft.bins)
            fft.forward(x, re, im)
            val (expRe, expIm) = naiveDft(x)
            for (k in 0..n / 2) {
                assertEquals("re[$k] n=$n", expRe[k], re[k], 1e-9)
                assertEquals("im[$k] n=$n", expIm[k], im[k], 1e-9)
            }
            val back = DoubleArray(n)
            fft.inverse(re, im, back)
            for (t in 0 until n) assertEquals(x[t], back[t], 1e-12)
        }
    }

    @Test
    fun autocorrelationViaFftIsLinear() {
        val w = 512
        val n = 2 * w
        val random = Random(11)
        val x = DoubleArray(n)
        for (i in 0 until w) x[i] = random.nextGaussian()
        val fft = RealFft(n)
        val re = DoubleArray(fft.bins)
        val im = DoubleArray(fft.bins)
        fft.forward(x, re, im)
        val power = DoubleArray(fft.bins) { re[it] * re[it] + im[it] * im[it] }
        val acf = DoubleArray(n)
        fft.inverse(power, DoubleArray(fft.bins), acf)
        for (tau in listOf(0, 1, 17, 200, w - 1)) {
            var expected = 0.0
            for (j in 0 until w - tau) expected += x[j] * x[j + tau]
            assertEquals(expected, acf[tau], 1e-9)
        }
    }
}
