import java.util.Properties

plugins {
	`kotlin-dsl`
}

repositories {
	mavenCentral()
	gradlePluginPortal()
}

val versions =
	Properties().apply {
		file("../gradle.properties").inputStream().use(::load)
	}

fun version(name: String): String = versions.getProperty(name)

dependencies {
	implementation("com.fasterxml.jackson.core:jackson-databind:${version("versionJackson")}") // For JSON parsing e.g. in build.forge.gradle.kts
	implementation(
		"dev.luna5ama.jar-optimizer:dev.luna5ama.jar-optimizer.gradle.plugin:${version("pluginJarOptimizerVersion")}",
	)
}
