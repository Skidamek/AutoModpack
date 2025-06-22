pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie" }
    }
}

plugins {
    // For some reason, this plugin is crucial - do not remove
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.7-beta.3"
}

include(":core")

fun getProperty(key: String): String? {
    return settings.extra[key] as? String
}

val coreModules = getProperty("core_modules")!!.split(',').map { it.trim() }

coreModules.forEach { module ->
    include(":loader-$module")

    val project = project(":loader-$module")
    val dir = module.replace("-", "/")
    project.projectDir = file("loader/$dir")
    when (module) {
        "core" -> project.buildFileName = "../loader-core.gradle.kts"
        "fabric-core" -> project.buildFileName = "../../loader-fabric-core.gradle.kts"
        "fabric-15", "fabric-16" -> project.buildFileName = "../../loader-fabric.gradle.kts"
        "forge-fml40", "forge-fml47" -> project.buildFileName = "../../loader-forge.gradle.kts"
        "neoforge-fml2", "neoforge-fml4" -> project.buildFileName = "../../loader-neoforge.gradle.kts"
    }
}

stonecutter {
    create(rootProject) {
        fun match(version: String, vararg loaders: String) = loaders
            .forEach { vers("$version-$it", version).buildscript = "build.$it.gradle.kts" }

        // Configure your targets here!
//        match("1.21.6", "fabric", "neoforge")
//        match("1.21.5", "fabric", "neoforge")
//        match("1.21.4", "fabric", "neoforge")
//        match("1.21.3", "fabric", "neoforge")
        match("1.21.1", "fabric", "neoforge")
//        match("1.20.6", "fabric", "neoforge")
//        match("1.20.4", "fabric", "neoforge")
//        match("1.20.1", "fabric", "forge")
//        match("1.19.4", "fabric", "forge")
//        match("1.19.2", "fabric", "forge")
//        match("1.18.2", "fabric", "forge")

        // This is the default target.
        // https://stonecutter.kikugie.dev/stonecutter/guide/setup#settings-settings-gradle-kts
//        vcsVersion = "1.21.1-fabric"
    }
}

rootProject.name = "AutoModpack"