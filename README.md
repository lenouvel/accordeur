# Accordeur — guitare 6 et 7 cordes (Android natif)

Accordeur de précision pour Samsung S25 (et tout Android 8+), écrit en Kotlin + Jetpack Compose,
sans NDK, avec une vue **Gammes** qui affiche gammes et modes sur un manche vertical.
Spécification de l'accordeur : [`PLAN.md`](PLAN.md).

- **Mono** : une corde à la fois, précision ~0,1 cent sur signal propre, lecture stable à ±0,5 cent.
- **Poly** (type PolyTune) : on gratte toutes les cordes, chacune s'affiche trop basse / juste / trop
  haute ; **l'affichage est maintenu** et une corde rejouée seule ne met à jour qu'elle.
- Cordes **très graves** : détection fiable jusqu'à ~35 Hz (G♯1 = 51,91 Hz en Drop G♯), même
  quand le micro du téléphone coupe la fondamentale (détection par la série de partiels).
- **Source micro** réglable (UNPROCESSED, musique, reconnaissance vocale…) avec test en direct.
- **Banque de sons de test** : chaque note jouée est gardée sans perte avec ce que l'accordeur a
  affiché, exportable vers Proton Drive, et rejouable sur ordinateur (non-régression).
- Notation **française** (Do Ré Mi), **anglaise** (C D E) ou **les deux**.
- Accordages fournis : 6 cordes Standard, Drop D ; 7 cordes Standard, Drop A, Drop G♯
  (`G#D#G#C#F#A#D#`) ; plus Mi♭ / Ré / Do / Si standard, Drop C♯, Drop C, Drop B, Double Drop D,
  DADGAD, Open G, D, E, A, C ; 7 cordes Mi♭ standard, La standard, Drop G ; et un **éditeur
  d'accordages personnalisés** (4 à 8 cordes).
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
(sinusoïdes, cordes « raides » inharmoniques captées par un micro de téléphone, bruits blanc, rose
et grondement, grattages complets puis cordes rejouées seules) — 49 tests.

`BankReplayTest` rejoue en plus la **banque de sons enregistrée sur le téléphone** (voir plus bas) :
décompresser l'archive exportée à la racine du projet sous le nom `testbank/` (ou indiquer le
dossier par la variable d'environnement `ACCORDEUR_BANQUE`), puis :

```sh
./gradlew test --tests '*BankReplayTest*'                          # rapport son par son
ACCORDEUR_BANQUE_STRICT=1 ./gradlew test --tests '*BankReplayTest*' # échoue en cas de recul
```

## Utilisation

