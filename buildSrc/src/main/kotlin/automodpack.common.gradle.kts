import dev.luna5ama.jaroptimizer.OptimizeJarTask
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

plugins {
	idea
	id("dev.luna5ama.jar-optimizer")
}

val automodpackBuildMode =
	providers
		.gradleProperty("automodpack.autotest")
		.map { "autotest" }
		.orElse("release")
val isAutotestBuild = automodpackBuildMode.get() == "autotest"

// Test-only instrumentation (AutoTestBridge + its dev mixins) must never ship in
// release jars. Exclude it from the source set for non-autotest builds, exclude
// stale compiled outputs from release archives, and strip the dev mixins from the
// config so Mixin doesn't look for the absent classes.
if (!isAutotestBuild) {
	plugins.withId("java") {
		the<SourceSetContainer>().named("main").configure {
			java.exclude(
				"pl/skidam/automodpack/client/autotest/**",
				"pl/skidam/automodpack/mixin/dev/**",
			)
		}
	}
}

// The source-set exclusion above changes the effective inputs of these tasks, but
// the build mode itself must also be an input. Otherwise Gradle can reuse a task
// result from the other mode, especially when a target is built through Stonecutter.
tasks.withType<JavaCompile>().configureEach {
	inputs.property("automodpackBuildMode", automodpackBuildMode)
}

tasks.withType<Jar>().configureEach {
	inputs.property("automodpackBuildMode", automodpackBuildMode)
	if (!isAutotestBuild) {
		exclude(
			"pl/skidam/automodpack/client/autotest/**",
			"pl/skidam/automodpack/mixin/dev/**",
		)
	}
}

tasks.configureEach {
	if (name == "remapJar" || name == "shadowJar") {
		inputs.property("automodpackBuildMode", automodpackBuildMode)
	}
}

tasks.named("processResources").configure {
	inputs.property("automodpackBuildMode", automodpackBuildMode)
	if (!isAutotestBuild) {
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
	if (isAutotestBuild) {
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
		this.rootProjectPath.set(project.rootProject.projectDir.absolutePath)
		this.loaderModuleName.set(getLoaderModuleName(project.name))
		this.buildMode.set(automodpackBuildMode)
		this.buildDirectory.set(layout.buildDirectory)
		this.mergedJar.set(
			layout.buildDirectory
				.file(
					provider {
						"merged/" +
							getMergedJarPath(
								layout.buildDirectory
									.dir("libs")
									.get()
									.asFile,
							).name
					},
				),
		)

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

		// Compute the actual hash of the content of all input jars and the build mode.
		// We use a provider so this is calculated just before task execution, ensuring files exist.
		this.inputHash.set(
			provider {
				val filesToHash = files(filesToHash)
				val digest = MessageDigest.getInstance("MD5") // Using MD5 just for speed
				digest.update(automodpackBuildMode.get().toByteArray(StandardCharsets.UTF_8))

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
	}

val optimizedMergedJar =
	tasks.register<OptimizeJarTask>("optimizeMergedJar") {
		dependsOn(mergeJarTask)
		jarFile.set(mergeJarTask.flatMap { it.mergedJar })
		keeps.add("pl.skidam")
		destinationDirectory.set(rootProject.layout.projectDirectory.dir("merged"))
		archiveFileName.set(
			provider {
				getMergedJarPath(
					layout.buildDirectory
						.dir("libs")
						.get()
						.asFile,
				).name
			},
		)
	}
optimizedMergedJar.configure {
	inputs.property("automodpackBuildMode", automodpackBuildMode)
}

val auditMergedJarTask =
	tasks.register<MergedJarAuditTask>("auditMergedJar") {
		mergedJar.set(optimizedMergedJar.flatMap { it.archiveFile })
		inputs.property("automodpackBuildMode", automodpackBuildMode)
		maxJarBytes.set(7L * 1024 * 1024 / 2)
		enforceReleaseSizeBudget.set(!isAutotestBuild)
		maxMusicBytes.set(64L * 1024)
	}

mergeJarTask.configure {
	finalizedBy(optimizedMergedJar)
}

optimizedMergedJar.configure {
	finalizedBy(auditMergedJarTask)
}
