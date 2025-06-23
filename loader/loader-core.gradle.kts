plugins {
    kotlin("jvm")
}

base {
    archivesName = property("mod.id") as String + "-" + project.name
    version =  property("mod.version") as String
    group = property("mod.group") as String
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))

    // our needed dependencies
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.logging.log4j:log4j-core:2.19.0")
    implementation("org.tomlj:tomlj:1.1.1")
}

java { // leave it on java 17 to be compatible with older versions and we dont really need 21 there anyway
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}