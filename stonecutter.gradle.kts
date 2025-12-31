plugins {
    id("dev.kikugie.stonecutter")
    kotlin("jvm") version "2.3.0" apply false
    id("fabric-loom") version "1.14-SNAPSHOT" apply false
    id("net.neoforged.moddev") version "2.0.136" apply false
    id("com.gradleup.shadow") version "9.3.0" apply false
    id("org.moddedmc.wiki.toolkit") version "0.4+"
}

wiki {
    docs.create("automodpack") {
        root = file("docs")
    }
}

stonecutter active "1.21.11-fabric" /* [SC] DO NOT EDIT */

stonecutter.parameters {
    constants.match(node.metadata.project.substringAfterLast('-'), "fabric", "neoforge", "forge")

    replacements {
        string(current.parsed >= "1.20.2") {
            replace("ServerboundCustomQueryPacket", "ServerboundCustomQueryAnswerPacket")
            replace(".SystemToastIds.", ".SystemToastId.")
        }

        regex(current.parsed >= "1.21.11") {
            replace("\\bResourceLocation\\b" to "Identifier", "\\bIdentifier\\b" to "ResourceLocation")
        }

        string(current.parsed >= "1.21.11") {
            replace("net.minecraft.Util", "net.minecraft.util.Util")
            replace("source.hasPermission(3))", "source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))")
        }
    }
}