import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Secrets live in local.properties (gitignored) and reach the app via BuildConfig.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String): String =
    System.getenv(key.uppercase()) ?: localProperties.getProperty(key) ?: ""

android {
    namespace = "com.leeotts.cicero"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.leeotts.cicero"
        // 31 is required for AudioManager.setCommunicationDevice(), used by the
        // Bluetooth HFP microphone path in later phases.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // In Developer Mode attestation is skipped, so 0/0 is correct here.
        // Replace with Wearables Developer Center credentials for a published build.
        //
        // The SDK logs "Failed to read metadata from manifest: APPLICATION_ID" at
        // startup. That is BENIGN in Developer Mode: aapt types the literal "0" as an
        // integer, so the SDK's getString() read returns null. Routing through a string
        // resource does NOT help either - it stores a resource reference, which
        // getString() also returns null for. Registration, sessions, streaming and
        // capture all work regardless. Revisit only if real-hardware registration fails.
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"

        buildConfigField("String", "GEMINI_API_KEY", "\"${secret("gemini_api_key")}\"")

        // The Maps SDK reads its key from the manifest. BuildConfig carries it as
        // well, purely so the Map screen can say "no key set" instead of showing
        // the grey grid the SDK draws when authentication fails.
        manifestPlaceholders["mapsApiKey"] = secret("maps_api_key")
        buildConfigField("String", "MAPS_API_KEY", "\"${secret("maps_api_key")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room schemas are exported and committed so a future version bump can be given
// a real migration instead of a destructive fallback. See app/schemas/.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.mockdevice)

    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
