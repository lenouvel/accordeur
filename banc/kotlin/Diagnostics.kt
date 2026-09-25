package com.blenouvel.accordeur

import com.blenouvel.accordeur.TestSignals.SAMPLE_RATE
import com.blenouvel.accordeur.TestSignals.cents
import com.blenouvel.accordeur.audio.*
import com.blenouvel.accordeur.model.*
import org.junit.Test
import kotlin.math.*

class Diagnostics {
    private fun pipeline(signal: DoubleArray, targets: TunerTargets = TunerTargets.NONE, mode: TunerMode = TunerMode.MONO): List<TunerFrame?> {
        val p = TunerProcessor(SAMPLE_RATE); p.targets = targets; p.mode = mode
        val input = TestSignals.toFloat(signal); val chunk = FloatArray(PitchDetector.HOP)
        val out = ArrayList<TunerFrame?>(); var i = 0
        while (i + chunk.size <= input.size) { input.copyInto(chunk, 0, i, i + chunk.size); out += p.process(chunk); i += chunk.size }
        return out
    }
    private fun fmt(x: Double) = String.format("%+.3f", x)

    @Test fun report() {
        println("=== Sinus + bruit -20 dB : erreur (cents) MPM / final, clarté")
        for (f in listOf(51.91, 55.0, 61.74, 82.41, 110.0, 146.83, 196.0, 246.94, 329.63)) {
            val s = TestSignals.addNoise(TestSignals.sine(f, 1.0), 0.3/sqrt(2.0)*0.1)
            val d = PitchDetector(); val e = PitchEstimate(); d.detect(s.copyOfRange(24000, 24000+8192), e)
            println("  $f Hz : MPM ${fmt(cents(e.mpmFrequency,f))}  final ${fmt(cents(e.frequency,f))}  clarté ${"%.3f".format(e.clarity)} partiels ${e.partials}")
        }
        println("=== Corde raide (fond. -20 dB, bruit -40 dB) : MPM vs raffiné")
        for ((f,b) in listOf(51.91 to 6e-4, 55.0 to 5e-4, 61.74 to 4e-4, 73.42 to 3e-4, 82.41 to 2e-4, 110.0 to 1.5e-4, 146.83 to 1e-4, 196.0 to 3e-5, 246.94 to 2e-5, 329.63 to 1e-5)) {
            val s = TestSignals.addNoise(TestSignals.pluckedString(f, 1.0, inharmonicity=b, partials=20, rolloff=0.9, fundamentalDb=-20.0, seed=9), 0.003)
            val d = PitchDetector(); val e = PitchEstimate(); d.detect(s.copyOfRange(24000, 24000+8192), e)
            println("  $f Hz B=$b : MPM ${fmt(cents(e.mpmFrequency,f))}  raffiné ${fmt(cents(e.frequency,f))}  B^=${"%.2e".format(e.inharmonicity)} partiels ${e.partials}")
        }
        println("=== Pipeline complet, corde raide + bruit, 3 s : écart moyen / max / dispersion (cents), latence 1re lecture")
        for ((f,b) in listOf(51.91 to 6e-4, 61.74 to 4e-4, 82.41 to 2e-4, 110.0 to 1.5e-4, 196.0 to 3e-5, 329.63 to 1e-5)) {
            val tone = TestSignals.pluckedString(f, 3.0, inharmonicity=b, partials=20, rolloff=0.9, fundamentalDb=-15.0, decaySeconds=2.0, seed=4)
            val s = TestSignals.addNoise(TestSignals.concat(TestSignals.silence(0.3), tone), 0.002, seed = 3)
            val frames = pipeline(s)
            val vals = frames.mapIndexedNotNull { i, fr -> if (fr != null && fr.hasPitch && !fr.holding) i to cents(fr.frequency, f) else null }
            val first = vals.firstOrNull()?.first ?: -1
            val cs = vals.map { it.second }
            val late = cs.drop(5)
            println("  $f Hz : n=${cs.size} moy ${fmt(late.average())} max ${fmt(late.maxOf{abs(it)})} disp ${"%.3f".format(late.max()-late.min())}  1re lecture ${"%.0f".format((first+1)*PitchDetector.HOP*1000.0/SAMPLE_RATE - 300)} ms après l'attaque")
        }
        println("=== G#1 + ronflement secteur 50 Hz (+100/150 Hz) : niveau du ronflement relatif")
        for (humDb in listOf(-40.0, -30.0, -20.0, -12.0, -6.0)) {
            val f = 51.91
            val tone = TestSignals.pluckedString(f, 2.0, inharmonicity=6e-4, partials=16, fundamentalDb=-15.0, seed=2)
            val a = 0.3 * 10.0.pow(humDb/20)
            val hum = TestSignals.mix(TestSignals.sine(50.0, 2.3, a), TestSignals.sine(100.0, 2.3, a*0.5), TestSignals.sine(150.0, 2.3, a*0.3))
            val s = TestSignals.mix(TestSignals.concat(TestSignals.silence(0.3), tone), hum)
            val frames = pipeline(s)
            val cs = frames.drop(16).filterNotNull().filter { it.hasPitch && !it.holding }.map { cents(it.frequency, f) }
            val notes = frames.filterNotNull().filter { it.hasPitch }.map { NoteNames.label(NoteMapper.nearest(it.frequency).note, Notation.ENGLISH) }.toSet()
            println("  hum ${humDb} dB : n=${cs.size} moy ${if (cs.isEmpty()) "-" else fmt(cs.average())} max ${if (cs.isEmpty()) "-" else fmt(cs.maxOf{abs(it)})} notes=$notes")
        }
        println("=== Glissando (mécanique) E2 -40 → +10 cents en 2 s : retard de suivi")
        run {
            val f0 = 82.41; val n = (2.5*SAMPLE_RATE).toInt(); val s = DoubleArray(n); var ph = 0.0
            for (i in 0 until n) { val t = i.toDouble()/SAMPLE_RATE; val c = if (t < 0.25) -40.0 else min(10.0, -40.0 + 25.0*(t-0.25)); val f = f0*2.0.pow(c/1200); ph += 2*PI*f/SAMPLE_RATE
                s[i] = 0.3*(sin(ph) + 0.5*sin(2*ph+0.3) + 0.3*sin(3*ph+1.1)) }
            val frames = pipeline(TestSignals.concat(TestSignals.silence(0.2), s))
            for ((i, fr) in frames.withIndex()) if (i % 6 == 0 && fr != null) {
                val t = (i+1)*PitchDetector.HOP.toDouble()/SAMPLE_RATE - 0.2
                val truth = if (t < 0.25) -40.0 else min(10.0, -40.0 + 25.0*(t-0.25))
                println("  t=${"%.2f".format(t)} s vrai ${fmt(truth)} affiché ${if (fr.hasPitch) fmt(cents(fr.frequency, f0)) else "-"}")
            }
        }
        println("=== Enchaînement E2 puis A2 (nouvelle attaque pendant que E2 sonne)")
        run {
            val e2 = TestSignals.pluckedString(82.41, 3.0, inharmonicity=2e-4, fundamentalDb=-10.0, amplitude=0.3, seed=1)
            val a2 = TestSignals.concat(TestSignals.silence(1.5), TestSignals.pluckedString(110.0, 1.5, inharmonicity=1.5e-4, fundamentalDb=-10.0, amplitude=0.5, seed=2))
            val frames = pipeline(TestSignals.concat(TestSignals.silence(0.2), TestSignals.mix(e2, a2)))
            val seq = frames.mapIndexed { i, fr -> "%.2f".format((i+1)*PitchDetector.HOP.toDouble()/SAMPLE_RATE - 0.2) + ":" + (if (fr != null && fr.hasPitch) NoteNames.label(NoteMapper.nearest(fr.frequency).note, Notation.ENGLISH) + (if (fr.holding) "(h)" else "") else "-") }
            println("  " + seq.filterIndexed { i, _ -> i in 30..48 }.joinToString(" "))
        }
        println("=== Poly : standard 6 désaccordé, erreurs (cents)")
        run {
            val t = Presets.STANDARD_6; val det = doubleArrayOf(-12.0, 7.0, 0.0, 20.0, -5.0, 3.0)
            val parts = t.frequencies().mapIndexed { i, f -> TestSignals.pluckedString(f*2.0.pow(det[i]/1200), 2.0, inharmonicity = if (f<100) 2e-4 else 5e-5, partials=12, fundamentalDb=-12.0, amplitude=0.1, seed=40L+i) }
            val s = TestSignals.addNoise(TestSignals.concat(TestSignals.silence(0.3), TestSignals.mix(*parts.toTypedArray())), 0.001)
            val frames = pipeline(s, TunerTargets(t.frequencies()), TunerMode.POLY)
            val firstPoly = frames.indexOfFirst { it?.poly != null }
            println("  1re lecture poly ${"%.0f".format((firstPoly+1)*PitchDetector.HOP*1000.0/SAMPLE_RATE - 300)} ms après le grattage")
            val last = frames.last { it?.poly != null }!!.poly!!
            println("  " + last.strings.mapIndexed { i, s -> "${t.strings[i].let{NoteNames.label(it.note, Notation.ENGLISH)}}: ${if (s.detected) fmt(s.cents - det[i]) else "--"}${if (!s.reliable) "≈" else ""}" }.joinToString("  "))
        }
    }

    @Test fun timing() {
        val d = PitchDetector(); val e = PitchEstimate()
        val s = TestSignals.pluckedString(82.41, 1.0, inharmonicity=2e-4, partials=16, seed=1)
        val w = s.copyOfRange(10000, 10000+8192)
        repeat(300) { d.detect(w, e) }
        val n = 2000; val t0 = System.nanoTime(); repeat(n) { d.detect(w, e) }; val dt = (System.nanoTime()-t0)/1e6/n
        val poly = PolyPitchDetector(); val pw = s.copyOfRange(0, 16384); val targets = Presets.STANDARD_7.frequencies()
        repeat(100) { poly.analyze(pw, targets, 0.0) }
        val t1 = System.nanoTime(); repeat(500) { poly.analyze(pw, targets, 0.0) }; val dp = (System.nanoTime()-t1)/1e6/500
        println("=== Coût (JVM desktop) : détection mono ${"%.3f".format(dt)} ms/analyse (×23/s = ${"%.1f".format(dt*23.4/10)} % d'un cœur) ; poly ${"%.3f".format(dp)} ms/analyse")
    }
}
