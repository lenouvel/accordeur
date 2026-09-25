#!/bin/sh
# Copies instrumentées de TunerProcessor + PolyTracker (paquet dbg) pour tracer le mode poly.
cd "$(dirname "$0")"
A=/home/user/accordeur/app/src/main/kotlin/com/blenouvel/accordeur/audio
for f in PolyTracker TunerProcessor; do
  sed -e 's/^package com.blenouvel.accordeur.audio/package com.blenouvel.accordeur.dbg\n\nimport com.blenouvel.accordeur.audio.*/' $A/$f.kt > diag/kotlin/Dbg$f.kt
done
python3 - <<'PY'
p='diag/kotlin/DbgPolyTracker.kt'
s=open(p).read()
s=s.replace("class PolyTracker(","class PolyTracker(")
s=s.replace("""        val energies = poly.energies
""","""        val energies = poly.energies
        if (trace) println(String.format("  t=%.2f %s", time, targets.indices.joinToString(" ") { i -> String.format("%5.1f%s", 10 * Math.log10(energies[i] + 1e-30), if (reading.strings[i].detected) (if (harmonic(i)) "*" else "'") else " ") }))
""")
s=s.replace("""    private fun startEvent(time: Double, lag: Double) {
        pending = true""","""    private fun startEvent(time: Double, lag: Double) {
        if (trace) println(String.format("  → attaque à %.2f (retard %.2f)", time, lag))
        pending = true""")
s=s.replace("""        if (count == 0) return // choc, bruit : rien de nouveau, le tableau reste tel quel""","""        if (trace) println("  → classement : fraîches " + targets.indices.filter { fresh[it] } + " base " + targets.indices.joinToString(" ") { String.format("%.1f", 10 * Math.log10(baseline[it] + 1e-30)) } + " mono " + monoString(monoWindow))
        if (count == 0) return // choc, bruit : rien de nouveau, le tableau reste tel quel""")
s=s.replace("""    private val monoEstimate = PitchEstimate()
""","""    private val monoEstimate = PitchEstimate()
    var trace = true
""")
open(p,'w').write(s)
p='diag/kotlin/DbgTunerProcessor.kt'
s=open(p).read()
s=s.replace("enum class TunerMode { MONO, POLY }","")
import re
# retire les déclarations publiques dupliquées (TunerTargets, TunerFrame) : on utilise celles de audio
start=s.index("/**\n * Cibles fournies par l'interface")
end=s.index("/**\n * Chaîne de traitement complète")
s=s[:start]+s[end:]
s=s.replace("""        } else if (attack && stabilizer.gateOpen) {
            polyTracker.onset(time)
        }""","""        } else if (attack && stabilizer.gateOpen) {
            println(String.format("  [clic] %.2f", time))
            polyTracker.onset(time)
        }""")
s=s.replace("""            polyOnsets = stabilizer.onsetCount
            polyTracker.onset(stabilizer.lastOnsetTime)""","""            polyOnsets = stabilizer.onsetCount
            println(String.format("  [gate] %.2f", stabilizer.lastOnsetTime))
            polyTracker.onset(stabilizer.lastOnsetTime)""")
open(p,'w').write(s)
PY
