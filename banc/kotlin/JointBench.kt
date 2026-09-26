package com.blenouvel.accordeur

import com.blenouvel.accordeur.PhoneSim.SR
import com.blenouvel.accordeur.audio.PitchDetector
import com.blenouvel.accordeur.audio.RealFft
import com.blenouvel.accordeur.model.Note
import com.blenouvel.accordeur.model.NoteMapper
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Prototype d'**estimation conjointe** (NNLS) pour la page Spectre, mesuré sur le même jeu de
 * données que [ChordBench] (accords réels × micro de téléphone simulé). But : voir si modéliser le
 * spectre comme une combinaison non négative de gabarits d'une note chacun nomme mieux l'accord que
 * le choix glouton actuel. Diagnostic seulement — rien n'est câblé dans l'app.
 *
 * Méthode : salience en log-fréquence (pics de la FFT ramenés sur une grille de [BINS_PER_SEMITONE]
 * points par demi-ton) = b ; dictionnaire A dont chaque colonne est la série harmonique d'une note
 * (partiels au-dessus de la coupure du micro, amplitude 1/h, un peu de raideur) ; on résout
 * min‖A·x − b‖² + λ·Σx, x ≥ 0 (Lawson-Hanson, λ = parcimonie) ; les notes d'activation ≥
 * [REL_THRESHOLD]× le maximum sont « présentes », nommées par le même `ChordNamer` que le reste.
 *
 * **Résultat (banc, `voix 150Hz/4`)** : ~**27 %** des trames au bon nom, contre **75 %** pour le
 * choix glouton — quels que soient λ, le seuil, salience dense ou sur les pics. Des gabarits
 * **génériques** (B fixe, grille log au demi-ton) ne collent pas aux partiels réels d'une corde :
 * la raideur décale les partiels aigus, et le glouton, lui, ajuste f0 **et** B par note puis compte
 * les trous pour écarter les sous-harmoniques — c'est cette précision qui fait sa force. Piste pour
 * une vraie estimation conjointe : garder l'ajustement précis du glouton (séries candidates avec
 * leur f0/B/partiels) et remplacer seulement la **sélection** gloutonne par un NNLS sur ces séries
 * (dictionnaire = candidats ajustés, pas notes génériques). Non fait ici (demande d'exposer les
 * internes de `SpectrumAnalyzer`), et l'ambiguïté d'octave à la basse (voir README, Mi2 vs Mi3)
 * resterait à trancher.
 */
class JointBench {
    private val sources = listOf("FluidR3_GM", "MusyngKite", "FatBoy").flatMap { sf ->
        listOf("acoustic_guitar_steel", "acoustic_guitar_nylon", "electric_guitar_clean").map { sf to it }
    }

    private class Result(val nnls: Double, val greedy: Double, val errs: Map<String, Int>)

