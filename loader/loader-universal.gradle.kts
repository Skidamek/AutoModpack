import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.luna5ama.jaroptimizer.OptimizeJarTask
import org.gradle.api.file.DuplicatesStrategy

// The one universal OUTER trampoline jar: every loader generation's entrypoints plus the three
// metadata files (fabric.mod.json, mods.toml, neoforge.mods.toml), published under every target.
// The root project's oneJar task optimizes this jar, then appends every target's impl as the
// solid impl/manifest.json + impl/all.zst pair - no nested jarjar discovery anywhere.

plugins {
	kotlin("jvm")
	id("automodpack.utils")
	id("automodpack.loader")
	id("com.gradleup.shadow")
	id("dev.luna5ama.jar-optimizer")
}

// Every module whose compiled output the shadowJar below bundles; evaluated first so their
// sourceSets are there when the shadowJar wires its inputs.
val bundledModules =
	listOf(
		":core",
		":loader-fabric-core",
		":loader-modlauncher-earlyservices",
		":loader-forge-earlyservices",
		":loader-forge-fml40",
		":loader-forge-fml47",
		":loader-neoforge-shared",
		":loader-neoforge-earlyservices",
		":loader-neoforge-fml4",
	)
bundledModules.forEach { evaluationDependsOn(it) }

repositories {
	mavenCentral()
	maven { url = uri("https://maven.fabricmc.net/") }
	flatDir {
		name = "mcholepunchLibs"
		dirs(rootProject.file("libs"))
	}
}

val bouncyCastleVersion = versionProperty("versionBouncyCastle")
val nettyVersion = versionProperty("versionNetty")
val mcholepunchVersion = versionProperty("versionMcholepunch")
val reconfVersion = versionProperty("versionReconf")
val aircompressorVersion = versionProperty("versionAircompressor")

dependencies {
	// Stuff to actually bundle
	implementation("io.airlift:aircompressor:$aircompressorVersion")
	implementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
	// Disable transitives so netty-buffer/common/transport aren't pulled in
	implementation("io.netty:netty-codec-haproxy:$nettyVersion") {
		isTransitive = false
	}

	// mcholepunch jars - shadowed into the loader so classes are available at
	// the root classpath (needed by the preload-stage client).
	implementation(":mcholepunch-core:$mcholepunchVersion")
	implementation(":mcholepunch-server-netty:$mcholepunchVersion")

	// reconf - the config format/edit layer for the human-editable configs (core's compile dep,
	// shadowed into the loader the same way as the mcholepunch jars).
	implementation(":reconf:$reconfVersion")
}

configurations {
	create("shadowImplementation") {
		extendsFrom(configurations.getByName("implementation"))
		isCanBeResolved = true
	}
}

tasks.named<ShadowJar>("shadowJar") {
	dependsOn(tasks.named("processResources"))
	archiveClassifier.set("")
	duplicatesStrategy = DuplicatesStrategy.INCLUDE
	filesNotMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	}

	// Combine every loader generation's output. The packages are split per generation, so the only
	// resource these modules may still share is nothing - all metadata and services live in THIS
	// module's resources, exactly once.
	bundledModules.forEach {
		from(
			project(it)
				.sourceSets.main
				.get()
				.output,
		)
	}

	configurations = listOf(project.configurations.getByName("shadowImplementation"))

	manifest {
		attributes("Multi-Release" to "true")
	}

	val reloc = "amp_libs"
	relocate("io.airlift.compress", "$reloc.io.airlift.compress")
	relocate("org.checkerframework", "$reloc.org.checkerframework")
	relocate("org.slf4j", "$reloc.org.slf4j")
	relocate("org.bouncycastle", "$reloc.org.bouncycastle")
	relocate("io.netty.handler.codec.haproxy", "$reloc.io.netty.handler.codec.haproxy")

	// Cleanup

	exclude("kotlin/**", "log4j2.xml")
	exclude("META-INF/maven/**", "META-INF/native-image/**", "META-INF/io.netty.versions.properties")
	exclude("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES*", "META-INF/LICENSE*", "META-INF/NOTICE*")
	exclude("META-INF/versions/**/OSGI-INF/**")
	exclude("META-INF/services/java.security.Provider")
	exclude("org/bouncycastle/pqc/legacy/picnic/*.properties")
	exclude("org/bouncycastle/pkix/CertPathReviewerMessages*.properties")
	exclude("org/bouncycastle/x509/CertPathReviewerMessages*.properties")

	mergeServiceFiles()
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
	toolchain.languageVersion.set(JavaLanguageVersion.of(17))
	withSourcesJar()
}

tasks.named<Jar>("jar") {
	isEnabled = false
}

// The outer keeps the optimizer pass the merged jar used to get; the oneJar task appends the impl
// entries only afterwards, so they stay STORE.
val optimizeUniversalJar =
	tasks.register<OptimizeJarTask>("optimizeUniversalJar") {
		dependsOn(tasks.named("shadowJar"))
		jarFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
		keeps.add("pl.skidam")
		archiveFileName.set(
			provider {
				tasks
					.named<ShadowJar>("shadowJar")
					.get()
					.archiveFileName
					.get()
					.replace(".jar", "-optimized.jar")
			},
		)
	}

tasks.named("assemble") {
	dependsOn("shadowJar")
	finalizedBy(optimizeUniversalJar)
}
