# Accordeur — guitare 6 et 7 cordes (Android natif)

Accordeur de précision pour Samsung S25 (et tout Android 8+), écrit en Kotlin + Jetpack Compose,
sans NDK, avec une vue **Gammes** qui affiche gammes et modes sur un manche vertical, et une vue
**Spectre** qui montre le spectre capté, les notes entendues et le nom de l'accord.
Spécification de l'accordeur : [`PLAN.md`](PLAN.md).

- **Mono** : une corde à la fois, précision ~0,1 cent sur signal propre, lecture stable à ±0,5 cent.
- **Poly** (type PolyTune) : on gratte toutes les cordes, chacune s'affiche trop basse / juste / trop
  haute ; **l'affichage est maintenu** et une corde rejouée seule ne met à jour qu'elle.
- Cordes **très graves** : détection fiable jusqu'à ~35 Hz (G♯1 = 51,91 Hz en Drop G♯), même
  quand le micro du téléphone coupe la fondamentale (détection par la série de partiels).
- **Source micro** réglable (UNPROCESSED, musique, reconnaissance vocale…) avec test en direct.
- **Spectre** : spectre du micro en direct (20 Hz–20 kHz), pics annotés de leur note pour une
  corde seule, notes d'un accord, nom de l'accord (notation anglaise, ex. `A7sus4`) et décomposition
  par degrés (La (Fondamentale), Ré (Quarte), Mi (Quinte), Sol (Septième mineure)).
