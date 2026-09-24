# Accordeur guitare — Spécification d'implémentation (app Android native)

> **But de ce document** : spécification autonome et exécutable. Une session (locale ou **cloud**)
> peut l'implémenter à froid, sans autre contexte. Projet **greenfield** : le dossier
> `/home/bsod/Desktop/accordeur` est vide, tout est à créer.

## 1. Objectif

Accordeur pour **Samsung S25 (SM-S931U1, Android 15)** pour guitares **6 et 7 cordes**, accordages
**standard et dropés** (+ custom). Exigences : **précision maximale (~1 cent)**, **robustesse au
bruit ambiant**, **usage efficient des ressources**, réglages simples, UI **lisible, épurée, moderne,
évolutive**.

**Précisions demandées (itération 2)** :
- **Basses fréquences** : détection fiable jusqu'à **G♯1 ≈ 51,91 Hz** (7e corde droppée) et en dessous → cf. §5.
- **Notation FR + EN** : Do Ré Mi Fa Sol La Si ↔ C D E F G A B, réglable → cf. §6.
- **Accordages fournis** : 6c Standard & Drop D ; 7c Standard, Drop A & Drop G♯ (`G#D#G#C#F#A#D#`) → cf. §6.
- **Mode polyphonique** (type TC PolyTune 3, grattage global) : **faisable** → cf. §8.

## 2. Décisions arrêtées (validées avec l'utilisateur)

| Aspect | Choix | Raison |
|---|---|---|
| Type | **App Android native** | Efficience CPU/batterie, latence, **micro brut** (sans AGC/anti-bruit système) |
| Langage | **Kotlin** | Standard Android moderne |
| UI | **Jetpack Compose + Material 3** | Déclaratif, animable, évolutif |
| DSP | **Kotlin pur (pas de NDK/C++)** | ~95 % de l'efficience max, build simple ; suffisant pour un accordeur |
| Audio | `AudioRecord`, source `UNPROCESSED`, 48 kHz, PCM float, basse latence | Signal brut = meilleur traitement |
| Build | Gradle (Kotlin DSL), Android Studio, **sans NDK** | Le plus simple à compiler/sideload |
| Package | `com.blenouvel.accordeur` · App : **Accordeur** | (modifiables) |
| SDK | `minSdk 26`, `compileSdk`/`targetSdk 35` | Android 8→15 ; S25 = API 35 |
| Basses fréq. | Fenêtre **8192**, détection jusqu'à **~35 Hz** (G♯1 = 51,91 Hz) | 7e corde droppée (Drop G♯) |
| Notation | **FR** (Do Ré Mi) **+ EN** (C D E), réglable | Demande utilisateur |
| Modes | **Mono** (précis) **+ Poly** (type PolyTune, §8) | Demande utilisateur |

> Aucun outil Android n'est installé sur la machine de dev. L'app est **livrée prête à compiler** ;
> l'utilisateur installe Android Studio, compile et *sideload* sur le S25 (cf. §11).

## 3. Architecture (couches)

```
Micro ─► AudioEngine ─► PitchDetector ─► Stabilisation ─► NoteMapper ─► TunerViewModel ─► UI Compose
       (AudioRecord)   (MPM + interp)   (médian + 1€)   (freq→note/cents) (StateFlow)      (meter)
```

- **AudioEngine** : capture temps réel → ring buffer → fenêtres glissantes.
- **PitchDetector** : pré-filtrage + détection de hauteur + interpolation parabolique (sous-échantillon).
- **Stabilisation** : gate RMS + clarté ; filtre médian anti-octave ; filtre 1€ (aiguille fluide).
- **NoteMapper** : fréquence ↔ note/octave/cents, La de référence réglable.
- **TunerViewModel** : `StateFlow<TunerUiState>` collecté par Compose (lifecycle-aware).

## 4. Structure du projet

