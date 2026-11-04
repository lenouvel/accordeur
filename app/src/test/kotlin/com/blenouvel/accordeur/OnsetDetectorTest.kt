package com.blenouvel.accordeur

import com.blenouvel.accordeur.audio.OnsetDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class OnsetDetectorTest {

    private val sampleRate = 48_000

    /** Détecte toutes les attaques d'un signal en une passe. */
    private fun detect(signal: FloatArray): List<Double> {
        val detector = OnsetDetector(sampleRate)
        val onsets = ArrayList<Double>()
        detector.process(signal, signal.size) { onsets.add(it) }
        return onsets
    }

    /** Plancher très faible + bouffées de bruit large bande (attaque de type médiator). */
    private fun bursts(times: List<Double>, totalSec: Double): FloatArray {
        val n = (totalSec * sampleRate).toInt()
        val rnd = Random(1)
        val out = FloatArray(n) { (rnd.nextGaussian() * 0.0005).toFloat() }
        val len = (0.03 * sampleRate).toInt()
        val decay = 0.010 * sampleRate
        for (t in times) {
            val start = (t * sampleRate).toInt()
            for (i in 0 until len) {
                val k = start + i
                if (k in 0 until n) out[k] += (rnd.nextGaussian() * 0.3 * exp(-i / decay)).toFloat()
            }
        }
        return out
    }

    @Test
    fun detectsEachBurstOnce() {
        val times = listOf(0.3, 0.6, 0.9)
        val onsets = detect(bursts(times, 1.2))
        assertEquals("une attaque par bouffée", times.size, onsets.size)
        for ((expected, got) in times.zip(onsets)) {
            assertTrue("attaque datée près de $expected s (obtenu $got)", got in expected..(expected + 0.03))
        }
    }

    @Test
    fun silenceProducesNoOnset() {
        val rnd = Random(2)
        val signal = FloatArray(sampleRate) { (rnd.nextGaussian() * 0.0005).toFloat() }
        assertTrue(detect(signal).isEmpty())
    }

    @Test
    fun steadyToneOnsetsOnlyAtItsStart() {
        // Silence puis une sinusoïde continue (1,5 kHz) : une seule attaque, à son départ.
        val n = (1.2 * sampleRate).toInt()
        val start = (0.4 * sampleRate).toInt()
        val signal = FloatArray(n)
        for (i in start until n) signal[i] = (0.3 * sin(2.0 * PI * 1500.0 * (i - start) / sampleRate)).toFloat()
        val onsets = detect(signal)
        assertEquals(1, onsets.size)
        assertTrue(onsets[0] in 0.4..0.46)
    }

    @Test
    fun respectsRefractoryPeriod() {
        // Deux bouffées à 25 ms d'intervalle (< réfractaire 60 ms) : une seule attaque retenue.
        val onsets = detect(bursts(listOf(0.5, 0.525), 0.9))
        assertEquals(1, onsets.size)
    }
}
