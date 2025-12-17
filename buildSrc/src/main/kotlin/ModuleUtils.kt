fun getLoaderModuleName(name: String): String {
    val minecraftVersionStr = name.substringAfterLast("-mc").substringBefore("-")
    if (minecraftVersionStr.isEmpty()) {
        error("Could not identify Minecraft version in: $name")
    }
    return when {
        name.contains("fabric") -> "fabric-core"
        name.contains("neoforge") -> when (minecraftVersionStr) {
            "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.19.2", "1.18.2" -> "neoforge-fml2"
            "1.21.8", "1.21.5", "1.21.4", "1.21.3", "1.21.1" -> "neoforge-fml4"
            "1.21.11", "1.21.10" -> "neoforge-fml10"
            else -> error("Unknown neoforge loader module for Minecraft version: $minecraftVersionStr")
        }
        name.contains("forge") -> if (minecraftVersionStr == "1.18.2") "forge-fml40" else "forge-fml47"
        else -> error("Unknown loader type")
    }
}
