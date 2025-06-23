import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

base {
    archivesName = property("mod.id") as String + "-" + project.name
    version =  property("mod.version") as String
    group = property("mod.group") as String
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":loader-core"))
    compileOnly(project(":loader-fabric-15"))
    compileOnly(project(":loader-fabric-16"))

    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.8.1")

    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.5")

    compileOnly("net.fabricmc:fabric-loader:${property("deps.fabric")}")
}

configurations {
    create("shadowImplementation") {
        extendsFrom(configurations.getByName("implementation"))
        isCanBeResolved = true
    }
}

// TODO: make it less messy
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")

    from(project(":core").sourceSets.main.get().output)
    from(project(":loader-core").sourceSets.main.get().output)
    from(project(":loader-fabric-core").sourceSets.main.get().output)
    from(project(":loader-fabric-15").sourceSets.main.get().output)
    from(project(":loader-fabric-16").sourceSets.main.get().output)

    // Include the tomlj dependency in the shadow jar
    configurations = listOf(project.configurations.getByName("shadowImplementation"))

    val reloc = "am_libs"
    relocate("org.antlr", "${reloc}.org.antlr")
    relocate("org.tomlj", "${reloc}.org.tomlj")
    relocate("org.apache.hc", "${reloc}.org.apache.hc")
    relocate("org.checkerframework", "${reloc}.org.checkerframework")
    relocate("org.slf4j", "${reloc}.org.slf4j")
//    relocate("com.github.luben", "${reloc}.com.github.luben") // cant relocate - natives
    relocate("org.bouncycastle", "${reloc}.org.bouncycastle")

    relocate("pl.skidam.automodpack_loader_core_fabric", "pl.skidam.automodpack_loader_core")
    relocate("pl.skidam.automodpack_loader_master_core_fabric", "pl.skidam.automodpack_loader_core")

    exclude("pl/skidam/automodpack_loader_core/loader/LoaderManager.class")
    exclude("pl/skidam/automodpack_loader_core/mods/ModpackLoader.class")
    exclude("pl/skidam/automodpack_loader_core_fabric/FabricLanguageAdapter.class")
    exclude("pl/skidam/automodpack_loader_core_fabric/FabricLoaderImplAccessor.class")

    exclude("log4j2.xml")

    manifest {
        attributes["AutoModpack-Version"] = version
    }

    mergeServiceFiles()
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

tasks.named<Jar>("jar") {
    isEnabled = false
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}