plugins {
    kotlin("jvm")
    id("automodpack.common")
    id("net.neoforged.moddev.legacyforge")
}

version = "${property("mod.version")}"
group = "${property("mod.group")}"
base.archivesName.set("${property("mod.name")}-mc${property("deps.minecraft")}-forge".lowercase())

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

    compileOnly("net.fabricmc.fabric-api:fabric-api:0.92.2+1.20.1")
    compileOnly(annotationProcessor("io.github.llamalad7:mixinextras-common:${property("deps.mixin-extras")}")!!)
    implementation(jarJar("io.github.llamalad7:mixinextras-forge:${property("deps.mixin-extras")}")!!)
}

tasks {
    processResources {
        exclude("**/fabric.mod.json", "**/automodpack.accesswidener")
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