plugins {
	kotlin("jvm")
	id("automodpack.loader")
}

repositories {
	mavenCentral()
	maven { url = uri("https://maven.fabricmc.net/") }
}

val gsonVersion = versionProperty("versionLoaderGson")
val log4jVersion = versionProperty("versionLoaderPlatformLog4j")
val fabricLoaderVersion = loaderVersion()

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-knit-api-stubs"))

	// External provided deps to compile this
	compileOnly("com.google.code.gson:gson:$gsonVersion")
	compileOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
	compileOnly("net.fabricmc:fabric-loader:$fabricLoaderVersion")
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	toolchain.languageVersion.set(JavaLanguageVersion.of(17))
	withSourcesJar()
}
