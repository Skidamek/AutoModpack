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

dependencies {
    implementation(project(":core"))
    implementation(project(":loader-core"))
    implementation(files(rootDir.resolve("ModflaredApi.jar")))

    compileOnly("net.fabricmc.fabric-api:fabric-api:0.92.2+1.20.1")
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