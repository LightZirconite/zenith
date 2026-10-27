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
    debugImplementation(libs.compose.ui.tooling)
}
