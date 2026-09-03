import com.diffplug.gradle.spotless.SpotlessExtension
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale

plugins {
	id("dev.kikugie.stonecutter")
	kotlin("jvm") apply false
	id("net.fabricmc.fabric-loom-remap") apply false
	id("net.fabricmc.fabric-loom") apply false
	id("net.neoforged.moddev") apply false
	id("com.gradleup.shadow") apply false
	id("org.moddedmc.wiki.toolkit")
	id("com.diffplug.spotless") apply false
}

repositories {
	mavenCentral()
}

wiki {
	docs.create("automodpack") {
		root = file("docs")
	}
}

stonecutter active "26.2-fabric" // [SC] DO NOT EDIT

fun structuredString(vararg path: String): String =
	stonecutter.properties
		.raw(*path)
		.asPrimitive()
		.content as String

extra["loaderVersions"] =
	mapOf(
		"loader-fabric-15" to structuredString("loader-modules", "fabric-15"),
		"loader-fabric-core" to structuredString("loader-modules", "fabric-15"),
		"loader-fabric-16" to structuredString("loader-modules", "fabric-16"),
		"loader-fabric-latest" to structuredString("fabric", "deps", "fabric-loader"),
		"loader-forge-fml40" to structuredString("1.18.2-forge", "deps", "forge"),
		"loader-forge-fml47" to structuredString("1.20.1-forge", "deps", "forge"),
		"loader-forge-earlyservices" to structuredString("1.20.1-forge", "deps", "forge"),
		"loader-modlauncher-earlyservices" to structuredString("1.20.1-forge", "deps", "forge"),
		"loader-neoforge-fml4" to structuredString("1.21.1-neoforge", "deps", "neoforge"),
		"loader-neoforge-fml10" to structuredString("1.21.10-neoforge", "deps", "neoforge"),
		"loader-neoforge-earlyservices" to structuredString("1.21.10-neoforge", "deps", "neoforge"),
		"loader-neoforge-fml11" to structuredString("26.1-neoforge", "deps", "neoforge"),
	)

stonecutter.parameters {
	val (version, loader) = current.project.split('-', limit = 2)

	constants.match(loader, "fabric", "neoforge", "forge")
	properties.tags(version, loader)

	replacements {
		string(current.parsed >= "1.20.2") {
			replace("ServerboundCustomQueryPacket", "ServerboundCustomQueryAnswerPacket")
			replace(".SystemToastIds.", ".SystemToastId.")
		}

		regex(current.parsed >= "1.21.11") {
			replace("\\bResourceLocation\\b", "Identifier", "\\bIdentifier\\b", "ResourceLocation")
		}

		string(current.parsed >= "1.21.11") {
			replace("net.minecraft.Util", "net.minecraft.util.Util")
			replace(
				"source.hasPermission(3))",
				"source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))",
			)
		}

		string(current.parsed >= "26.2") {
			replace("minecraft.setScreen(", "minecraft.gui.setScreen(")
			replace("minecraft.getToastManager()", "minecraft.gui.toastManager()")
		}
	}
}

val stonecutterJava =
	files(
		providers.provider {
			fileTree("src/main/java") {
				include("**/*.java")
			}.files.filter { it.readText().contains("/*?") }
		},
	)
val miscExtensions = setOf("md", "mdx", "json", "json5", "yml", "yaml", "toml", "xml", "properties", "py", "sh")
val trackedMiscFiles =
	files(
		providers
			.exec {
				commandLine("git", "ls-files", "-z")
			}.standardOutput.asText
			.map { output ->
				output.split('\u0000').filter { path -> path == ".gitignore" || path.substringAfterLast('.', "") in miscExtensions }
			},
	)

// Spotless applies Gradle's base plugin, which makes Stonecutter misclassify this controller
// project as buildable. Apply it after Stonecutter's end-of-evaluation validation instead.
afterEvaluate {
	pluginManager.apply("com.diffplug.spotless")
	extensions.configure<SpotlessExtension> {
		java {
			target("src/main/java/**/*.java", "core/src/**/*.java", "loader/**/src/**/*.java")
			targetExclude("versions/**", stonecutterJava)
			eclipse().configFile("config/format/eclipse-java.xml")
			removeUnusedImports()
			importOrder("java", "javax", "org", "com", "", "pl.skidam")
			trimTrailingWhitespace()
			endWithNewline()
		}

		format("stonecutterJava") {
			target(stonecutterJava)
			leadingSpacesToTabs(4)
			trimTrailingWhitespace()
			endWithNewline()
		}

		kotlinGradle {
			target("**/*.gradle.kts")
			targetExclude("versions/**", ".gradle/**", "**/build/**")
			ktlint()
			trimTrailingWhitespace()
			endWithNewline()
		}

		format("misc") {
			target(trackedMiscFiles)
			targetExclude("versions/**", "autotester/uv.lock")
			trimTrailingWhitespace()
			endWithNewline()
		}
	}
	tasks.named("spotlessApply") { dependsOn("fixFullyQualifiedNames") }
}

val availableTargets = stonecutter.versions.map { it.project }.sorted()
val selectedTargets =
	run {
		val targets =
			providers
				.gradleProperty("automodpack.targets")
				.orNull
				?.split(',')
				?.map(String::trim)
				?.filter(String::isNotEmpty)
				.orEmpty()
				.ifEmpty { availableTargets }
		val duplicates =
			targets
				.groupingBy { it }
				.eachCount()
				.filterValues { it > 1 }
				.keys
		require(duplicates.isEmpty()) { "Duplicate AutoModpack targets: ${duplicates.sorted().joinToString()}" }
		val unknown = targets.toSet() - availableTargets.toSet()
		require(unknown.isEmpty()) { "Unknown AutoModpack targets: ${unknown.sorted().joinToString()}" }
		targets
	}

