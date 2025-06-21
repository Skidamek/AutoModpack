pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.architectury.dev/") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://files.minecraftforge.net/maven/") }
        maven { url = uri("https://maven.kikugie.dev/snapshots") }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.7-beta.3"
    id("com.gradleup.shadow") version "8.3.6" apply false
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
        "forge-fml40", "forge-fml47", "neoforge-fml2", "neoforge-fml4" -> project.buildFileName = "../../loader-forge.gradle.kts"
    }
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        /**
         * @param mcVersion The base minecraft version.
         * @param loaders A list of loaders to target, supports "fabric" (1.14+), "neoforge"(1.20.6+), "vanilla"(any) or "forge"(<=1.20.1)
         */
        fun mc(mcVersion: String, name: String = mcVersion, loaders: Iterable<String>) =
            loaders.forEach { vers("$name-$it", mcVersion) }

        // Configure your targets here!
//        mc("1.21.6", loaders = listOf("fabric", "neoforge"))
//        mc("1.21.5", loaders = listOf("fabric", "neoforge"))
//        mc("1.21.4", loaders = listOf("fabric", "neoforge"))
//        mc("1.21.3", loaders = listOf("fabric", "neoforge"))
        mc("1.21.1", loaders = listOf("fabric", "neoforge"))
//        mc("1.20.6", loaders = listOf("fabric", "neoforge"))
//        mc("1.20.4", loaders = listOf("fabric", "neoforge"))
//        mc("1.20.1", loaders = listOf("fabric", "forge"))
//        mc("1.19.4", loaders = listOf("fabric", "forge"))
//        mc("1.19.2", loaders = listOf("fabric", "forge"))
//        mc("1.18.2", loaders = listOf("fabric", "forge"))

        // This is the default target.
        // https://stonecutter.kikugie.dev/stonecutter/guide/setup#settings-settings-gradle-kts
        vcsVersion = "1.21.1-fabric"
    }
}

rootProject.name = "AutoModpack"