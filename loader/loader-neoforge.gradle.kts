import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("automodpack.utils")
    id("net.neoforged.moddev")
    id("com.gradleup.shadow")
}

base {
    archivesName = property("mod.id") as String + "-" + project.name
    version = property("mod_version") as String
    group = property("mod.group") as String
}

neoForge {
    enable {
        version = property("deps.neoforge") as String
        isDisableRecompilation = true
    }
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(project(":loader-core"))

    // External provided deps to compile this
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.apache.logging.log4j:log4j-core:2.8.1")

    // Stuff to actually bundle
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.83")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.5.1")
    // Disable transitives so netty-buffer/common/transport aren't pulled in
    implementation("io.netty:netty-codec-haproxy:4.2.9.Final") {
        isTransitive = false
    }
    implementation("com.h2database:h2-mvstore:2.4.240")
}

configurations {
    create("shadowImplementation") {
        extendsFrom(configurations.getByName("implementation"))
        isCanBeResolved = true
    }
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(tasks.named("processResources"))
    archiveClassifier.set("")

    // Combine all subproject outputs efficiently
    val subprojects = listOf(":core", ":loader-core")
    subprojects.forEach {
        from(project(it).sourceSets.main.get().output)
    }

    configurations = listOf(project.configurations.getByName("shadowImplementation"))

    val reloc = "amp_libs"
    relocate("org.antlr", "${reloc}.org.antlr")
    relocate("org.tomlj", "${reloc}.org.tomlj")
    relocate("org.apache.hc", "${reloc}.org.apache.hc")
    relocate("org.checkerframework", "${reloc}.org.checkerframework")
    relocate("org.slf4j", "${reloc}.org.slf4j")
    relocate("org.bouncycastle", "${reloc}.org.bouncycastle")
    relocate("io.netty.handler.codec.haproxy", "${reloc}.io.netty.handler.codec.haproxy")

    // Project internal relocations
    relocate("pl.skidam.automodpack_loader_core_neoforge", "pl.skidam.automodpack_loader_core")

    // Cleanup
    exclude("pl/skidam/automodpack_loader_core/loader/LoaderManager.class")
    exclude("pl/skidam/automodpack_loader_core/mods/ModpackLoader.class")

    exclude("kotlin/**", "log4j2.xml")
    exclude("META-INF/maven/**", "META-INF/native-image/**", "META-INF/io.netty.versions.properties")

    mergeServiceFiles()
}


java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}
