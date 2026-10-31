import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// core for the iOS app: the same sources as :core (src/main only, plain Kotlin), compiled by
// Kotlin/Native into ZenithCore.xcframework (static), which the Xcode project links.
// ./gradlew :core-ios:assembleZenithCoreReleaseXCFramework (macOS)
kotlin {
    val xcf = XCFramework("ZenithCore")
    iosArm64 {
        binaries.framework {
            baseName = "ZenithCore"
            isStatic = true
            xcf.add(this)
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir("../core/src/main/kotlin")
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
