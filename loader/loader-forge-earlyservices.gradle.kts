// Shares the ModLauncher/securejarhandler GAME-classloader bridge (ModuleClassLoaderAccess,
// EarlyServiceBridgePlugin) with NeoForge fml4 via :loader-modlauncher-earlyservices - both loaders
// still run the original ModLauncher machinery, so that mechanism is identical; only the SPI types
// (net.minecraftforge.forgespi vs net.neoforged.neoforgespi) differ, and those stay here.
evaluationDependsOn(":loader-modlauncher-earlyservices")

plugins {
	kotlin("jvm")
	id("automodpack.utils")
	id("net.neoforged.moddev.legacyforge")
}

val selectedForgeVersion = loaderVersion()

base {
	archivesName = property("mod.id") as String + "-" + project.name
	version = property("mod_version") as String
	group = property("mod.group") as String
}

legacyForge {
	enable {
		forgeVersion = selectedForgeVersion
		isDisableRecompilation = true
	}
}

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-core"))
	compileOnly(project(":loader-modlauncher-earlyservices"))
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
