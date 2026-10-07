
// The flat-classloader NeoForge 21.10+ generation: these jars are hosted on FML's own classloader
// before discovery, so FML enumerates their locators and readers itself.

evaluationDependsOn(":core")

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
}
