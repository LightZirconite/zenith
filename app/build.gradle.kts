plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "zenith.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "zenith.app"
        minSdk = 26
        targetSdk = 36
        // CI passes the run number so every release installs over the previous one.
        val build = System.getenv("ZENITH_BUILD_NUMBER")?.toInt() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        // MapLibre ships native code: 64-bit ARM only keeps the APK (and each update) small.
        ndk { abiFilters += "arm64-v8a" }
        // The author's Spotify app (repository variable SPOTIFY_CLIENT_ID, given by the CI); empty: no sign-in.
        val spotifyClientId = System.getenv("SPOTIFY_CLIENT_ID").orEmpty().filter { it.isLetterOrDigit() }
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
    }

    signingConfigs {
        val keystore = System.getenv("ZENITH_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ZENITH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZENITH_KEY_ALIAS")
                keyPassword = System.getenv("ZENITH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Android 10 is a real target (a car head unit): any call to a newer API without a version
    // check fails the build, in the app and in core.
    lint {
        checkOnly += setOf("NewApi", "InlinedApi")
        error += "NewApi"
        checkDependencies = true
        abortOnError = true
        // The findings in the CI log, warnings included.
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.maplibre.android)
    // Activity recognition (vehicle enter / exit) for the automatic start without Bluetooth.
    implementation(libs.play.services.location)
    debugImplementation(libs.compose.ui.tooling)
}