| Geste | Effet |
|---|---|
| Toucher le nom de l'accordage (en haut) | Choisir / créer / modifier un accordage |
| **Mono / Poly** | Accord fin corde par corde / coup d'œil sur toutes les cordes |
| Poly : gratter toutes les cordes | Met à jour toute la rangée ; les valeurs restent affichées après l'extinction du son |
| Poly : jouer une corde seule | Ne met à jour qu'elle (vive) ; les autres gardent leur valeur (atténuées) |
| Toucher une corde | Mode auto : la verrouiller (re-toucher pour libérer). Mode manuel : la choisir. Mode poly : passer en mono sur cette corde |
| Appui long sur une corde | Son de référence (2,5 s, l'analyse est suspendue pendant la lecture) |
| Puce **Auto / Manuel** | Auto : note la plus proche. Manuel : écart par rapport à la corde choisie |

La jauge passe au vert dans la tolérance (±5 cents par défaut, « Parfait » sous ±3 cents) ; une
corde restée juste ~0,35 s passe au vert dans la rangée (avec une légère vibration).

La barre du bas bascule entre **Accordeur** et **Gammes** ; les réglages (⚙) sont accessibles
depuis les deux. Le micro n'est actif que sur l'accordeur (et ses réglages).

### Source du micro

Réglages › *Source du micro*. **Automatique** prend la source la moins traitée : `UNPROCESSED`
si le téléphone l'annonce, sinon `VOICE_PERFORMANCE` (chemin « musique en direct », Android 10+),
puis `VOICE_RECOGNITION`, puis `MIC`. Les autres choix forcent une source ; la ligne « En service »
indique celle réellement ouverte et le **test en direct** (niveau + note entendue) permet de
comparer : jouer la grosse corde avec chaque source et garder celle qui la reconnaît le mieux.
Réduction de bruit, gain automatique et annulation d'écho sont désactivés quand le téléphone les
expose aux applications.

### Banque de sons de test

Réglages › *Banque de sons de test* (activée par défaut, désactivable). Pendant l'écoute, chaque
note jouée est gardée **sur le téléphone** :

- le **signal brut** du micro, tel que livré, avant tout filtrage : WAV mono **sans perte**
  (float 32 bits, ou 16 bits si le téléphone ne fournit que du 16 bits) ;
- seulement quand ça joue (gate ouvert), avec 1 s avant (le rejeu apprend le bruit de fond et voit
  l'attaque entière) et 1 s après ; 2 min au plus par son ; **1 Go au plus** (les plus anciens
  partent d'abord) ;
- une **fiche texte** par son : téléphone, Android, version de l'app, source micro, réglages
  (mode, détection, accordage, La, corde verrouillée) et, trame par trame (~43 ms), ce que
  l'accordeur a affiché (niveau, son détecté, fréquence, valeur maintenue, tableau poly).

L'écriture se fait sur un fil dédié (le fil audio ne fait que copier chaque bloc dans un tampon
recyclé) ; une pastille rouge près de la barre de niveau signale un enregistrement en cours.
**Exporter…** prépare une archive zip et ouvre le menu de partage d'Android : choisir
**Proton Drive** (ou Drive, e-mail…). **Vider** efface la banque. Rien n'est jamais envoyé
automatiquement.

## Gammes sur le manche

Manche **vertical** (tête en haut, corde grave à gauche), pensé pour une main sur le téléphone :

| Réglage | Choix |
|---|---|
| **Tonalité** | 12 notes, sur un clavier d'une octave (touches noires : « Do♯ / Ré♭ ») |
| **Gamme / mode** | 41 gammes en 6 familles (liste ci-dessous), avec leurs degrés dans le sélecteur |
| **Accordage** | tous les accordages de l'accordeur (6, 7 cordes, personnalisés 4–8 cordes) — partagé avec l'accordeur |
| **Cases** | 12, 15 ou 22 (12 et 15 tiennent sans défilement sur un S25, 22 défile) |
| **Notes / Degrés** | nom des notes (notation FR/EN des réglages) ou intervalles (1, ♭3, ♯4…) |
| **Gaucher** | manche en miroir (corde grave à droite) |

- La **fondamentale** est dans la couleur d'accent ; les notes à vide sont cerclées en tête du manche.
- La rangée des notes de la gamme sert de légende : **toucher un degré le met en évidence** (ambre)
  sur tout le manche — par exemple 3 et 5 pour voir l'arpège.
- **Toucher une case** fait entendre la note (dans ou hors de la gamme).
- La formule (T T ½T T T T ½T…) est affichée à côté des options.
- Orthographe musicale correcte : chaque degré a sa lettre (Si♭ en Fa majeur, Mi♯ en Fa♯ majeur) ;
  pour une tonalité sur touche noire, l'enharmonie la plus simple est choisie (Ré♭ majeur,
  Do♯ mineur).
- Tous ces choix sont mémorisés.

Gammes disponibles :

- **Gamme majeure et ses modes** : majeure (ionien), dorien, phrygien, lydien, mixolydien,
  mineure naturelle (éolien), locrien.
- **Pentatoniques et blues** : pentatonique majeure, pentatonique mineure, blues (mineure),
  blues majeure, pentatonique suspendue (égyptienne), hirajoshi, in-sen.
- **Mineure harmonique et ses modes** : mineure harmonique, locrien ♮6, ionien augmenté,
  dorien ♯4 (roumain), phrygien dominant (espagnole), lydien ♯2, superlocrien ♭♭7.
- **Mineure mélodique et ses modes** : mineure mélodique, dorien ♭2, lydien augmenté,
  lydien dominant, mixolydien ♭6, locrien ♮2 (semi-diminué), altérée.
- **Symétriques** : par tons, diminuée ton/demi-ton, diminuée demi-ton/ton, chromatique.
- **Autres** : majeure harmonique, double harmonique (byzantine), mineure hongroise, napolitaines
  mineure et majeure, persane, énigmatique, bebop dominante, bebop majeure.

## Architecture

