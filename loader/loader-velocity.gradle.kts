import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.github.johnrengelman.shadow")
}

base {
    archivesName = property("mod_id") as String + project.name.replace("loader", "")
    version = property("mod_version") as String
    group = property("mod_group") as String
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    implementation(project(":core"))
    implementation(project(":loader-core"))

    // our needed dependencies
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("org.tomlj:tomlj:1.1.1")
}

configurations {
    create("shadowImplementation") {
        extendsFrom(configurations.getByName("implementation"))
        isCanBeResolved = true
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()

    // Include the tomlj dependency in the shadow jar
    configurations = listOf(project.configurations.getByName("shadowImplementation"))

    relocate("org.antlr.v4", "reloc.org.antlr.v4")
    relocate("org.tomlj", "reloc.org.tomlj")
    relocate("org.checkerframework", "reloc.org.checkerframework")

    exclude("log4j2.xml")

    manifest {
        attributes["AutoModpack-Version"] = version
    }
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

tasks.named("assemble") {
    dependsOn("shadowJar")
}