plugins {
    java
}

base {
    archivesName = rootProject.findProperty("archives_base_name") as String + "-" + project.name
    version =  rootProject.findProperty("mod_version") as String
    group = rootProject.findProperty("maven_group") as String
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))

    // our needed dependencies
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}