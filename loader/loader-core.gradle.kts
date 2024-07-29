plugins {
    java
}

base {
    archivesName = property("mod_id") as String + "-" + project.name
    version =  property("mod_version") as String
    group = property("mod_group") as String
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))

    // our needed dependencies
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("org.tomlj:tomlj:1.1.1")
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