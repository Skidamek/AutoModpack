import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

// fml10 and fml11 (NeoForge 21.6+) share one early-service implementation
// (:loader-neoforge-earlyservices): that NeoForge generation removed ModLauncher/securejarhandler
// entirely in favor of a flat FMLLoader-owned classloader chain, so their in-place-loading bridge
// works completely differently from fml4's (NeoForge 21.1) ModLauncher-module-layer approach. fml4
// instead shares :loader-modlauncher-earlyservices with legacy Forge - both still run the original
// ModLauncher/securejarhandler machinery, so the GAME-classloader bridge mechanics are identical;
// only the SPI-specific EarlyServiceLayer/EarlyServiceBootstrapper stay in fml4's own sources.
val earlyServicesModule =
	if (project.name == "loader-neoforge-fml4") {
		"loader-modlauncher-earlyservices"
	} else {
		"loader-neoforge-earlyservices"
	}

// Forces these to configure before us: shadowJar (below) reads their sourceSets output at
// configuration time via a lazy tasks.named{} block, which - unlike the dependencies{} block -
// configuration-on-demand does not always reach in time otherwise (surfaces when this project is
// built standalone, e.g. `gradlew :loader-neoforge-fml4:build`, rather than as part of a full build).
evaluationDependsOn(":core")
evaluationDependsOn(":loader-core")
evaluationDependsOn(":$earlyServicesModule")

plugins {
	kotlin("jvm")
	id("automodpack.utils")
	id("net.neoforged.moddev")
	id("com.gradleup.shadow")
}

val neoForgeVersion = loaderVersion()
val gsonVersion = versionProperty("versionLoaderGson")
val log4jVersion = versionProperty("versionLoaderPlatformLog4j")
val tomljVersion = versionProperty("versionTomlj")
val bouncyCastleVersion = versionProperty("versionBouncyCastle")
val httpClientVersion = versionProperty("versionHttpClient")
val nettyVersion = versionProperty("versionNetty")
val h2Version = versionProperty("versionH2")

base {
	archivesName = property("mod.id") as String + "-" + project.name
	version = property("mod_version") as String
	group = property("mod.group") as String
}

neoForge {
	enable {
		version = neoForgeVersion
		isDisableRecompilation = true
	}
}

dependencies {
	compileOnly(project(":core"))
	compileOnly(project(":loader-core"))
	compileOnly(project(":$earlyServicesModule"))

	// External provided deps to compile this
	compileOnly("com.google.code.gson:gson:$gsonVersion")
	compileOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")

	// Stuff to actually bundle
	implementation("org.tomlj:tomlj:$tomljVersion")
	implementation("org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
	implementation("org.apache.httpcomponents.client5:httpclient5:$httpClientVersion")
	// Disable transitives so netty-buffer/common/transport aren't pulled in
	implementation("io.netty:netty-codec-haproxy:$nettyVersion") {
		isTransitive = false
	}
	implementation("com.h2database:h2-mvstore:$h2Version")
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

	// Combine all subproject outputs efficiently
	val subprojects = listOf(":core", ":loader-core", ":$earlyServicesModule")
	subprojects.forEach {
		from(
			project(it)
				.sourceSets.main
				.get()
				.output,
		)
	}

	configurations = listOf(project.configurations.getByName("shadowImplementation"))

	val reloc = "amp_libs"
	relocate("org.antlr", "$reloc.org.antlr")
	relocate("org.tomlj", "$reloc.org.tomlj")
	relocate("org.apache.hc", "$reloc.org.apache.hc")
	relocate("org.checkerframework", "$reloc.org.checkerframework")
	relocate("org.slf4j", "$reloc.org.slf4j")
	relocate("org.bouncycastle", "$reloc.org.bouncycastle")
	relocate("io.netty.handler.codec.haproxy", "$reloc.io.netty.handler.codec.haproxy")

	// Project internal relocations
	relocate("pl.skidam.automodpack_loader_core_neoforge", "pl.skidam.automodpack_loader_core")
	// Only present for fml4 (:loader-modlauncher-earlyservices); harmless no-op relocate otherwise.
	relocate("pl.skidam.automodpack_loader_core_modlauncher", "pl.skidam.automodpack_loader_core")

	// Cleanup
	exclude("pl/skidam/automodpack_loader_core/loader/LoaderManager.class")
	exclude("pl/skidam/automodpack_loader_core/mods/ModpackLoader.class")

	exclude("kotlin/**", "log4j2.xml")
	exclude("META-INF/maven/**", "META-INF/native-image/**", "META-INF/io.netty.versions.properties")
	exclude("META-INF/services/java.security.Provider")

	mergeServiceFiles()
}

java {
	val javaVersion = findProperty("deps.java") as String
	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
	toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
	withSourcesJar()
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

tasks.named("assemble") {
	dependsOn("shadowJar")
}
