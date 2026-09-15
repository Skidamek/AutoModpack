// Forces these to configure before us: their sourceSets are referenced lazily below, which -
// unlike the dependencies{} block - configuration-on-demand does not always reach in time
// otherwise (surfaces when this project is built standalone, e.g.
// `gradlew :loader-forge-fml47:build`, rather than as part of a full build).
evaluationDependsOn(":core")
evaluationDependsOn(":loader-forge-earlyservices")
evaluationDependsOn(":loader-modlauncher-earlyservices")

plugins {
	kotlin("jvm")
	id("net.neoforged.moddev.legacyforge")
}

val selectedForgeVersion = loaderVersion()
val gsonVersion = versionProperty("versionLoaderGson")
val log4jVersion = versionProperty("versionLoaderPlatformLog4j")

base {
	archivesName = property("mod.id") as String + "-" + project.name
	version = property("mod_version") as String
	group = property("mod.group") as String
}

legacyForge {
	enable {
		forgeVersion = selectedForgeVersion
		isDisableRecompilation = true
	}
}

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-forge-earlyservices"))
	compileOnly(project(":loader-modlauncher-earlyservices"))

	// External provided deps to compile this
	compileOnly("com.google.code.gson:gson:$gsonVersion")
	compileOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	toolchain.languageVersion.set(JavaLanguageVersion.of(17))
	withSourcesJar()
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
