// Fichier de build racine : déclare les plugins (appliqués dans :app).
// AGP 9 compile le Kotlin nativement (« built-in Kotlin ») : pas de plugin
// org.jetbrains.kotlin.android. Le plugin Compose fixe la version de Kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