    @Test
    fun chords() {
        val mic = PhoneSim.VOICE_150_4
        val jobs = sources.flatMap { src -> ChordBench.CHORDS.entries.map { src to it } }
        val results = jobs.parallelStream().map { (src, chord) ->
            val (sf, inst) = src
            val (expected, voicing) = chord
            val notes = voicing.split(' ').map { Note.parse(it) }
            val lead = SR / 2
            val raw = ChordBench.strum(sf, inst, notes, seed = expected.hashCode().toLong())
            val sig = DoubleArray(lead + raw.size) { if (it >= lead) raw[it - lead] else 0.0 }
            val phone = PhoneSim.applyMic(sig, mic)
            val noise = PhoneSim.noise(phone.size, -90.0, seed = 5); for (i in phone.indices) phone[i] += noise[i]
            val detector = JointDetector()
            val w = FloatArray(JointModel.WINDOW)
            val greedy = ChordBench.analyze(phone, lead, if (notes.any { it.midi < 40 }) 46.25 else 73.42)
            var okNnls = 0
            var okGreedy = 0
            var frames = 0
            val errs = LinkedHashMap<String, Int>()
            val from = (lead + 0.4 * SR).toInt()
            val to = (lead + 1.6 * SR).toInt()
            var end = PitchDetector.HOP
            var frameIndex = 0
            while (end <= phone.size) {
                if (end in from..to) {
                    for (i in 0 until JointModel.WINDOW) { val k = end - JointModel.WINDOW + i; w[i] = if (k >= 0) phone[k].toFloat() else 0f }
                    val nnlsName = ChordBench.chordName(detector.detect(w))
                    if (nnlsName == expected) okNnls++ else errs.merge(nnlsName, 1, Int::plus)
                    if (frameIndex < greedy.size && ChordBench.chordName(greedy[frameIndex]) == expected) okGreedy++
                    frames++
                }
                end += PitchDetector.HOP
                frameIndex++
            }
            val f = max(1, frames)
            expected to Result(okNnls.toDouble() / f, okGreedy.toDouble() / f, errs)
        }.toList()
        println("=== Estimation conjointe (NNLS) vs choix glouton — accords, ${mic.name}, 0,4–1,6 s")
        println(String.format("  %-8s %8s %8s   %s", "accord", "NNLS", "glouton", "erreurs NNLS (top)"))
        var totalNnls = 0.0
        var totalGreedy = 0.0
        var count = 0
        for ((name, list) in results.groupBy { it.first }) {
            val nnls = list.map { it.second.nnls }.average()
            val greedy = list.map { it.second.greedy }.average()
            val errs = LinkedHashMap<String, Int>()
            for (r in list) for ((k, v) in r.second.errs) errs.merge(k, v, Int::plus)
            val top = errs.entries.sortedByDescending { it.value }.take(3).joinToString(" ") { "${it.key}×${it.value}" }
            println(String.format("  %-8s %7.1f%% %7.1f%%   %s", name, 100 * nnls, 100 * greedy, top))
            totalNnls += nnls; totalGreedy += greedy; count++
        }
        println(String.format("  %-8s %7.1f%% %7.1f%%", "TOTAL", 100 * totalNnls / count, 100 * totalGreedy / count))
    }
}

/** Dictionnaire de gabarits harmoniques et A^T·A, calculés une fois. */
object JointModel {
    const val WINDOW = 16384
    const val PAD = 2
    const val MIN_HZ = 150.0 // sous la coupure du micro : rien d'exploitable en dessous
    const val MAX_HZ = 4200.0
    const val BINS_PER_SEMITONE = 4
    const val PARTIALS = 16
    const val STIFFNESS = 2e-4
    const val PARTIAL_HALFWIDTH = 2 // demi-largeur du gabarit d'un partiel (points de grille)
    const val LOW_MIDI = 28 // E1
    const val HIGH_MIDI = 88 // E6

    // Réglables par propriété système (-Dnnls.thresh=… -Dnnls.lambda=…) pour balayer vite.
    val REL_THRESHOLD = System.getProperty("nnls.thresh")?.toDouble() ?: 0.25
    val LAMBDA = System.getProperty("nnls.lambda")?.toDouble() ?: 0.1

    val GRID = gridOf(MAX_HZ) + 1
    val NOTES = HIGH_MIDI - LOW_MIDI + 1

    // Dictionnaire creux : pour chaque note, indices de grille et valeurs (colonne unitaire).
    val dictIdx: Array<IntArray>
    val dictVal: Array<DoubleArray>

    // A^T·A dense (NOTES × NOTES).
    val ata: Array<DoubleArray>

    fun gridOf(hz: Double): Int = (BINS_PER_SEMITONE * 12 * log2(hz / MIN_HZ)).toInt()
    fun gridOfExact(hz: Double): Double = BINS_PER_SEMITONE * 12 * log2(hz / MIN_HZ)

