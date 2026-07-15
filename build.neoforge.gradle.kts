plugins {
	kotlin("jvm")
	id("automodpack.common")
	id("automodpack.utils")
	id("net.neoforged.moddev")
}

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc${property("deps.minecraft")}-neoforge".lowercase())

neoForge {
	validateAccessTransformers = true
	enable {
		version = property("deps.neoforge") as String
		isDisableRecompilation = true
	}
}

dependencies {
	implementation(project(":core")) { isTransitive = false }
	implementation(project(":loader-core")) { isTransitive = false }
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
