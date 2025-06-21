plugins {
    kotlin("jvm")
    id("automodpack.common")
    id("net.neoforged.moddev")
}

version = "${property("mod.version")}+${property("deps.minecraft")}"
base.archivesName = property("mod.id") as String

neoForge {
    version = property("deps.neoforge") as String
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
}

tasks {
    processResources {
        exclude("**/fabric.mod.json", "**/automodpack.accesswidener", "**/forge.mods.toml")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(jar.map { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
        dependsOn("build")
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}