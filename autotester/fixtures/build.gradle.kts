plugins {
	java
}

repositories {
	mavenCentral()
	maven("https://maven.fabricmc.net/")
	maven("https://repo.spongepowered.org/repository/maven-public/")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val connectorFixture = sourceSets.create("connectorFixture")
val launchpadFixture = sourceSets.create("launchpadFixture")

dependencies {
	for (sourceSet in listOf(connectorFixture, launchpadFixture)) {
		add(sourceSet.compileOnlyConfigurationName, "net.fabricmc:fabric-loader:0.18.4")
		add(sourceSet.compileOnlyConfigurationName, "org.spongepowered:mixin:0.8.7")
	}
}

fun registerFixtureJar(
	name: String,
	sourceSet: SourceSet,
	fileName: String,
) = tasks.register<Jar>(name) {
	dependsOn(sourceSet.classesTaskName)
	from(sourceSet.output)
	archiveFileName.set(fileName)
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("merged/autotest-fixtures"))
	isPreserveFileTimestamps = false
	isReproducibleFileOrder = true
}

val connectorFixtureJar = registerFixtureJar("connectorFixtureJar", connectorFixture, "connector-downstream-26.1.jar")
val launchpadFixtureJar = registerFixtureJar("launchpadFixtureJar", launchpadFixture, "launchpad-downstream-26.1.jar")

tasks.jar {
	enabled = false
}

tasks.build {
	dependsOn(connectorFixtureJar, launchpadFixtureJar)
}
