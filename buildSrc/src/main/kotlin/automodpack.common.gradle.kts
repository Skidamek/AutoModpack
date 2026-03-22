import java.security.MessageDigest
import java.math.BigInteger
import org.gradle.api.plugins.BasePluginExtension

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

tasks.register("prepareKotlinBuildScriptModel") {
    // IntelliJ may request this task against individual Stonecutter variant projects.
    // The real model task exists on the root build; leaf projects only need a no-op.
}

tasks.named("build") {
    val taksToRun = mutableListOf<String>()
    for (module in getAllDependentLoaderModules(project.name)) {
        taksToRun.add(":$module:build")
    }
    dependsOn(taksToRun)
    finalizedBy(tasks.named("mergeJar"))
}

val mergedRootDir = rootProject.projectDir.resolve("merged")
val projectMergedStagingDir = mergedRootDir.resolve(".staging").resolve(project.name)
val publishedMergedJarFileName = providers.provider {
    val baseExtension = extensions.getByType<BasePluginExtension>()
    "${baseExtension.archivesName.get()}-${project.version}.jar"
}
val publishedMergedJarPath = publishedMergedJarFileName.map { mergedRootDir.resolve(it).absolutePath }

tasks.named("clean") {
    finalizedBy("cleanMerged")
}

tasks.register("cleanMerged") {
    val mergedDir = projectMergedStagingDir
    doLast {
        mergedDir.deleteRecursively()
        File(publishedMergedJarPath.get()).delete()
    }
}

val mergeJarTask = tasks.register<MergeJarTask>("mergeJar") {
    this.mergedDirPath.set(projectMergedStagingDir.absolutePath)
    this.rootProjectPath.set(project.rootProject.projectDir.absolutePath)
    this.libsPath.set(project.rootProject.projectDir.absolutePath + "/libs")
    this.buildDirectory.set(layout.buildDirectory)
    this.outputJar.set(layout.buildDirectory.file("merged-jar-path.txt"))

    val filesToHash = mutableListOf<Any>()
    for (module in getAllDependentLoaderModules(project.name)) {
        val moduleBuildDir = rootProject.project(module).layout.buildDirectory
        filesToHash.add(fileTree(moduleBuildDir) {
            include("libs/*.jar")
            exclude("libs/*-sources.jar")
        })
    }
    filesToHash.add(fileTree(rootProject.projectDir.resolve("libs")) {
        include("zstd-jni-*.jar")
        include("iroh-pipes-jni-*.jar")
    })

    // Compute the actual hash of the content of all input jars.
    // We use a provider so this is calculated just before task execution, ensuring files exist.
    this.inputHash.set(provider {
        val outputFile = File(this@register.mergedDirPath.get(), getMergedJarPath(buildDirectory.get().dir("libs").asFile).name)
        if (!outputFile.exists()) { // Return a random hash if the output file doesn't exist yet. We need to have something.
            return@provider BigInteger(1, MessageDigest.getInstance("MD5").digest(System.currentTimeMillis().toString().toByteArray())).toString(16)
        }
        val filesToHash = files(filesToHash)
        val digest = MessageDigest.getInstance("MD5") // Using MD5 just for speed

        filesToHash.files.sortedBy { it.name }.forEach { file ->
            if (file.exists()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        digest.update(buffer, 0, bytesRead)
                        bytesRead = input.read(buffer)
                    }
                }
            }
        }
        BigInteger(1, digest.digest()).toString(16)
    })

    finalizedBy(tasks.named("optimizeMergedJar"))
}

val mergedJarWrapper = tasks.register<Jar>("mergedJarWrapper") {
    dependsOn(mergeJarTask)
    enabled = false
    destinationDirectory.set(projectMergedStagingDir)
    archiveFileName.set(publishedMergedJarFileName)
}

val optimizedMergedJar = jarOptimizer.register(mergedJarWrapper, "pl.skidam", "amp_libs.org.bouncycastle.jcajce.provider.asymmetric")

tasks.register("optimizeMergedJar") {
    dependsOn(optimizedMergedJar)

    val outputJarFile = mergeJarTask.flatMap { it.outputJar }
    val optimizedFileProvider = optimizedMergedJar.flatMap { it.archiveFile }
    val publishedJarPathProvider = publishedMergedJarPath

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
        }

        mergedRootDir.mkdirs()
        val publishedJar = File(publishedJarPathProvider.get())
        jarFile.copyTo(publishedJar, overwrite = true)
        println("Optimized ${jarFile.name} - Took: ${System.currentTimeMillis() - time}ms")
    }
}
