// Banc de mesure JVM, optionnel et hors du build normal : `./gradlew banc`.
//
// Compile le code pur de l'app (model/ + audio/, sans les classes Android) et les bancs de
// banc/kotlin/, puis les exécute sur de vrais échantillons de guitare (soundfonts) téléchargés
// à la première exécution, ainsi que sur des signaux synthétiques.
//
// Les bancs vivent dans un source set dédié `banc` (pas `test`) : `./gradlew test`, `check` et
// `build` ne les compilent pas et ne les exécutent pas. Exemples :
//
//   ./gradlew banc                              # tous les bancs (télécharge les échantillons)
//   ./gradlew banc --tests '*ChordBench*'       # un seul banc
//   ./gradlew banc -x downloadSamples --tests '*FftBench*'   # bancs synthétiques, sans téléchargement
//   ./gradlew downloadSamples                    # préparer les échantillons seuls
//
// Les traceurs (FrameTrace, GlitchDebug, HumTrace, PolyTrace, StiffTrace, OctaveDebug) ont besoin
// des copies instrumentées du paquet `dbg` : lancer d'abord les scripts regen-*.sh (qui écrivent
// dans banc/diag/kotlin/). Sans ce dossier, ces six fichiers sont exclus de la compilation.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Le plugin Kotlin est déjà sur le classpath du build (AGP 9 / plugin Compose de la racine) :
// on l'applique sans version.
plugins {
    kotlin("jvm")
}

private val diagSources = layout.projectDirectory.dir("diag/kotlin").asFile

java {
    // Aligné sur Kotlin (ci-dessous) : évite l'incohérence de cible JVM avec le JDK courant.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Source set dédié au banc : les bancs et leurs dépendances (JLayer, JTransforms) restent en
// dehors du source set `test`, qui demeure vide (donc `./gradlew test` ne compile rien ici).
sourceSets {
    create("banc") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
    sourceSets {
        named("main") {
            // Code indépendant d'Android : traitement du signal et modèle.
            kotlin.srcDir("../app/src/main/kotlin")
            kotlin.include("com/blenouvel/accordeur/model/**", "com/blenouvel/accordeur/audio/**")
            kotlin.exclude("**/AudioEngine.kt", "**/ReferenceTone.kt")
        }
        named("banc") {
            kotlin.srcDir("kotlin")
            // TestSignals, importé seul (voir importTestSignals).
            kotlin.srcDir(layout.buildDirectory.dir("shared-test-sources"))
            if (diagSources.isDirectory) {
                // Copies instrumentées présentes : les traceurs peuvent être compilés.
                kotlin.srcDir("diag/kotlin")
            } else {
                kotlin.exclude(
                    "**/FrameTrace.kt", "**/GlitchDebug.kt", "**/HumTrace.kt",
                    "**/PolyTrace.kt", "**/StiffTrace.kt", "**/OctaveDebug.kt",
                )
            }
        }
    }
}

dependencies {
    "bancImplementation"("junit:junit:4.13.2")
    "bancImplementation"("javazoom:jlayer:1.0.1") // décodage MP3 des échantillons (PhoneSim)
    "bancImplementation"("com.github.wendykierp:JTransforms:3.1:with-dependencies") // FftBench (référence)
}

// TestSignals (générateurs synthétiques) est le seul helper partagé avec les tests de l'app :
// on l'importe seul plutôt que d'embarquer toute la suite app/src/test.
val importTestSignals = tasks.register<Copy>("importTestSignals") {
    from("../app/src/test/kotlin/com/blenouvel/accordeur/TestSignals.kt")
    into(layout.buildDirectory.dir("shared-test-sources/com/blenouvel/accordeur"))
}
tasks.named("compileBancKotlin") { dependsOn(importTestSignals) }

// --- Échantillons de guitare -----------------------------------------------------------------
// Notes G#1–E5 (MIDI 32–76) de trois soundfonts, trois instruments : acier, nylon, électrique.
// FluidR3_GM (CC BY 3.0), MusyngKite et FatBoy (CC BY-SA 3.0), commit épinglé.
val samplesDir = layout.projectDirectory.dir("samples")

val downloadSamples = tasks.register("downloadSamples") {
    description = "Télécharge les notes de guitare (G#1–E5) des soundfonts si elles manquent."
    group = "verification"
    val target = samplesDir.asFile
    doLast {
        val base = "https://raw.githubusercontent.com/gleitz/midi-js-soundfonts/044fab8e1456bfafc5776e86dfd6bb8697149aef"
        val names = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
        val soundfonts = listOf("FluidR3_GM", "MusyngKite", "FatBoy")
        val instruments = listOf("acoustic_guitar_steel", "acoustic_guitar_nylon", "electric_guitar_clean")
        val missing = ArrayList<Pair<String, File>>()
        for (sf in soundfonts) for (inst in instruments) for (midi in 32..76) {
            val name = names[midi % 12] + (midi / 12 - 1)
            val file = File(target, "$sf/$inst/$name.mp3")
            if (file.exists() && file.length() > 0L) continue
            missing += "$base/$sf/$inst-mp3/$name.mp3" to file
        }
        if (missing.isEmpty()) {
            logger.lifecycle("Échantillons déjà présents dans $target.")
            return@doLast
        }
        logger.lifecycle("Téléchargement de ${missing.size} échantillons dans $target…")
        val pool = Executors.newFixedThreadPool(16)
        val failures = ConcurrentLinkedQueue<String>()
        for ((url, file) in missing) {
            pool.submit {
                file.parentFile.mkdirs()
                val part = File(file.parentFile, file.name + ".part")
                var ok = false
                for (attempt in 1..3) {
                    try {
                        URI(url).toURL().openStream().use { input ->
                            part.outputStream().use { input.copyTo(it) }
                        }
                        if (part.length() > 0L && part.renameTo(file)) { ok = true; break }
                        part.delete()
                    } catch (e: Exception) {
                        part.delete()
                        Thread.sleep(500L * attempt)
                    }
                }
                if (!ok) failures += url
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.MINUTES)
        if (failures.isNotEmpty()) {
            throw GradleException("Téléchargement incomplet : ${failures.size} échec(s). Relancez la tâche.")
        }
        logger.lifecycle("Échantillons prêts.")
    }
}

// --- Tâche du banc ---------------------------------------------------------------------------
val bancSourceSet = sourceSets["banc"]

tasks.register<Test>("banc") {
    description = "Banc de mesure hors appareil (échantillons réels + signaux synthétiques)."
    group = "verification"
    testClassesDirs = bancSourceSet.output.classesDirs
    classpath = bancSourceSet.runtimeClasspath
    dependsOn(downloadSamples)
    useJUnit()
    maxHeapSize = "2g"
    systemProperty("banc.samples", samplesDir.asFile.absolutePath)
    // Propriétés de réglage des traceurs (voir banc/kotlin/*.kt), transmises si présentes.
    for (key in listOf("g.sf", "g.inst", "g.note", "g.mic", "g.level", "g.trace", "trace.t")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
