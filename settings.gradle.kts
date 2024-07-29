import dev.kikugie.stonecutter.StonecutterSettings

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.architectury.dev/") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://files.minecraftforge.net/maven/") }
        maven { url = uri("https://maven.kikugie.dev/releases") }
        mavenLocal()
    }
}

plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
    id("dev.architectury.loom") version "1.7-SNAPSHOT" apply false
    id("dev.kikugie.stonecutter") version "0.4.+"
}

include(":core")

val coreModules = arrayOf(
    "core",
    "fabric",
    "forge-fml40",
    "forge-fml47",
    "neoforge-fml2",
    "neoforge-fml4"
)

coreModules.forEach { module ->
    include(":loader-$module")

    val project = project(":loader-$module")
    val dir = module.replace("-", "/")
    project.projectDir = file("loader/$dir")
    when (module) {
        "core" -> project.buildFileName = "../loader-core.gradle.kts"
        else -> project.buildFileName = if (dir == module) "../loader-common.gradle.kts" else "../../loader-common.gradle.kts"
    }
}

fun getProperty(key: String): String? {
    return settings.extra[key] as? String
}

fun getVersions(key: String): Set<String> {
    return getProperty(key)!!.split(',').map { it.trim() }.toSet()
}

val versions = mapOf(
    "forge" to getVersions("forge_versions"),
    "fabric" to getVersions("fabric_versions"),
    "neoforge" to getVersions("neoforge_versions")
)

val sharedVersions = versions.map { entry ->
    val loader = entry.key
    entry.value.map { "$it-$loader" }
}.flatten().toSet()

extensions.configure<StonecutterSettings> {
    kotlinController = true
    centralScript = "build.gradle.kts"

    shared {
        versions(sharedVersions)
    }

    create(rootProject)
}
