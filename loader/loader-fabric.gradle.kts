import net.fabricmc.loom.task.RemapJarTask

plugins {
    id("dev.architectury.loom") version "1.9-SNAPSHOT"
    id("com.gradleup.shadow")
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

    modImplementation("net.fabricmc:fabric-loader:${property("loader_fabric")}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }

    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<RemapJarTask>("remapJar") {
    isEnabled = false
}