    init {
        dictIdx = Array(NOTES) { IntArray(0) }
        dictVal = Array(NOTES) { DoubleArray(0) }
        for (j in 0 until NOTES) {
            val f0 = NoteMapper.frequencyOf(LOW_MIDI + j)
            val col = DoubleArray(GRID)
            for (h in 1..PARTIALS) {
                val fh = h * f0 * sqrt(1.0 + STIFFNESS * h * h)
                if (fh < MIN_HZ || fh > MAX_HZ) continue
                val center = gridOfExact(fh)
                val amp = 1.0 / h
                val lo = (center - PARTIAL_HALFWIDTH).toInt()
                val hi = (center + PARTIAL_HALFWIDTH).toInt()
                for (g in lo..hi) {
                    if (g < 0 || g >= GRID) continue
                    val d = abs(g - center)
                    val wgt = (1.0 - d / (PARTIAL_HALFWIDTH + 1.0)).coerceAtLeast(0.0) // triangle
                    col[g] = max(col[g], amp * wgt) // un partiel domine sa cellule
                }
            }
            var norm = 0.0
            for (v in col) norm += v * v
            norm = sqrt(norm)
            if (norm < 1e-12) continue
            val idx = ArrayList<Int>()
            val vals = ArrayList<Double>()
            for (g in 0 until GRID) if (col[g] > 0.0) { idx += g; vals += col[g] / norm }
            dictIdx[j] = idx.toIntArray()
            dictVal[j] = vals.toDoubleArray()
        }
        // A^T·A par produit scalaire creux (colonnes triées par indice de grille).
        ata = Array(NOTES) { DoubleArray(NOTES) }
        for (i in 0 until NOTES) {
            for (j in i until NOTES) {
                val s = dot(dictIdx[i], dictVal[i], dictIdx[j], dictVal[j])
                ata[i][j] = s
                ata[j][i] = s
            }
        }
    }

    private fun dot(ai: IntArray, av: DoubleArray, bi: IntArray, bv: DoubleArray): Double {
        var p = 0; var q = 0; var s = 0.0
        while (p < ai.size && q < bi.size) {
            when {
                ai[p] < bi[q] -> p++
                ai[p] > bi[q] -> q++
                else -> { s += av[p] * bv[q]; p++; q++ }
            }
        }
        return s
    }
}

/** Détecteur NNLS réutilisable (une instance par fil ; le modèle partagé est immuable). */
class JointDetector {
    private val fftSize = JointModel.WINDOW * JointModel.PAD
    private val fft = RealFft(fftSize)
    private val hann = DoubleArray(JointModel.WINDOW) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / JointModel.WINDOW) }
    private val padded = DoubleArray(fftSize)
    private val re = DoubleArray(fft.bins)
    private val im = DoubleArray(fft.bins)
    private val binHz = SR.toDouble() / fftSize
    private val salience = DoubleArray(JointModel.GRID)
    private val atb = DoubleArray(JointModel.NOTES)

    /** Notes présentes (numéros MIDI) pour la fenêtre [window]. */
    fun detect(window: FloatArray): List<Int> {
        for (i in 0 until JointModel.WINDOW) padded[i] = window[i] * hann[i]
        java.util.Arrays.fill(padded, JointModel.WINDOW, fftSize, 0.0)
        fft.forward(padded, re, im)
        java.util.Arrays.fill(salience, 0.0)
        // Pics seulement (maxima locaux) : coller aux partiels, ignorer l'énergie large bande
        // entre eux — sinon NNLS l'« explique » par de fausses notes.
        val kMin = max(2, (JointModel.MIN_HZ / binHz).toInt())
        val kMax = min(fft.bins - 2, (JointModel.MAX_HZ / binHz).toInt())
        var k = kMin
        while (k <= kMax) {
            val m = re[k] * re[k] + im[k] * im[k]
            if (m > re[k - 1] * re[k - 1] + im[k - 1] * im[k - 1] &&
                m >= re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1]
            ) {
                val g = JointModel.gridOf(k * binHz)
                if (g in 0 until JointModel.GRID) {
                    val mag = sqrt(m)
                    if (mag > salience[g]) salience[g] = mag
                }
            }
            k++
        }
        var norm = 0.0
        for (v in salience) norm += v * v
        norm = sqrt(norm)
        if (norm < 1e-12) return emptyList()
        for (i in salience.indices) salience[i] /= norm
        for (j in 0 until JointModel.NOTES) {
            val idx = JointModel.dictIdx[j]
            val vals = JointModel.dictVal[j]
            var s = 0.0
            for (t in idx.indices) s += vals[t] * salience[idx[t]]
            atb[j] = s
        }
        val x = Nnls.solve(JointModel.ata, atb, JointModel.LAMBDA)
        var xmax = 0.0
        for (v in x) if (v > xmax) xmax = v
        if (xmax <= 0.0) return emptyList()
        val present = ArrayList<Int>()
        for (j in 0 until JointModel.NOTES) if (x[j] >= JointModel.REL_THRESHOLD * xmax) present += JointModel.LOW_MIDI + j
        return present
    }
}

