import com.replaymod.gradle.preprocess.PreprocessExtension
import groovy.json.JsonSlurper
import net.fabricmc.loom.task.RemapJarTask
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
	id("dev.architectury.loom")
	id("com.replaymod.preprocess")
}

val modBrand = project.findProperty("mod_platform") as String
val settingsFile = File("$rootDir/settings.json")
@Suppress("UNCHECKED_CAST")
val settings = JsonSlurper().parseText(settingsFile.readText()) as Map<String, List<String>>
val loaderMcMap: Map<String, List<String>> = settings["versions"]!!.map { it.split("-").last() to it.split("-").first() }.groupBy({ it.first }, { it.second })
val mcVersions: List<String> = loaderMcMap.values.flatten().distinct()
val loaders: List<String> = loaderMcMap.keys.toList()
val mergedDir = File("${rootProject.projectDir}/merged").apply { mkdirs() }

var mcVer = 1
configure<PreprocessExtension> {
	tabIndentation = true
	mcVer = mcVersion

	vars.put("MC", mcVersion)
	vars.put("FABRIC", if (modBrand == "fabric") 1 else 0)
	vars.put("QUILT", if (modBrand == "quilt") 1 else 0)
	vars.put("NEOFORGE", if (modBrand == "neoforge") 1 else 0)
	vars.put("FORGE", if (modBrand == "forge") 1 else 0)

	vars.put("FABRICLIKE", if (modBrand == "fabric" || modBrand == "quilt") 1 else 0)
	vars.put("FORGELIKE", if (modBrand == "neoforge" || modBrand == "forge") 1 else 0)
}

repositories {
	mavenCentral()
	mavenLocal()
	maven { url = uri("https://maven.quiltmc.org/repository/release") }
	maven { url = uri("https://api.modrinth.com/maven") }
	maven { url = uri("https://maven.neoforged.net/releases") }
	maven { url = uri("https://files.minecraftforge.net/maven/") }
	maven { url = uri("https://libraries.minecraft.net/") }
}

configurations {
	getByName("modRuntimeOnly").exclude(group = "net.fabricmc", module = "fabric-loader")
	getByName("modRuntimeOnly").exclude(group = "org.quiltmc", module = "quilt-loader")
}

