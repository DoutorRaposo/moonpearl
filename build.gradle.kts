plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere: AGP compiles Kotlin itself. Declaring it pins the
    // Kotlin version so it matches the Compose compiler plugin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
