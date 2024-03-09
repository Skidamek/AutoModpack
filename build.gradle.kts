import com.replaymod.gradle.preprocess.Node
import groovy.json.JsonSlurper

plugins {
    id("com.github.hierynomus.license") version "0.16.1" apply false
    id("dev.architectury.loom") version "1.5-SNAPSHOT" apply false

    // https://github.com/ReplayMod/preprocessor
    // https://github.com/Fallen-Breath/preprocessor
    id("com.replaymod.preprocess") version "ce1aeb2be0"

    id("com.github.johnrengelman.shadow") version "7.1.2" apply false
}

val settingsFile = File("$rootDir/settings.json")
val settings = JsonSlurper().parseText(settingsFile.readText()) as Map<String, List<String>>
val loaderMcMap: Map<String, List<String>> = settings["versions"]!!.map { it.split("-").last() to it.split("-").first() }.groupBy({ it.first }, { it.second })
val mcVersions: List<String> = loaderMcMap.values.flatten().distinct()
val loaders: List<String> = loaderMcMap.keys.toList()
val mergedDir = File("${rootProject.projectDir}/merged").apply { mkdirs() }

preprocess {
    // first version of fabric in settings.json is the main
    val mappings = "yarn"
    val mainLoader = "fabric"
    val mainChain = mutableMapOf<Int, Node>()
    var nodeBefore: Node? = null
    for (version in settings["versions"]!!) {
        val loader = version.split("-").last()
        val mcVer = version.split("-").first().replace(".", "").toInt()

        val node = createNode(version, mcVer, mappings)
        // new fabric link with fabric before
        // not fabric link with the same version of mc fabric
        if (loader == mainLoader && nodeBefore != null) {
            nodeBefore.link(node, null)
        } else {
            mainChain[mcVer]?.link(node, null)
        }

        if (loader == mainLoader) {
            mainChain[mcVer] = node
        }

        nodeBefore = node
    }
}

// required to properly merge jars
tasks.register("build") {
    subprojects.forEach {
        dependsOn("${it.name}:build")
    }
}