dependencies {
	// just for IDE not complaining (its already included in the shadow jar of core-${mod_brand})
	// + needed for runtime in dev env
	implementation(project(":core"))
	implementation(project(":loader-core"))

	testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")

	// TODO fix dev env launch

	minecraft("com.mojang:minecraft:${project.findProperty("minecraft_version")}")

	// Mappings have to be patched for new neoforge
	if (modBrand == "neoforge" && mcVer == 1206) {
		mappings(
			loom.layered {
				mappings("net.fabricmc:yarn:${project.findProperty("yarn_mappings")}:v2")
				mappings("dev.architectury:yarn-mappings-patch-neoforge:1.20.6+build.4")
			}
		)
	} else if (modBrand == "neoforge" && mcVer == 1210) {
		mappings(
			loom.layered {
				mappings("net.fabricmc:yarn:${project.findProperty("yarn_mappings")}:v2")
				mappings("dev.architectury:yarn-mappings-patch-neoforge:1.21+build.4")
			}
		)
	} else {
		mappings("net.fabricmc:yarn:${project.findProperty("yarn_mappings")}:v2")
	}

	if (modBrand == "fabric" || modBrand == "quilt") {
		setOf(
			"fabric-api-base", // Required by modules below
			"fabric-resource-loader-v0", // Required for translatable texts
			"fabric-registry-sync-v0", // Required for custom sounds
			"fabric-networking-api-v1" // Required by registry sync module
		).forEach {
			include(modImplementation(fabricApi.module(it, project.findProperty("fabric_version") as String))!!) // TODO transitive false
		}
		if (mcVer < 1192) {
			include(modImplementation(fabricApi.module("fabric-command-api-v1", project.findProperty("fabric_version") as String))!!) // TODO transitive false
		} else {
			include(modImplementation(fabricApi.module("fabric-command-api-v2", project.findProperty("fabric_version") as String))!!) // TODO transitive false
		}

		// JiJ mixin extras, fabric loader 0.15.7 has it but many people are using older versions
		include(implementation(annotationProcessor("io.github.llamalad7:mixinextras-fabric:${rootProject.findProperty("mixin_extras_version")}")!!)!!)
	}

	if (modBrand == "fabric") {
		modImplementation("net.fabricmc:fabric-loader:${project.findProperty("fabric_loader_version")}")
	}
	if (modBrand == "quilt") {
		modImplementation("org.quiltmc:quilt-loader:${project.findProperty("quilt_loader_version")}")
	}
	if (modBrand == "forge") {
		"forge"("net.minecraftforge:forge:${project.findProperty("minecraft_version")}-${project.findProperty("forge_version")}")

		// For pseudo mixin
		compileOnly(fabricApi.module("fabric-networking-api-v1", project.findProperty("fabric_version") as String))

		implementation(annotationProcessor("io.github.llamalad7:mixinextras-common:${rootProject.findProperty("mixin_extras_version")}")!!)
		implementation(include("io.github.llamalad7:mixinextras-forge:${rootProject.findProperty("mixin_extras_version")}")!!)
	}
	if (modBrand == "neoforge") {

		// For pseudo mixin
		compileOnly(fabricApi.module("fabric-networking-api-v1", project.findProperty("fabric_version") as String))

		"neoForge"("net.neoforged:neoforge:${project.findProperty("neoforge_version")}")

		// 1.20.2 has old mixinextras
		if (mcVer <= 1202) {
			implementation(include("io.github.llamalad7:mixinextras-neoforge:${rootProject.findProperty("mixin_extras_version")}")!!)
		}
	}
}

loom {
	runConfigs["client"].apply {
		ideConfigGenerated(true)
		vmArgs("-Dmixin.debug.export=true")
		runDir = "../../run"
	}

	runConfigs["server"].apply {
		ideConfigGenerated(true)
		vmArgs("-Dmixin.debug.export=true")
		runDir = "../../run"
	}

	if (modBrand == "forge") {
		forge {
			mixinConfigs = listOf("automodpack-main.mixins.json")
		}
	}

	accessWidenerPath = file("../../src/main/resources/automodpack.accesswidener")
}

base {
	version = rootProject.findProperty("mod_version") as String
	group = rootProject.findProperty("maven_group") as String
	archivesName = "${rootProject.findProperty("archives_base_name")}-mc${mcVer}-$modBrand"
}

tasks.named<ProcessResources>("processResources") {
	mapOf(
		"fabric" to listOf("fabric.mod.json"),
		"quilt" to listOf("quilt.mod.json"),
		"forge" to listOf("META-INF/mods.toml", "pack.mcmeta"),
		"neoforge" to listOf("META-INF/neoforge.mods.toml", "pack.mcmeta")
	).forEach { (brand, files) ->
		files.forEach { file ->
			if (modBrand.contains(brand)) {
				filesMatching(file) {
					expand(mapOf(
						"id" to rootProject.findProperty("mod_id"),
						"name" to rootProject.findProperty("mod_name"),
						"version" to version,
						"minecraft_dependency" to project.findProperty("minecraft_dependency"),
						"description" to rootProject.findProperty("mod_description")
					))
				}
			} else if (!brand.contains("forge") && !file.contains("pack.mcmeta")) {
				exclude(file)
			}
		}
	}

	if (modBrand == "forge" || modBrand == "neoforge") {
		filesMatching("assets/automodpack/icon.png") {
			path = "icon.png"
		}
	}

	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from("../../src/main/resources") {
		include("META-INF/services/**")
	}
}

tasks.named<RemapJarTask>("remapJar") {
	remapperIsolation.set(true)
}

