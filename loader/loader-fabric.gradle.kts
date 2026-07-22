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
	maven { url = uri("https://libraries.minecraft.net/") }
}

val gsonVersion = versionProperty("versionLoaderGson")
val log4jVersion = versionProperty("versionLoaderFabricLog4j")
val tomljVersion = versionProperty("versionTomlj")
val fabricLoaderVersion = loaderVersion()

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-core"))

	compileOnly("com.google.code.gson:gson:$gsonVersion")
	compileOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
	implementation("org.tomlj:tomlj:$tomljVersion")

	implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
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
