plugins {
    `kotlin-dsl`
    kotlin("jvm") version "2.2.20"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0") // For JSON parsing e.g. in build.forge.gradle.kts
}