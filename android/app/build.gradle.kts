import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------------------------
// Helpers: read a value from -P project properties first, then keystore.properties, then the environment.
// ---------------------------------------------------------------------------------------------
// Optional, never committed (see .gitignore): android/keystore.properties with MERIDIAN_KEYSTORE_PATH=…,
// MERIDIAN_KEYSTORE_PASSWORD=…, MERIDIAN_KEY_ALIAS=…, MERIDIAN_KEY_PASSWORD=… (and optionally MERIDIAN_API_BASE_URL).
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun configValue(name: String): String? {
    val fromProject = (project.findProperty(name) as String?)?.trim()
    if (!fromProject.isNullOrEmpty()) return fromProject
    val fromFile = keystoreProperties.getProperty(name)?.trim()
    if (!fromFile.isNullOrEmpty()) return fromFile
    val fromEnv = System.getenv(name)?.trim()
    return if (fromEnv.isNullOrEmpty()) null else fromEnv
}

fun isAcceptableReleaseUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    if (!lower.startsWith("https://")) return false
    val forbidden = listOf("localhost", "127.0.0.1", "10.0.2.2", "0.0.0.0", "::1")
    return forbidden.none { lower.contains(it) }
}

fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

val appVersionCode: Int = configValue("MERIDIAN_VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName: String = configValue("MERIDIAN_VERSION_NAME") ?: "1.0.0"
val debugApiBaseUrl: String = (configValue("MERIDIAN_DEBUG_API_BASE_URL") ?: "https://meridian-api-dbvk1c3l550s7383beug.onrender.com").ensureTrailingSlash()
val releaseApiBaseUrl: String? = configValue("MERIDIAN_API_BASE_URL")?.ensureTrailingSlash()

// Release signing is read from the environment / gradle properties only. No keystore is ever
// generated or committed. When any value is missing the release build is produced UNSIGNED
// (it cannot be installed until signed) and a warning is printed.
val releaseKeystorePath: String? = configValue("MERIDIAN_KEYSTORE_PATH")
val releaseKeystorePassword: String? = configValue("MERIDIAN_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = configValue("MERIDIAN_KEY_ALIAS")
val releaseKeyPassword: String? = configValue("MERIDIAN_KEY_PASSWORD")
val hasReleaseSigning: Boolean =
    releaseKeystorePath != null && file(releaseKeystorePath).exists() &&
        !releaseKeystorePassword.isNullOrEmpty() && !releaseKeyAlias.isNullOrEmpty() && !releaseKeyPassword.isNullOrEmpty()

// Firebase Cloud Messaging: the google-services plugin is applied only when the company's
// google-services.json is present. Without it the app still builds and runs; push messages
// are simply not delivered (the app polls instead). See docs/android/BUILD-VERIFICATION.md.
val googleServicesJson = file("google-services.json")
if (googleServicesJson.exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("[Meridian] app/google-services.json not found - building WITHOUT Firebase Cloud Messaging configuration.")
}

android {
    namespace = "za.co.meridiangroup.attendance"
    compileSdk = 36

    defaultConfig {
        applicationId = "za.co.meridiangroup.attendance"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"${releaseApiBaseUrl ?: ""}\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        xmlReport = true
        htmlReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room schema export (checked in under app/schemas so migrations can be verified in tests).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// ---------------------------------------------------------------------------------------------
// Release safety checks. These run as soon as the task graph is known (before any task executes)
// and only when a release task of this module is requested, so debug builds never need the
// release settings.
// ---------------------------------------------------------------------------------------------
gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { task ->
        task.path.startsWith(":app:") && task.name.contains("Release")
    }
    if (releaseRequested) {
        check(isAcceptableReleaseUrl(releaseApiBaseUrl)) {
            """
            |[Meridian] Release build refused: MERIDIAN_API_BASE_URL is missing or invalid ('${releaseApiBaseUrl ?: ""}').
            |It must be an https:// URL of the production (or staging) backend and must not point at
            |localhost / 127.0.0.1 / 10.0.2.2. Pass it with -PMERIDIAN_API_BASE_URL=https://attendance.example.co.za
            |or export MERIDIAN_API_BASE_URL in the environment (CI uses the repository secret of the same name).
            """.trimMargin()
        }
        if (!hasReleaseSigning) {
            logger.warn(
                "[Meridian] Release signing is NOT configured (MERIDIAN_KEYSTORE_PATH / MERIDIAN_KEYSTORE_PASSWORD / " +
                    "MERIDIAN_KEY_ALIAS / MERIDIAN_KEY_PASSWORD). The release APK/AAB will be UNSIGNED and cannot be " +
                    "installed or uploaded until signed. See docs/RELEASE.md."
            )
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Persistence & background work
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Google Play services / Firebase
    implementation(libs.play.services.location)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.integrity)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumentation tests
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test.junit)
}
