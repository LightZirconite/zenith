pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "zenith"
include(":core", ":app")

// The iOS framework of core: Kotlin/Native links Apple targets on macOS only (the iOS CI), or on
// demand with -Pzenith.ios=true to check that core still compiles for iOS.
if (System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("zenith.ios").orNull == "true") {
    include(":core-ios")
}
