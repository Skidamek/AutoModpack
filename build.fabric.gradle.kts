@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("jvm")
    id("automodpack.common")
    id("fabric-loom")
}

version = "${property("mod.version")}+${property("deps.minecraft")}"
base.archivesName = property("mod.id") as String

loom {
    accessWidenerPath = rootProject.file("src/main/resources/automodpack.accesswidener")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":loader-core"))

    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")
    mappings(loom.layered {
        officialMojangMappings()
        if (hasProperty("deps.parchment"))
            parchment("org.parchmentmc.data:parchment-${property("deps.parchment")}@zip")
    })

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric-loader")}")

    // TODO transitive false
    setOf(
        "api-base", // Required by modules below
        "resource-loader-v0", // Required for translatable texts
        "registry-sync-v0", // Required for custom sounds
        "networking-api-v1" // Required by registry sync module
    ).forEach {
        include(modImplementation(fabricApi.module("fabric-$it", property("deps.fabric-api") as String))!!)
    }

    if (stonecutter.eval(stonecutter.current.version, "<1.19.2")) {
        include(modImplementation(fabricApi.module("fabric-command-api-v1", property("deps.fabric-api") as String))!!) // TODO transitive false
    } else {
        include(modImplementation(fabricApi.module("fabric-command-api-v2", property("deps.fabric-api") as String))!!) // TODO transitive false
    }

    include(implementation(annotationProcessor("io.github.llamalad7:mixinextras-fabric:${property("deps.mixin-extras")}")!!)!!)
}

java {
    if (stonecutter.eval(stonecutter.current.version, ">=1.20.5")) {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    } else {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

tasks {
    processResources {
        exclude("**/neoforge.mods.toml", "**/forge.mods.toml")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(remapJar.map { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
        dependsOn("build")
    }
}