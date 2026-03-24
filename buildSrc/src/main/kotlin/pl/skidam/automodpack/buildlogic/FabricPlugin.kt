package pl.skidam.automodpack.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

class FabricPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val isUnobf = isUnobfFabricTarget()

        if (isUnobf) {
            setupNewLoomFacade()
        } else {
            setupOldLoomFacade()
        }

        extensions.create("fabric", FabricExtension::class.java, this, isUnobf)
    }

    private fun Project.isUnobfFabricTarget(): Boolean {
        val minecraftLine = name.substringBeforeLast('-')
        val major = minecraftLine.substringBefore('.').toIntOrNull() ?: return false
        return major >= 26
    }

    private fun Project.setupNewLoomFacade() {
        plugins.apply("net.fabricmc.fabric-loom")

        listOf(
            "api",
            "implementation",
            "compileOnly",
            "runtimeOnly",
            "localRuntime"
        ).forEach { standard ->
            createLoomFacade("mod" + standard.replaceFirstChar(Char::uppercaseChar), standard)
        }

        configurations.maybeCreate("mappings").apply {
            isCanBeResolved = false
            isCanBeConsumed = false
        }
    }

    private fun Project.setupOldLoomFacade() {
        plugins.apply("net.fabricmc.fabric-loom-remap")
    }

    private fun Project.createLoomFacade(aliasName: String, standardName: String) {
        val alias = configurations.maybeCreate(aliasName).apply {
            isCanBeResolved = false
            isCanBeConsumed = false
        }

        configurations.matching { it.name == standardName }.all {
            extendsFrom(alias)
        }
    }

    open class FabricExtension(private val project: Project, val isUnobf: Boolean) {
        val accessWidenerPath: String =
            if (isUnobf) "src/main/resources/automodpack.unobf.accesswidener"
            else "src/main/resources/automodpack.accesswidener"

        val modJar: TaskProvider<Jar> by lazy {
            project.tasks.named(if (isUnobf) "jar" else "remapJar", Jar::class.java)
        }

        val modSourcesJar: TaskProvider<Jar> by lazy {
            project.tasks.named(if (isUnobf) "sourcesJar" else "remapSourcesJar", Jar::class.java)
        }
    }
}
