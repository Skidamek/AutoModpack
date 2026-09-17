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
	implementation("com.github.luben:zstd-jni:${version("versionZstdJni")}") // Precompiled zstd natives for packing the impl solid; the version pins the compressed bytes
	implementation(
		"dev.luna5ama.jar-optimizer:dev.luna5ama.jar-optimizer.gradle.plugin:${version("pluginJarOptimizerVersion")}",
	)
	testImplementation(gradleTestKit())
	testImplementation("org.junit.jupiter:junit-jupiter:${version("versionJunit")}")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:${version("versionJunit")}")
}

tasks.test {
	useJUnitPlatform()
}
