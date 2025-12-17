plugins {
    idea
    id("dev.luna5ama.jar-optimizer")
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

repositories {
    maven("https://maven.fabricmc.net/")
}

tasks.named("build") {
    dependsOn(":core:build", ":loader-core:build")

    val loaderModule = getLoaderModuleName(project.name)
    dependsOn(":loader-" + loaderModule + ":build")
    when (loaderModule) { // special case
        "fabric-core" -> {
            dependsOn(":loader-fabric-15:build", ":loader-fabric-16:build")
        }
    }

    finalizedBy(tasks.named("mergeJar"))
}

val mergedDirPath = rootProject.projectDir.absolutePath + "/merged"

tasks.named("clean") {
    finalizedBy("cleanMerged")
}

tasks.register("cleanMerged") {
    val mergedDir = mergedDirPath
    doLast {
        File(mergedDir).deleteRecursively()
    }
}

val mergeJarTask = tasks.register<MergeJarTask>("mergeJar") {
    this.mergedDirPath.set(project.rootProject.projectDir.absolutePath + "/merged")
    this.rootProjectPath.set(project.rootProject.projectDir.absolutePath)
    this.libsPath.set(project.rootProject.projectDir.absolutePath + "/libs")
    this.buildDirectory.set(layout.buildDirectory)
    outputJar.set(layout.buildDirectory.file("merged-jar-path.txt"))
    finalizedBy(tasks.named("optimizeMergedJar"))
}

val mergedJarWrapper = tasks.register<Jar>("mergedJarWrapper") {
    dependsOn(mergeJarTask)
    enabled = false
    destinationDirectory.set(File(mergedDirPath))
}

val optimizedMergedJar = jarOptimizer.register(mergedJarWrapper, "pl.skidam")

tasks.register("optimizeMergedJar") {
    dependsOn(optimizedMergedJar)

    val outputJarFile = mergeJarTask.flatMap { it.outputJar }
    val optimizedFileProvider = optimizedMergedJar.flatMap { it.archiveFile }

    inputs.file(outputJarFile)

    doLast {
        val jarPath = outputJarFile.get().asFile.readText()
        val jarFile = File(jarPath)
        if (!jarFile.exists()) {
            println("Merged jar not found: ${jarFile.absolutePath}")
            return@doLast
        }

        val time = System.currentTimeMillis()
        val optimizedFile = optimizedFileProvider.get().asFile

        if (optimizedFile.exists() && optimizedFile.length() > 0) {
            jarFile.delete()
            optimizedFile.renameTo(jarFile)
            println("Optimized ${jarFile.name} - Took: ${System.currentTimeMillis() - time}ms")
        }
    }
}
