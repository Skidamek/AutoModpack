@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("jvm")
    id("automodpack.common")
    id("fabric-loom")
}

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc${property("deps.minecraft")}-fabric".lowercase())

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

    setOf(
        "api-base", // Required by modules below
        "registry-sync-v0", // Required for custom sounds
        "networking-api-v1", // Required by registry sync module
        "resource-loader-v0" // Required for translatable texts
    ).forEach {
        include(modImplementation(fabricApi.module("fabric-$it", property("deps.fabric-api") as String))!!)
    }

    // Required for commands
    if (stonecutter.eval(stonecutter.current.version, "<1.19.2")) {
        include(modImplementation(fabricApi.module("fabric-command-api-v1", property("deps.fabric-api") as String))!!)
    } else {
        include(modImplementation(fabricApi.module("fabric-command-api-v2", property("deps.fabric-api") as String))!!)
    }

    // Required for translatable texts in 1.21.9+ for some reason i need both v0 and v1?
    if (stonecutter.eval(stonecutter.current.version, ">=1.21.9")) {
        include(modImplementation(fabricApi.module("fabric-resource-loader-v1", property("deps.fabric-api") as String))!!)
    }
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
        exclude("**/neoforge.mods.toml", "**/mods.toml", "**/accesstransformer.cfg")
        if (stonecutter.eval(stonecutter.current.version, ">=1.21.9")) {
            exclude("**/pack.mcmeta")
            rename("new-pack.mcmeta", "pack.mcmeta")
        } else {
            exclude("**/new-pack.mcmeta")
        }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(remapJar.map { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod_version")}"))
        dependsOn("build")
    }
}