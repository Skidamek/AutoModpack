plugins {
    java
}

base {
    archivesName = property("mod.id") as String + "-" + project.name
    version =  property("mod_version") as String
    group = property("mod.group") as String
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://libraries.minecraft.net/") }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":loader-core"))

    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("org.tomlj:tomlj:1.1.1")

    implementation("net.fabricmc:fabric-loader:${property("deps.fabric")}")
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
