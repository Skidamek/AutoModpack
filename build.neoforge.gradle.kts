plugins {
    kotlin("jvm")
    id("automodpack.common")
    id("net.neoforged.moddev")
}

version = "${property("mod_version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod_name")}-mc${property("deps.minecraft")}-neoforge".lowercase())

neoForge {
    version = property("deps.neoforge") as String
    validateAccessTransformers = true

    if (hasProperty("deps.parchment")) parchment {
        val (mc, ver) = (property("deps.parchment") as String).split(':')
        mappingsVersion = ver
        minecraftVersion = mc
    }
}

repositories {
    maven("https://maven.su5ed.dev/releases") { name = "FFAPI" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":loader-core"))

    implementation("org.sinytra.forgified-fabric-api:forgified-fabric-api:0.115.6+2.1.4+1.21.1")
}

tasks {
    processResources {
        exclude("**/fabric.mod.json", "**/automodpack.accesswidener", "**/forge.mods.toml")
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

    register<Copy>("buildAndCollect") {
        group = "build"
        from(jar.map { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod_version")}"))
        dependsOn("build")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}