package com.blenouvel.accordeur.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT complexe radix-2 itérative (Cooley–Tukey), en place, Kotlin pur.
 * Tables de twiddles et de permutation précalculées : aucune allocation par appel.
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "taille non puissance de 2 : $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val bitReversed: IntArray = run {
        val bits = Integer.numberOfTrailingZeros(size)
        IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    /** Transformée directe : X[k] = Σ x[n]·e^(−2iπkn/N). */
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, -1.0)

    /** Transformée inverse non normalisée : x[n] = Σ X[k]·e^(+2iπkn/N) (diviser par N). */
    fun inverse(re: DoubleArray, im: DoubleArray) = transform(re, im, 1.0)

    private fun transform(re: DoubleArray, im: DoubleArray, sign: Double) {
        val n = size
        for (i in 0 until n) {
            val j = bitReversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var half = 1
        while (half < n) {
            val step = n / (half shl 1)
            var start = 0
            while (start < n) {
                var t = 0
                for (j in start until start + half) {
                    val l = j + half
                    val c = cosTable[t]
                    val s = sign * sinTable[t]
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    t += step
                }
                start += half shl 1
            }
            half = half shl 1
        }
    }
}

/**
 * FFT d'un signal réel de taille [size] calculée avec une FFT complexe de taille size/2
 * (≈ 2× moins de calcul qu'une FFT complexe pleine taille). Sortie : bins 0…size/2.
 */
class RealFft(val size: Int) {
    init {
        require(size >= 4 && (size and (size - 1)) == 0) { "taille non puissance de 2 : $size" }
    }

    private val half = size / 2
    private val fft = Fft(half)
    private val zr = DoubleArray(half)
    private val zi = DoubleArray(half)

    // W^k = e^(−2iπk/size) pour k = 0…size/2.
    private val wr = DoubleArray(half + 1) { cos(2.0 * PI * it / size) }
    private val wi = DoubleArray(half + 1) { -sin(2.0 * PI * it / size) }

    /** Nombre de bins produits : size/2 + 1 (du continu à Nyquist). */
    val bins: Int get() = half + 1

    /**
     * X[k] = Σ x[n]·e^(−2iπkn/size), k = 0…size/2.
     * [input] : au moins [size] valeurs ; [outRe]/[outIm] : au moins [bins] valeurs.
     */
    fun forward(input: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        for (n in 0 until half) {
            zr[n] = input[2 * n]
            zi[n] = input[2 * n + 1]
        }
        fft.forward(zr, zi)
        for (k in 0..half) {
            val a = if (k == half) 0 else k
            val b = if (k == 0) 0 else half - k
            val ar = zr[a]; val ai = zi[a]
            val br = zr[b]; val bi = zi[b]
            // Pairs : E = (Z[k] + conj Z[N/2−k]) / 2 ; impairs : O = (Z[k] − conj Z[N/2−k]) / 2i.
            val er = 0.5 * (ar + br)
            val ei = 0.5 * (ai - bi)
            val odr = 0.5 * (ai + bi)
            val odi = -0.5 * (ar - br)
            // X[k] = E + W^k · O
            val c = wr[k]; val s = wi[k]
            outRe[k] = er + (c * odr - s * odi)
            outIm[k] = ei + (c * odi + s * odr)
        }
    }

    /**
     * Inverse de [forward] : à partir du demi-spectre X[k], k = 0…size/2, d'un signal réel,
     * reconstruit x[n], n = 0…size−1 (normalisé).
     */
    fun inverse(inRe: DoubleArray, inIm: DoubleArray, output: DoubleArray) {
        for (k in 0 until half) {
            val ar = inRe[k]; val ai = inIm[k]
            val br = inRe[half - k]; val bi = -inIm[half - k] // conj X[N/2−k]
            val er = 0.5 * (ar + br)
            val ei = 0.5 * (ai + bi)
            val dr = 0.5 * (ar - br)
            val di = 0.5 * (ai - bi)
            // O = (X[k] − conj X[N/2−k]) / 2 · conj(W^k)
            val c = wr[k]; val s = -wi[k]
            val odr = dr * c - di * s
            val odi = dr * s + di * c
            // Z = E + i·O
            zr[k] = er - odi
            zi[k] = ei + odr
        }
        fft.inverse(zr, zi)
        val scale = 1.0 / half
        for (n in 0 until half) {
            output[2 * n] = zr[n] * scale
            output[2 * n + 1] = zi[n] * scale
        }
    }
}
