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
    validateAccessTransformers = true
    enable {
        forgeVersion = property("deps.forge") as String
        isDisableRecompilation = true
    }
}

dependencies {
    implementation(project(":core")) { isTransitive = false }
    implementation(project(":loader-core")) { isTransitive = false }

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
    jar { // add the mixin config to the jar
        manifest {
            attributes(
                "MixinConfigs" to "automodpack-main.mixins.json"
            )
        }
    }

    processResources {
        exclude("**/fabric.mod.json", "**/automodpack.accesswidener")
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
