import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("dev.kikugie.stonecutter")
    id("org.moddedmc.wiki.toolkit") version "0.2.7"
}

wiki {
    docs.create("automodpack") {
        root = file("docs")
    }
}

stonecutter active "1.21.1-neoforge" /* [SC] DO NOT EDIT */

stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) { 
    group = "project"
    ofTask("build")
    finalizedBy("mergeJars")
}

stonecutter parameters {
    val loader = metadata.project.substringAfterLast("-")
    consts(loader, "fabric", "forge", "neoforge")
}

// Non stonecutter stuff
val mergedDir = File("${rootProject.projectDir}/merged")

class MinecraftVersionData(private val name: String) {
    fun greaterThan(other: String) : Boolean {
        return stonecutter.eval(name, ">" + other.lowercase())
    }

    fun lessThan(other: String) : Boolean {
        return stonecutter.eval(name, "<" + other.lowercase())
    }

    fun greaterOrEqual(other: String) : Boolean {
        return stonecutter.eval(name, ">=" + other.lowercase())
    }

    fun lessOrEqual(other: String) : Boolean {
        return stonecutter.eval(name, "<=" + other.lowercase())
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

val coreModules = getProperty("core_modules")!!.split(',').map { it.trim() }

fun getProperty(key: String): String? {
    return project.findProperty(key) as? String
}

// TODO find better way to do it
// If you get Array Exception, run "clean" task
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
                    ?: emptyList()
            }
            ?: emptyList()

        val tasks = mutableListOf<CompletableFuture<Void>>()
        val time = System.currentTimeMillis()
        val size = jarsToMerge.size
        var current = 0

        for (jarToMerge in jarsToMerge) {
            val task = CompletableFuture.runAsync {
                val minecraftVersionStr = jarToMerge.name.substringAfterLast("-mc").substringBefore("-")
                val minecraftVersion = MinecraftVersionData(minecraftVersionStr)

                var loaderModule = ""
                if (jarToMerge.name.contains("fabric")) {
                    loaderModule = "fabric/core"
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

                val loaderFile = File("${rootProject.projectDir}/loader/$loaderModule/build/libs").listFiles()
                    ?.single { it.isFile && !it.name.endsWith("-sources.jar") && it.name.endsWith(".jar") } ?: return@runAsync
                val finalJar = File("$mergedDir/${jarToMerge.name}")

                loaderFile.copyTo(finalJar, overwrite = true)
                appendFileToZip(finalJar, jarToMerge, "automodpack-mod.jar")

                println("${++current}/$size - Merged: ${jarToMerge.name} into: ${finalJar.name} from: ${loaderFile.name}")
            }

            tasks.add(task)
        }

        tasks.forEach { it.join() }

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
    val entries = ZipInputStream(FileInputStream(zipFile)).use { zipStream ->
        generateSequence { zipStream.nextEntry }
            .toList()
    }

    val graph = mutableMapOf<String, MutableList<String>>()

    entries.forEach { entry ->
        val children = entry.name.split("/")
        var currentParent = ""
        children.forEach { child ->
            if (child.isNotEmpty()) {
                currentParent = "$currentParent$child/"
                val parent = graph.getOrPut(currentParent) { mutableListOf() }
                parent.add(child)
            }
        }
    }

//    graph.forEach { (parent, children) ->
//        println("$parent -> $children")
//    }

    val filteredGraph = filterEntries(entries, graph, zipFile)

    // Doing with temp file since for some reason just adding the file breaks the zip/jar file
    val tempFile = File("$zipFile.temp")
    tempFile.createNewFile()

    ZipOutputStream(FileOutputStream(tempFile)).use { zipStream ->
        ZipInputStream(FileInputStream(zipFile)).use { existingZipStream ->
            while (true) {
                val entry = existingZipStream.nextEntry ?: break
                if (filteredGraph.containsKey("${entry.name}/")) {
                    try {
                        val zipEntry = ZipEntry(entry.name)
                        zipStream.putNextEntry(zipEntry)
                        existingZipStream.copyTo(zipStream)
                        zipStream.closeEntry()
                    } catch (e: Exception) {
                        println("Error while copying entry: ${entry.name}")
                    }
                }
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

val dupesDir = File("${rootProject.projectDir}/dupes")

fun filterEntries(entries: List<ZipEntry>, graph: Map<String, MutableList<String>>, zipFile: File):  Map<String, MutableList<String>> {

    val emptyDirs = mutableSetOf<String>()

    // find empty directories
    graph.forEach { (parent, children) ->
        if (children.size == 0) {
            emptyDirs.add(parent)
        }
    }

    // dump duplicate entries
    dupesDir.deleteRecursively()
    dupesDir.mkdirs()

    dumpDupeEntries(zipFile, entries)

    // filter empty directories
    // filter single duplicate files (leave only one)
    val dupes = mutableSetOf<String>()

    val filteredGraph = graph.filter { (parent, children) ->
        if (emptyDirs.contains(parent)) {
            println("Filtering empty dir: $parent -> $children")
            return@filter false
        }

        val count = graph.count { parent == it.key }
        if (count > 1 && !dupes.add(children[0])) {
            return@filter false
        }

        return@filter true
    }

    return filteredGraph
}

fun dumpDupeEntries(zipFile: File, entries: List<ZipEntry>) {
    // check for duplicates
    val entryNames = mutableSetOf<String>()
    entries.forEach { duplicate ->
        if (!entryNames.add(duplicate.name)) {
            println("Duplicate entry: $duplicate")

            // write the entry to the file
            ZipInputStream(FileInputStream(zipFile)).use { zipStream ->
                var i = 0
                generateSequence { zipStream.nextEntry }
                    .filter { it.name == duplicate.name }
                    .forEach { _ ->
                        i++
                        val dupeFile = File("$dupesDir/$i-$duplicate.dupe")
                        println("Extracting to: $dupeFile")
                        dupeFile.parentFile.mkdirs()
                        dupeFile.createNewFile()
                        FileOutputStream(dupeFile).use { fileOutputStream ->
                            zipStream.copyTo(fileOutputStream)
                        }
                    }
            }
        }
    }
}


tasks.register("clean") {
    dependsOn("cleanMerged")
}

tasks.register("cleanMerged") {
    doLast {
        mergedDir.deleteRecursively()
    }
}
