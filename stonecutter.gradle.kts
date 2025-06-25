plugins {
    id("dev.kikugie.stonecutter")
    kotlin("jvm") version "2.1.21" apply false
    id("fabric-loom") version "1.10-SNAPSHOT" apply false
    id("net.neoforged.moddev") version "2.0.95" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
    id("org.moddedmc.wiki.toolkit") version "0.2.7"
}

wiki {
    docs.create("automodpack") {
        root = file("docs")
    }
}

stonecutter active "1.21.6-neoforge" /* [SC] DO NOT EDIT */

stonecutter.parameters {
    constants.match(node.metadata.project.substringAfterLast('-'), "fabric", "neoforge", "forge")

    replacements {
        string {
            direction = eval(current.version, ">=1.20.2")
            phase = "FIRST"
            replace("ServerboundCustomQueryPacket", "ServerboundCustomQueryAnswerPacket")
        }

        string {
            direction = eval(current.version, ">=1.20.2")
            phase = "FIRST"
            replace(".SystemToastIds.", ".SystemToastId.")
        }
    }
}