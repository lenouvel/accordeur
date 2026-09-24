# Accordeur — guitare 6 et 7 cordes (Android natif)

Accordeur de précision pour Samsung S25 (et tout Android 8+), écrit en Kotlin + Jetpack Compose,
sans NDK. Spécification complète : [`PLAN.md`](PLAN.md).

- **Mono** : une corde à la fois, précision ~0,1 cent sur signal propre, lecture stable à ±0,5 cent.
- **Poly** (type PolyTune) : on gratte toutes les cordes, chacune s'affiche trop basse / juste / trop haute.
- Cordes **très graves** : détection fiable jusqu'à ~35 Hz (G♯1 = 51,91 Hz en Drop G♯).
- Notation **française** (Do Ré Mi), **anglaise** (C D E) ou **les deux**.
- Accordages fournis : 6 cordes Standard, Drop D ; 7 cordes Standard, Drop A, Drop G♯
  (`G#D#G#C#F#A#D#`) ; plus Mi♭ standard, Ré standard, Drop C♯, Drop C, DADGAD, Open G, Open D,
  Mi♭ standard 7 cordes, et un **éditeur d'accordages personnalisés** (4 à 8 cordes).
- La de référence réglable (430–450 Hz, pas de 0,5 Hz), tolérance réglable (±1 à ±15 cents).
- Thème sombre par défaut (clair / système / Material You), vibration quand une corde est juste,
  écran maintenu allumé, **son de référence** (appui long sur une corde).

## Compiler et installer sur le S25

### 1. Android Studio

1. Installer **Android Studio** (Quail ou plus récent — il embarque le SDK et le JDK).
2. *File › Open* → dossier `accordeur` → laisser la synchronisation Gradle se terminer.
   Android Studio propose d'installer ce qui manque (SDK Platform 37, Build Tools) : accepter.

### 2. Préparer le téléphone

1. *Paramètres › À propos du téléphone › Informations sur le logiciel* → toucher 7 fois
   **Numéro de version** → les **Options de développement** apparaissent.
2. *Paramètres › Options de développement* → activer **Débogage USB**.
3. Brancher le S25 en USB et accepter l'empreinte de l'ordinateur.

### 3. Installer

- **Depuis Android Studio** : choisir le S25 dans la liste des appareils puis **Run ▶**.
- **En ligne de commande** :

  ```sh
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

  Ou copier l'APK sur le téléphone et l'ouvrir (autoriser « Installer des applis inconnues »).
- **Version optimisée (recommandée à l'usage)** : `./gradlew assembleRelease` produit
  `app/build/outputs/apk/release/app-release.apk`, passé par R8 et signé avec la clé de debug
  (installable directement, à ne pas publier sur un store). Compose est nettement plus fluide
  dans ce build qu'en debug.

Au premier lancement, autoriser le **micro**.

### Tests (sans téléphone)

```sh
./gradlew test
```

Le traitement du signal est en Kotlin pur : il est testé sur la JVM avec des signaux synthétiques
(sinusoïdes, cordes « raides » inharmoniques, bruit blanc, grattages complets désaccordés).

## Utilisation

| Geste | Effet |
|---|---|
| Toucher le nom de l'accordage (en haut) | Choisir / créer / modifier un accordage |
| **Mono / Poly** | Accord fin corde par corde / coup d'œil sur toutes les cordes |
| Toucher une corde | Mode auto : la verrouiller (re-toucher pour libérer). Mode manuel : la choisir. Mode poly : passer en mono sur cette corde |
| Appui long sur une corde | Son de référence (2,5 s, l'analyse est suspendue pendant la lecture) |
| Puce **Auto / Manuel** | Auto : note la plus proche. Manuel : écart par rapport à la corde choisie |

La jauge passe au vert dans la tolérance (±5 cents par défaut, « Parfait » sous ±3 cents) ; une
corde restée juste ~0,35 s passe au vert dans la rangée (avec une légère vibration).

## Architecture

```
Micro ─► AudioEngine ─► TunerProcessor ──────────────────────────────► TunerViewModel ─► UI Compose
       (AudioRecord,    pré-filtrage ─► PitchDetector (MPM) ─► PitchStabilizer  (StateFlow)
        thread audio)   (DC, 32 Hz–1,3 kHz)  PolyPitchDetector (mode poly)