```
accordeur/
├─ settings.gradle.kts
├─ build.gradle.kts                     (racine)
├─ gradle.properties
├─ gradle/libs.versions.toml            (version catalog)
├─ gradle/wrapper/gradle-wrapper.properties
├─ README.md                            (build + install + activation mode dev)
└─ app/
   ├─ build.gradle.kts
   ├─ proguard-rules.pro
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml         (permission RECORD_AUDIO)
      │  ├─ kotlin/com/blenouvel/accordeur/
      │  │  ├─ MainActivity.kt          (permission runtime, keepScreenOn, setContent)
      │  │  ├─ TunerViewModel.kt
      │  │  ├─ audio/
      │  │  │  ├─ AudioEngine.kt        (AudioRecord UNPROCESSED, ring buffer, coroutine)
      │  │  │  ├─ PitchDetector.kt      (MPM/NSDF + interpolation parabolique — mode mono)
      │  │  │  ├─ PolyPitchDetector.kt  (FFT haute résolution par corde cible — mode poly, §8)
      │  │  │  ├─ Fft.kt                (FFT radix-2 Kotlin pur — poly + autocorr. optionnelle)
      │  │  │  ├─ Biquad.kt             (DC block, passe-haut, passe-bas)
      │  │  │  └─ OneEuroFilter.kt      (lissage aiguille)
      │  │  ├─ model/
      │  │  │  ├─ Tuning.kt             (Note, GuitarString, Tuning, presets 6/7 cordes)
      │  │  │  └─ NoteMapper.kt         (freq↔MIDI↔note↔cents, A4 réglable, notation FR/EN)
      │  │  ├─ data/
      │  │  │  └─ SettingsStore.kt      (DataStore : tuning, A4, tolérance, thème, options)
      │  │  └─ ui/
      │  │     ├─ TunerScreen.kt        (écran principal)
      │  │     ├─ TunerMeter.kt         (jauge cents Canvas, animée)
      │  │     ├─ StringSelector.kt     (rangée de cordes : auto-highlight + verrou manuel)
      │  │     ├─ TuningSheet.kt        (sélecteur d'accordage + éditeur custom)
      │  │     ├─ SettingsScreen.kt
      │  │     └─ theme/ (Color.kt, Theme.kt, Type.kt)
      │  └─ res/ (mipmap launcher, values/strings.xml, xml/themes)
      └─ test/kotlin/com/blenouvel/accordeur/
         ├─ PitchDetectorTest.kt        (sinus + bruit → freq/note attendus)
         └─ NoteMapperTest.kt           (freq↔note↔cents, presets)
```

## 5. Pipeline DSP — le cœur (précision + robustesse au bruit)

**Constantes** : `SAMPLE_RATE = 48000`, `WINDOW = 8192` (~171 ms), `HOP = 2048` (~23 analyses/s).
Fenêtre de 8192 → ≥5 périodes même à **35 Hz** → détection fiable des cordes **très graves**, dont
**G♯1 = 51,91 Hz** (7e corde droppée, ~4,4 périodes) et A1/B1. C'est le point faible habituel des
accordeurs ; la fenêtre longue + le lissage 1€ le règlent. Plage de lags : `τ_min ≈ 35` (~1370 Hz) →
`τ_max ≈ 1370` (~35 Hz). 23 analyses/s + filtre 1€ suffisent à une aiguille fluide (pas besoin de plus).

> **Mains 50 Hz** (secteur FR) tombe près de G♯1 : on ne peut pas le notcher sans tuer la fondamentale.
> En pratique c'est bénin — micro **acoustique** (pas d'entrée jack électrique), signal guitare
> dominant, et la détection par **périodicité** (MPM) rejette naturellement un hum faible. Passe-haut
> à ~32 Hz (cf. §5.2) plutôt que 50 Hz pour préserver G♯1.