```
Micro ─► AudioEngine ─► TunerProcessor ───────────────────────────────────► TunerViewModel ─► UI Compose
       (AudioRecord,    pré-filtrage ─► PitchDetector (MPM + série de        (StateFlow)
        thread audio)   (DC, 32 Hz–1,3 kHz)  partiels) ─► PitchStabilizer
            │                          PolyTracker (mode poly, tableau maintenu)
            └─► SoundRecorder (banque de sons, fil d'écriture dédié)
```

```
app/src/main/kotlin/com/blenouvel/accordeur/
├─ MainActivity.kt          navigation (Accordeur / Gammes / Réglages), permission micro, start/stop audio, écran allumé
├─ TunerViewModel.kt        état UI (StateFlow), verrou de corde, cordes « au vert », son de référence, banque de sons
├─ ScalesViewModel.kt       vue Gammes : réglages mémorisés, degrés mis en évidence, notes jouées
├─ audio/
│  ├─ AudioEngine.kt        AudioRecord (source choisie ou auto), 48 kHz float (replis 44,1 kHz / 16 bits), effets coupés
│  ├─ MicSource.kt          sources micro (auto, UNPROCESSED, VOICE_PERFORMANCE, VOICE_RECOGNITION, CAMCORDER, MIC)
│  ├─ TunerProcessor.kt     chaîne complète indépendante d'Android : filtres, tampon circulaire, attaques, analyses
│  ├─ PitchDetector.kt      MPM/NSDF par FFT + série de partiels d'une corde raide (candidats, ajustement f0/B)
│  ├─ PitchStabilizer.kt    gate adaptatif, confirmation, garde d'octave, médian 5, filtre 1€, maintien
│  ├─ PolyPitchDetector.kt  FFT haute résolution par corde cible (mesure et énergie de chaque corde)
│  ├─ PolyTracker.kt        mode poly : grattage / corde seule, tableau maintenu, suivi pendant que ça sonne
│  ├─ SoundRecorder.kt      banque de sons : pré-roll, WAV sans perte, fiche texte, plafond
│  ├─ Fft.kt                FFT radix-2 complexe + FFT réelle (Kotlin pur)
│  ├─ Biquad.kt             DC block, passe-haut 32 Hz, passe-bas 1,3 kHz (ordre 4)
│  ├─ OneEuroFilter.kt      lissage adaptatif de l'aiguille
│  └─ ReferenceTone.kt      son d'une note (synthèse additive en arrière-plan, AudioTrack)
├─ model/                   Note, GuitarString, Tuning, presets ; NoteMapper (fréq ↔ MIDI ↔ note ↔ cents, FR/EN)
│                           Scales : catalogue des gammes, degrés, formule, orthographe, positions sur le manche
├─ data/                    SettingsStore (DataStore), SoundBank (bilan, archive zip, suppression)
└─ ui/                      TunerScreen, TunerMeter (jauge Canvas), StringSelector, PolyMeter, TuningSheet,
                            ScalesScreen, FretboardView (manche Canvas), ScalePickers (clavier, gammes),
                            SettingsScreen, Share (partage de l'archive), AppIcons, thème
```

### Traitement du signal (mode mono)

1. **Capture** : 48 kHz mono float, source réglable (voir *Source du micro*). Blocs de 2048
   échantillons (hop ≈ 43 ms, ~23 analyses/s), tampons préalloués.
2. **Pré-filtrage continu** : suppression du continu, passe-haut 32 Hz (préserve G♯1),
   passe-bas 1,3 kHz d'ordre 4 (Butterworth, biquads RBJ en double).
3. **Gate** : niveau > max(−100 dBFS, bruit ambiant + 6 dB), avec hystérésis ; le bruit de fond
   est pris sur la première trame puis suivi en continu. Sans traitement (UNPROCESSED),
   94 dB SPL ≈ −36 dBFS : une guitare jouée doucement arrive vers −90 dBFS. Le bruit, lui, est
   rejeté par l'analyse (pas de série de partiels).
4. **MPM** (McLeod) sur 8192 échantillons (171 ms, ≥ 4 périodes à 51,9 Hz), autocorrélation
   **par FFT** : première estimation de la période. Sur une corde filée captée par un téléphone
   (fondamentale coupée, partiels aigus étirés par la raideur), le MPM suit l'écart entre partiels
   et lit **20 à 50 cents trop haut** avec une clarté de 0,5 à 0,85 : c'est pour cela que la
   grosse corde n'était jamais affichée (seuil de clarté 0,9). Il ne sert plus que de candidat.
