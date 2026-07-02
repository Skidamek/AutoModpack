plugins {
    kotlin("jvm")
    id("automodpack.utils")
    id("net.neoforged.moddev.legacyforge")
}

base {
    archivesName = property("mod.id") as String + "-" + project.name
    version = property("mod_version") as String
    group = property("mod.group") as String
}

legacyForge {
    enable {
        forgeVersion = property("deps.forge") as String
        isDisableRecompilation = true
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":loader-core"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