### 5.1 Capture (`AudioEngine`)
```
val source = if (AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                 MediaRecorder.AudioSource.UNPROCESSED
             else MediaRecorder.AudioSource.VOICE_RECOGNITION   // fallback moins "processé" que MIC/DEFAULT
AudioRecord.Builder()
  .setAudioSource(source)
  .setAudioFormat(AudioFormat.Builder()
      .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
      .setSampleRate(48000)
      .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
  .setBufferSizeInBytes(max(minBuffer, WINDOW*4*4))
  .build()
  .also { if (Build.VERSION.SDK_INT >= 26) it.setPreferredDevice(...) /* + PERFORMANCE_MODE_LOW_LATENCY via Builder */ }
```
Boucle de lecture sur thread dédié → **ring buffer** de floats. Toutes les `HOP` frames, copier la
dernière fenêtre `WINDOW` et lancer l'analyse (coroutine `Dispatchers.Default`). **Buffers
préalloués, zéro allocation par frame.**

### 5.2 Pré-traitement (`Biquad`)
1. **DC block** (retrait de la composante continue).
2. **Passe-bande ~32 Hz–1,3 kHz** : biquads passe-haut (fc≈32 Hz, pour laisser passer G♯1=51,91 Hz)
   + passe-bas (fc≈1300 Hz) en cascade (Direct Form II, coeffs RBJ). **Levier n°1 de robustesse au
   bruit** (coupe rumble, DC, manip, souffle hors-bande).
3. **Gate RMS** : `rms < SEUIL` → pas de détection, l'UI passe en état « écoute… ».

### 5.3 Détection — McLeod Pitch Method (MPM / NSDF)
Robuste aux erreurs d'octave (défaut classique des accordeurs). Étapes :
1. Autocorrélation `r(τ)` et fonction `m(τ)=Σ(x[j]²+x[j+τ]²)` pour τ ∈ [τ_min, τ_max].
2. **NSDF** : `n(τ) = 2·r(τ) / m(τ)` ∈ [−1, 1].
3. Détecter les maxima positifs ; retenir le **premier pic** dont la valeur ≥ `k · max` (k≈0,85).
4. **Interpolation parabolique** du sommet (3 points) → τ* sous-échantillon :
   `δ = 0.5·(a−c)/(a−2b+c)` ; `τ* = τ + δ` ; `f = SAMPLE_RATE / τ*`. → **résolution sub-cent**.
5. **Clarté** = valeur NSDF au pic ∈ [0,1] → indice de confiance.
   *(Alternative en commentaire : YIN + CMNDF + seuil absolu 0,10–0,15, même interpolation.)*
   *(Optimisation possible mais non requise : autocorrélation par FFT.)*

### 5.4 Stabilisation
- **Gating** : maj note/cents seulement si `clarté ≥ ~0,9` **et** `rms > seuil` ; sinon « écoute… »
  ou maintien avec fondu.
- **Filtre médian** (5 dernières fréquences) → rejette outliers / sauts d'octave.
- **Filtre 1€ (one-euro)** sur les cents → lissage adaptatif : réactif en mouvement, stable près de la
  justesse (aiguille sans tremblement). Params de départ : `minCutoff≈1.0`, `beta≈0.007`, `dCutoff≈1.0`.

