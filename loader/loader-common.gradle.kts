import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

plugins {
    id("dev.architectury.loom")
    id("com.github.johnrengelman.shadow")
}

val loader = project.findProperty("mod_platform") as String
var mcVer = (project.findProperty("minecraft_version") as String).replace(".", "").toInt()
// make from 3 char release 4 char release e.g. 1.21 -> 1.21.0 == 1210
if (mcVer < 1000) {
    mcVer *= 10
}

base {
    archivesName = rootProject.findProperty("archives_base_name") as String + "-" + project.name
    version =  rootProject.findProperty("mod_version") as String
    group = rootProject.findProperty("maven_group") as String
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.quiltmc.org/repository/release") }
    maven { url = uri("https://maven.neoforged.net/releases") }
    maven { url = uri("https://files.minecraftforge.net/maven/") }
    maven { url = uri("https://libraries.minecraft.net/") }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":loader-core"))

    minecraft("com.mojang:minecraft:${project.findProperty("minecraft_version")}")

    // These has to be patched
    if (loader == "neoforge" && mcVer == 1210) {
        mappings(
            loom.layered {
                mappings("net.fabricmc:yarn:${project.findProperty("yarn_mappings")}:v2")
                mappings("dev.architectury:yarn-mappings-patch-neoforge:1.21+build.4")
            }
        )
    } else {
        mappings("net.fabricmc:yarn:${project.findProperty("yarn_mappings")}:v2")
    }

    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("org.tomlj:tomlj:1.1.1")

    if (project.name.contains("quilt")) {
        modImplementation("org.quiltmc:quilt-loader:${rootProject.findProperty("quilt_loader_version")}")
    } else if (project.name.contains("neoforge")) {
        "neoForge"("net.neoforged:neoforge:${project.findProperty("neoforge_version")}")
    } else if (project.name.contains("forge")) {
        "forge"("net.minecraftforge:forge:${project.findProperty("minecraft_version")}-${project.findProperty("forge_version")}")
    } else if (project.name.contains("fabric")) {
        modImplementation("net.fabricmc:fabric-loader:${rootProject.findProperty("fabric_loader_version")}")
    }
}

configurations {
    create("shadowImplementation") {
        extendsFrom(configurations.getByName("implementation"))
        isCanBeResolved = true
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()

    from(project(":core").sourceSets.main.get().output)
    from(project(":loader-core").sourceSets.main.get().output)

    // Include the tomlj dependency in the shadow jar
    configurations = listOf(project.configurations.getByName("shadowImplementation"))

    relocate("org.antlr.v4", "pl.skidam.tomlj")
    relocate("org.tomlj", "pl.skidam.tomlj")
    relocate("org.checkerframework", "pl.skidam.tomlj")


//    configurations = emptyList()

    if (project.name.contains("fabric")) {
        relocate("pl.skidam.automodpack_loader_core_fabric", "pl.skidam.automodpack_loader_core")
    } else if (project.name.contains("quilt")) {
        relocate("pl.skidam.automodpack_loader_core_quilt", "pl.skidam.automodpack_loader_core")
    } else if (project.name.contains("neoforge")) {
        relocate("pl.skidam.automodpack_loader_core_neoforge", "pl.skidam.automodpack_loader_core")
    } else if (project.name.contains("forge")) {
        relocate("pl.skidam.automodpack_loader_core_forge", "pl.skidam.automodpack_loader_core")
    }

    exclude("pl/skidam/automodpack_loader_core/loader/LoaderManager.class")
    exclude("pl/skidam/automodpack_loader_core/mods/ModpackLoader.class")
    exclude("log4j2.xml")

    manifest {
        attributes["AutoModpack-Version"] = version
    }
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

tasks.named<RemapJarTask>("remapJar") {
    isEnabled = false

}

tasks.named<Jar>("jar") {
    isEnabled = false
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}