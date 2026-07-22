plugins {
	id("dev.kikugie.stonecutter")
	kotlin("jvm") apply false
	id("net.fabricmc.fabric-loom-remap") apply false
	id("net.fabricmc.fabric-loom") apply false
	id("net.neoforged.moddev") apply false
	id("com.gradleup.shadow") apply false
	id("org.moddedmc.wiki.toolkit")
	id("com.diffplug.spotless")
}

repositories {
	mavenCentral()
}

wiki {
	docs.create("automodpack") {
		root = file("docs")
	}
}

stonecutter active "26.2-fabric" // [SC] DO NOT EDIT

stonecutter.parameters {
	val (version, loader) = current.project.split('-', limit = 2)

	constants.match(loader, "fabric", "neoforge", "forge")
	properties.tags(version, loader)

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
			replace(
				"source.hasPermission(3))",
				"source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))",
			)
		}

		string(current.parsed >= "26.2") {
			replace("minecraft.setScreen(", "minecraft.gui.setScreen(")
			replace("minecraft.getToastManager()", "minecraft.gui.toastManager()")
		}
	}
}

val stonecutterJava =
	files(
		providers.provider {
			fileTree("src/main/java") {
				include("**/*.java")
			}.files.filter { it.readText().contains("/*?") }
		},
	)
val miscExtensions = setOf("md", "mdx", "json", "json5", "yml", "yaml", "toml", "xml", "properties", "py", "sh")
val trackedMiscFiles =
	files(
		providers
			.exec {
				commandLine("git", "ls-files", "-z")
			}.standardOutput.asText
			.map { output ->
				output.split('\u0000').filter { path -> path == ".gitignore" || path.substringAfterLast('.', "") in miscExtensions }
			},
	)

spotless {
	java {
		target("src/main/java/**/*.java", "core/src/**/*.java", "loader/**/src/**/*.java")
		targetExclude("versions/**", stonecutterJava)
		eclipse().configFile("config/format/eclipse-java.xml")
		importOrder("java", "javax", "org", "com", "", "pl.skidam")
		trimTrailingWhitespace()
		endWithNewline()
	}

	format("stonecutterJava") {
		target(stonecutterJava)
		leadingSpacesToTabs(4)
		trimTrailingWhitespace()
		endWithNewline()
	}

	kotlinGradle {
		target("**/*.gradle.kts")
		targetExclude("versions/**", ".gradle/**", "**/build/**")
		ktlint()
		trimTrailingWhitespace()
		endWithNewline()
	}

	format("misc") {
		target(trackedMiscFiles)
		targetExclude("versions/**", "autotester/uv.lock")
		trimTrailingWhitespace()
		endWithNewline()
	}
}

if (providers.gradleProperty("automodpack.autotest").isPresent) {
	tasks.matching { it.name == "build" }.configureEach {
		dependsOn(":autotest-fixtures:build")
	}
}

tasks.register("formatApply") {
	group = "formatting"
	description = "Formats all authored source files."
	dependsOn("spotlessApply")
}

tasks.register("formatCheck") {
	group = "verification"
	description = "Checks formatting without changing files."
	dependsOn("spotlessCheck")
}
