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

fun structuredProtocol(version: String): Int =
	stonecutter.properties
		.raw("protocols", version)
		.asPrimitive()
		.content
		.toString()
		.toInt()

// What each loader module compiles against: the oldest platform API floor that has everything the
// module calls, so newer-API slips are compile errors here instead of LinkageErrors on a user's
// install. loader-fabric-latest is not a module, it is a pin alias for the autotest fixtures, which
// deliberately compile against the newest loader.
extra["loaderVersions"] =
	mapOf(
		"loader-fabric-core" to structuredString("loader-modules", "fabric"),
		"loader-fabric-latest" to structuredString("fabric", "deps", "fabric-loader"),
		"loader-forge-fml40" to structuredString("1.18.2-forge", "deps", "forge"),
		"loader-forge-fml47" to structuredString("1.20.1-forge", "deps", "forge"),
		"loader-forge-earlyservices" to structuredString("1.20.1-forge", "deps", "forge"),
		"loader-modlauncher-earlyservices" to structuredString("1.20.1-forge", "deps", "forge"),
		"loader-neoforge-shared" to structuredString("1.21.1-neoforge", "deps", "neoforge"),
		"loader-neoforge-fml4" to structuredString("1.21.1-neoforge", "deps", "neoforge"),
		"loader-neoforge-earlyservices" to structuredString("1.21.10-neoforge", "deps", "neoforge"),
	)

