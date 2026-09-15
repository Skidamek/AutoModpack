import org.gradle.api.Project
import java.io.File

fun Project.versionProperty(name: String): String = providers.gradleProperty(name).get()

fun Project.loaderVersion(moduleName: String = project.name): String {
    @Suppress("UNCHECKED_CAST")
    val versions = rootProject.extensions.extraProperties["loaderVersions"] as Map<String, String>
    return versions[moduleName] ?: error("Unknown loader module: $moduleName")
}

// Every published target merges the same universal outer jar (:loader-universal) with its own
// nested per-target impl; the universal shadowJar transitively builds every loader generation.
fun getUniversalLoaderModuleName(): String = "universal"

fun getAllDependentLoaderModules(): List<String> = listOf("core", "loader-${getUniversalLoaderModuleName()}")

fun getMergedJarPath(buildDirLibs: File): File {
    return buildDirLibs.listFiles()
        ?.firstOrNull { file -> file.isFile && !file.name.endsWith("-sources.jar") && file.name.endsWith(".jar") }
        ?: error("No jar found to merge in build/libs directory! ${buildDirLibs.absolutePath}")
}
