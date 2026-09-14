@file:Suppress("UnstableApiUsage")

import pl.skidam.automodpack.buildlogic.FabricPlugin.FabricExtension
import java.util.Locale

plugins {
	kotlin("jvm")
	id("automodpack.common")
	id("automodpack.utils")
	id("net.fabricmc.fabric-loom-remap") apply false
	id("net.fabricmc.fabric-loom") apply false
	id("automodpack.fabric")
}

val fabric = the<FabricExtension>()
val targetName = sc.current.project
val minecraftVersion = property("deps.minecraft") as String
val fabricLoaderVersion = property("deps.fabric-loader") as String
val mcholepunchVersion = versionProperty("versionMcholepunch")

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc$targetName".lowercase(Locale.ROOT))

loom {
	accessWidenerPath = rootProject.file(fabric.accessWidenerPath)
}

repositories {
	flatDir {
		name = "mcholepunchLibs"
		dirs(rootProject.file("libs"))
	}
}

dependencies {
	implementation(project(":core")) { isTransitive = false }

	compileOnly(":mcholepunch-core:$mcholepunchVersion")
	compileOnly(":mcholepunch-server-netty:$mcholepunchVersion")

	minecraft("com.mojang:minecraft:$minecraftVersion")
	if (!fabric.isUnobf) {
		mappings(loom.officialMojangMappings())
	}

	modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
}

java {
	if (sc.current.parsed >= "26.1") {
		sourceCompatibility = JavaVersion.VERSION_25
		targetCompatibility = JavaVersion.VERSION_25
		toolchain.languageVersion.set(JavaLanguageVersion.of(25))
	} else if (sc.current.parsed >= "1.20.5") {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
		toolchain.languageVersion.set(JavaLanguageVersion.of(21))
	} else {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
		toolchain.languageVersion.set(JavaLanguageVersion.of(17))
	}
	withSourcesJar()
}

tasks {
	processResources {
		exclude("**/neoforge.mods.toml", "**/mods.toml", "**/accesstransformer*.cfg")
		if (fabric.isUnobf) {
			exclude("**/automodpack.accesswidener")
			rename("automodpack.unobf.accesswidener", "automodpack.accesswidener")
		} else {
			exclude("**/automodpack.unobf.accesswidener")
		}

		if (sc.current.parsed >= "1.21.9") {
			exclude("**/pack.mcmeta")
			rename("new-pack.mcmeta", "pack.mcmeta")
		} else {
			exclude("**/new-pack.mcmeta")
		}
	}
}