stonecutter.parameters {
	val (version, loader) = current.project.split('-', limit = 2)

	constants.match(loader, "fabric", "neoforge", "forge")
	properties.tags(version, loader)

	replacements {
		string(current.parsed < "1.20.2") {
			replace(".SystemToastId.", ".SystemToastIds.")
		}
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

		// KeyEvent renamed its record component in 26.3 (GLFW scancode -> SDL keycode)
		string(current.parsed >= "26.3") {
			replace(".scancode()", ".keycode()")
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
			// One jar publishes once per loader that actually has an impl among the selected targets;
			// a partial selection must never advertise a loader whose manifest would crash the boot.
			// The game-version list is the distinct union across the selected targets, in stable order;
			// tildes collapse to their base because Modrinth's game-version lists enumerate real
			// releases only - the tilde rides in the jar, not in the listing.
			val publishVersions =
				selectedTargets
					.map { target -> structuredString(target.substringBeforeLast('-'), "publish_versions") }
					.flatMap { it.split('\n') }
					.map { it.removePrefix("~") }
					.filter { it.isNotBlank() }
					.distinct()
					.joinToString(",")
			val entries =
				listOf(
					mapOf(
						"subproject" to "one-jar",
						"target" to "universal",
						"loader" to
							selectedTargets
								.map { it.substringAfterLast('-') }
								.distinct()
								.joinToString(","),
						"name" to "$displayName $modVersion",
						"file" to "$modName-$modVersion.jar",
						"mod_name" to displayName,
						"mod_version" to modVersion,
						"publish_versions" to publishVersions,
					),
				)
			val output = releaseMatrixFile.get().asFile
			output.parentFile.mkdirs()
			output.writeText(ObjectMapper().writeValueAsString(mapOf("include" to entries)) + "\n")
			println(output.absolutePath)
		}
	}

val automodpackBuildMode =
	providers
		.gradleProperty("automodpack.autotest")
		.map { "autotest" }
		.orElse("release")
val modVersion = project.property("mod_version").toString()
val modName = project.property("mod_name").toString().lowercase(Locale.ROOT)
val modId = project.property("mod.id").toString()

// Convention paths shared with the producing tasks; if a convention drifts, oneJar fails loudly on
// the missing file instead of packing stale bytes.
fun optimizedImplJar(target: String) = layout.projectDirectory.file("versions/$target/build/libs-optimized/$modName-mc$target-$modVersion-optimized.jar")

fun optimizedOuterJar() = layout.projectDirectory.file("loader/universal/build/libs/$modId-loader-universal-$modVersion-optimized.jar")

val oneJarTask =
	tasks.register<OneJarTask>("oneJar") {
		group = "build"
		description = "Packs the optimized universal outer and every selected target's optimized impl jar into the one published jar."
		buildMode.set(automodpackBuildMode)
		zstdVersion.set(versionProperty("versionZstdJni"))
		outerJar.set(optimizedOuterJar())
		implJars.set(providers.provider { selectedTargets.associateWith { target -> optimizedImplJar(target).asFile.absolutePath } })
		implJarFiles.setFrom(selectedTargets.map { optimizedImplJar(it) })
		// The Minecraft versions each target covers (its group's publish_versions, exact or ~
		// dotted-prefix): the manifest's version-resolution source of truth, so a live 26.1.2 client
		// resolves to the 26.1-fabric impl through its ~26.1 cover.
		implVersions.set(
			providers.provider {
				selectedTargets.associateWith { target -> structuredString(target.substringBeforeLast('-'), "publish_versions").split('\n').filter(String::isNotBlank) }
			},
		)
		// Vanilla protocol per cover (the [protocols] table, keys mirroring the covers verbatim),
		// packed as mc-protocols.json for the holepunch handshake: a cover without a protocol fails
		// the build here instead of a player's connect screen.
		protocols.set(
			providers.provider {
				implVersions
					.get()
					.values
					.flatten()
					.distinct()
					.associateWith(::structuredProtocol)
			},
		)
		oneJar.set(layout.projectDirectory.file("merged/$modName-$modVersion.jar"))
		dependsOn(":loader-universal:optimizeUniversalJar")
		dependsOn(selectedTargets.map { ":$it:optimizeModJar" })
		if (automodpackBuildMode.get() == "autotest") {
			dependsOn(":autotest-fixtures:build")
		}
	}

val auditOneJarTask =
	tasks.register<OneJarAuditTask>("auditOneJar") {
		group = "verification"
		description = "Audits the packed one jar: size budget, manifest ids, protocol table, STORE entries, assets, no nested jarjar."
		oneJar.set(oneJarTask.flatMap { it.oneJar })
		expectedIds.set(selectedTargets.sorted())
		expectedProtocols.set(oneJarTask.flatMap { it.protocols })
		// The size tripwire; the measured receipts that justify the budget live on OneJarAuditTask.
		maxJarBytes.set(5L * 1024 * 1024)
		enforceReleaseSizeBudget.set(automodpackBuildMode.map { it != "autotest" })
		// The waiting loop is the transcribed note-block bossa nova, 550322 bytes as packaged; 1 MiB leaves it headroom and still trips on accidental full songs.
		maxMusicBytes.set(1024L * 1024)
	}

oneJarTask.configure {
	finalizedBy(auditOneJarTask)
}

// `build` is the one verb: it takes the selected targets (-Pautomodpack.targets, all of them by
// default) from impl jars to the audited one jar in merged/. Release metadata stays out of the
// everyday build; the release workflow asks writeReleaseMatrix for it explicitly.
afterEvaluate {
	tasks.named("build") {
		dependsOn(oneJarTask)
	}
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

// The Windows native ships as a committed binary plus a receipt (core/src/main/c/natives-receipt.txt) naming the exact sources and DLL it was built from. Cross-toolchain builds are not byte-identical, so this check validates the receipt with no mingw needed; CI additionally compiles the C with mingw to prove the sources build.
tasks.register("checkWinNatives") {
	group = "verification"
	description = "Fails when the committed Windows native is stale relative to its receipt in core/src/main/c."
	doLast {
		val receipt = file("core/src/main/c/natives-receipt.txt")
		if (!receipt.isFile) {
			throw GradleException(
				"Missing $receipt; run core/src/main/c/rebuild-windows-natives.sh (needs mingw-w64 and JAVA_HOME) and commit the refreshed DLL and receipt",
			)
		}
		val digest = java.security.MessageDigest.getInstance("SHA-256")

		fun sha256(file: File): String = digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
		val violations = mutableListOf<String>()
		for (line in receipt.readLines()) {
			if (line.isBlank() || line.startsWith("#")) continue
			val parts = line.split(Regex("\\s+"), limit = 2)
			if (parts.size != 2) {
				violations.add("Unreadable receipt line: $line")
				continue
			}
			val named = file(parts[1].removePrefix("*"))
			if (!named.isFile) {
				violations.add("The receipt names a missing file: ${parts[1]}")
				continue
			}
			if (sha256(named) != parts[0]) violations.add("${parts[1]} does not match the receipt; the committed native is stale")
		}
		if (violations.isNotEmpty()) {
			throw GradleException(
				"The committed Windows native is stale relative to core/src/main/c/natives-receipt.txt:\n" + violations.sorted().joinToString("\n") +
					"\nRun core/src/main/c/rebuild-windows-natives.sh (needs mingw-w64 and JAVA_HOME) and commit the refreshed DLL and receipt",
			)
		}
	}
}
