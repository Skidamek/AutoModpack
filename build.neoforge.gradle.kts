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
	maven("https://skidamek.github.io/maven/") { name = "Skidamek" }
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

	compileOnly("pl.skidam:mcholepunch-core:$mcholepunchVersion") { isTransitive = false }
	compileOnly("pl.skidam:mcholepunch-server-netty:$mcholepunchVersion") { isTransitive = false }
}

tasks {
	processResources {
		exclude("**/fabric.mod.json", "**/automodpack*.accesswidener", "**/mods.toml", "**/pack.mcmeta")
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
