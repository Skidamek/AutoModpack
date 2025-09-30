plugins {
    id("dev.kikugie.stonecutter")
    kotlin("jvm") version "2.2.20" apply false
    id("fabric-loom") version "1.11-SNAPSHOT" apply false
    id("net.neoforged.moddev") version "2.0.110" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
    id("org.moddedmc.wiki.toolkit") version "0.3.2"
}

wiki {
    docs.create("automodpack") {
        root = file("docs")
    }
}

stonecutter active "1.21.9-fabric" /* [SC] DO NOT EDIT */

stonecutter.parameters {
    constants.match(node.metadata.project.substringAfterLast('-'), "fabric", "neoforge", "forge")

    replacements {
        string {
            direction = eval(current.version, ">=1.20.2")
            replace("ServerboundCustomQueryPacket", "ServerboundCustomQueryAnswerPacket")
        }

        string {
            direction = eval(current.version, ">=1.20.2")
            replace(".SystemToastIds.", ".SystemToastId.")
        }
    }
}