package com.blenouvel.accordeur.audio

import java.util.Arrays
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Détection d'attaques (onsets) pour le suivi de tempo : repère **quand** une note commence, sans
 * se soucier de **quelle** note (pas de hauteur). Même recette que l'attaque du médiator de
 * [TunerProcessor] mais isolée, pure et testable, et cadencée sur un hop court (~5 ms) pour dater
 * l'attaque finement.
 *
 * Par bloc de [hop] échantillons : énergie des aigus (passe-haut [bandHz]) en dB, comparée à la
 * médiane glissante des derniers blocs ; un saut de [jumpDb] au-dessus de la médiane (au-dessus du
 * plancher [floorDb], hors période réfractaire) signale une attaque. Les aigus d'une corde qui sonne
 * s'éteignent vite, pas le transitoire d'une nouvelle attaque.
 */
class OnsetDetector(
    sampleRate: Int,
    private val hop: Int = HOP,
    bandHz: Double = BAND_HZ,
    private val jumpDb: Double = JUMP_DB,
    refractorySec: Double = REFRACTORY_SEC,
    private val floorDb: Double = FLOOR_DB,
) {
    private val filter = Biquad.highPass(sampleRate, bandHz)
    private val secPerHop = hop.toDouble() / sampleRate
    private val refractoryHops = max(1, (refractorySec / secPerHop).roundToInt())

    private val history = DoubleArray(HISTORY)
    private val sorted = DoubleArray(HISTORY)
    private var count = 0
    private var next = 0

    private var energy = 0.0
    private var fill = 0
    private var hopIndex = 0L
    private var lastOnsetHop = Long.MIN_VALUE / 2
    private var prevLevelDb = -120.0

    /**
     * Alimente le détecteur avec [n] échantillons ; appelle [onOnset] (instant en secondes depuis le
     * premier échantillon) à chaque attaque repérée.
     */
    inline fun process(input: FloatArray, n: Int = input.size, onOnset: (timeSec: Double) -> Unit) {
        for (i in 0 until n) {
            val onset = feed(input[i])
            if (onset >= 0.0) onOnset(onset)
        }
    }

    /**
     * Traite un échantillon ; renvoie l'instant (s) de l'attaque qui vient de se clore sur ce bloc,
     * ou une valeur négative s'il n'y en a pas. (Public pour [process] inline ; usage interne.)
     */
    fun feed(sample: Float): Double {
        val a = filter.process(sample.toDouble())
        energy += a * a
        if (++fill < hop) return -1.0
        val levelDb = toDb(energy / fill)
        energy = 0.0
        fill = 0
        return detect(levelDb)
    }

    private fun detect(levelDb: Double): Double {
        hopIndex++
        var median = Double.NaN
        if (count >= HISTORY / 2) {
            System.arraycopy(history, 0, sorted, 0, count)
            Arrays.sort(sorted, 0, count)
            median = sorted[count / 2]
        }
        history[next] = levelDb
        next = (next + 1) % HISTORY
        if (count < HISTORY) count++

        // Front montant : l'énergie dépasse le fond (médiane) ET monte franchement par rapport au bloc
        // précédent. Le second critère évite qu'une note **tenue** ne redéclenche en boucle le temps
        // que la médiane rattrape son niveau (une attaque, elle, décroît tout de suite après le pic).
        val rising = levelDb - prevLevelDb
        prevLevelDb = levelDb
        val isOnset = !median.isNaN() &&
            levelDb > floorDb &&
            levelDb > median + jumpDb &&
            rising > RISE_DB &&
            hopIndex - lastOnsetHop >= refractoryHops
        if (!isOnset) return -1.0
        lastOnsetHop = hopIndex
        return hopIndex * secPerHop
    }

    fun reset() {
        filter.reset()
        count = 0
        next = 0
        energy = 0.0
        fill = 0
        hopIndex = 0
        lastOnsetHop = Long.MIN_VALUE / 2
        prevLevelDb = -120.0
    }

    private fun toDb(meanSquare: Double): Double =
        if (meanSquare <= 1e-12) -120.0 else 10.0 * log10(meanSquare)

    companion object {
        /** ~5,3 ms à 48 kHz : assez court pour dater l'attaque, assez long pour une énergie stable. */
        const val HOP = 256
        const val BAND_HZ = 1200.0
        const val JUMP_DB = 8.0

        /** Montée minimale d'un bloc à l'autre pour parler d'attaque (sinon : note tenue). */
        const val RISE_DB = 3.0

        /**
         * 80 ms : coalesce les double-déclenchements (médiator, et surtout le 2ᵉ transitoire d'un
         * clic de métronome fort entendu au micro, mesuré ~60 ms après le premier). Limite le débit
         * à ~12 attaques/s (OK jusqu'aux doubles-croches à ~150 BPM).
         */
        const val REFRACTORY_SEC = 0.08
        const val FLOOR_DB = -55.0

        /** ~0,25 s d'historique pour la médiane (48 × 5,3 ms). */
        const val HISTORY = 48
    }
}