- **Banque de sons de test** : un bouton ● sur l'accordeur enregistre une prise sans perte, avec
  ce que l'accordeur a affiché, exportable vers Proton Drive, et rejouable sur ordinateur
  (non-régression). Rien n'est enregistré sans appui.
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
et grondement, grattages complets puis cordes rejouées seules, accords) — 62 tests, dont un
ignoré : une limite connue de la page Spectre (voir [Reste à faire](#reste-à-faire)).

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
| **●** (en haut, à côté de ⚙) | Lance une prise de test (■ rouge et durée pendant la prise) ; second appui : l'arrête |

La jauge passe au vert dans la tolérance (±5 cents par défaut, « Parfait » sous ±3 cents) ; une
corde restée juste ~0,35 s passe au vert dans la rangée (avec une légère vibration).

La barre du bas, compacte (48 dp au lieu des 80 dp de Material, icône et libellé sur une ligne),
bascule entre **Accordeur**, **Gammes** et **Spectre** ; les réglages (⚙) sont accessibles depuis
chaque page. Le micro est actif sur l'accordeur (et ses réglages) et sur le spectre, pas sur Gammes.

### Source du micro

Réglages › *Source du micro*. **Automatique** prend la source la moins traitée : `UNPROCESSED`
si le téléphone l'annonce, sinon `VOICE_PERFORMANCE` (chemin « musique en direct », Android 10+),
puis `VOICE_RECOGNITION`, puis `MIC`. Les autres choix forcent une source ; la ligne « En service »
indique celle réellement ouverte et le **test en direct** (niveau + note entendue) permet de
comparer : jouer la grosse corde avec chaque source et garder celle qui la reconnaît le mieux.
Réduction de bruit, gain automatique et annulation d'écho sont désactivés quand le téléphone les
expose aux applications.

### Banque de sons de test

Rien n'est enregistré sans appui sur le bouton **●** de l'accordeur. Une **prise** va de ~1 s avant
l'appui (pré-enregistrement glissant, en mémoire) jusqu'au second appui ; elle s'arrête aussi en
quittant l'accordeur (Gammes, Spectre, réglages, arrière-plan) et au bout de 5 min. Elle est gardée
**sur le téléphone** :

- le **signal brut** du micro, tel que livré, avant tout filtrage, **silences compris** : WAV mono
  **sans perte** (float 32 bits, ou 16 bits si le téléphone ne fournit que du 16 bits) ; changer
  un réglage pendant la prise la coupe en deux fichiers (même numéro de prise) ; **1 Go au plus**
  (les plus anciennes partent d'abord) ;
- une **fiche texte** par fichier (format 2) : téléphone, Android, version de l'app, source micro,
  réglages (mode, détection, accordage, La, corde verrouillée), numéro de prise et, trame par trame
  (~43 ms), ce que l'accordeur a affiché (niveau, son détecté, fréquence, valeur maintenue,
  tableau poly).

L'écriture se fait sur un fil dédié (le fil audio ne fait que copier chaque bloc dans un tampon
recyclé) ; pendant la prise, le bouton devient ■ rouge et la durée s'affiche près de la barre de
niveau. Réglages › *Banque de sons de test* › *Bouton d'enregistrement sur l'accordeur* masque le
bouton (et interdit toute prise).
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

## Spectre

Le spectre du signal **brut** du micro (sans le filtrage de l'accordeur), axe logarithmique de
20 Hz à 20 kHz (repères 50 Hz … 10 kHz), niveaux en dBFS sur une plage de 66 dB qui suit le son.
La courbe est animée à la cadence de l'écran (montée en 40 ms, descente en 180 ms) : fluide même
si l'analyse ne tombe que toutes les 43 ms.

- **Une corde** : sa note en pastille au-dessus de sa fondamentale (même absente du spectre,
  coupée par le micro), et ses partiels annotés de leur note (La3, Mi4, La4, Do♯5…).
- **Plusieurs cordes** : une pastille par note entendue, sur une ligne, reliée à sa note.
- **Sous le spectre** : la note (et sa fréquence), ou l'**accord** en notation anglaise (`Am7`,
  `F♯m7♭5`, `C/E`, `G♯11`…) et sa **décomposition** : chaque note, orthographiée selon son degré
  (Mi♭ et non Ré♯ dans Do mineur), avec son nom de degré (Fondamentale, Tierce mineure, Quarte,
  Quinte, Septième mineure…). Deux notes qui ne forment pas d'accord : les notes et l'intervalle.
  L'affichage suit l'harmonie la plus fréquente des ~0,35 dernières secondes et reste affiché,
  atténué, après l'extinction du son.

Méthode (`SpectrumAnalyzer`, ~2,4 ms par analyse sur la JVM) : fenêtre de Hann de 16384
échantillons (0,34 s), pics au-dessus du bruit local ; chaque pic fort propose une fondamentale
(partiel 1 à 6) sur laquelle on ajuste la série d'une corde raide, comme dans l'accordeur ; les
notes sont choisies une à une selon la part de l'énergie des pics qu'elles expliquent **en
propre** (les partiels d'une corde ne deviennent pas des notes), une série trouée (sous-harmonique
fantôme) est écartée, une basse complète est ajoutée si besoin ; une note doit être entendue sur 2
des 3 dernières trames. Le nom vient de `ChordNamer` : chaque note présente est essayée comme
fondamentale, on garde le nom le plus simple (table de ~40 formules), la basse servant de
fondamentale à égalité et un renversement coûtant cher.

Mesuré sur le banc (échantillons réels de 3 banques de sons × 3 guitares, micro de téléphone
simulé) : **92 %** des trames d'une corde seule montrent la bonne note, seule ; **82 %** des
trames affichent le bon nom pour 33 accords courants (75 % trame par trame). Limites : voir
[Reste à faire](#reste-à-faire).

## Architecture

```
Micro ─► AudioEngine ─► TunerProcessor ───────────────────────────────────► TunerViewModel ─► UI Compose
       (AudioRecord,    pré-filtrage ─► PitchDetector (MPM + série de        (StateFlow)
        thread audio)   (DC, 32 Hz–1,3 kHz)  partiels) ─► PitchStabilizer
            │                          PolyTracker (mode poly, tableau maintenu)
            │                   signal brut ─► SpectrumAnalyzer (page Spectre : spectre, notes)
            └─► SoundRecorder (prises au bouton ●, fil d'écriture dédié)
```

```
app/src/main/kotlin/com/blenouvel/accordeur/
├─ MainActivity.kt          navigation (Accordeur / Gammes / Spectre / Réglages), permission micro, start/stop audio, écran allumé
├─ TunerViewModel.kt        état UI (StateFlow), page, verrou de corde, cordes « au vert », son de référence, prises, spectre (vote)
├─ ScalesViewModel.kt       vue Gammes : réglages mémorisés, degrés mis en évidence, notes jouées
├─ audio/
│  ├─ AudioEngine.kt        AudioRecord (source choisie ou auto), 48 kHz float (replis 44,1 kHz / 16 bits), effets coupés
│  ├─ MicSource.kt          sources micro (auto, UNPROCESSED, VOICE_PERFORMANCE, VOICE_RECOGNITION, CAMCORDER, MIC)
│  ├─ TunerProcessor.kt     chaîne complète indépendante d'Android : filtres, tampon circulaire, attaques, analyses
│  ├─ PitchDetector.kt      MPM/NSDF par FFT + série de partiels d'une corde raide (candidats, ajustement f0/B)
│  ├─ PitchStabilizer.kt    gate adaptatif, confirmation, garde d'octave, médian 5, filtre 1€, maintien
│  ├─ PolyPitchDetector.kt  FFT haute résolution par corde cible (mesure et énergie de chaque corde)
│  ├─ PolyTracker.kt        mode poly : grattage / corde seule, tableau maintenu, suivi pendant que ça sonne
│  ├─ SpectrumAnalyzer.kt   page Spectre : spectre log 20 Hz–20 kHz, pics, notes (séries de partiels, choix glouton)
│  ├─ SoundRecorder.kt      banque de sons : prises au bouton, pré-enregistrement, WAV sans perte, fiche, plafond
│  ├─ Fft.kt                FFT radix-2 complexe + FFT réelle (Kotlin pur)
│  ├─ Biquad.kt             DC block, passe-haut 32 Hz, passe-bas 1,3 kHz (ordre 4)
│  ├─ OneEuroFilter.kt      lissage adaptatif de l'aiguille
│  └─ ReferenceTone.kt      son d'une note (synthèse additive en arrière-plan, AudioTrack)
├─ model/                   Note, GuitarString, Tuning, presets ; NoteMapper (fréq ↔ MIDI ↔ note ↔ cents, FR/EN)
│                           Scales : catalogue des gammes, degrés, formule, orthographe, positions sur le manche
│                           Chords : nom des accords (ChordNamer), degrés, orthographe, Harmony (note/accord)
├─ data/                    SettingsStore (DataStore), SoundBank (bilan, archive zip, suppression)
└─ ui/                      TunerScreen, TunerMeter (jauge Canvas), StringSelector, PolyMeter, TuningSheet,
                            ScalesScreen, FretboardView (manche Canvas), ScalePickers (clavier, gammes),
                            SpectrumScreen (spectre Canvas animé, accord), NavigationBar (barre compacte),
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
   f_h = h·f0·√(1 + B·h²) est ancrée sur les partiels forts ; la raideur B de départ est choisie
   sur une grille (1e-4…2e-3, celle qui aligne le plus de partiels : un partiel grave décalé, par
   un ronflement voisin par exemple, ne la fausse plus), puis la série est étendue vers l'aigu
   (ajustement de f0 et B par moindres carrés, partiels aberrants écartés). On retient la série qui
   explique le plus d'énergie du spectre ;
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

- compilation et exécution des 62 tests JUnit (DSP, modèle, poly, banque de sons, gammes,
  spectre et accords ; 1 ignoré, limite connue) ;
- banc de mesure hors appareil (archivé dans [`banc/`](banc/)) : vrais échantillons de guitare
  (soundfonts FluidR3, MusyngKite, FatBoy) passés dans des modèles de micro de téléphone, cordes
  filées synthétiques, conditions dégradées (ronflement 50 Hz, grondement, frisage, résonances…),
  bruits, scénarios poly, 33 accords × 9 guitares — chiffres ci-dessus ;
- compilation de **tout** le code de l'app (UI Compose, ViewModel, AudioEngine, DataStore,
  MainActivity) contre Compose 1.12.1 / Material 3 1.4, le framework Android réel (API 37) et
  des stubs pour activity / DataStore / lifecycle / core : aucune erreur, aucun avertissement ;
- rendu des écrans Accordeur (bouton ●), Gammes (barre compacte, avant/après), Spectre (corde
  seule, accords à partir de vraies analyses) et Réglages (vrai code, Compose Desktop + Skia, à la
  taille d'un S25) pour contrôle visuel de la mise en page.

À faire sur le téléphone (non exécuté ici) : la recette manuelle du plan (§12) — chaque corde de
chaque preset, robustesse au bruit ambiant, cordes graves (Drop G♯, Drop A), bascule Mono/Poly,
notation FR/EN, La de référence, accordage personnalisé, thèmes ; vue Gammes ; **comparer les
sources micro** avec le test en direct (grosse corde) ; mode poly (grattage, corde rejouée seule,
affichage maintenu) ; prises au bouton ● (arrêt en changeant de page, export vers Proton Drive,
rejeu sur ordinateur avec `BankReplayTest`) ; page Spectre (fluidité, notes, accords) ; barre du
bas compacte.

## Reste à faire

Développement arrêté ici à la demande ; état au 25 septembre 2026.

### Corde grave G♯1 (Drop G♯) : diagnostic à reprendre

Essais faits (chaîne complète, accordage G♯D♯G♯C♯F♯A♯D♯, micro de téléphone simulé en coupe-bas
150–200 Hz, crête −60 et −72 dBFS, lecture mesurée de 0,2 à 7,5 s après l'attaque) :

- vrais échantillons Ab1 (3 banques × 3 guitares) : 100 % ; cordes synthétiques : raideur B de
  1e-4 à 1,2e-3, électrique débranchée, pincement au 1/10 et au 1/4, glissement de 30 ¢,
  2 polarisations, résonance de Sol♯2/Ré♯2, réverbération, frisage : ~100 % ;
- échecs trouvés : **ronflement secteur 50 Hz** (ampli, micros simple bobinage) — ses harmoniques
  150–300 Hz tombent à 6–11 Hz des partiels 3 à 6 de G♯1 et faussaient l'estimation de raideur ;
  corrigé en partie (raideur choisie sur une grille, partiels aberrants écartés : de 83–93 % à
  93–97 % de lecture en début de note), mais en fin de note la lecture tombe encore à 0–16 % : le
  gate (niveau global) reste fermé tant que le ronflement domine ; grondement de pièce : 71–85 % en
  fin de note ; partiels réels s'écartant de 0,4 % du modèle de corde raide : 6 à 15 ¢ d'erreur.

Pistes : **enregistrer des prises de la corde G♯ avec le bouton ●** et les rejouer
(`BankReplayTest`), c'est le plus sûr pour trouver la vraie cause ; empreinte spectrale du bruit
apprise dans les silences (ronflement, ventilation) retirée des pics et du gate ; gate sur
l'excédent spectral plutôt que sur le niveau global ; ne pas laisser monter le bruit de fond
estimé tant qu'une note est suivie ; comparer les sources micro du S25 avec le test en direct.

### Page Spectre : limites connues

- Accords manqués (banc) : `G♯11` (cordes à vide du Drop G♯ : La♯3 est invisible, ses partiels
  tombent à 2–4 ¢ de ceux de Sol♯1 et Ré♯2), `Em7` lu `Em` (Ré4 manqué), `Fmaj7`, `A7sus4` et
  `Cadd9` parfois lus avec une **sous-octave fantôme** à la basse (Sol2 « expliquant » Sol3 + Ré4) ;
  Mi majeur synthétique : basse Mi2 perdue une trame sur deux (`E/B`, test
  `SpectrumAnalyzerTest.rootInTheBassOfEMajor` ignoré en attendant).
- Notes seules aiguës parfois lues une octave au-dessus (La4 → La5) quand leurs partiels 3 et 4
  sont très faibles.
- Pistes : bouton ● aussi sur la page Spectre pour enregistrer de vrais accords ; estimation
  conjointe (NNLS / gabarits d'accords) ; logique de basse plus fine ; réglage sur prises réelles.
- Non essayé sur téléphone : fluidité réelle, charge CPU (≈ 2,4 ms par analyse sur la JVM).

### Banc d'essai à intégrer au build

Le banc utilisé pour ces mesures est **archivé tel quel** dans [`banc/`](banc/) (hors du build :
il ne change rien à `./gradlew test`) : `kotlin/` (bancs `LowStringDiag`, `StiffStringDiag`,
`LowTorture`, `NoiseDiag`, `PolyScenario`, `ChordBench`, `FftBench`, traces), `harnais/` (projet
Gradle JVM autonome qui compile le code pur de l'app avec les tests, scripts qui génèrent les
copies instrumentées, `telecharger-echantillons.sh` : notes G♯1–E5 des banques FluidR3 (CC BY 3.0),
MusyngKite et FatBoy (CC BY-SA 3.0), commit `044fab8e` épinglé). À faire : remplacer les chemins
absolus du conteneur de développement (`PhoneSim.dir`, `build.gradle.kts`, scripts), en faire un
jeu de tests optionnel (`./gradlew banc`) avec JLayer en dépendance de test, et télécharger les
échantillons à la première exécution.

### Calibration du micro

Non explorée (à la demande) : à reprendre plus tard.
