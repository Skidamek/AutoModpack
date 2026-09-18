import dev.luna5ama.jaroptimizer.OptimizeJarTask
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

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
	if (isAutotestBuild) {
		dependsOn(":autotest-fixtures:build")
	}
	finalizedBy(tasks.named("optimizeModJar"))
}

val libsDirectory = layout.buildDirectory.dir("libs")
val optimizedLibsDirectory = layout.buildDirectory.dir("libs-optimized")

// A per-target build ends at the optimized impl jar; assembling the one jar out of every target's
// output is the root project's oneJar task, and merged/ is that one jar's output directory only.
val modJarFileName =
	extensions
		.getByType(BasePluginExtension::class.java)
		.archivesName
		.zip(providers.provider { version.toString() }) { name, v -> "$name-$v.jar" }
val optimizeModJar =
	tasks.register<OptimizeJarTask>("optimizeModJar") {
		// Resolved when the task joins the graph, after every plugin has applied. The chain's last
		// writer of the jar differs per loader: reobfJar (forge/neoforge), remapJar (fabric), jar (unobf).
		dependsOn(
			providers.provider {
				tasks.findByName("reobfJar") ?: tasks.findByName("remapJar") ?: tasks.named("jar").get()
			},
		)
		// The input is the producer's standard archive path, never a directory scan: on a clean CI
		// build/libs is still empty when the task graph is built, and a scan there fails the graph
		// before anything has produced the jar.
		jarFile.set(modJarFileName.flatMap { fileName -> libsDirectory.map { dir -> dir.file(fileName) } })
		keeps.add("pl.skidam")
		destinationDirectory.set(optimizedLibsDirectory)
		archiveFileName.set(modJarFileName.map { it.removeSuffix(".jar") + "-optimized.jar" })
	}
optimizeModJar.configure {
	inputs.property("automodpackBuildMode", automodpackBuildMode)
}
