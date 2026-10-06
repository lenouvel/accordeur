package com.blenouvel.accordeur.audio

import com.blenouvel.accordeur.model.ClickSound
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthèse des clics du métronome (sur le modèle de [ReferenceTone]). Chaque son a trois variantes :
 * accent (1er temps), temps et subdivision. Un clic est un bref transitoire (≤ 120 ms) ; on le
 * rend une fois par son puis le moteur ne fait que recopier les échantillons.
 */
object ClickSynth {
    const val SAMPLE_RATE = 48_000

    /** Variante du clic selon sa place dans la mesure. */
    enum class Tick { ACCENT, BEAT, SUB }

    /** Durée (ms) du tampon synthétisé selon le timbre (assez pour laisser décroître le son). */
    private fun durationMs(sound: ClickSound): Int = when (sound) {
        ClickSound.CLICK -> 30
        ClickSound.WOOD -> 60
        ClickSound.BEEP -> 70
        ClickSound.CLAVE -> 45
        ClickSound.COWBELL -> 150
    }

    private fun decaySec(sound: ClickSound): Double = when (sound) {
        ClickSound.CLICK -> 0.008
        ClickSound.WOOD -> 0.040
        ClickSound.BEEP -> 0.055
        ClickSound.CLAVE -> 0.025
        ClickSound.COWBELL -> 0.120
    }

    /** Fréquence fondamentale (Hz) du timbre pour la variante demandée. */
    private fun frequency(sound: ClickSound, tick: Tick): Double {
        val accent = when (sound) {
            ClickSound.CLICK -> 2000.0
            ClickSound.WOOD -> 1200.0
            ClickSound.BEEP -> 880.0
            ClickSound.CLAVE -> 2500.0
            ClickSound.COWBELL -> 830.0
        }
        val beat = when (sound) {
            ClickSound.CLICK -> 1500.0
            ClickSound.WOOD -> 900.0
            ClickSound.BEEP -> 660.0
            ClickSound.CLAVE -> 2000.0
            ClickSound.COWBELL -> 560.0
        }
        return when (tick) {
            Tick.ACCENT -> accent
            Tick.BEAT -> beat
            Tick.SUB -> beat * 0.85
        }
    }

    private fun gain(tick: Tick): Double = when (tick) {
        Tick.ACCENT -> 1.0
        Tick.BEAT -> 0.85
        Tick.SUB -> 0.5
    }

    /**
     * Rend un clic (PCM 16 bits mono) pour [sound]/[tick], à plein niveau : le volume est laissé
     * au flux média du téléphone (boutons physiques).
     */
    fun render(sound: ClickSound, tick: Tick): ShortArray {
        val n = SAMPLE_RATE * durationMs(sound) / 1000
        val f = frequency(sound, tick)
        val decay = decaySec(sound)
        val amp = gain(tick)
        val attackN = (SAMPLE_RATE * 0.0015).toInt().coerceAtLeast(1)
        val metallic = sound == ClickSound.COWBELL || sound == ClickSound.WOOD
        val noisy = sound == ClickSound.CLICK
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t / decay)
            var s = sin(2.0 * PI * f * t)
            if (metallic) s += 0.5 * sin(2.0 * PI * f * 1.48 * t)
            if (noisy) s += 0.6 * (Random.nextDouble() * 2.0 - 1.0) * exp(-t / 0.002)
            val attack = if (i < attackN) i.toDouble() / attackN else 1.0
            val v = (s * env * attack * amp * HEADROOM).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Marge pour éviter l'écrêtage quand les partiels s'additionnent. */
    private const val HEADROOM = 0.6
}
