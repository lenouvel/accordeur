#!/bin/sh
# Copie instrumentée de l'analyseur de spectre (traces des séries et des notes).
cd "$(dirname "$0")"
sed -e 's/^package com.blenouvel.accordeur.audio/package com.blenouvel.accordeur.dbg/' -e 's/class SpectrumAnalyzer(/class DebugSpectrum(/' -e 's/^class SpectrumPeak/class DbgPeak/' -e 's/^class HeardNote/class DbgNote/' -e 's/^class SpectrumFrame(/class DbgFrame(/' /home/user/accordeur/app/src/main/kotlin/com/blenouvel/accordeur/audio/SpectrumAnalyzer.kt | sed -e 's/SpectrumPeak(/DbgPeak(/g; s/HeardNote(/DbgNote(/g; s/SpectrumFrame(/DbgFrame(/g; s/: SpectrumFrame/: DbgFrame/g; s/List<SpectrumPeak>/List<DbgPeak>/g; s/List<HeardNote>/List<DbgNote>/g; s/ArrayList<HeardNote>/ArrayList<DbgNote>/g; s/SpectrumAnalyzer\./DebugSpectrum./g' > diag/kotlin/DebugSpectrum.kt
python3 - <<'PY'
p='diag/kotlin/DebugSpectrum.kt'
s=open(p).read()
s=s.replace("import kotlin.math.sqrt","import kotlin.math.sqrt\nimport com.blenouvel.accordeur.audio.RealFft")
s=s.replace("""        while (noteCount < MAX_NOTES) {
            var best = -1""","""        if (trace) for (t in 0 until seriesTotal) if (seriesF1[t] < 700) {
            var share = 0.0; for (k in 0 until seriesCount[t]) share += power[seriesPeaks[t][k]]
            println(String.format("   série %7.2f Hz pics %2d trous %d/%d fort %.0f dB part %.2f %s", seriesF1[t], seriesCount[t], seriesHoles[t], seriesSpan[t], seriesStrongestDb[t] - strongestDb, share / totalPower, if (seriesValid[t]) "" else "INVALIDE"))
        }
        while (noteCount < MAX_NOTES) {
            var best = -1""")
s=s.replace("""            if (best < 0) break
            noteHz[noteCount++] = seriesF1[best]""","""            if (best < 0) break
            if (trace) println(String.format("   → note %.2f Hz apport %.3f", seriesF1[best], bestGain))
            noteHz[noteCount++] = seriesF1[best]""")
s=s.replace("""    // --- Axe d'affichage""","""    var trace = false
    fun peaks() = peakCount
    fun dumpPeaks(maxHz: Double) { for (i in 0 until peakCount) if (peakHz[i] < maxHz) println(String.format("   pic %8.2f Hz %6.1f dB snr %5.1f", peakHz[i], peakDb[i], peakSnr[i])) }
    // --- Axe d'affichage""")
open(p,'w').write(s)
PY
