plugins {
	kotlin("jvm")
}

base {
	archivesName = property("mod.id") as String + "-" + project.name
	version = property("mod_version") as String
	group = property("mod.group") as String
}

repositories {
	mavenCentral()
	maven { url = uri("https://maven.fabricmc.net/") }
}

// Loader internals access shared by every fabric generation: the language adapter that preloads
// the mod and the FabricLoaderImpl accessors the versioned mod loaders compile against.
val fabricLoaderVersion = loaderVersion()
val log4jVersion = versionProperty("versionLoaderPlatformLog4j")

dependencies {
	compileOnly("net.fabricmc:fabric-loader:$fabricLoaderVersion")
	compileOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
}

java {
	// Floor of every fabric target's loader generation.
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	toolchain.languageVersion.set(JavaLanguageVersion.of(17))
	withSourcesJar()
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
