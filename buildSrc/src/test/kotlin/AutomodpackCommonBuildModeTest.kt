import java.io.File
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AutomodpackCommonBuildModeTest {
	@TempDir
	lateinit var projectDir: File

	@BeforeEach
	fun createFixture() {
		projectDir.toPath().resolve("settings.gradle.kts").writeText(
			"""
			rootProject.name = "1.21.8-fabric"
			include(":core", ":loader-core", ":loader-fabric-core", ":loader-fabric-15", ":loader-fabric-16")
			project(":core").projectDir = file("core")
			project(":loader-core").projectDir = file("loader/core")
			project(":loader-fabric-core").projectDir = file("loader/fabric/core")
			project(":loader-fabric-15").projectDir = file("loader/fabric/15")
			project(":loader-fabric-16").projectDir = file("loader/fabric/16")
			""".trimIndent() + "\n",
		)
		projectDir.toPath().resolve("build.gradle.kts").writeText(
			"""
			plugins {
				java
				id("automodpack.common")
			}

			tasks.named("mergeJar") {
				enabled = false
			}
			""".trimIndent() + "\n",
		)
		for (module in listOf("core", "loader/core", "loader/fabric/core", "loader/fabric/15", "loader/fabric/16")) {
			projectDir.toPath().resolve(module).createDirectories()
			projectDir.toPath().resolve(module).resolve("build.gradle.kts").writeText("plugins { java }\n")
		}

		projectDir.toPath().resolve("src/main/java/pl/skidam/automodpack/client/autotest").createDirectories()
		projectDir.toPath().resolve("src/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java").writeText(
			"package pl.skidam.automodpack.client.autotest; public final class AutoTestBridge {}\n",
		)
		projectDir.toPath().resolve("src/main/java/pl/skidam/automodpack/mixin/dev").createDirectories()
		projectDir.toPath().resolve("src/main/java/pl/skidam/automodpack/mixin/dev/MinecraftMixin.java").writeText(
			"package pl.skidam.automodpack.mixin.dev; public final class MinecraftMixin {}\n",
		)
		projectDir.toPath().resolve("src/main/resources").createDirectories()
		projectDir.toPath().resolve("src/main/resources/automodpack-main.mixins.json").writeText(
			"{\"mixins\":[\"common.Mixin\",\"dev.MinecraftMixin\"]}\n",
		)
	}

	@Test
	fun `normal autotest normal builds produce their own artifacts`() {
		runGradle("jar")
		assertArtifact(hasAutotestCode = false, hasDevMixin = false)
		runGradle("jar", "-Pautomodpack.autotest")
		assertArtifact(hasAutotestCode = true, hasDevMixin = true)
		runGradle("jar")
		assertArtifact(hasAutotestCode = false, hasDevMixin = false)
	}

	private fun runGradle(vararg arguments: String) =
		GradleRunner.create()
			.withProjectDir(projectDir)
			.withPluginClasspath()
			.withArguments(*arguments)
			.forwardOutput()
			.build()

	private fun assertArtifact(hasAutotestCode: Boolean, hasDevMixin: Boolean) {
		val jar = projectDir.resolve("build/libs/1.21.8-fabric.jar")
		JarFile(jar).use { archive ->
			val entries = archive.entries().asSequence().map { it.name }.toSet()
			assertTrue(entries.contains("automodpack-main.mixins.json"))
			assertTrue(entries.contains("pl/skidam/automodpack/client/autotest/AutoTestBridge.class") == hasAutotestCode)
			assertTrue(entries.contains("pl/skidam/automodpack/mixin/dev/MinecraftMixin.class") == hasDevMixin)
			val config = archive.getInputStream(archive.getJarEntry("automodpack-main.mixins.json")).bufferedReader().readText()
			assertTrue(config.contains("dev.MinecraftMixin") == hasDevMixin)
		}
	}
}
