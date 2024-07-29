import net.fabricmc.loom.task.RemapJarTask
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("dev.architectury.loom")
}

class ModData {
    val id = property("mod_id").toString()
    val name = property("mod_name").toString()
    val version = property("mod_version").toString()
    val group = property("mod_group").toString()
    val minecraftDependency = property("minecraft_dependency").toString()
    val description = property("mod_description").toString()
//    val minSupportedVersion = property("mod_min_supported_version").toString()
//    val maxSupportedVersion = property("mod_max_supported_version").toString()
}

class LoaderData {
    private val name = loom.platform.get().name.lowercase()
    val isFabric = name == "fabric"
    val isForge = name == "forge"
    val isNeoForge = name == "neoforge"

    fun getVersion() : String? {
        return if (isForge) {
            property("loader_forge")?.toString()
        } else if (isNeoForge) {
            property("loader_neoforge")?.toString()
        } else if (isFabric) {
            property("loader_fabric")?.toString()
        } else {
            null
        }
    }

    override fun toString(): String {
        return name
    }
}

class MinecraftVersionData {
    private val name = stonecutter.current.version.substringBeforeLast("-")
    val needsJava21 = greaterOrEqual("1.20.5")

    fun greaterThan(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) > 0
    }

    fun lessThan(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) < 0
    }

    fun greaterOrEqual(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) >= 0
    }

    fun lessOrEqual(other: String) : Boolean {
        return stonecutter.compare(name, other.lowercase()) <= 0
    }

    override fun equals(other: Any?) : Boolean {
        return name == other
    }

    override fun toString(): String {
        return name
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + needsJava21.hashCode()
        return result
    }
}

val mod = ModData()
val loader = LoaderData()
val minecraftVersion = MinecraftVersionData()


version = mod.version
group = mod.group
base.archivesName.set("${mod.name}-mc$minecraftVersion-$loader".lowercase(Locale.getDefault()))

repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://api.modrinth.com/maven") }
    maven { url = uri("https://maven.neoforged.net/releases") }
    maven { url = uri("https://files.minecraftforge.net/maven/") }
    maven { url = uri("https://libraries.minecraft.net/") }
}

dependencies {
    // just for IDE not complaining (its already included in the shadow jar of core-${mod_brand})
    // + needed for runtime in dev env
    implementation(project(":core"))
    implementation(project(":loader-core"))

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")

    // TODO fix dev env launch

    minecraft("com.mojang:minecraft:$minecraftVersion")

    // Mappings have to be patched for new neoforge
    if (loader.isNeoForge && minecraftVersion.equals("1.20.6")) {
        mappings(
            loom.layered {
                mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
                mappings("dev.architectury:yarn-mappings-patch-neoforge:1.20.6+build.4")
            }
        )
    } else if (loader.isNeoForge && minecraftVersion.equals("1.21")) {
        mappings(
            loom.layered {
                mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
                mappings("dev.architectury:yarn-mappings-patch-neoforge:1.21+build.4")
            }
        )
    } else {
        mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    }

    if (loader.isFabric) {
        setOf(
            "fabric-api-base", // Required by modules below
            "fabric-resource-loader-v0", // Required for translatable texts
            "fabric-registry-sync-v0", // Required for custom sounds
            "fabric-networking-api-v1" // Required by registry sync module
        ).forEach {
            include(modImplementation(fabricApi.module(it, property("fabric_version") as String))!!) // TODO transitive false
        }
        if (minecraftVersion.lessThan("1.19.2")) {
            include(modImplementation(fabricApi.module("fabric-command-api-v1", property("fabric_version") as String))!!) // TODO transitive false
        } else {
            include(modImplementation(fabricApi.module("fabric-command-api-v2", property("fabric_version") as String))!!) // TODO transitive false
        }

        // JiJ mixin extras, fabric loader 0.15.7 has it but many people are using older versions
        include(implementation(annotationProcessor("io.github.llamalad7:mixinextras-fabric:${property("mixin_extras")}")!!)!!)
    }

    if (loader.isFabric) {
        modImplementation("net.fabricmc:fabric-loader:${loader.getVersion()}")
    } else if (loader.isForge) {
        "forge"("net.minecraftforge:forge:$minecraftVersion-${loader.getVersion()}")

        // For pseudo mixin
        compileOnly(fabricApi.module("fabric-networking-api-v1", property("fabric_version") as String))

        implementation(annotationProcessor("io.github.llamalad7:mixinextras-common:${property("mixin_extras")}")!!)
        implementation(include("io.github.llamalad7:mixinextras-forge:${property("mixin_extras")}")!!)
    } else if (loader.isNeoForge) {

        // For pseudo mixin
        compileOnly(fabricApi.module("fabric-networking-api-v1", property("fabric_version") as String))

        "neoForge"("net.neoforged:neoforge:${loader.getVersion()}")

        // bundle mixin extras to 1.20.2 since it has too old version of it
        if (minecraftVersion.equals("1.20.2")) {
            implementation(include("io.github.llamalad7:mixinextras-neoforge:${property("mixin_extras")}")!!)
        }
    }
}

loom {
    runConfigs["client"].apply {
        ideConfigGenerated(true)
        vmArgs("-Dmixin.debug.export=true")
        runDir = "../../run"
    }

    runConfigs["server"].apply {
        ideConfigGenerated(true)
        vmArgs("-Dmixin.debug.export=true")
        runDir = "../../run"
    }

    if (loader.isForge) {
        forge {
            convertAccessWideners = true
            mixinConfigs = listOf("automodpack-main.mixins.json")
        }
    }

    accessWidenerPath = file("../../src/main/resources/automodpack.accesswidener")
}

if (stonecutter.current.isActive) {
    rootProject.tasks.register("buildActive") {
        group = "project"
        dependsOn(tasks.named("build"))
    }
}

tasks.processResources {
    val map = mapOf(
        "id" to mod.id,
        "name" to mod.name,
        "version" to mod.version,
        "minecraft_dependency" to mod.minecraftDependency,
        "description" to mod.description
    )

    inputs.properties(map)

    if (loader.isFabric) {
        exclude("META-INF/neoforge.mods.toml")
        exclude("META-INF/mods.toml")
        filesMatching("fabric.mod.json") {
            expand(map)
        }
        println("Fabric mod json map $map")
    } else if (loader.isNeoForge) {
        exclude("fabric.mod.json")
        exclude("META-INF/mods.toml")
        filesMatching("META-INF/neoforge.mods.toml") {
            expand(map)
        }
    } else if (loader.isForge) {
        exclude("fabric.mod.json")
        exclude("META-INF/neoforge-mods.toml")
        filesMatching("META-INF/mods.toml") {
            expand(map)
        }
    }

    if (loader.isForge || loader.isNeoForge) {
        filesMatching("assets/automodpack/icon.png") {
            path = "icon.png"
        }
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("../../src/main/resources") {
        include("META-INF/services/**")
    }
}

tasks.named<RemapJarTask>("remapJar") {
    remapperIsolation.set(true)
}

java {
    if (minecraftVersion.needsJava21) {
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

tasks.named<Jar>("jar") {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_${mod.id}" }
    }
}