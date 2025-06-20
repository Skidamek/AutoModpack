import java.util.*

plugins {
    id("dev.kikugie.stonecutter")
    id("dev.isxander.modstitch.base") version "0.5.16-unstable-local"
}

fun prop(name: String, consumer: (prop: String) -> Unit) {
    (findProperty(name) as? String?)
        ?.let(consumer)
}

class ModData {
    val id = property("mod_id").toString()
    val name = property("mod_name").toString()
    val version = property("mod_version").toString()
    val group = property("mod_group").toString()
    val minecraftDependency = property("minecraft_dependency").toString()
    val description = property("mod_description").toString()
}

class LoaderData {
    private val name = stonecutter.current.project.substringAfterLast("-")
    val isFabric = name == "fabric"
    val isForge = name == "forge"
    val isNeoForge = name == "neoforge"

    fun getVersion() : String? {
        return if (isForge) {
            property("deps.forge")?.toString()
        } else if (isNeoForge) {
            property("deps.neoforge")?.toString()
        } else if (isFabric) {
            "0.16.14"
//            property("loader_fabric")?.toString()
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
    private val needsJava21 = stonecutter.eval(name, ">=1.20.5")

    override fun toString(): String {
        return name
    }

    fun needsJava21(): Boolean {
        return needsJava21
    }
}

val mod = ModData()
val loader = LoaderData()
val minecraftVersionData = MinecraftVersionData()


version = mod.version
group = mod.group
base.archivesName.set("${mod.name}-mc$minecraftVersionData-$loader".lowercase(Locale.getDefault()))

modstitch {
    minecraftVersion = minecraftVersionData.toString()

    javaTarget = if (minecraftVersionData.needsJava21()) 21 else 17

    // If parchment doesnt exist for a version yet you can safely
    // omit the "deps.parchment" property from your versioned gradle.properties
    parchment {
        prop("deps.parchment") { mappingsVersion = it }
    }

    // This metadata is used to fill out the information inside
    // the metadata files found in the templates folder.
    metadata {
        modId = "automodpack"
        modName = "AutoModpack"
        modVersion = mod.version
        modGroup = mod.group
        modAuthor = "Skidam"

        fun <K, V> MapProperty<K, V>.populate(block: MapProperty<K, V>.() -> Unit) {
            block()
        }

        replacementProperties.populate {
            // You can put any other replacement properties/metadata here that
            // modstitch doesn't initially support. Some examples below.
            put("mod_issue_tracker", "https://github.com/modunion/modstitch/issues")
            put("pack_format", when (property("deps.minecraft")) {
                "1.19" -> 9
                "1.20.1" -> 15
                "1.21.4" -> 46
                else -> 80
//                else -> throw IllegalArgumentException("Please store the resource pack version for ${property("deps.minecraft")} in build.gradle.kts! https://minecraft.wiki/w/Pack_format")
            }.toString())
        }
    }

    // Fabric Loom (Fabric)
    loom {
        // It's not recommended to store the Fabric Loader version in properties.
        // Make sure its up to date.
        fabricLoaderVersion = "0.16.14"

        // Configure loom like normal in this block.
        configureLoom {
            accessWidenerPath = file("../../src/main/resources/automodpack.accesswidener")
        }
    }

    // ModDevGradle (NeoForge, Forge, Forgelike)
    moddevgradle {
        enable {
            prop("deps.forge") { forgeVersion = it }
            prop("deps.neoform") { neoFormVersion = it }
            prop("deps.neoforge") { neoForgeVersion = it }
            prop("deps.mcp") { mcpVersion = it }
        }

        // Configures client and server runs for MDG, it is not done by default
        defaultRuns()

        // This block configures the `neoforge` extension that MDG exposes by default,
        // you can configure MDG like normal from here
        configureNeoforge {
            accessTransformers {
                file("../../src/main/resources/META-INF/accesstransformer.cfg")
            }
            runs.all {
                disableIdeRun()
            }
        }
    }

    mixin {
        // You do not need to specify mixins in any mods.json/toml file if this is set to
        // true, it will automatically be generated.
        addMixinsToModManifest = true

//        configs.register("examplemod")

        // Most of the time you wont ever need loader specific mixins.
        // If you do, simply make the mixin file and add it like so for the respective loader:
        // if (isLoom) configs.register("examplemod-fabric")
        // if (isModDevGradleRegular) configs.register("examplemod-neoforge")
        // if (isModDevGradleLegacy) configs.register("examplemod-forge")
    }
}

dependencies {
//    modstitchImplementation(project(":core"))
//    modstitchImplementation(project(":loader-core"))
    implementation(project(":core"))
    implementation(project(":loader-core"))

    modstitch.loom {

        // TODO: fix it
//        setOf(
//            "fabric-api-base", // Required by modules below
//            "fabric-resource-loader-v0", // Required for translatable texts
//            "fabric-registry-sync-v0", // Required for custom sounds
//            "fabric-networking-api-v1" // Required by registry sync module
//        ).forEach {
//            modstitchModImplementation(fabricApi.module(it, property("fabric_version") as String))
//        }
        // TODO transitive false
//        modstitchModImplementation("net.fabricmc.fabric-api:fabric-api-base:${property("fabric_version")}")
//        modstitchModImplementation("net.fabricmc.fabric-api:fabric-resource-loader-v0:${property("fabric_version")}")
//        modstitchModImplementation("net.fabricmc.fabric-api:fabric-registry-sync-v0:${property("fabric_version")}")
//        modstitchModImplementation("net.fabricmc.fabric-api:fabric-networking-api-v1:${property("fabric_version")}")
//        if (stonecutter.eval(minecraftVersionData.toString(), "<1.19.2")) {
//            modstitchModImplementation("net.fabricmc.fabric-api:fabric-command-api-v1:${property("fabric_version")}") // TODO transitive false
//        } else {
//            modstitchModImplementation("net.fabricmc.fabric-api:fabric-command-api-v2:${property("fabric_version")}") // TODO transitive false
//        }

        modstitchModImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

        // JiJ lastest version of mixin extras so all mods work (workaround) - remove when we detect such incompatibilities and copy the jij mod to the mods folder, currently the stable loader version would be loaded instead of the lastest required by some mods
        modstitchJiJ(modstitchImplementation(annotationProcessor("io.github.llamalad7:mixinextras-fabric:${property("mixin_extras")}")!!)!!)
    }

    if (!loader.isFabric) {
        compileOnly("net.fabricmc.fabric-api:fabric-api:0.92.2+1.20.1")
    }

    if (loader.isForge) {
        modstitchImplementation(annotationProcessor("io.github.llamalad7:mixinextras-common:${property("mixin_extras")}")!!)
        modstitchImplementation(modstitchJiJ("io.github.llamalad7:mixinextras-forge:${property("mixin_extras")}")!!)
    } else if (loader.isNeoForge) {
        modstitchImplementation(modstitchJiJ("io.github.llamalad7:mixinextras-neoforge:${property("mixin_extras")}")!!)
    }
}

//// Stonecutter constants for mod loaders.
//// See https://stonecutter.kikugie.dev/stonecutter/guide/comments#condition-constants
//var constraint: String = name.split("-")[1]
//stonecutter {
//    consts(
//        "fabric" to constraint.equals("fabric"),
//        "neoforge" to constraint.equals("neoforge"),
//        "forge" to constraint.equals("forge"),
//        "vanilla" to constraint.equals("vanilla")
//    )
//}

//repositories {
//    mavenCentral()
//    mavenLocal()
//    maven { url = uri("https://api.modrinth.com/maven") }
//    maven { url = uri("https://maven.neoforged.net/releases") }
//    maven { url = uri("https://files.minecraftforge.net/maven/") }
//    maven { url = uri("https://libraries.minecraft.net/") }
//}

//dependencies {
//    // just for IDE not complaining (its already included in the shadow jar of core-${mod_brand})
//    // + needed for runtime in dev env
//    implementation(project(":core"))
//    implementation(project(":loader-core"))
//
//    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
//
//    // TODO fix dev env launch
//
//    minecraft("com.mojang:minecraft:$minecraftVersion")
//
//    // Mappings have to be patched for new neoforge
//    if (loader.isNeoForge && minecraftVersion.equals("1.20.6")) {
//        mappings(
//            loom.layered {
//                mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
//                mappings("dev.architectury:yarn-mappings-patch-neoforge:1.20.6+build.4")
//            }
//        )
//    } else if (loader.isNeoForge && minecraftVersion.greaterOrEqual("1.21")) {
//        mappings(
//            loom.layered {
//                mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
//                mappings("dev.architectury:yarn-mappings-patch-neoforge:1.21+build.4")
//            }
//        )
//    } else {
//        mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
//    }
//
//    if (loader.isFabric) {
//        setOf(
//            "fabric-api-base", // Required by modules below
//            "fabric-resource-loader-v0", // Required for translatable texts
//            "fabric-registry-sync-v0", // Required for custom sounds
//            "fabric-networking-api-v1" // Required by registry sync module
//        ).forEach {
//            include(modImplementation(fabricApi.module(it, property("fabric_version") as String))!!) // TODO transitive false
//        }
//        if (minecraftVersion.lessThan("1.19.2")) {
//            include(modImplementation(fabricApi.module("fabric-command-api-v1", property("fabric_version") as String))!!) // TODO transitive false
//        } else {
//            include(modImplementation(fabricApi.module("fabric-command-api-v2", property("fabric_version") as String))!!) // TODO transitive false
//        }
//
//        // JiJ lastest version of mixin extras so all mods work (workaround) - remove when we detect such incompatibilities and copy the jij mod to the mods folder, currently the stable loader version would be loaded instead of the lastest required by some mods
//        include(implementation(annotationProcessor("io.github.llamalad7:mixinextras-fabric:${property("mixin_extras")}")!!)!!)
//    }
//
//    if (loader.isFabric) {
//        modImplementation("net.fabricmc:fabric-loader:${loader.getVersion()}")
//    } else if (loader.isForge) {
//        "forge"("net.minecraftforge:forge:$minecraftVersion-${loader.getVersion()}")
//
//        // For pseudo mixin (compat with Forgified Fabric API)
//        compileOnly(fabricApi.module("fabric-networking-api-v1", "0.92.2+1.20.1")) // version is not important
//
//        implementation(annotationProcessor("io.github.llamalad7:mixinextras-common:${property("mixin_extras")}")!!)
//        implementation(include("io.github.llamalad7:mixinextras-forge:${property("mixin_extras")}")!!)
//    } else if (loader.isNeoForge) {
//
//        // For pseudo mixin (compat with Forgified Fabric API)
//        compileOnly(fabricApi.module("fabric-networking-api-v1", "0.92.2+1.20.1")) // version is not important
//
//        "neoForge"("net.neoforged:neoforge:${loader.getVersion()}")
//
//        // Bundle mixin extras to 1.20.2 since it has too old version of it
//        if (minecraftVersion.equals("1.20.2")) {
//            implementation(include("io.github.llamalad7:mixinextras-neoforge:${property("mixin_extras")}")!!)
//        }
//    }
//}

//loom {
//    runConfigs["client"].apply {
//        ideConfigGenerated(true)
//        vmArgs("-Dmixin.debug.export=true")
//        runDir = "../../run"
//    }
//
//    runConfigs["server"].apply {
//        ideConfigGenerated(true)
//        vmArgs("-Dmixin.debug.export=true")
//        runDir = "../../run"
//    }
//
//    if (loader.isForge) {
//        forge {
//            convertAccessWideners = true
//            mixinConfigs = listOf("automodpack-main.mixins.json")
//        }
//    }
//
//    accessWidenerPath = file("../../src/main/resources/automodpack.accesswidener")
//}

if (stonecutter.current.isActive) {
    rootProject.tasks.register("buildActive") {
        group = "project"
        dependsOn(tasks.named("build"))
        finalizedBy("mergeJars")
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

java {
    if (minecraftVersionData.needsJava21()) {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21

        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    } else {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
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

tasks.named("build") {
    finalizedBy(rootProject.tasks.named("mergeJars"))
}