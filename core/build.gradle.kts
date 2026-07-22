import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
	kotlin("jvm")
	id("com.gradleup.shadow")
}

base {
	archivesName = property("mod.id") as String + "-" + project.name
	version = property("mod_version") as String
	group = property("mod.group") as String
}

repositories {
	mavenCentral()
}

val nettyVersion = versionProperty("versionNetty")
val log4jVersion = versionProperty("versionLog4j")
val gsonVersion = versionProperty("versionGson")
val bouncyCastleVersion = versionProperty("versionBouncyCastle")
val httpClientVersion = versionProperty("versionHttpClient")
val tomljVersion = versionProperty("versionTomlj")
val antlrVersion = versionProperty("versionAntlr")
val h2Version = versionProperty("versionH2")
val junitVersion = versionProperty("versionJunit")

val deps =
	listOf(
		"io.netty:netty-all:$nettyVersion",
		"org.apache.logging.log4j:log4j-core:$log4jVersion",
		"com.google.code.gson:gson:$gsonVersion",
		"org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion",
		"org.apache.httpcomponents.client5:httpclient5:$httpClientVersion",
		"org.tomlj:tomlj:$tomljVersion",
		"org.antlr:antlr4-runtime:$antlrVersion",
		"com.h2database:h2-mvstore:$h2Version",
	)

dependencies {
	// minecraft/loaders uses these, so we cant just implement them because it wont resolve in gradle
	deps.forEach { compileOnly(it) }
	deps.forEach { runtimeOnly(it) }
	deps.forEach { testImplementation(it) }

	testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
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

tasks.named<Test>("test") {
	useJUnitPlatform()
}

// Configure the ShadowJar task
tasks.named<ShadowJar>("shadowJar") {
	archiveBaseName.set("automodpack-server")
	configurations = listOf(project.configurations.compileClasspath.get())

	manifest {
		attributes(
			"Main-Class" to "pl.skidam.automodpack_core.Server",
		)
	}
}

tasks.named("assemble") {
	dependsOn("shadowJar")
}