```

```
app/src/main/kotlin/com/blenouvel/accordeur/
├─ MainActivity.kt          permission micro, navigation, start/stop audio (onResume/onPause), écran allumé
├─ TunerViewModel.kt        état UI (StateFlow), verrou de corde, cordes « au vert », son de référence
├─ audio/
│  ├─ AudioEngine.kt        AudioRecord UNPROCESSED → VOICE_RECOGNITION → MIC, 48 kHz float (replis 44,1 kHz / 16 bits)
│  ├─ TunerProcessor.kt     chaîne complète indépendante d'Android : filtres, tampon circulaire, analyses, poly
│  ├─ PitchDetector.kt      MPM/NSDF par FFT + interpolation parabolique + raffinement par les partiels
│  ├─ PitchStabilizer.kt    gate adaptatif, garde d'octave, médian 5, filtre 1€, maintien
│  ├─ PolyPitchDetector.kt  FFT haute résolution par corde cible (mode poly)
│  ├─ Fft.kt                FFT radix-2 complexe + FFT réelle (Kotlin pur)
│  ├─ Biquad.kt             DC block, passe-haut 32 Hz, passe-bas 1,3 kHz (ordre 4)
│  ├─ OneEuroFilter.kt      lissage adaptatif de l'aiguille
│  └─ ReferenceTone.kt      son de référence (synthèse additive, AudioTrack)
├─ model/                   Note, GuitarString, Tuning, presets ; NoteMapper (fréq ↔ MIDI ↔ note ↔ cents, FR/EN)
├─ data/SettingsStore.kt    réglages (DataStore)
└─ ui/                      TunerScreen, TunerMeter (jauge Canvas), StringSelector, PolyMeter, TuningSheet, SettingsScreen, thème
```

### Traitement du signal (mode mono)

1. **Capture** : 48 kHz mono float, source `UNPROCESSED` (sans AGC ni anti-bruit) si l'appareil
   la propose. Blocs de 2048 échantillons (hop ≈ 43 ms, ~23 analyses/s), tampons préalloués.
2. **Pré-filtrage continu** : suppression du continu, passe-haut 32 Hz (préserve G♯1),
   passe-bas 1,3 kHz d'ordre 4 (Butterworth, biquads RBJ en double).
3. **Gate** : niveau > max(−85 dBFS, bruit ambiant + 10 dB), avec hystérésis ; le bruit de fond
   est appris en continu. Pas d'analyse sur le silence.
4. **MPM** (McLeod) sur 8192 échantillons (171 ms, ≥ 4 périodes à 51,9 Hz) : autocorrélation
   **par FFT** (10 à 20× moins de calcul que le calcul direct), NSDF, premier maximum ≥ 0,9 × max,
   interpolation parabolique, clarté.
5. **Raffinement par les partiels** : les partiels sont mesurés dans le spectre (fenêtre de Hann,
   estimateur exact à deux bins) et ajustés au modèle de corde raide f_h = h·f0·√(1 + B·h²).
   Les cordes filées ont des partiels aigus trop hauts (inharmonicité) qui tirent le MPM de
   plusieurs cents vers l'aigu ; l'ajustement rend la vraie fondamentale et reste valable quand
   le micro du téléphone atténue la fondamentale.
6. **Stabilisation** : clarté ≥ 0,9, garde d'octave (repli vers la corde verrouillée / une corde
   de l'accordage, puis continuité pendant la note), médian sur 5 mesures, filtre 1€, maintien
   1,2 s de la dernière valeur.

### Précision mesurée hors appareil (tests et diagnostics JVM)

| Cas | Résultat |
|---|---|
| Sinusoïdes 51,91 → 329,63 Hz + bruit blanc à −20 dB | erreur ≤ 0,2 cent |
| G♯1 / A1 / B1 + bruit à −10 dB | bonne octave, ≤ 2 cents |
| Cordes raides (B jusqu'à 6·10⁻⁴, fondamentale −20 dB) | MPM seul : jusqu'à +21 cents ; après raffinement : ≤ 0,1 cent |
| Chaîne complète, 3 s de note, 5 accordages × toutes les cordes | écart max < 1 cent, dispersion < 0,5 cent |
| Ronflement secteur 50/100/150 Hz à −20 dB sous G♯1 | erreur < 0,8 cent |
| Mode poly, grattage 6 / 7 cordes désaccordé | ±3,5 cents (cordes à l'octave d'une autre : ±6 cents, marquées « ≈ ») |
| Coût d'une analyse mono / poly (JVM desktop) | ~0,4 ms / ~0,6 ms |

Première lecture : ~85 ms après l'attaque sur les cordes aiguës, ~0,2 s sur B1, ~0,4 s sur G♯1.

### Mode poly

Après un grattage, fenêtre de 16384 échantillons (0,34 s) × Hann, zéro-padding ×2, FFT réelle.
Pour chaque corde, on choisit parmi ses partiels 1 à 3 ceux qui ne sont pas recouverts par les
partiels des autres cordes (accordage en quartes/quintes), on cherche le pic à ±½ ton (fenêtre
rognée à mi-chemin des partiels voisins), puis on combine les mesures. Limites assumées :

- une corde à l'octave d'une autre (D2/D3 en Drop D, G♯1/G♯2 en Drop G♯) partage tous ses
  partiels : mesure approximative, signalée par un anneau et « ≈ » ;
- en standard, Si3 et Mi4 coïncident avec des harmoniques de Mi2/La2 : muettes, elles peuvent
  rester « vues » ;
- le poly sert au coup d'œil ; toucher une corde bascule en mono pour l'accord fin.

## Versions (septembre 2026)

| Outil / bibliothèque | Version |
|---|---|
| Gradle (wrapper, somme SHA-256 vérifiée) | 9.5.1 |
| Android Gradle Plugin | 9.3.3 (Kotlin intégré, nouveau DSL) |
| Kotlin / plugin Compose | 2.4.20 |
| Compose BOM | 2026.09.00 (Compose 1.12.1, Material 3 1.4.0) |
| activity-compose / lifecycle / core-ktx / datastore / coroutines | 1.13.0 / 2.11.0 / 1.19.1 / 1.2.1 / 1.11.0 |
| SDK | minSdk 26, targetSdk 35 (Android 15), compileSdk 37 |

Toutes les versions sont centralisées dans `gradle/libs.versions.toml`.

## Écarts par rapport au plan

- **compileSdk 37** au lieu de 35 : imposé par Compose 1.12 (dernière version stable). Le
  `targetSdk` reste à 35 comme prévu (comportement d'exécution d'Android 15).
- **Autocorrélation par FFT** (optionnelle dans le plan) : retenue pour l'efficience.
- **Raffinement par les partiels** ajouté après le MPM : sans lui, l'inharmonicité des cordes
  filées fausse la lecture de plusieurs cents (voir le tableau ci-dessus).
- **Seuil MPM k = 0,9** (plan : ≈ 0,85) et **β du filtre 1€ = 0,05** (plan : ≈ 0,007, valeur
  pensée pour d'autres unités) : réglés sur les signaux de test ; β = 0,05 réduit le retard de
  l'aiguille sans ajouter de tremblement.
- **Gate adaptatif** (bruit de fond appris) plutôt qu'un seuil fixe, pour la robustesse au bruit.
- Bonus réalisés : son de référence, accordages supplémentaires.

## Vérifications faites / restant à faire

Faites dans l'environnement de développement (sans SDK Android, Google Maven y étant inaccessible) :

- compilation et exécution des 29 tests JUnit (DSP, modèle, poly) ;
- compilation de **tout** le code de l'app (UI Compose, ViewModel, AudioEngine, DataStore,
  MainActivity) contre Compose 1.12.1 / Material 3 1.4, le framework Android réel (API 37) et
  des stubs pour activity / DataStore / lifecycle : aucune erreur, aucun avertissement.

À faire sur la machine de build / le téléphone (non exécuté ici) : synchronisation Gradle réelle
avec AGP 9.3.3, `assembleDebug`, et la recette manuelle du plan (§12) : chaque corde de chaque
preset, robustesse au bruit ambiant, cordes graves (Drop G♯, Drop A), bascule Mono/Poly,
notation FR/EN, La de référence, accordage personnalisé, thèmes.
