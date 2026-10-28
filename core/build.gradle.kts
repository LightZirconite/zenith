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

// ./gradlew :core:audit --args="radars.json dossier_des_tuiles [trajets] [graine]"
tasks.register<JavaExec>("audit") {
    group = "zenith"
    description = "Trajets simulés sur les vraies routes et les vrais radars : radars ratés, annonces hors route."
    mainClass = "zenith.core.FieldAuditMain"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}
