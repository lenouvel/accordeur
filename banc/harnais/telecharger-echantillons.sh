#!/bin/bash
# Télécharge les notes de guitare (G#1..E5) des soundfonts FluidR3_GM, MusyngKite, FatBoy.
set -u
BASE=https://raw.githubusercontent.com/gleitz/midi-js-soundfonts/044fab8e1456bfafc5776e86dfd6bb8697149aef
NAMES=(C Db D Eb E F Gb G Ab A Bb B)
jobs=()
for sf in FluidR3_GM MusyngKite FatBoy; do
  for inst in acoustic_guitar_steel acoustic_guitar_nylon electric_guitar_clean; do
    mkdir -p samples/$sf/$inst
    for midi in $(seq 32 76); do
      name=${NAMES[$((midi % 12))]}$((midi / 12 - 1))
      f=samples/$sf/$inst/$name.mp3
      [ -s "$f" ] && continue
      echo "$BASE/$sf/$inst-mp3/$name.mp3 $f"
    done
  done
done > /tmp/sample_list.txt
wc -l /tmp/sample_list.txt
cat /tmp/sample_list.txt | xargs -P 16 -n 2 sh -c 'curl -sS --retry 3 -o "$1" "$0" || echo "échec $0"'
