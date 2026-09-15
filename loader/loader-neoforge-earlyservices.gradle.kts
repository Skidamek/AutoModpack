import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
	kotlin("jvm")
	id("net.neoforged.moddev")
}

val neoForgeVersion = loaderVersion()

base {
	archivesName = property("mod.id") as String + "-" + project.name
	version = property("mod_version") as String
	group = property("mod.group") as String
}

neoForge {
	enable {
		version = neoForgeVersion
		isDisableRecompilation = true
	}
}

// NeoForge 21.10+ artifacts resolve only against a Java 21 consumer; ask for those variants
// explicitly instead of inheriting the release-driven Java 17 consumer attribute below.
configurations.configureEach {
	if (isCanBeResolved) attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

dependencies {
	compileOnly(project(":core"))
}

java {
	// NeoForge 21.10+ artifacts resolve only against a Java 21 consumer, but the universal outer jar
	// must load on Java 17 (1.18.2): compile on the 21 toolchain, emit Java 17 bytecode and API usage.
	toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
	options.release.set(17)
}
