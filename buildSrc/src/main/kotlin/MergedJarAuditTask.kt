import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayInputStream
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

abstract class MergedJarAuditTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedJar: RegularFileProperty

    @get:Input
    abstract val maxJarBytes: Property<Long>

	@get:Input
	abstract val enforceReleaseSizeBudget: Property<Boolean>

    @get:Input
    abstract val maxMusicBytes: Property<Long>

    @TaskAction
    fun audit() {
        val jarFile = mergedJar.get().asFile
        if (!jarFile.isFile) throw GradleException("Merged jar not found: ${jarFile.absolutePath}")
		if (enforceReleaseSizeBudget.get() && jarFile.length() > maxJarBytes.get()) {
            throw GradleException("${jarFile.name} is ${jarFile.length()} bytes, exceeding the ${maxJarBytes.get()} byte merged-jar budget")
        }

        val prohibitedOuterPaths = listOf(
            "amp_libs/org/apache/hc/",
            "amp_libs/org/publicsuffix/",
            "amp_libs/org/bouncycastle/jcajce/provider/",
            "amp_libs/org/bouncycastle/pqc/",
        )
        val groupedSizes = mutableMapOf<String, Long>()
        var nestedJar: ByteArray? = null

        JarFile(jarFile).use { jar ->
            jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val prohibitedPath = prohibitedOuterPaths.firstOrNull(entry.name::startsWith)
                if (prohibitedPath != null || entry.name == "META-INF/services/java.security.Provider" || entry.name.contains("/lowmcL")) {
                    throw GradleException("Prohibited content remains in ${jarFile.name}: ${entry.name}")
                }

                val compressedSize = entry.compressedSize.coerceAtLeast(0)
                groupedSizes.merge(groupName(entry.name), compressedSize) { current, added -> current + added }
                if (entry.name == NESTED_JAR_PATH) nestedJar = jar.getInputStream(entry).use { it.readBytes() }
            }
        }

        val nestedBytes = nestedJar ?: throw GradleException("$NESTED_JAR_PATH is missing from ${jarFile.name}")
        auditNestedJar(jarFile.name, nestedBytes)

        val largestGroups = groupedSizes.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" }
        println("Audited ${jarFile.name}: ${jarFile.length()} bytes; largest groups: $largestGroups")
    }

    private fun auditNestedJar(jarName: String, nestedJar: ByteArray) {
        var musicSize: Int? = null
        var soundsDefinition: String? = null

        ZipInputStream(ByteArrayInputStream(nestedJar)).use { input ->
            generateSequence { input.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                when (entry.name) {
                    WAITING_MUSIC_PATH -> musicSize = input.readBytes().size
                    SOUNDS_DEFINITION_PATH -> soundsDefinition = input.readBytes().toString(Charsets.UTF_8)
                }
                if (entry.name == OLD_MUSIC_PATH || entry.name == OLD_MUSIC_LICENSE_PATH) {
                    throw GradleException("Old Bensound content remains in $jarName: ${entry.name}")
                }
            }
        }

        val packagedMusicSize = musicSize ?: throw GradleException("$WAITING_MUSIC_PATH is missing from nested jar in $jarName")
        if (packagedMusicSize.toLong() > maxMusicBytes.get()) {
            throw GradleException("Waiting music in $jarName is $packagedMusicSize bytes, exceeding the ${maxMusicBytes.get()} byte budget")
        }

        val soundsJson = soundsDefinition ?: throw GradleException("$SOUNDS_DEFINITION_PATH is missing from nested jar in $jarName")
        if (!soundsJson.contains("automodpack:music/waiting") || !soundsJson.contains("\"stream\": true")) {
            throw GradleException("$SOUNDS_DEFINITION_PATH does not reference the streamed waiting loop in $jarName")
        }
    }

    private fun groupName(path: String): String {
        if (path == NESTED_JAR_PATH) return NESTED_JAR_PATH
        if (path.startsWith("META-INF/")) return "META-INF"

        val parts = path.split('/')
        val segments = when {
            path.startsWith("amp_libs/org/") -> 3
            path.startsWith("amp_libs/") -> 4
            path.startsWith("pl/skidam/") -> 3
            else -> 1
        }
        return parts.take(segments).joinToString("/")
    }

    companion object {
        private const val NESTED_JAR_PATH = "META-INF/jarjar/automodpack-mod.jar"
        private const val SOUNDS_DEFINITION_PATH = "assets/automodpack/sounds.json"
        private const val WAITING_MUSIC_PATH = "assets/automodpack/sounds/music/waiting.ogg"
        private const val OLD_MUSIC_PATH = "assets/automodpack/sounds/music/theelevatorbossanova.ogg"
        private const val OLD_MUSIC_LICENSE_PATH = "assets/automodpack/sounds/music/music-license"
    }
}
