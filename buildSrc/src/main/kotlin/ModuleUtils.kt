import java.io.File

fun getLoaderModuleName(name: String): String {
    // 1. Expected format: {name}-mc{mcversion}-{loader}-{version}.jar
    // 2. Fallback format: {mcversion}-{loader}
    val mcVersion = name.substringAfterLast("-mc", "").substringBefore("-")
        .ifEmpty { name.substringBefore("-") }
        .ifEmpty { error("Could not determine Minecraft version from: $name") }

    return when {
        name.contains("fabric") -> "fabric-core"
        name.contains("neoforge") -> when (mcVersion) {
            "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.19.2", "1.18.2" -> "neoforge-fml2"
            "1.21.8", "1.21.5", "1.21.4", "1.21.3", "1.21.1" -> "neoforge-fml4"
            "1.21.11", "1.21.10" -> "neoforge-fml10"
            else -> error("Unknown neoforge loader module for Minecraft version: $mcVersion")
        }
        name.contains("forge") -> if (mcVersion == "1.18.2") "forge-fml40" else "forge-fml47"
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