5. **Série de partiels** : pics du spectre de Hann (déduit du spectre zéro-paddé, estimateur exact
   à deux bins) au-dessus du bruit local ; candidats f0 = MPM et ses sous-multiples, et chaque pic
   fort divisé par h = 1…12. Pour chaque candidat, la série de partiels d'une **corde raide**
   f_h = h·f0·√(1 + B·h²) est ancrée sur les partiels forts puis étendue vers l'aigu (ajustement
   de f0 et B par moindres carrés). On retient la série qui explique le plus d'énergie du spectre ;
   un candidat plus grave doit en expliquer nettement plus, avec une série assez complète (pas de
   fausse sous-octave). La fréquence affichée est celle du **premier partiel** ajusté, même
   inaudible.
6. **Stabilisation** : deux mesures concordantes au début d'une note (les trames d'attaque sont
   peu sûres), mesure isolée aberrante ignorée, garde d'octave (repli vers la corde verrouillée /
   une corde de l'accordage, puis continuité pendant la note), médian sur 5 mesures, filtre 1€,
   maintien 1,2 s de la dernière valeur.

### Précision mesurée hors appareil (tests et diagnostics JVM)

| Cas | Résultat |
|---|---|
| Sinusoïdes 51,91 → 329,63 Hz + bruit blanc à −20 dB | erreur ≤ 0,5 cent |
| Cordes filées (B = 1,5·10⁻⁴ à 8·10⁻⁴) via un micro « voix » (coupe-bas 120–200 Hz), guitare acoustique ou électrique non branchée | avant : 24 % (électrique) / 74 % (acoustique) des trames lues ; **après : 100 %**, bonne octave, écart moyen 0,01 cent |
| Vrais échantillons de guitare (acier, nylon, électrique ; 10 notes de G♯1 à E4 ; 4 réponses de micro ; −50 à −75 dBFS) | avant : 91,2 % des trames lues ; **après : 96,9 %**, aucune note fausse |
| Chaîne complète, 3 s de note, 5 accordages × toutes les cordes | écart max < 1 cent, dispersion < 0,5 cent |
| Bruits seuls (blanc, rose, grondement, clics, rafales, secteur 50 Hz) | aucune note affichée |
| Mode poly, grattage 6 / 7 cordes désaccordé | ±3,5 cents (cordes à l'octave d'une autre : ±6 cents, marquées « ≈ ») |
| Mode poly, corde rejouée seule (vrais échantillons, 72 scénarios) | jamais une autre corde mise à jour ; corde rejouée reconnue dans 65 cas (les autres : rejouée alors qu'elle sonnait encore au même niveau, elle reste suivie en direct) |
| Coût d'une analyse mono / poly (JVM desktop) | ~0,5 ms / ~1 ms (budget : 43 ms par trame) |

Première lecture : ~0,2 s après l'attaque (deux mesures concordantes), ~0,3 s sur G♯1.

### Mode poly

Fenêtre de 16384 échantillons (0,34 s) × Hann, zéro-padding ×2, FFT réelle, toutes les ~85 ms
tant que ça sonne. Pour chaque corde, on choisit parmi ses partiels 1 à 3 ceux qui ne sont pas
recouverts par les partiels des autres cordes (accordage en quartes/quintes), on cherche le pic à
±½ ton (fenêtre rognée à mi-chemin des partiels voisins), puis on combine les mesures.

Tableau **maintenu** (`PolyTracker`) :

- **Attaques** : saut de niveau, clic du médiator (aigus > 2 kHz, audible même quand un accord
  sonne) ou énergie d'une corde qui dépasse de 9 dB son maximum des 1–2 s précédentes.
- **Classement**, 0,3 s après : une corde est « jouée » si elle est détectée avec au moins deux
  partiels, si son énergie dépasse de 3 dB son maximum d'avant l'attaque (un battement ne dépasse
  pas son propre maximum) et si elle n'est pas 20 dB sous la plus forte. Au moins 60 % des
  cordes : **grattage**, toute la rangée est mise à jour. Sinon **corde seule** : le détecteur mono
  nomme la note jouée (une résonance de caisse ou les partiels communs d'une autre corde ne le
  trompent pas) et la mesure à ~1 cent ; les autres cordes gardent leur valeur.
- Tant qu'elles sonnent, les cordes de la dernière attaque restent suivies (on peut tourner la
  mécanique) ; rien ne s'efface ensuite. Valeurs de la dernière attaque vives, plus anciennes
  atténuées.

