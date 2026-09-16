import org.gradle.api.Project

fun Project.versionProperty(name: String): String = providers.gradleProperty(name).get()

fun Project.loaderVersion(moduleName: String = project.name): String {
    @Suppress("UNCHECKED_CAST")
    val versions = rootProject.extensions.extraProperties["loaderVersions"] as Map<String, String>
    return versions[moduleName] ?: error("Unknown loader module: $moduleName")
}
