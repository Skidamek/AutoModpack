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

val deps = listOf(
    "io.netty:netty-all:4.2.9.Final",
    "org.apache.logging.log4j:log4j-core:2.25.2",
    "com.google.code.gson:gson:2.13.2",
    "org.bouncycastle:bcpkix-jdk18on:1.82",
    "org.apache.httpcomponents.client5:httpclient5:5.5.1",
    "org.tomlj:tomlj:1.1.1"
)

dependencies {
    // minecraft/loaders uses these, so we cant just implement them because it wont resolve in gradle
    deps.forEach { compileOnly(it) }
    deps.forEach { testImplementation(it) }

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.1")
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
            "Main-Class" to "pl.skidam.automodpack_core.Server"
        )
    }
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}