// Replay machinery shared by BOTH neoforge generations: the ModLauncher-era fml4 and the
// flat-classloader fml10/11 each host early-service jars whose locators/readers FML enumerated
// before hosting, so both must drive them by hand (see EarlyServiceReplay). Per-generation knowledge
// (service caches, classloader sources) stays in each generation's EarlyServiceLayer, which passes
// it in. fml4 is the oldest consumer pin, so the signatures' neoforgespi types are erasure-identical
// on fml10/11 too and both generations link this class safely. It must never reference
// ModLauncher/securejarhandler types: those do not exist on the flat-classloader generation.

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
