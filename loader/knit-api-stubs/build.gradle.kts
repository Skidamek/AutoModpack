plugins {
	kotlin("jvm")
}

// Compile-time stubs of Kilt's Knit API (https://github.com/KiltMC/KnitLoader).
// Provided at runtime by Kilt - this module is never bundled or published; it only
// exists so AutoModpack's Knit extension can compile against the real interface
// FQNs. Members are trimmed to the minimum referenced by AutoModpack.

base {
	archivesName = "automodpack-knit-api-stubs"
}

repositories {
	mavenCentral()
}

kotlin {
	jvmToolchain(17)
}
