// Top-level build file. Plugin versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // Declared here so it is on the build classpath; applied in :app only when
    // app/google-services.json exists (see app/build.gradle.kts).
    alias(libs.plugins.google.services) apply false
}
