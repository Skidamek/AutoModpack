import org.gradle.api.Project
import java.io.File

fun Project.versionProperty(name: String): String = providers.gradleProperty(name).get()

fun Project.loaderVersion(moduleName: String = project.name): String {
    @Suppress("UNCHECKED_CAST")
    val versions = rootProject.extensions.extraProperties["loaderVersions"] as Map<String, String>
    return versions[moduleName] ?: error("Unknown loader module: $moduleName")
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
