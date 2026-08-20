import java.util.Locale

plugins {
	kotlin("jvm")
	id("automodpack.common")
	id("automodpack.utils")
	id("net.neoforged.moddev")
}

val targetName = sc.current.project
val minecraftVersion = property("deps.minecraft") as String
val neoForgeVersion = property("deps.neoforge") as String
val mcholepunchVersion = versionProperty("versionMcholepunch")

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc$targetName".lowercase(Locale.ROOT))

repositories {
	flatDir {
		name = "mcholepunchLibs"
		dirs(rootProject.file("libs"))
	}
}

neoForge {
	validateAccessTransformers = true
	enable {
		version = neoForgeVersion
		isDisableRecompilation = true
	}
}

dependencies {
	implementation(project(":core")) { isTransitive = false }
	implementation(project(":loader-core")) { isTransitive = false }

	compileOnly(":mcholepunch-core:$mcholepunchVersion") { isTransitive = false }
	compileOnly(":mcholepunch-server-netty:$mcholepunchVersion") { isTransitive = false }
}

tasks {
	processResources {
		exclude("**/fabric.mod.json", "**/automodpack*.accesswidener", "**/mods.toml")
		if (sc.current.parsed >= "1.21.9") {
			exclude("**/pack.mcmeta")
			rename("new-pack.mcmeta", "pack.mcmeta")
		} else {
			exclude("**/new-pack.mcmeta")
		}
	}

	named("createMinecraftArtifacts") {
		dependsOn("stonecutterGenerate")
	}
}

java {
	if (sc.current.parsed >= "26.1") {
		sourceCompatibility = JavaVersion.VERSION_25
		targetCompatibility = JavaVersion.VERSION_25
		toolchain.languageVersion.set(JavaLanguageVersion.of(25))
	} else {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
		toolchain.languageVersion.set(JavaLanguageVersion.of(21))
	}
	withSourcesJar()
}
