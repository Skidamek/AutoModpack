import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode

plugins {
    kotlin("jvm")
    id("automodpack.common")
    id("automodpack.utils")
    id("net.neoforged.moddev.legacyforge")
}

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc${property("deps.minecraft")}-forge".lowercase())

legacyForge {
    version = property("deps.forge") as String
    validateAccessTransformers = true

    if (hasProperty("deps.parchment")) parchment {
        val (mc, ver) = (property("deps.parchment") as String).split(':')
        mappingsVersion = ver
        minecraftVersion = mc
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":loader-core"))

    compileOnly("net.fabricmc.fabric-api:fabric-api:0.92.6+1.20.1")
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor") // Required to generate refmaps
}

mixin { // Add mixins
    add(sourceSets.main.get(), "automodpack-main.mixins.refmap.json")
    config("automodpack-main.mixins.json")
}

tasks.getByName<Copy>("processResources") {
    doLast { // Add refmap to the mixin config
        val mixinConfigFile = File(destinationDir, "automodpack-main.mixins.json")
        addRefmapToJsonFile(mixinConfigFile, "automodpack-main.mixins.refmap.json")
    }
}

tasks {
    jar { // add the mixin config to the jar
        manifest {
            attributes(
                "MixinConfigs" to "automodpack-main.mixins.json"
            )
        }
    }

    processResources {
        exclude("**/fabric.mod.json", "**/automodpack.accesswidener")
        if (stonecutter.eval(stonecutter.current.version, ">=1.21.9")) {
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
    if (stonecutter.eval(stonecutter.current.version, ">=1.20.5")) {
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

fun addRefmapToJsonFile(jsonFile: File, refmap: String) {
    if (!jsonFile.exists()) {
        error("JSON file not found: ${jsonFile.absolutePath}")
    }

    val objectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    try {
        val jsonNode = objectMapper.readTree(jsonFile)
        if (jsonNode.isObject) {
            val objectNode = jsonNode as ObjectNode
            objectNode.put("refmap", refmap)
            objectMapper.writeValue(jsonFile, objectNode)
            println("Added refmap ($refmap) to ${jsonFile.name}")
        } else {
            error("JSON file ${jsonFile.name} is not a JSON object, couldn't add refmap.")
        }
    } catch (e: Exception) {
        println("Error processing JSON file ${jsonFile.absolutePath}: ${e.message}")
        e.printStackTrace()
    }
}