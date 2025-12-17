plugins {
    id("dev.kikugie.stonecutter")
    kotlin("jvm") version "2.3.0" apply false
    id("fabric-loom") version "1.14-SNAPSHOT" apply false
    id("net.neoforged.moddev") version "2.0.126" apply false
    id("com.gradleup.shadow") version "9.3.0" apply false
    id("org.moddedmc.wiki.toolkit") version "0.3.2"
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
        string {
            direction = current.parsed >= "1.20.2"
            replace("ServerboundCustomQueryPacket", "ServerboundCustomQueryAnswerPacket")
        }

        string {
            direction = current.parsed >= "1.20.2"
            replace(".SystemToastIds.", ".SystemToastId.")
        }

        string {
            direction = current.parsed >= "1.21.11"
            replace("net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier")
        }

        string {
            direction = current.parsed >= "1.21.11"
            replace(" ResourceLocation ", " Identifier ")
        }

        string {
            direction = current.parsed >= "1.21.11"
            replace("(ResourceLocation ", "(Identifier ")
        }

        string {
            direction = current.parsed >= "1.21.11"
            replace(" ResourceLocation>", " Identifier>")
        }

        string {
            direction = current.parsed >= "1.21.11"
            replace("<ResourceLocation,", "<Identifier,")
        }

        string {
            direction = current.parsed >= "1.21.11"
            replace("net.minecraft.Util", "net.minecraft.util.Util")
        }

        string {
            direction = current.parsed >= "1.21.11"
            replace("source.hasPermission(3))", "source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))")
        }
    }
}