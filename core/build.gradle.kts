import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.language.jvm.tasks.ProcessResources

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
	maven("https://skidamek.github.io/maven/") { name = "Skidamek" }
	flatDir {
		name = "mcholepunchLibs"
		dirs(rootProject.file("libs"))
	}
}

val nettyVersion = versionProperty("versionNetty")
val log4jVersion = versionProperty("versionLog4j")
val gsonVersion = versionProperty("versionGson")
val bouncyCastleVersion = versionProperty("versionBouncyCastle")
val junitVersion = versionProperty("versionJunit")
val mcholepunchVersion = versionProperty("versionMcholepunch")
val tomlEditVersion = versionProperty("versionTomlEdit")
val aircompressorVersion = versionProperty("versionAircompressor")

val deps =
	listOf(
		"io.netty:netty-all:$nettyVersion",
		"org.apache.logging.log4j:log4j-core:$log4jVersion",
		"com.google.code.gson:gson:$gsonVersion",
		// zstd-only, Unsafe-free fork of io.airlift:aircompressor from Skidamek Maven
		"pl.skidam:aircompressor:$aircompressorVersion",
	)

dependencies {
	implementation("pl.skidam:mcholepunch-core:$mcholepunchVersion") { isTransitive = false }
	implementation("pl.skidam:toml-edit:$tomlEditVersion")
	implementation("pl.skidam:mcholepunch-server-netty:$mcholepunchVersion") { isTransitive = false }

	// minecraft/loaders uses these, so we cant just implement them because it wont resolve in gradle
	deps.forEach { compileOnly(it) }
	deps.forEach { runtimeOnly(it) }
	deps.forEach { testImplementation(it) }

	// Tests fabricate a CA, its leaves and its CRLs with it, so the parser meets a third party's encoding; no production
	// code may name it - core derives and signs its own TLS certificate with the JDK.
	testImplementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
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
	options.debugOptions.debugLevel = "lines,source"
}

val compressOneCrlSnapshot =
	tasks.register<CompressOneCrlTask>("compressOneCrlSnapshot") {
		source.set(layout.projectDirectory.file("src/main/onecrl/onecrl.json"))
		output.set(layout.buildDirectory.file("generated/onecrl.json.zst"))
	}

tasks.named<ProcessResources>("processResources") {
	dependsOn(compressOneCrlSnapshot)
	from(compressOneCrlSnapshot.flatMap { it.output })
}

tasks.named<Test>("test") {
	useJUnitPlatform {
		// Neither of these decides anything about the code. One needs minutes and real sockets to measure, the other needs
		// outbound internet and the cloudflared binary. Run them with -Pinclude.bench or -Pinclude.live when you want them.
		if (!providers.gradleProperty("include.bench").isPresent) excludeTags("bench")
		if (!providers.gradleProperty("include.live").isPresent) excludeTags("live")
	}
	testLogging {
		exceptionFormat = TestExceptionFormat.FULL
	}
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
