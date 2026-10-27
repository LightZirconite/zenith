import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// ./gradlew :core:replay --args="trajet.gpx [radars.json] [dossier des tuiles]"
tasks.register<JavaExec>("replay") {
    group = "zenith"
    description = "Rejoue un trajet GPX et liste les annonces."
    mainClass = "zenith.core.ReplayKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}
