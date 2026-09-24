package com.blenouvel.accordeur.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Son d'une note (référence de l'accordeur, notes touchées sur le manche). Synthèse additive
 * exacte (partiels harmoniques 1…8 en 1/h, enveloppe de corde pincée) : un haut-parleur de
 * téléphone ne restitue pas 52 Hz, mais les harmoniques font entendre la bonne hauteur.
 *
 * À appeler depuis le thread principal : la synthèse se fait en arrière-plan, une nouvelle
 * lecture remplace la précédente.
 */
class ReferenceTone {
    private var track: AudioTrack? = null
    private var generation = 0

    /** Joue [frequency] pendant [durationMs] ms (la lecture en cours est coupée). */
    suspend fun play(frequency: Double, durationMs: Int = DURATION_MS) {
        val request = ++generation
        val samples = withContext(Dispatchers.Default) { synthesize(frequency, durationMs) }
        if (request != generation) return // une lecture plus récente a été demandée entre-temps
        release()
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

    /** Coupe le son (et annule une lecture en préparation). */
    fun stop() {
        generation++
        release()
    }

    private fun release() {
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
            // Oscillateur récursif (rotation) + décroissance exponentielle : ni sin ni exp par échantillon.
            val w = 2.0 * PI * fh / SAMPLE_RATE
            val c = cos(w)
            val s = sin(w)
            val decay = exp(-(1.0 + 0.3 * (h - 1)) / (DECAY_S * SAMPLE_RATE))
            var x = 1.0 / h
            var y = 0.0
            for (i in 0 until n) {
                out[i] += y
                val nx = (x * c - y * s) * decay
                y = (x * s + y * c) * decay
                x = nx
            }
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

        /** Durée d'une note touchée sur le manche. */
        const val NOTE_MS = 1600

        private const val SAMPLE_RATE = 48_000
        private const val PARTIALS = 8
        private const val MAX_PARTIAL_HZ = 6000.0
        private const val DECAY_S = 1.4
        private const val ATTACK_MS = 8
        private const val RELEASE_MS = 120
        private const val LEVEL = 0.6
    }
}
