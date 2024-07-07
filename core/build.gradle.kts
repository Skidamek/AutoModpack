import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.github.johnrengelman.shadow")
}

base {
    archivesName = rootProject.findProperty("archives_base_name") as String + "-" + project.name
    version = rootProject.findProperty("mod_version") as String
    group = rootProject.findProperty("maven_group") as String
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.netty:netty-all:4.1.90.Final")
    implementation("org.tomlj:tomlj:1.1.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

java {
    // leave it on java 17 to be compatible with older versions and we dont really need 21 there anyway
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

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
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest {
        attributes(
            "Main-Class" to "pl.skidam.automodpack_core.Server"
        )
    }
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}