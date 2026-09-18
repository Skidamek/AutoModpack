
// NeoForge fml4 (1.21.1) is the last ModLauncher-era NeoForge generation: it shares
// :loader-modlauncher-earlyservices with legacy Forge - both still run the original
// ModLauncher/securejarhandler machinery, so the GAME-classloader bridge mechanics are identical;
// the SPI-specific EarlyServiceLayer/EarlyServiceBootstrapper stay in fml4's own sources. The replay
// machinery shared with the flat-classloader generations lives in :loader-neoforge-shared. The
// 21.10+ generations use :loader-neoforge-earlyservices instead.

// Forces these to configure before us: their sourceSets are referenced lazily by :loader-universal,
// which - configuration-on-demand does not always reach in time otherwise (surfaces when this
// project is built standalone, e.g. `gradlew :loader-neoforge-fml4:build`, rather than as part of a
// full build).
evaluationDependsOn(":core")
evaluationDependsOn(":loader-modlauncher-earlyservices")
evaluationDependsOn(":loader-neoforge-shared")

plugins {
	kotlin("jvm")
	id("automodpack.loader")
	id("automodpack.neoforge-toolchain")
	id("net.neoforged.moddev")
}

val neoForgeVersion = loaderVersion()
val gsonVersion = versionProperty("versionLoaderGson")
val log4jVersion = versionProperty("versionLoaderPlatformLog4j")

neoForge {
	enable {
		version = neoForgeVersion
		isDisableRecompilation = true
	}
}

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-modlauncher-earlyservices"))
	compileOnly(project(":loader-neoforge-shared"))

	// External provided deps to compile this
	compileOnly("com.google.code.gson:gson:$gsonVersion")
	compileOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
}

java {
	withSourcesJar()
}
