import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

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
	flatDir {
		name = "mcholepunchLibs"
		dirs(rootProject.file("libs"))
	}
}

val nettyVersion = versionProperty("versionNetty")
val log4jVersion = versionProperty("versionLog4j")
val gsonVersion = versionProperty("versionGson")
val bouncyCastleVersion = versionProperty("versionBouncyCastle")
val tomljVersion = versionProperty("versionTomlj")
val antlrVersion = versionProperty("versionAntlr")
val junitVersion = versionProperty("versionJunit")
val mcholepunchVersion = versionProperty("versionMcholepunch")
val aircompressorVersion = versionProperty("versionAircompressor")

val deps =
	listOf(
		"io.netty:netty-all:$nettyVersion",
		"org.apache.logging.log4j:log4j-core:$log4jVersion",
		"com.google.code.gson:gson:$gsonVersion",
		"org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion",
		"org.tomlj:tomlj:$tomljVersion",
		"org.antlr:antlr4-runtime:$antlrVersion",
		"io.airlift:aircompressor:$aircompressorVersion",
	)

dependencies {
	implementation(":mcholepunch-core:$mcholepunchVersion")
	implementation(":mcholepunch-server-netty:$mcholepunchVersion")

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
	// ClientLeakTripwireTest scans the versioned targets' compiled classes. Ordering-only
	// (mustRunAfter, not dependsOn): version projects implement :core, so depending on their
	// tasks from here would form a project dependency cycle and break their compile classpath.
	mustRunAfter(
		rootProject.subprojects
			.filter { rootProject.file("versions/${it.name}").isDirectory }
			.map { it.tasks.named("compileJava") },
	)
}

// Configure the ShadowJar task
tasks.named<ShadowJar>("shadowJar") {
	archiveBaseName.set("automodpack-server")
	configurations = listOf(project.configurations.compileClasspath.get())
	duplicatesStrategy = DuplicatesStrategy.INCLUDE
	filesNotMatching("META-INF/*.kotlin_module") {
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	}

	manifest {
		attributes(
			"Main-Class" to "pl.skidam.automodpack_core.Server",
		)
	}
}

tasks.named("assemble") {
	dependsOn("shadowJar")
}
