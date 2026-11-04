package com.blenouvel.accordeur.audio

import com.blenouvel.accordeur.model.ClickSound
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthèse des clics du métronome. Chaque son a trois variantes : accent (1er temps), temps et
 * subdivision. Un clic est un bref transitoire ; on le rend une fois par son puis le moteur recopie
 * les échantillons.
 *
 * [ClickSound.CLICK] est le son normal (avec une pointe de bruit à l'attaque). Les sons marqués
 * `experimental` ([ClickSound.SINE], [ClickSound.CHIRP]) sont **propres et déterministes** (aucun
 * bruit aléatoire, même forme d'onde à chaque rendu) et de crête réduite : pensés pour le filtrage
 * adaptatif (annulation du clic au micro), qui exige un signal reproductible et un chemin linéaire.
 */
object ClickSynth {
    const val SAMPLE_RATE = 48_000

    /** Variante du clic selon sa place dans la mesure. */
    enum class Tick { ACCENT, BEAT, SUB }

    /** Durée (ms) du tampon synthétisé selon le timbre. */
    private fun durationMs(sound: ClickSound): Int = when (sound) {
        ClickSound.CLICK -> 30
        ClickSound.SINE -> 45
        ClickSound.CHIRP -> 60 // long : étalé dans le temps → audible à crête plus basse
    }

    /** Fréquence (Hz) de la variante pour les sons toniques (CLICK, SINE). */
    private fun frequency(sound: ClickSound, tick: Tick): Double {
        val accent = when (sound) {
            ClickSound.CLICK -> 2000.0
            ClickSound.SINE -> 2637.0
            ClickSound.CHIRP -> 0.0
        }
        val beat = when (sound) {
            ClickSound.CLICK -> 1500.0
            ClickSound.SINE -> 2093.0
            ClickSound.CHIRP -> 0.0
        }
        return when (tick) {
            Tick.ACCENT -> accent
            Tick.BEAT -> beat
            Tick.SUB -> beat * 0.85
        }
    }

    /** Bornes du balayage (Hz) du chirp selon la variante, centrées sur 1,5‑4,5 kHz (oreille sensible). */
    private fun chirpRange(tick: Tick): Pair<Double, Double> = when (tick) {
        Tick.ACCENT -> 1800.0 to 4500.0
        Tick.BEAT -> 1500.0 to 3800.0
        Tick.SUB -> 1200.0 to 2800.0
    }

    private fun gain(tick: Tick): Double = when (tick) {
        Tick.ACCENT -> 1.0
        Tick.BEAT -> 0.85
        Tick.SUB -> 0.5
    }

    /** Crête laissée au son : réduite pour les sons expérimentaux (éviter l'écrêtage du micro). */
    private fun headroom(sound: ClickSound): Double = when (sound) {
        ClickSound.CLICK -> 0.6
        ClickSound.SINE -> 0.45
        ClickSound.CHIRP -> 0.5
    }

    /** Rend un clic (PCM 16 bits mono) pour [sound]/[tick]. */
    fun render(sound: ClickSound, tick: Tick): ShortArray = when (sound) {
        ClickSound.CLICK -> renderTonal(sound, tick, noisy = true)
        ClickSound.SINE -> renderTonal(sound, tick, noisy = false)
        ClickSound.CHIRP -> renderChirp(tick)
    }

    /** Son tonique : sinus amorti, avec éventuellement une pointe de bruit à l'attaque (CLICK). */
    private fun renderTonal(sound: ClickSound, tick: Tick, noisy: Boolean): ShortArray {
        val n = SAMPLE_RATE * durationMs(sound) / 1000
        val f = frequency(sound, tick)
        val decay = if (sound == ClickSound.SINE) 0.018 else 0.008
        val amp = gain(tick) * headroom(sound)
        val attackN = (SAMPLE_RATE * 0.0018).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t / decay)
            var s = sin(2.0 * PI * f * t)
            if (noisy) s += 0.6 * (Random.nextDouble() * 2.0 - 1.0) * exp(-t / 0.002)
            val attack = if (i < attackN) i.toDouble() / attackN else 1.0
            val v = (s * env * attack * amp).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * Chirp déterministe : balayage linéaire de fréquence sous enveloppe de Hann (attaque et chute
     * douces, pas de transitoire écrêtant). Excellente autocorrélation → repérage temporel net et
     * annulation facile par filtre connu.
     */
    private fun renderChirp(tick: Tick): ShortArray {
        val n = SAMPLE_RATE * durationMs(ClickSound.CHIRP) / 1000
        val (f0, f1) = chirpRange(tick)
        val amp = gain(tick) * headroom(ClickSound.CHIRP)
        val dur = n.toDouble() / SAMPLE_RATE
        val k = (f1 - f0) / dur // pente du balayage (Hz/s)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2.0 * PI * (f0 * t + 0.5 * k * t * t)
            val env = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)) // Hann
            val v = (sin(phase) * env * amp).coerceIn(-1.0, 1.0)
            out[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