Limites assumées :

- une corde à l'octave d'une autre (D2/D3 en Drop D, G♯1/G♯2 en Drop G♯) partage tous ses
  partiels : mesure approximative au grattage, signalée par un anneau et « ≈ » (jouée seule, elle
  est mesurée par le détecteur mono, donc précisément) ;
- une corde rejouée pendant qu'elle sonne encore au même niveau n'est pas une nouvelle attaque :
  elle reste suivie en direct depuis le grattage ;
- toucher une corde bascule en mono sur cette corde pour l'accord fin.

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
- **Série de partiels** plutôt que le seuil de clarté MPM pour valider une note : indispensable
  pour les cordes graves captées par un téléphone (voir *Traitement du signal*).
- Bonus réalisés : son de référence, accordages supplémentaires, vue Gammes, source micro
  réglable, banque de sons de test.

### Pourquoi une FFT maison ?

Le plan demandait un traitement du signal **en Kotlin pur, sans NDK**, et le SDK Android n'a pas
de FFT générale (le `Visualizer` d'`android.media.audiofx` ne voit que la sortie audio, en 8 bits
et 1024 points). La FFT de `Fft.kt` fait ~130 lignes, n'alloue rien et est vérifiée contre une
DFT directe dans les tests. Mesure sur la JVM (un seul cœur), tailles utilisées par l'accordeur :

| FFT réelle | Maison | JTransforms 3.1 (référence Java) |
|---|---|---|
| 16384 points (mono) | 0,21 ms | 0,09 ms |
| 32768 points (poly) | 0,50 ms | 0,17 ms |

JTransforms est 2 à 3 fois plus rapide, mais l'analyse mono complète prend ~0,5 ms pour un budget
de 43 ms par trame : le gain serait d'environ 0,1 ms par trame, imperceptible (et sans rapport avec
la sensibilité, qui dépendait de l'algorithme de détection). Contreparties : trois dépendances
(JTransforms, JLargeArrays qui s'appuie sur `sun.misc.Unsafe`, commons-math3), des fils de calcul
à désactiver. Les FFT natives optimisées NEON (PFFFT, KissFFT, FFTW sous GPL) demandent le NDK.
Le passage à JTransforms reste possible en quelques lignes si on le souhaite.

## Vérifications faites / restant à faire

Faites dans l'environnement de développement (sans SDK Android, Google Maven y étant inaccessible) :

- compilation et exécution des 49 tests JUnit (DSP, modèle, poly, banque de sons, gammes) ;
- banc de mesure hors appareil : vrais échantillons de guitare (soundfonts FluidR3 et MusyngKite)
  passés dans des modèles de micro de téléphone, cordes filées synthétiques, bruits, scénarios
  poly (grattage puis corde rejouée) — chiffres ci-dessus ;
- compilation de **tout** le code de l'app (UI Compose, ViewModel, AudioEngine, DataStore,
  MainActivity) contre Compose 1.12.1 / Material 3 1.4, le framework Android réel (API 37) et
  des stubs pour activity / DataStore / lifecycle / core : aucune erreur, aucun avertissement ;
- rendu des écrans Accordeur, Gammes et Réglages (vrai code, Compose Desktop + Skia, à la taille
  d'un S25) pour contrôle visuel de la mise en page.

À faire sur le téléphone (non exécuté ici) : la recette manuelle du plan (§12) — chaque corde de
chaque preset, robustesse au bruit ambiant, cordes graves (Drop G♯, Drop A), bascule Mono/Poly,
notation FR/EN, La de référence, accordage personnalisé, thèmes ; vue Gammes ; **comparer les
sources micro** avec le test en direct (grosse corde) ; mode poly (grattage, corde rejouée seule,
affichage maintenu) ; banque de sons (pastille rouge, export vers Proton Drive, rejeu sur
ordinateur avec `BankReplayTest`).
