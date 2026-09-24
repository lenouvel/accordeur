package com.blenouvel.accordeur.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Son de référence d'une corde, pour accorder à l'oreille. Synthèse additive exacte (partiels
 * harmoniques 1…8 en 1/h, enveloppe de corde pincée) : un haut-parleur de téléphone ne restitue pas
 * 52 Hz, mais les harmoniques font entendre la bonne hauteur (fondamentale « virtuelle »).
 */
class ReferenceTone {
    private var track: AudioTrack? = null

    /** Joue [frequency] pendant [durationMs] ms (la lecture précédente est coupée). */
    fun play(frequency: Double, durationMs: Int = DURATION_MS) {
        stop()
        val samples = synthesize(frequency, durationMs)
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            t.write(samples, 0, samples.size)
            t.play()
            track = t
        } catch (e: RuntimeException) {
            Log.w("ReferenceTone", "Lecture impossible", e)
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (_: IllegalStateException) {
        }
        t.release()
    }

    private fun synthesize(frequency: Double, durationMs: Int): ShortArray {
        val n = SAMPLE_RATE * durationMs / 1000
        val out = DoubleArray(n)
        for (h in 1..PARTIALS) {
            val fh = h * frequency
            if (fh > MAX_PARTIAL_HZ) break
            val w = 2.0 * PI * fh / SAMPLE_RATE
            val amplitude = 1.0 / h
            val decay = DECAY_S * SAMPLE_RATE / (1.0 + 0.3 * (h - 1))
            for (i in 0 until n) out[i] += amplitude * exp(-i / decay) * sin(w * i)
        }
        val attack = SAMPLE_RATE * ATTACK_MS / 1000
        val release = SAMPLE_RATE * RELEASE_MS / 1000
        var peak = 1e-9
        for (i in 0 until n) {
            val gain = min(1.0, min(i.toDouble() / attack, (n - 1 - i).toDouble() / release))
            out[i] *= gain
            peak = maxOf(peak, abs(out[i]))
        }
        val scale = LEVEL * Short.MAX_VALUE / peak
        return ShortArray(n) { (out[it] * scale).toInt().toShort() }
    }

    companion object {
        const val DURATION_MS = 2500
        private const val SAMPLE_RATE = 48_000
        private const val PARTIALS = 8
        private const val MAX_PARTIAL_HZ = 6000.0
        private const val DECAY_S = 1.4
        private const val ATTACK_MS = 8
        private const val RELEASE_MS = 120
        private const val LEVEL = 0.6
    }
}
