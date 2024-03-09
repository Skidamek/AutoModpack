import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

plugins {
    id("dev.architectury.loom")
    id("com.github.johnrengelman.shadow")
}

val loader = project.findProperty("mod_platform") as String

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
    implementation(project(":core"))
    implementation(project(":loader-core"))

    minecraft("com.mojang:minecraft:${project.findProperty("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.findProperty("yarn_mappings")}:v2")

    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")

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

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    from(project(":core").sourceSets.main.get().output)
    from(project(":loader-core").sourceSets.main.get().output)

    exclude("pl/skidam/automodpack_loader_core/loader/LoaderManager.class")
    exclude("pl/skidam/automodpack_loader_core/mods/SetupMods.class")

    if (project.name.contains("fabric")) {
        relocate("pl.skidam.automodpack_loader_core_fabric", "pl.skidam.automodpack_loader_core")
    } else if (project.name.contains("quilt")) {
        relocate("pl.skidam.automodpack_loader_core_quilt", "pl.skidam.automodpack_loader_core")
    } else if (project.name.contains("neoforge")) {
        relocate("pl.skidam.automodpack_loader_core_neoforge", "pl.skidam.automodpack_loader_core")
    } else if (project.name.contains("forge")) {
        relocate("pl.skidam.automodpack_loader_core_forge", "pl.skidam.automodpack_loader_core")
    }

    configurations = emptyList()

    manifest {
        attributes["AutoModpack-Version"] = version
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

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