### 5.5 Mapping note (`NoteMapper`)
```
midi   = 69 + 12*log2(f / a4ref)          // a4ref défaut 440 Hz, réglable ~430–450
nearest= round(midi)
fCible = a4ref * 2^((nearest-69)/12)
cents  = 1200 * log2(f / fCible)          // ∈ [-50, +50]
nom/oct depuis nearest (C, C#, … B ; octave = nearest/12 - 1)
```
Mode **auto** (plus proche note chromatique) ou **manuel** (corde verrouillée par l'utilisateur).
**Noms de notes** en **Français** (Do Ré Mi Fa Sol La Si) *et* **English** (C D E F G A B), dièses/bémols
inclus (Do♯/C♯…), réglable → table complète en §6. Séparer proprement *pitch class* (0–11) + octave du
libellé affiché, pour basculer FR/EN sans retoucher la détection.

**Cible de résultat** : lecture stable **±0,5–1 cent** en bruit ambiant modéré, jusqu'aux **cordes très
graves** (G♯1 51,91 Hz / A1 55 Hz / B1 61,74 Hz), sans scintillement d'octave.

## 6. Accordages (`Tuning.kt`) — presets requis + notation FR/EN (A4=440, grave → aiguë)

**Notation** — un accordage stocke la note de façon neutre (*pitch class* 0–11 + octave) ; l'affichage
choisit FR ou EN :

| EN | C | C♯/D♭ | D | D♯/E♭ | E | F | F♯/G♭ | G | G♯/A♭ | A | A♯/B♭ | B |
|----|---|-------|---|-------|---|---|-------|---|-------|---|-------|---|
| FR | Do | Do♯/Ré♭ | Ré | Ré♯/Mi♭ | Mi | Fa | Fa♯/Sol♭ | Sol | Sol♯/La♭ | La | La♯/Si♭ | Si |

**Presets à fournir** (grave → aiguë) :

*6 cordes*
| Nom | EN | FR | Fréquences (Hz) |
|---|---|---|---|
| **Standard** | E A D G B E | Mi La Ré Sol Si Mi | E2 82.41 · A2 110.00 · D3 146.83 · G3 196.00 · B3 246.94 · E4 329.63 |
| **Drop D** | D A D G B E | Ré La Ré Sol Si Mi | D2 73.42 · A2 110.00 · D3 146.83 · G3 196.00 · B3 246.94 · E4 329.63 |

*7 cordes*
| Nom | EN | FR | Fréquences (Hz) |
|---|---|---|---|
| **Standard** | B E A D G B E | Si Mi La Ré Sol Si Mi | B1 61.74 · E2 82.41 · A2 110.00 · D3 146.83 · G3 196.00 · B3 246.94 · E4 329.63 |
| **Drop A** | A E A D G B E | La Mi La Ré Sol Si Mi | A1 55.00 · E2 82.41 · A2 110.00 · D3 146.83 · G3 196.00 · B3 246.94 · E4 329.63 |
| **Drop G♯ (Eb)** | G♯ D♯ G♯ C♯ F♯ A♯ D♯ | Sol♯ Ré♯ Sol♯ Do♯ Fa♯ La♯ Ré♯ | **G♯1 51.91** · D♯2 77.78 · G♯2 103.83 · C♯3 138.59 · F♯3 185.00 · A♯3 233.08 · D♯4 311.13 |

> **Drop G♯** = ton accordage `G#D#G#C#F#A#D#` : cordes 6→1 en **Mi♭ standard** (½ ton sous le standard),
> 7e corde droppée à **G♯1 ≈ 51,91 Hz** → c'est le cas « basse fréquence » ciblé (cf. §5, fenêtre 8192).
> Fréquences **recalculées depuis `a4ref`** (rien codé en dur si A4≠440). Un accordage =
> `List<GuitarString(pitchClass, octave, label?)>` ; la fréquence est dérivée.
> **Éditeur custom** (4–8 cordes, note par corde). Extensible : autres presets (Eb, Drop C, Drop B,
> open tunings) faciles à ajouter via le même modèle.

## 7. UI / UX (Compose + Material 3)

**Direction** : thème **sombre par défaut** (scène/faible lumière, économe OLED), option clair/système
+ couleur dynamique (Material You, API 31+ garde). Palette restreinte : fond quasi noir, **une** couleur
d'accent, sémantique **vert = juste / ambre-rouge = à corriger**. Typo large, très lisible ; espacements
généreux ; animations discrètes.

**Écran principal (`TunerScreen`)**
- Barre haute : nom de l'accordage (tap → `TuningSheet`), La de référence, icône réglages.
- **Note centrale** en grand, dans la notation choisie (ex. `Mi2` ou `E2`) + fréquence mesurée.
- **`TunerMeter`** (Canvas animé) : échelle −50…+50 cents, **zone centrale verte** dans la tolérance
  (défaut **±5 cents**, réglable ; ±3 = « parfait »), valeur numérique, aiguille lissée (1€).
  **Haptique** légère quand une corde devient juste.
- **`StringSelector`** : les 6/7 cordes ; la corde jouée **s'auto-surligne** ; tap = verrou manuel ;
  chaque corde passe **au vert** une fois juste (feedback « toutes au vert »).
- État « écoute… » + niveau micro discret quand signal faible.
- **Mode Poly** (cf. §8) : après un grattage global, affichage **simultané** des 6/7 cordes (trop bas /
  juste / trop haut) ; bascule **Mono ⇄ Poly** accessible depuis l'écran principal.

**`TuningSheet`** (bottom sheet) : 6/7 cordes, presets, ou **Custom** (éditeur).
**`SettingsScreen`** : La de référence, tolérance ±cents, **notation (Français / English / les deux)**,
mode de détection auto/manuel, **mode d'accordage Mono / Poly**, thème, haptique on/off, garder l'écran
allumé. *(Bonus : ton de référence jouable pour accord à l'oreille.)*

**Cycle de vie / permissions** : demande runtime `RECORD_AUDIO` ; start/stop audio sur
`onResume`/`onPause` (libère le micro en arrière-plan) ; `keepScreenOn` pendant l'accordage.

## 8. Mode polyphonique (type TC PolyTune 3) — faisabilité & approche

**Oui, c'est faisable.** Rendu réaliste parce qu'on **connaît les fréquences cibles** de l'accordage :
ce n'est pas de la transcription polyphonique générale (très dure), mais une **estimation multi-hauteurs
*contrainte*** — mesurer l'écart de chaque corde *connue*.

**Approche** (`PolyPitchDetector` + `Fft.kt`) :
1. Détecter l'**onset** du grattage (saut d'énergie), puis accumuler une **fenêtre longue**
   (~16384–32768 échantillons, 0,34–0,68 s), fenêtrage **Hann**, **FFT haute résolution** (radix-2,
   zéro-padding ×2).
2. Pour **chaque corde cible** `f_i` : chercher le pic spectral dans une bande étroite (±½ ton) autour
   de `f_i` ; **raffiner via une harmonique** (mesurer près de `k·f_i` puis diviser par `k` → bien
   meilleure résolution en cents pour les graves) ; interpolation parabolique du pic (ou fréquence
   instantanée par différence de phase entre deux trames) → **écart en cents**.
3. **Afficher les 6/7 cordes simultanément** : chaque corde = trop bas / juste / trop haut, + celles
   non détectées.

**Limites honnêtes (à assumer dans l'UI)** :
- **Recouvrement harmonique** entre cordes (accordées en 4tes/5tes) → précision par corde plus grossière
  que le mode mono (±quelques cents). C'est le principe même du PolyTune : le mode poly sert au
  **coup d'œil** (« quelles cordes sont fausses »), puis on **bascule en Mono** pour l'accord fin.
- Dépend d'un **grattage propre** de toutes les cordes ; les cordes graves très proches sont les plus
  délicates à séparer.

**Reco** : livrer d'abord le **mode Mono** (robuste, ~1 cent) ; ajouter le **mode Poly** ensuite, comme
mode distinct (toggle Mono ⇄ Poly). Réutilise `NoteMapper` et le modèle d'accordage ; nécessite
`Fft.kt` (FFT Kotlin pur, O(N·log N) → léger même en 32768 sur le S25).

## 9. Efficience (usage ressources)

- Un seul `AudioRecord`, **buffers préalloués**, **0 allocation/frame** dans le DSP.
- Détection **court-circuitée sur silence** (gate RMS).
- MàJ UI cadencées au hop (~23 Hz) ; aiguille via animation Compose (spring).
- Libération des ressources en pause ; collecte Compose lifecycle-aware.
- Thème sombre OLED.

## 10. Dépendances & config build

**`libs.versions.toml`** (versions indicatives → prendre la **dernière stable** au moment du build) :
- Android Gradle Plugin ~8.7+, **Kotlin 2.x** avec le plugin `org.jetbrains.kotlin.plugin.compose`
  (compilateur Compose intégré à Kotlin 2.x).
- `androidx.core:core-ktx` ~1.15
- `androidx.lifecycle:{lifecycle-runtime-ktx, lifecycle-runtime-compose, lifecycle-viewmodel-compose}` ~2.8+
- `androidx.activity:activity-compose` ~1.9+
- **Compose BOM** ~2024.12+ → `material3`, `ui`, `ui-graphics`, `ui-tooling-preview`
- `androidx.datastore:datastore-preferences` ~1.1
- `org.jetbrains.kotlinx:kotlinx-coroutines-android` ~1.9
- Aucune lib audio tierce (AudioRecord = framework).

**`AndroidManifest.xml`** : `<uses-permission android:name="android.permission.RECORD_AUDIO"/>`.

## 11. Build & installation sur le S25 (README.md)

1. Installer **Android Studio** (embarque le SDK). Ouvrir le dossier `accordeur` → *Gradle sync*.
2. Sur le S25 : Réglages → À propos → *Numéro de build* tapé 7× → **Options développeur** →
   activer **Débogage USB**.
3. Brancher en USB → **Run** dans Android Studio ; **ou** `./gradlew assembleDebug` puis installer
   `app/build/outputs/apk/debug/app-debug.apk` (`adb install -r app-debug.apk`, ou copie sur le tel +
   autoriser « installer applis inconnues »).
4. Autoriser le **micro** au 1er lancement.

## 12. Tests & vérification

**Tests unitaires JVM** (possibles grâce au DSP en Kotlin pur, **sans appareil**) — `./gradlew test` :
- `PitchDetectorTest` : sinus synthétiques aux fréquences guitare — **incluant les graves 51.91 (G♯1) ·
  55 (A1) · 61.74 (B1)** · 82.41 · 110 · 146.83 · 196 · 246.94 · 329.63 Hz — **+ bruit blanc** →
  fréquence détectée à ±quelques cents, bonne note/octave. Vérifie explicitement la **tenue en basse
  fréquence** et la robustesse au bruit.
- `NoteMapperTest` : bijections freq↔note↔cents, effet de `a4ref`, **notation FR/EN**, les 5 presets.
- *(optionnel)* `PolyPitchDetectorTest` : somme de sinus (accordage complet) légèrement désaccordés →
  écart par corde détecté dans le bon sens.

**Recette manuelle sur appareil** : chaque corde de chaque preset ; justesse (vert + haptique) ;
robustesse (bruit ambiant/TV) ; précision cordes graves (**Drop G♯ : G♯1 51,91 Hz**, Drop A) ; bascule
**Mono/Poly** ; **notation FR/EN** ; changement de La ref ; accordage custom ; thème.

## 13. Ordre d'implémentation suggéré

1. Squelette Gradle + manifest + `MainActivity` (permission, écran vide).
2. `NoteMapper` (**notation FR/EN**) + `Tuning` (**5 presets requis** + éditeur custom) (+ `NoteMapperTest`).
3. `PitchDetector` (MPM) + `Biquad` + `OneEuroFilter` (+ `PitchDetectorTest`) — **valider la précision hors appareil**.
4. `AudioEngine` (capture + ring buffer) → `TunerViewModel` (StateFlow).
5. UI : `TunerMeter`, `TunerScreen`, `StringSelector`, thème.
6. `TuningSheet` + éditeur custom + `SettingsScreen` + `SettingsStore` (DataStore).
7. Finitions : haptique, keepScreenOn, états écoute, (bonus) ton de référence.
8. **Mode Poly** (`Fft.kt` + `PolyPitchDetector` + affichage multi-cordes, toggle Mono/Poly) — après
   validation du mode Mono (cf. §8).

## 14. Bonus optionnels (hors chemin critique)

Ton de référence jouable (sinus de la corde cible), capo/transposition, open tunings, mode « strobe ».
