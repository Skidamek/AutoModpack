import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

// The one universal OUTER trampoline jar: every loader generation's entrypoints plus the three
// metadata files (fabric.mod.json, mods.toml, neoforge.mods.toml), published under every target.
// The nested per-target META-INF/jarjar/automodpack-mod.jar is merged in afterwards by MergeJarTask
// and mounted explicitly by each generation's entrypoints - no jars/metadata.json discovery anywhere.
evaluationDependsOn(":core")
evaluationDependsOn(":loader-fabric-shared")
evaluationDependsOn(":loader-fabric-core")
evaluationDependsOn(":loader-fabric-15")
evaluationDependsOn(":loader-fabric-16")
evaluationDependsOn(":loader-modlauncher-earlyservices")
evaluationDependsOn(":loader-forge-earlyservices")
evaluationDependsOn(":loader-forge-fml40")
evaluationDependsOn(":loader-forge-fml47")
evaluationDependsOn(":loader-neoforge-earlyservices")
evaluationDependsOn(":loader-neoforge-fml4")

plugins {
	kotlin("jvm")
	id("automodpack.utils")
	id("com.gradleup.shadow")
}

base {
	archivesName = property("mod.id") as String + "-loader-universal"
	version = property("mod_version") as String
	group = property("mod.group") as String
}

repositories {
	mavenCentral()
	maven { url = uri("https://maven.fabricmc.net/") }
	flatDir {
		name = "mcholepunchLibs"
		dirs(rootProject.file("libs"))
	}
}

val tomljVersion = versionProperty("versionTomlj")
val bouncyCastleVersion = versionProperty("versionBouncyCastle")
val nettyVersion = versionProperty("versionNetty")
val mcholepunchVersion = versionProperty("versionMcholepunch")
val aircompressorVersion = versionProperty("versionAircompressor")

dependencies {
	// Stuff to actually bundle
	implementation("io.airlift:aircompressor:$aircompressorVersion")
	implementation("org.tomlj:tomlj:$tomljVersion")
	implementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
	// Disable transitives so netty-buffer/common/transport aren't pulled in
	implementation("io.netty:netty-codec-haproxy:$nettyVersion") {
		isTransitive = false
	}

	// mcholepunch jars — shadowed into the loader so classes are available at
	// the root classpath (needed by the preload-stage client).
	implementation(":mcholepunch-core:$mcholepunchVersion")
	implementation(":mcholepunch-server-netty:$mcholepunchVersion")
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
	val subprojects =
		listOf(
			":core",
			":loader-fabric-shared",
			":loader-fabric-core",
			":loader-fabric-15",
			":loader-fabric-16",
			":loader-modlauncher-earlyservices",
			":loader-forge-earlyservices",
			":loader-forge-fml40",
			":loader-forge-fml47",
			":loader-neoforge-earlyservices",
			":loader-neoforge-fml4",
		)
	subprojects.forEach {
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
	relocate("org.antlr", "$reloc.org.antlr")
	relocate("org.tomlj", "$reloc.org.tomlj")
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

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
	isEnabled = false
}

tasks.named("assemble") {
	dependsOn("shadowJar")
}
