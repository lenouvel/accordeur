// Harnais de vérification JVM : compile le code Kotlin pur de l'app (model/ + audio/ hors
// Android) et exécute les tests unitaires de app/src/test, sans SDK Android.
plugins {
    kotlin("jvm") version "2.4.20"
}

val app = "/home/user/accordeur/app/src"

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDir("$app/main/kotlin")
            kotlin.include("com/blenouvel/accordeur/model/**", "com/blenouvel/accordeur/audio/**")
            kotlin.exclude("**/AudioEngine.kt", "**/ReferenceTone.kt")
        }
        test {
            kotlin.srcDir("$app/test/kotlin")
            kotlin.srcDir("diag/kotlin")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("javazoom:jlayer:1.0.1")
    testImplementation("com.github.wendykierp:JTransforms:3.1:with-dependencies")
}

tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    for (key in listOf("g.sf", "g.inst", "g.note", "g.mic", "g.level", "g.trace")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