java {
	if (mcVer >= 1206) {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	} else {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	withSourcesJar()
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
	from(rootProject.file("LICENSE")) {
		rename { "${it}_${rootProject.findProperty("archives_base_name")}" }
	}

	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from("../../src/main/resources") {
		include("META-INF/services/**")
	}
}

tasks.named("build") {
	finalizedBy("mergeJars")
}

tasks.register("mergeJars") {
	doLast {
		val loaders: List<String> = listOf("fabric", "neoforge/fml2", "neoforge/fml4", "forge/fml47", "forge/fml40") // add quilt when it's ready

		loaders.forEach { it ->

			var loader = it
			if (it == "quilt") loader = "fabric" // we are matching quilt preload to fabric mod jar since there match with each other
			if ((it == "forge/fml40" && mcVer <= 1182) || (it == "forge/fml47" && mcVer > 1182)) loader = "forge"
			if ((it == "neoforge/fml2" && mcVer <= 1204) || (it == "neoforge/fml4" && mcVer >= 1206)) loader = "neoforge"

			if (loader != modBrand) {
				return@forEach
			}

			println("Merging for $it")

			val mainFile = File("${rootProject.projectDir}/loader/$it/build/libs").listFiles()?.firstOrNull { it.isFile && !it.name.endsWith("-sources.jar") && it.name.endsWith(".jar") } ?: return@forEach

			// make a map of mc ver without dots (key) and mc ver with dots (value)
			val mcVersionsMap: Map<String, String> = loaderMcMap[loader]!!.associateBy { it.replace(".", "") }

			val rootDir = rootProject.projectDir
			val jarsToMerge = File("$rootDir/versions").listFiles()
				?.filter { it.name.endsWith(loader) && mcVersionsMap.values.any { mcVer -> it.name.contains(mcVer) } }
				?.flatMap { File("$it/build/libs").listFiles()?.filter { file -> file.isFile && !file.name.endsWith("-sources.jar") && file.name.endsWith(".jar") } ?: error("Couldn't find any $loader mod jar!") }
				?: emptyList()

			val jarToMerge: File = jarsToMerge.find { it.name.contains(base.archivesName.get()) } ?: return@forEach
			// basically we are naming it automodpack-{loader}-{version}-{mc_version}.jar
			val mc = mcVersions.find { mcVer.toString() == it.replace(".", "") } ?: return@forEach
			val finalJar = File("$mergedDir/${mainFile.name.replace(".jar", "")}-${mc}.jar".replace("-loader", "").replace(it.replace("/", "-"), it.split("/").first()))

			mainFile.copyTo(finalJar, overwrite = true)
			appendFileToZip(finalJar, jarToMerge, "automodpack-mod.jar")

			println("Merging: ${jarToMerge.name} into: ${finalJar.name}")
			println("Done: $finalJar")
		}
	}
}

fun appendFileToZip(zipFile: File, fileToAppend: File, entryName: String) {
	// Doing with temp file since for some reason just adding the file breaks the zip/jar file
	val tempFile = File("$zipFile.temp")
	tempFile.createNewFile()

	ZipOutputStream(FileOutputStream(tempFile)).use { zipStream ->
		// Copy existing entries
		ZipInputStream(FileInputStream(zipFile)).use { existingZipStream ->
			while (true) {
				val entry = existingZipStream.nextEntry ?: break
				zipStream.putNextEntry(ZipEntry(entry.name))
				existingZipStream.copyTo(zipStream)
				zipStream.closeEntry()
			}
		}

		// Add the new entry
		zipStream.putNextEntry(ZipEntry(entryName))
		FileInputStream(fileToAppend).use { fileInputStream ->
			fileInputStream.copyTo(zipStream)
		}
		zipStream.closeEntry()
	}

	// Replace the original zip file with the one containing the new entry
	zipFile.delete()
	tempFile.renameTo(zipFile)
}