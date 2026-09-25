pluginManagement {
    repositories {
        // Miroir Google de Maven Central (Maven Central renvoie des 429 depuis cet environnement).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "accordeur-jvmcheck"
