plugins {
    `kotlin-dsl`
    kotlin("jvm") version "2.1.21"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.1") // For JSON parsing e.g. in build.forge.gradle.kts
}