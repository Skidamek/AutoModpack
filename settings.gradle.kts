pluginManagement {
	repositories {
		mavenLocal()
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie" }
	}

	plugins {
		val pluginKotlinVersion = providers.gradleProperty("pluginKotlinVersion").get()
		val pluginLoomVersion = providers.gradleProperty("pluginLoomVersion").get()
		val pluginModDevVersion = providers.gradleProperty("pluginModDevVersion").get()
		val pluginShadowVersion = providers.gradleProperty("pluginShadowVersion").get()
		val pluginWikiToolkitVersion = providers.gradleProperty("pluginWikiToolkitVersion").get()
		val pluginSpotlessVersion = providers.gradleProperty("pluginSpotlessVersion").get()
		val pluginFoojayVersion = providers.gradleProperty("pluginFoojayVersion").get()
		val pluginStonecutterVersion = providers.gradleProperty("pluginStonecutterVersion").get()

		id("org.jetbrains.kotlin.jvm") version pluginKotlinVersion
		id("net.fabricmc.fabric-loom-remap") version pluginLoomVersion
		id("net.fabricmc.fabric-loom") version pluginLoomVersion
		id("net.neoforged.moddev") version pluginModDevVersion
		id("com.gradleup.shadow") version pluginShadowVersion
		id("org.moddedmc.wiki.toolkit") version pluginWikiToolkitVersion
		id("com.diffplug.spotless") version pluginSpotlessVersion
		id("org.gradle.toolchains.foojay-resolver-convention") version pluginFoojayVersion
		id("dev.kikugie.stonecutter") version pluginStonecutterVersion
	}
}

plugins {
	// For some reason, this plugin is crucial - do not remove
	id("org.gradle.toolchains.foojay-resolver-convention")
	id("dev.kikugie.stonecutter")
}

include(":core")
if (providers.gradleProperty("automodpack.autotest").isPresent) {
	include(":autotest-fixtures")
	project(":autotest-fixtures").projectDir = file("autotester/fixtures")
}

fun getProperty(key: String): String? = settings.extra[key] as? String

val coreModules = getProperty("core_modules")!!.split(',').map { it.trim() }

coreModules.forEach { module ->
	include(":loader-$module")

	val project = project(":loader-$module")
	val dir = module.replace("-", "/")
	project.projectDir = file("loader/$dir")
	when (module) {
		"core" -> project.buildFileName = "../loader-core.gradle.kts"
		"fabric-core" -> project.buildFileName = "../../loader-fabric-core.gradle.kts"
		"fabric-15", "fabric-16" -> project.buildFileName = "../../loader-fabric.gradle.kts"
		"forge-fml40", "forge-fml47" -> project.buildFileName = "../../loader-forge.gradle.kts"
		"forge-earlyservices" -> project.buildFileName = "../../loader-forge-earlyservices.gradle.kts"
		"neoforge-earlyservices" -> project.buildFileName = "../../loader-neoforge-earlyservices.gradle.kts"
		"modlauncher-earlyservices" -> project.buildFileName = "../../loader-modlauncher-earlyservices.gradle.kts"
		"neoforge-fml4", "neoforge-fml10", "neoforge-fml11" -> project.buildFileName = "../../loader-neoforge.gradle.kts"
	}
}

stonecutter {
	create(rootProject) {
		fun match(
			version: String,
			vararg loaders: String,
		) = loaders
			.forEach { version("$version-$it", version).buildscript = "build.$it.gradle.kts" }

		// Configure your targets here!
		match("26.2", "fabric", "neoforge")
		match("26.1", "fabric", "neoforge")
		match("1.21.11", "fabric", "neoforge")
		match("1.21.10", "fabric", "neoforge")
		match("1.21.8", "fabric", "neoforge")
		match("1.21.5", "fabric", "neoforge")
		match("1.21.4", "fabric", "neoforge")
		match("1.21.1", "fabric", "neoforge")
		match("1.20.1", "fabric", "forge")
		match("1.19.2", "fabric", "forge")
		match("1.18.2", "fabric", "forge")
	}
}

rootProject.name = "AutoModpack"
