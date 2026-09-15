import org.gradle.api.Project
import java.io.File

fun Project.versionProperty(name: String): String = providers.gradleProperty(name).get()

fun Project.loaderVersion(moduleName: String = project.name): String {
    @Suppress("UNCHECKED_CAST")
    val versions = rootProject.extensions.extraProperties["loaderVersions"] as Map<String, String>
    return versions[moduleName] ?: error("Unknown loader module: $moduleName")
}

// The one jar packs each target's optimized impl jar (build/libs-optimized) into the optimized
// universal outer (:loader-universal); the universal shadowJar transitively builds every loader
// generation.
fun getModJarPath(buildDirLibs: File): File {
    return buildDirLibs.listFiles()
        ?.firstOrNull { file -> file.isFile && !file.name.endsWith("-sources.jar") && file.name.endsWith(".jar") }
        ?: error("No jar found in build/libs directory! ${buildDirLibs.absolutePath}")
}
