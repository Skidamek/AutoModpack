import groovy.json.JsonSlurper

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.architectury.dev/") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.quiltmc.org/repository/release") }
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://files.minecraftforge.net/maven/") }
        maven { url = uri("https://jitpack.io") }
        mavenLocal()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.replaymod.preprocess" -> useModule("com.github.Fallen-Breath:preprocessor:${requested.version}")
            }
        }
    }
}

include(":core")

val coreModules = arrayOf(
    "core",
    "fabric",
    "quilt",
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

val settingsFile = File("$rootDir/settings.json")
val settings = JsonSlurper().parseText(settingsFile.readText()) as Map<String, List<String>>

for (version in settings["versions"]!!) {
    include(":$version")

    val project = project(":$version")
    project.projectDir = file("versions/$version")
    project.buildFileName = "../build.gradle.kts"
}