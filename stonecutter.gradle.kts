import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21-fabric" /* [SC] DO NOT EDIT */

stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) { 
    group = "project"
    ofTask("build")
    finalizedBy("mergeJars")
}

stonecutter configureEach {
    val current = project.property("loom.platform")
    val platforms = listOf("fabric", "forge", "neoforge").map { it to (it == current) }
    consts(platforms)
}

// Non stonecutter stuff
val mergedDir = File("${rootProject.projectDir}/merged")

class MinecraftVersionData(private val name: String) {
    fun greaterThan(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) > 0
    }

    fun lessThan(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) < 0
    }

    fun greaterOrEqual(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) >= 0
    }

    fun lessOrEqual(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) <= 0
    }

    override fun equals(other: Any?) : Boolean {
        return name == other
    }

    override fun toString(): String {
        return name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }
}

// copy from settings.gradle.kts
val coreModules = arrayOf(
    "core",
    "fabric",
    "forge-fml40",
    "forge-fml47",
    "neoforge-fml2",
    "neoforge-fml4"
)

// TODO find better way to do it
tasks.register("mergeJars") {
    coreModules.forEach { module ->
        dependsOn(":loader-$module:build")
    }

    doLast {
        mergedDir.mkdirs()
        val jarsToMerge = File("$rootDir/versions").listFiles()
            ?.flatMap {
                File("$it/build/libs").listFiles()
                    ?.filter { file -> file.isFile && !file.name.endsWith("-sources.jar") && file.name.endsWith(".jar") }
                    ?: error("Couldn't find any mod jar!")
            }
            ?: emptyList()

        val time = System.currentTimeMillis()
        val size = jarsToMerge.size
        var current = 0;

        for (jarToMerge in jarsToMerge) {
            val minecraftVersionStr = jarToMerge.name.substringAfterLast("-mc").substringBefore("-")
            val minecraftVersion = MinecraftVersionData(minecraftVersionStr)

            var loaderModule = ""
            if (jarToMerge.name.contains("fabric")) {
                loaderModule = "fabric"
            } else if (jarToMerge.name.contains("neoforge")) {
                loaderModule = if (minecraftVersion.greaterOrEqual("1.20.6")) {
                    "neoforge/fml4"
                } else {
                    "neoforge/fml2"
                }
            } else if (jarToMerge.name.contains("forge")) {
                loaderModule = if (minecraftVersion.lessOrEqual("1.18.2")) {
                    "forge/fml40"
                } else {
                    "forge/fml47"
                }
            }

            val loaderFile = File("${rootProject.projectDir}/loader/$loaderModule/build/libs").listFiles()?.single { it.isFile && !it.name.endsWith("-sources.jar") && it.name.endsWith(".jar") } ?: continue
            val finalJar = File("$mergedDir/${jarToMerge.name}")

            loaderFile.copyTo(finalJar, overwrite = true)
            appendFileToZip(finalJar, jarToMerge, "automodpack-mod.jar")

            println("${++current}/$size - Merged: ${jarToMerge.name} into: ${finalJar.name} from: ${loaderFile.name}")
        }

        if (size == 0) {
            error("No jars to merge!")
        } else if (size != current) {
            error("Not all jars were merged!")
        } else {
            println("All jars were merged! Took: ${System.currentTimeMillis() - time}ms")
        }
    }
}

fun appendFileToZip(zipFile: File, fileToAppend: File, entryName: String) {
    // Doing with temp file since for some reason just adding the file breaks the zip/jar file
    val tempFile = File("$zipFile.temp")
    tempFile.createNewFile()

    ZipOutputStream(FileOutputStream(tempFile)).use { zipStream ->
        // Copy existing entries
        ZipInputStream(FileInputStream(zipFile)).use { existingZipStream ->
            while (true) {
                val entry = existingZipStream.nextEntry ?: break
                zipStream.putNextEntry(ZipEntry(entry.name))
                existingZipStream.copyTo(zipStream)
                zipStream.closeEntry()
            }
        }

        // Add the new entry
        zipStream.putNextEntry(ZipEntry(entryName))
        FileInputStream(fileToAppend).use { fileInputStream ->
            fileInputStream.copyTo(zipStream)
        }
        zipStream.closeEntry()
    }

    // Replace the original zip file with the one containing the new entry
    zipFile.delete()
    tempFile.renameTo(zipFile)
}

tasks.register("clean") {
    dependsOn("cleanMerged")
}

tasks.register("cleanMerged") {
    doLast {
        mergedDir.deleteRecursively()
    }
}
