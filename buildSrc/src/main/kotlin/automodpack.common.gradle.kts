import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    idea
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

repositories {
    fun strictMaven(url: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://maven.parchmentmc.org", "org.parchmentmc.data")
    maven("https://maven.fabricmc.net/")
}

tasks.named<ProcessResources>("processResources") {
    fun prop(name: String) = project.property(name) as String

    val props = HashMap<String, String>().apply {
        this["version"] = prop("mod.version")
        this["minecraft"] = prop("meta.minecraft")
        this["id"] = prop("mod.id")
        this["name"] = prop("mod.name")
        this["description"] = prop("mod.description")
    }

    filesMatching(listOf("pack.mcmeta", "fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        expand(props)
    }
}

tasks.named("build") {
    // We need to build the loader modules first, so that we can merge the jars later
    dependsOn(":core:build", ":loader-core:build")
    when {
        project.name.contains("fabric") -> {
            dependsOn(":loader-fabric-core:build", ":loader-fabric-15:build", ":loader-fabric-16:build")
        }
        project.name.contains("neoforge") -> {
            dependsOn(":loader-neoforge-fml2:build", ":loader-neoforge-fml4:build")
        }
        project.name.contains("forge") -> {
            dependsOn(":loader-forge-fml40:build", ":loader-forge-fml47:build")
        }
    }

    finalizedBy(tasks.named("mergeJar"))
}

val mergedDir = File("${rootProject.projectDir}/merged")

tasks.named("clean") {
    finalizedBy("cleanMerged")
}

tasks.register("cleanMerged") {
    doLast {
        mergedDir.deleteRecursively()
    }
}

// TODO make it faster
// If something goes wrong, try to run the "clean" task
tasks.register("mergeJar") {
    doLast {
        mergedDir.mkdirs()
        val buildDirLibs = project.layout.buildDirectory.dir("libs").get().asFile
        val jarToMerge = buildDirLibs.listFiles()
            ?.firstOrNull { file -> file.isFile && !file.name.endsWith("-sources.jar") && file.name.endsWith(".jar") }
            ?: error("No jar found to merge in build/libs directory! ${buildDirLibs.absolutePath}")

        val time = System.currentTimeMillis()
        println("Found ${jarToMerge} merge. Merging...")

        val minecraftVersionStr = jarToMerge.name.substringAfterLast("-mc").substringBefore("-")
        if (minecraftVersionStr.isEmpty()) {
            error("Could not identify Minecraft version in the jar name: ${jarToMerge.name}")
        }

        var loaderModule = ""
        if (jarToMerge.name.contains("fabric")) {
            loaderModule = "fabric-core"
        } else if (jarToMerge.name.contains("neoforge")) {
            loaderModule = when (minecraftVersionStr) {
                "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.19.2", "1.18.2" -> "neoforge-fml2"
                else -> "neoforge-fml4"
            }
        } else if (jarToMerge.name.contains("forge")) {
            loaderModule = if (minecraftVersionStr == "1.18.2") {
                "forge-fml40"
            } else {
                "forge-fml47"
            }
        }

        val loaderModuleProject = rootProject.findProject("loader-$loaderModule")
            ?: error("Loader module '$loaderModule' not found in the project.")

        val loaderFile = loaderModuleProject.layout.buildDirectory.dir("libs").get().asFile.listFiles()
            ?.single { it.isFile && !it.name.endsWith("-sources.jar") && it.name.endsWith(".jar") }
            ?: error("No loader jar found in loader/$loaderModule/build/libs directory! Make sure to build the loader module first.")

        val finalJar = File("$mergedDir/${jarToMerge.name}")
        loaderFile.copyTo(finalJar, overwrite = true)
        appendFileToZip(finalJar, jarToMerge, "automodpack-mod.jar")

        println("Merged: ${jarToMerge.name} into: ${finalJar.name} from: ${loaderFile.name} Took: ${System.currentTimeMillis() - time}ms")
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
                    } catch (_: Exception) {
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
