plugins {
    `kotlin-dsl`
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1") // For JSON parsing e.g. in build.forge.gradle.kts
    implementation("dev.luna5ama.jar-optimizer:dev.luna5ama.jar-optimizer.gradle.plugin:1.2.2")
}