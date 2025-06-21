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
}

// TODO: Uncomment and fix this
//tasks.named<ProcessResources>("processResources") {
//    fun prop(name: String) = project.property(name) as String
//
//    val props = HashMap<String, String>().apply {
//        this["version"] = prop("mod.version")
//        this["minecraft"] = prop("deps.minecraft")
//    }
//
//    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
//        expand(props)
//    }
//}