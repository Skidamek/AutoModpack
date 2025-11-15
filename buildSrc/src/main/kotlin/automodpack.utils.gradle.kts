tasks.named<ProcessResources>("processResources") {
    fun prop(name: String) = project.findProperty(name) as? String

    val props = HashMap<String, String>().apply {
        fun putIfNotEmpty(key: String, value: String?) {
            if (!value.isNullOrEmpty()) put(key, value)
        }

        putIfNotEmpty("version",prop("mod_version"))
        putIfNotEmpty("minecraft",prop("meta.minecraft"))
        putIfNotEmpty("id",prop("mod.id"))
        putIfNotEmpty("name",prop("mod_name"))
        putIfNotEmpty("description",prop("mod.description"))
    }

    filesMatching(listOf("pack.mcmeta", "fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        expand(props)
    }
}