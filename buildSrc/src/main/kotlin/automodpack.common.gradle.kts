plugins {
    idea
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

repositories {
    fun strictMaven(url: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://maven.parchmentmc.org", "org.parchmentmc.data")
    maven("https://maven.fabricmc.net/")
}

tasks.named<ProcessResources>("processResources") {
    if (project.name.contains("core") || project.name.contains("loader")) {
        println("Skipping processResources for ${project.name} as it is a core or loader module.")
        return@named // skip
    }

    fun prop(name: String) = project.property(name) as String

    val props = HashMap<String, String>().apply {
        this["version"] = prop("mod.version")
        this["minecraft"] = prop("meta.minecraft")
        this["id"] = prop("mod.id")
        this["name"] = prop("mod.name")
        this["description"] = prop("mod.description")
    }

    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        expand(props)
    }
}