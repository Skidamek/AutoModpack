plugins {
	kotlin("jvm")
	id("automodpack.utils")
	id("net.neoforged.moddev.legacyforge")
}

val selectedForgeVersion = loaderVersion()

// Compile-only stubs for cpw.mods.cl/cpw.mods.modlauncher (the third-party ModLauncher/
// securejarhandler libraries, not any Forge/NeoForge-owned API) - pulled in via legacy Forge's
// moddev plugin rather than NeoForge's, purely so the resolved artifacts (Forge republishes them
// as net.minecraftforge:modlauncher/securejarhandler) are Java-17-safe, matching every consumer's
// bytecode floor (see java{} below). The two classes here reference nothing else from that
// dependency and NeoForge's own cpw.mods:modlauncher republishes the identical cpw.mods.* package,
// so this module's compiled output is equally valid raw .class input for both
// :loader-forge-earlyservices' and :loader-neoforge-fml4's shadowJar merges.
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
}

java {
	// Floor of every consumer's minimum runtime (legacy Forge 1.18.2-1.20.1 needs 17; NeoForge fml4
	// needs 21 but happily loads older bytecode).
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
