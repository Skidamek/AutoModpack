import org.gradle.api.Project
import org.tomlj.Toml
import java.io.File

fun Project.versionProperty(name: String): String = providers.gradleProperty(name).get()

fun Project.loaderVersion(moduleName: String = project.name): String {
    val (section, key) = when (moduleName) {
        "loader-fabric-15", "loader-fabric-core" -> "loader-modules" to "fabric-15"
        "loader-fabric-16" -> "loader-modules" to "fabric-16"
        "loader-fabric-19" -> "fabric" to "deps.fabric-loader"
        "loader-forge-fml40" -> "1.18.2-forge" to "deps.forge"
        "loader-forge-fml47", "loader-forge-earlyservices", "loader-modlauncher-earlyservices" ->
            "1.20.1-forge" to "deps.forge"
        "loader-neoforge-fml4" -> "1.21.1-neoforge" to "deps.neoforge"
        "loader-neoforge-fml10", "loader-neoforge-earlyservices" ->
            "1.21.10-neoforge" to "deps.neoforge"
        "loader-neoforge-fml11" -> "26.1-neoforge" to "deps.neoforge"
        else -> error("Unknown loader module: $moduleName")
    }

    val properties = Toml.parse(rootProject.file("stonecutter.properties.toml").toPath())
    if (properties.hasErrors()) {
        error(properties.errors().joinToString("\n"))
    }

    return properties.getString(listOf(section) + key.split('.'))
        ?: error("Missing $section.$key in stonecutter.properties.toml")
}

fun getLoaderModuleName(projectName: String): String {
    val minecraftFamily = projectName.substringBefore("-")
        .ifEmpty { error("Could not determine Minecraft family from: $projectName") }

    return when {
        projectName.contains("fabric") -> "fabric-core"
        projectName.contains("neoforge") -> when (minecraftFamily) {
            "1.21.8", "1.21.5", "1.21.4", "1.21.1" -> "neoforge-fml4"
            "1.21.10", "1.21.11" -> "neoforge-fml10"
            "26.1", "26.2" -> "neoforge-fml11"
            else -> error("Unknown neoforge loader module for Minecraft family: $minecraftFamily")
        }
        projectName.contains("forge") -> if (minecraftFamily == "1.18.2") "forge-fml40" else "forge-fml47"
        else -> error("Unknown loader type")
    }
}

fun getAllDependentLoaderModules(name: String): List<String> {
    val loaderModule = getLoaderModuleName(name)
    val list = mutableListOf("core", "loader-core", "loader-$loaderModule")
    if (loaderModule == "fabric-core") { // Special case for fabric.
        list.add("loader-fabric-15")
        list.add("loader-fabric-16")
    }
    return list
}

fun getMergedJarPath(buildDirLibs: File): File {
    return buildDirLibs.listFiles()
        ?.firstOrNull { file -> file.isFile && !file.name.endsWith("-sources.jar") && file.name.endsWith(".jar") }
        ?: error("No jar found to merge in build/libs directory! ${buildDirLibs.absolutePath}")
}
