plugins {
	kotlin("jvm")
	id("automodpack.utils")
	id("net.neoforged.moddev")
}

base {
	archivesName = property("mod.id") as String + "-" + project.name
	version = property("mod_version") as String
	group = property("mod.group") as String
}

neoForge {
	enable {
		version = property("deps.neoforge") as String
		isDisableRecompilation = true
	}
}

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-core"))
}

java {
	val javaVersion = findProperty("deps.java") as String
	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
	toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
