#!/bin/sh
# Copie instrumentée du détecteur courant (traces des candidats).
cd "$(dirname "$0")"
sed -e 's/^package com.blenouvel.accordeur.audio/package com.blenouvel.accordeur.dbg/' -e 's/class PitchDetector(/class DebugDetector(/' -e 's/class PitchEstimate {/class DebugEstimate {/' -e 's/out: PitchEstimate/out: DebugEstimate/' /home/user/accordeur/app/src/main/kotlin/com/blenouvel/accordeur/audio/PitchDetector.kt > diag/kotlin/DebugDetector.kt
python3 - <<'PY'
p='diag/kotlin/DebugDetector.kt'
s=open(p).read()
s=s.replace("import kotlin.math.sqrt","import kotlin.math.sqrt\nimport com.blenouvel.accordeur.audio.RealFft")
s=s.replace("""                if (!evaluate(candidates[i])) continue""","""                if (!evaluate(candidates[i])) { if (trace) println(String.format("    cand %8.3f → rien", candidates[i])); continue }
                if (trace) println(String.format("    cand %8.3f → f1 %8.3f B %.1e expl %.3f partiels %d", candidates[i], fitF0 * sqrt(1 + fitB), fitB, evalExplained, evalPartials))""")
s=s.replace("""    // Meilleure mesure de la MPM (dernier appel).""","""    var trace = false
    fun dumpPeaks() { for (i in 0 until peakCount) println(String.format("    pic %8.2f Hz  %.2e (snr %.0f)", peakFrequency[i], peakMagnitude[i], peakSnr[i])) }
    // Meilleure mesure de la MPM (dernier appel).""")
open(p,'w').write(s)
PY