val releaseMatrixFile = layout.buildDirectory.file("ci/release-matrix.json")

val writeReleaseMatrix =
	tasks.register("writeReleaseMatrix") {
		group = "publishing"
		description = "Writes release metadata for the selected AutoModpack targets."
		inputs.property("targets", selectedTargets)
		outputs.file(releaseMatrixFile)

		doLast {
			val displayName = project.property("mod_name").toString()
			val modName = displayName.lowercase(Locale.ROOT)
			val modVersion = project.property("mod_version").toString()
			val entries =
				selectedTargets.map { target ->
					val targetLine = target.substringBeforeLast('-')
					val loader = target.substringAfterLast('-')
					mapOf(
						"subproject" to target,
						"target" to targetLine,
						"loader" to loader,
						"file" to "$modName-mc$target-$modVersion.jar",
						"mod_name" to displayName,
						"mod_version" to modVersion,
						"publish_versions" to structuredString(targetLine, "publish_versions"),
					)
				}
			val output = releaseMatrixFile.get().asFile
			output.parentFile.mkdirs()
			output.writeText(ObjectMapper().writeValueAsString(mapOf("include" to entries)) + "\n")
			println(output.absolutePath)
		}
	}

tasks.register("buildTargets") {
	group = "build"
	description = "Builds the selected AutoModpack targets and writes their release metadata."
	dependsOn(selectedTargets.map { ":$it:build" })
	dependsOn(writeReleaseMatrix)
}

tasks.register("formatApply") {
	group = "formatting"
	description = "Formats all authored source files."
	dependsOn("spotlessApply")
}

// Fully qualified names (e.g. `pl.skidam.automodpack_core.config.ConfigTools` or `java.util.ArrayList` used inline) bypass the import order and rot silently when packages move, so they are banned: use a proper import instead. Spotless has no built-in "FQN to import" step, so formatApply rewrites the roots that are identical on every Minecraft target (own code plus stdlib) and formatCheck fails on anything left. Loader and Minecraft packages (net.*, com.mojang, ...) and stonecutter template files (`/*?` markers) are intentionally out of scope for the rewrite: an unconditional import can break another target, so those need a human and a stonecutter-guarded import. The masking lexer and rewrite rules live in buildSrc (FqnImports.kt) so they stay unit-testable.
tasks.register("fixFullyQualifiedNames") {
	group = "formatting"
	description = "Rewrites fully qualified names (own code plus stdlib) to imports; runs before spotlessApply."
	doLast {
		var fixed = 0
		for (root in listOf("src/main/java", "core/src", "loader")) {
			fileTree(root) { include("**/*.java") }.visit {
				if (isDirectory) return@visit
				if (path.contains("/versions/")) return@visit
				val text = file.readText()
				if (text.contains("/*?")) return@visit // Stonecutter template: needs a human and a guarded import.
				val rewritten = fixFullyQualifiedNames(text)
				if (rewritten != text) {
					file.writeText(rewritten)
					fixed++
					logger.lifecycle("Replaced fully qualified names in $path")
				}
			}
		}
		if (fixed > 0) logger.lifecycle("Replaced fully qualified names in $fixed files")
	}
}

tasks.register("formatCheck") {
	group = "verification"
	description = "Checks formatting without changing files."
	dependsOn("spotlessCheck")
	dependsOn("checkNoFullyQualifiedNames")
}

tasks.register("checkNoFullyQualifiedNames") {
	group = "verification"
	description = "Fails on fully qualified names in Java sources; use imports instead."
	doLast {
		val violations = mutableListOf<String>()
		for (root in listOf("src/main/java", "core/src", "loader")) {
			fileTree(root) { include("**/*.java") }.visit {
				if (isDirectory) return@visit
				if (path.contains("/versions/")) return@visit
				for (hit in findFullyQualifiedNames(file.readText())) violations.add("$path:$hit")
			}
		}
		if (violations.isNotEmpty()) {
			throw GradleException(
				"Fully qualified names must be replaced with imports (loader/Minecraft ones need a stonecutter-guarded import):\n" + violations.sorted().joinToString("\n"),
			)
		}
	}
}

// The Windows native ships as a committed binary, so nothing else would notice the C sources drifting away from it (the Java side degrades to null and logs at debug). CI installs mingw-w64 and runs this as a hard gate; a rebuild is deterministic, so a byte difference means exactly "sources and DLL disagree".
tasks.register("checkWinNatives") {
	group = "verification"
	description = "Rebuilds the committed Windows native from source and fails when the DLL differs; needs mingw-w64 and JAVA_HOME."
	doLast {
		val dll = file("core/src/main/resources/natives/windows-x86_64/win_file_stat.dll")
		val committed = dll.readBytes()
		val javaHome = providers.environmentVariable("JAVA_HOME").orElse(providers.systemProperty("java.home"))
		val rebuild =
			providers.exec {
				commandLine("bash", "core/src/main/c/rebuild-windows-natives.sh")
				environment("JAVA_HOME", javaHome.get())
			}
		rebuild.result.get().assertNormalExitValue()
		if (!committed.contentEquals(dll.readBytes())) {
			throw GradleException("The committed win_file_stat.dll did not match its sources in core/src/main/c; a rebuilt copy has been left in place - commit it")
		}
	}
}
