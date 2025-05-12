import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

plugins {
    id("dev.architectury.loom")
    id("com.github.johnrengelman.shadow")
}

val loader = property("loom.platform") as String
var mcVer = (property("minecraft_version") as String).replace(".", "").toInt()
// make from 3 char release 4 char release e.g. 1.21 -> 1.21.0 == 1210
if (mcVer < 1000) {
    mcVer *= 10
}

base {
    archivesName = property("mod_id") as String + "-" + project.name
    version =  property("mod_version") as String
    group = property("mod_group") as String
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.neoforged.net/releases") }
    maven { url = uri("https://files.minecraftforge.net/maven/") }
    maven { url = uri("https://libraries.minecraft.net/") }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":loader-core"))

    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings()) // We dont really use minecraft code there so we can use mojang mappings

    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")

    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.4.4")

    if (project.name.contains("neoforge")) {
        "neoForge"("net.neoforged:neoforge:${property("loader_neoforge")}")
    } else {
        "forge"("net.minecraftforge:forge:${property("minecraft_version")}-${property("loader_forge")}")
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

    from(project(":core").sourceSets.main.get().output)
    from(project(":loader-core").sourceSets.main.get().output)

    // Include the tomlj dependency in the shadow jar
    configurations = listOf(project.configurations.getByName("shadowImplementation"))

    val reloc = "am_libs"
    relocate("org.antlr", "${reloc}.org.antlr")
    relocate("org.tomlj", "${reloc}.org.tomlj")
    relocate("org.apache.hc", "${reloc}.org.apache.hc")
    relocate("org.checkerframework", "${reloc}.org.checkerframework")
    relocate("org.slf4j", "${reloc}.org.slf4j")
//    relocate("com.github.luben", "${reloc}.com.github.luben") // cant relocate - natives
    relocate("org.bouncycastle", "${reloc}.org.bouncycastle")

    if (project.name.contains("neoforge")) {
        relocate("pl.skidam.automodpack_loader_core_neoforge", "pl.skidam.automodpack_loader_core")
    } else {
        relocate("pl.skidam.automodpack_loader_core_forge", "pl.skidam.automodpack_loader_core")
    }

    exclude("pl/skidam/automodpack_loader_core/loader/LoaderManager.class")
    exclude("pl/skidam/automodpack_loader_core/mods/ModpackLoader.class")
    exclude("log4j2.xml")

    manifest {
        attributes["AutoModpack-Version"] = version
    }

    mergeServiceFiles()
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