/**
 * Moindres carrés non négatifs (Lawson-Hanson) via les équations normales A^T·A x = A^T·b.
 * [lambda] : seuil d'entrée (parcimonie type L1) — une note n'est activée que si elle réduit le
 * résidu de plus que [lambda], ce qui empêche d'empiler des notes parasites (9es, 11es fantômes).
 */
object Nnls {
    fun solve(ata: Array<DoubleArray>, atb: DoubleArray, lambda: Double = 0.0, maxIter: Int = 300): DoubleArray {
        val tol = 1e-9
        val n = atb.size
        val x = DoubleArray(n)
        val passive = BooleanArray(n)
        val w = DoubleArray(n)
        var iter = 0
        while (iter < maxIter) {
            for (i in 0 until n) {
                var s = atb[i]
                val row = ata[i]
                for (j in 0 until n) s -= row[j] * x[j]
                w[i] = s
            }
            var jmax = -1
            var wmax = max(tol, lambda)
            for (j in 0 until n) if (!passive[j] && w[j] > wmax) { wmax = w[j]; jmax = j }
            if (jmax < 0) break
            passive[jmax] = true
            while (iter < maxIter) {
                iter++
                val idx = ArrayList<Int>()
                for (j in 0 until n) if (passive[j]) idx += j
                val z = solvePassive(ata, atb, idx, n)
                var allPos = true
                for (p in idx) if (z[p] <= 0.0) { allPos = false; break }
                if (allPos) { for (i in 0 until n) x[i] = z[i]; break }
                var alpha = Double.MAX_VALUE
                for (p in idx) if (z[p] <= 0.0) { val a = x[p] / (x[p] - z[p]); if (a < alpha) alpha = a }
                for (i in 0 until n) x[i] += alpha * (z[i] - x[i])
                for (p in idx.toList()) if (x[p] <= tol) { passive[p] = false; x[p] = 0.0 }
            }
        }
        return x
    }

    /** Résout (A^T·A restreint à [idx]) z = (A^T·b restreint), z hors [idx] = 0. Élimination de Gauss. */
    private fun solvePassive(ata: Array<DoubleArray>, atb: DoubleArray, idx: List<Int>, n: Int): DoubleArray {
        val k = idx.size
        val m = Array(k) { DoubleArray(k + 1) }
        for (a in 0 until k) {
            val row = ata[idx[a]]
            for (b in 0 until k) m[a][b] = row[idx[b]]
            m[a][k] = atb[idx[a]]
        }
        for (col in 0 until k) {
            var piv = col
            for (r in col + 1 until k) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            val d = m[col][col]
            if (abs(d) < 1e-12) continue
            for (r in 0 until k) if (r != col) {
                val fac = m[r][col] / d
                for (c in col..k) m[r][c] -= fac * m[col][c]
            }
        }
        val z = DoubleArray(n)
        for (a in 0 until k) { val d = m[a][a]; if (abs(d) > 1e-12) z[idx[a]] = m[a][k] / d }
        return z
    }
}
