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
}

val gsonVersion = versionProperty("versionLoaderGson")
val log4jVersion = versionProperty("versionLoaderCoreLog4j")
val tomljVersion = versionProperty("versionTomlj")

dependencies {
	implementation(project(":core"))

	// our needed dependencies
	implementation("com.google.code.gson:gson:$gsonVersion")
	implementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
	implementation("org.tomlj:tomlj:$tomljVersion")
}

java {
	// leave it on java 17 to be compatible with older versions and we dont really need 21 there anyway
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	toolchain.languageVersion.set(JavaLanguageVersion.of(17))
	withSourcesJar()
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
