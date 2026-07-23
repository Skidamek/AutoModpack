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
}

val availableTargets = stonecutter.versions.map { it.project }.sorted()
val requestedTargets =
	providers
		.gradleProperty("automodpack.targets")
		.orNull
		?.split(',')
		?.map(String::trim)
		?.filter(String::isNotEmpty)
		.orEmpty()
val selectedTargets = requestedTargets.ifEmpty { availableTargets }
val duplicateTargets =
	selectedTargets
		.groupingBy { it }
		.eachCount()
		.filterValues { it > 1 }
		.keys
require(duplicateTargets.isEmpty()) { "Duplicate AutoModpack targets: ${duplicateTargets.sorted().joinToString()}" }
val unknownTargets = selectedTargets.toSet() - availableTargets.toSet()
require(unknownTargets.isEmpty()) { "Unknown AutoModpack targets: ${unknownTargets.sorted().joinToString()}" }

val releaseMatrixFile = layout.buildDirectory.file("ci/release-matrix.json")

tasks.register("buildTargets") {
	group = "build"
	description = "Builds all targets or those selected with -Pautomodpack.targets."
	dependsOn(selectedTargets.map { ":$it:build" })
	if (providers.gradleProperty("automodpack.autotest").isPresent) {
		dependsOn(":autotest-fixtures:build")
	}
}

tasks.register("writeReleaseMatrix") {
	group = "publishing"
	description = "Writes release metadata for all targets or those selected with -Pautomodpack.targets."
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

if (providers.gradleProperty("automodpack.autotest").isPresent) {
	tasks.matching { it.name == "build" }.configureEach {
		dependsOn(":autotest-fixtures:build")
	}
}

tasks.register("formatApply") {
	group = "formatting"
	description = "Formats all authored source files."
	dependsOn("spotlessApply")
}

tasks.register("formatCheck") {
	group = "verification"
	description = "Checks formatting without changing files."
	dependsOn("spotlessCheck")
}
