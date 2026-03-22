@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("jvm")
    id("automodpack.common")
    id("automodpack.utils")
    id("net.fabricmc.fabric-loom")
}

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc${property("deps.minecraft")}-fabric".lowercase())

loom {
    accessWidenerPath = rootProject.file("src/main/resources/automodpack.unobf.accesswidener")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":loader-core"))

    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")

    implementation("net.fabricmc:fabric-loader:${property("deps.fabric-loader")}")

    setOf(
        "api-base",
        "registry-sync-v0",
        "networking-api-v1",
        "resource-loader-v0"
    ).forEach {
        include(implementation(fabricApi.module("fabric-$it", property("deps.fabric-api") as String))!!)
    }

    include(implementation(fabricApi.module("fabric-command-api-v2", property("deps.fabric-api") as String))!!)
    include(implementation(fabricApi.module("fabric-resource-loader-v1", property("deps.fabric-api") as String))!!)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks {
    processResources {
        exclude("**/neoforge.mods.toml", "**/mods.toml", "**/accesstransformer.cfg", "**/pack.mcmeta", "**/automodpack.accesswidener")
        rename("automodpack.unobf.accesswidener", "automodpack.accesswidener")
        rename("new-pack.mcmeta", "pack.mcmeta")
    }
}
