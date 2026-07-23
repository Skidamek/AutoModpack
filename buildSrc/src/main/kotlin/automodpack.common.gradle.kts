import org.gradle.api.tasks.SourceSetContainer
import java.math.BigInteger
import java.security.MessageDigest

plugins {
	idea
	id("dev.luna5ama.jar-optimizer")
}

// Test-only instrumentation (AutoTestBridge + its dev mixins) must never ship in
// release jars. Exclude it from the source set for non-autotest builds and strip
// the dev mixins from the config so Mixin doesn't look for the absent classes.
if (!project.hasProperty("automodpack.autotest")) {
	plugins.withId("java") {
		the<SourceSetContainer>().named("main").configure {
			java.exclude(
				"pl/skidam/automodpack/client/autotest/**",
				"pl/skidam/automodpack/mixin/dev/**",
			)
		}
	}
	tasks.named("processResources").configure {
		doLast {
			val cfg =
				layout.buildDirectory
					.file("resources/main/automodpack-main.mixins.json")
					.get()
					.asFile
			if (cfg.exists()) {
				cfg.writeText(cfg.readText().replace(Regex(",\\s*\"dev\\.[^\"]*\""), ""))
			}
		}
	}
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
	val taksToRun = mutableListOf<String>()
	for (module in getAllDependentLoaderModules(project.name)) {
		taksToRun.add(":$module:build")
	}
	dependsOn(taksToRun)
	if (project.hasProperty("automodpack.autotest")) {
		dependsOn(":autotest-fixtures:build")
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

val mergeJarTask =
	tasks.register<MergeJarTask>("mergeJar") {
		this.mergedDirPath.set(project.rootProject.projectDir.absolutePath + "/merged")
		this.rootProjectPath.set(project.rootProject.projectDir.absolutePath)
		this.libsPath.set(project.rootProject.projectDir.absolutePath + "/libs")
		this.loaderModuleName.set(getLoaderModuleName(project.name))
		this.zstdVersion.set(versionProperty("versionZstdJni"))
		this.buildDirectory.set(layout.buildDirectory)
		this.outputJar.set(layout.buildDirectory.file("merged-jar-path.txt"))

		// Hash the shadow jars where they exist: they're what this task actually merges and
		// the only outputs that change when a shared subproject (core, loader-core, an
		// earlyservices module) changes. The plain `jar` outputs don't contain those classes.
		val filesToHash = mutableListOf<Any>()
		(tasks.findByName("shadowJar") ?: tasks.findByName("jar"))?.let { projectJar ->
			dependsOn(projectJar)
			filesToHash.add(projectJar)
		}
		for (module in getAllDependentLoaderModules(project.name)) {
			val moduleTasks = rootProject.project(module).tasks
			(moduleTasks.findByName("shadowJar") ?: moduleTasks.findByName("jar"))?.let { modLoaderJar ->
				filesToHash.add(modLoaderJar)
			}
		}

		// Compute the actual hash of the content of all input jars.
		// We use a provider so this is calculated just before task execution, ensuring files exist.
		this.inputHash.set(
			provider {
				val outputFile = File(mergedDirPath.get(), getMergedJarPath(buildDirectory.get().dir("libs").asFile).name)
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
			},
		)

		finalizedBy(tasks.named("optimizeMergedJar"))
	}

val mergedJarWrapper =
	tasks.register<Jar>("mergedJarWrapper") {
		dependsOn(mergeJarTask)
		enabled = false
		destinationDirectory.set(File(mergedDirPath))
	}

val optimizedMergedJar = jarOptimizer.register(mergedJarWrapper, "pl.skidam", "amp_libs.org.bouncycastle.jcajce.provider.asymmetric")

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
