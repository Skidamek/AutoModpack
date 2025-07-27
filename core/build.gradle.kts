import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
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

dependencies {
    implementation("org.apache.logging.log4j:log4j-core:2.19.0")
    implementation("com.google.code.gson:gson:2.10.1")
    compileOnly("io.netty:netty-all:4.1.118.Final")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.5")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
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