
// The flat-classloader NeoForge 21.10+ generation. Its replay machinery is shared with the
// ModLauncher-era fml4 generation through :loader-neoforge-shared, compiled against the fml4 pin
// (the oldest consumer) so both generations link it safely.

evaluationDependsOn(":core")
evaluationDependsOn(":loader-neoforge-shared")

plugins {
	kotlin("jvm")
	id("automodpack.loader")
	id("automodpack.neoforge-toolchain")
	id("net.neoforged.moddev")
}

val neoForgeVersion = loaderVersion()

neoForge {
	enable {
		version = neoForgeVersion
		isDisableRecompilation = true
	}
}

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-neoforge-shared"))
}
