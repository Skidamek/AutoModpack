import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Locale

plugins {
	kotlin("jvm")
	id("automodpack.common")
	id("automodpack.utils")
	id("net.neoforged.moddev.legacyforge")
}

val targetName = sc.current.project
val minecraftVersion = property("deps.minecraft") as String
val selectedForgeVersion = property("deps.forge") as String
val mixinExtrasVersion = versionProperty("versionMixinExtras")
val mixinVersion = versionProperty("versionMixin")

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc$targetName".lowercase(Locale.ROOT))

legacyForge {
	validateAccessTransformers = true
	enable {
		forgeVersion = selectedForgeVersion
		isDisableRecompilation = true
	}
}

dependencies {
	implementation(project(":core")) { isTransitive = false }
	implementation(project(":loader-core")) { isTransitive = false }

	compileOnly(annotationProcessor("io.github.llamalad7:mixinextras-common:$mixinExtrasVersion")!!)
	implementation(jarJar("io.github.llamalad7:mixinextras-forge:$mixinExtrasVersion")!!)

	annotationProcessor("org.spongepowered:mixin:$mixinVersion:processor") // Required to generate refmaps
}

mixin {
	// Add mixins
	add(sourceSets.main.get(), "automodpack-main.mixins.refmap.json")
	config("automodpack-main.mixins.json")
}

tasks.getByName<Copy>("processResources") {
	doLast {
		// Add refmap to the mixin config
		val mixinConfigFile = File(destinationDir, "automodpack-main.mixins.json")

		// Inline the refmap addition to avoid configuration cache issues
		if (!mixinConfigFile.exists()) {
			error("JSON file not found: ${mixinConfigFile.absolutePath}")
		}

		val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
		try {
			val jsonNode = objectMapper.readTree(mixinConfigFile)
			if (jsonNode.isObject) {
				val objectNode = jsonNode as ObjectNode
				objectNode.put("refmap", "automodpack-main.mixins.refmap.json")
				objectMapper.writeValue(mixinConfigFile, objectNode)
				println("Added refmap (automodpack-main.mixins.refmap.json) to ${mixinConfigFile.name}")
			} else {
				error("JSON file ${mixinConfigFile.name} is not a JSON object, couldn't add refmap.")
			}
		} catch (e: Exception) {
			println("Error processing JSON file ${mixinConfigFile.absolutePath}: ${e.message}")
			e.printStackTrace()
		}
	}
}

tasks {
	jar {
		// add the mixin config to the jar
		manifest {
			attributes(
				"MixinConfigs" to "automodpack-main.mixins.json",
			)
		}
	}

	processResources {
		exclude("**/fabric.mod.json", "**/automodpack*.accesswidener", "**/neoforge.mods.toml")
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
	if (sc.current.parsed >= "1.20.5") {
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
