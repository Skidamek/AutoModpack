import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.jar.JarFile
import java.util.zip.ZipEntry

/**
 * Audits the ONE jar. Receipts: the packed one-jar measured 3560659 bytes (deflated outer assets, 22
 * STORE impls in the zstd solid, no zip directory entries), so the 5 MiB budget is the tripwire past where a good
 * build never goes, not a target. The waiting music is checked on the outer `assets/automodpack/`
 * tree - the only place assets may live (the one jar strips them from every impl, so an outer asset
 * entry is by construction the only copy). The manifest is parsed with the build-side implementation
 * while the runtime parses with core's, so a format drift fails here instead of surviving both.
 */
abstract class OneJarAuditTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val oneJar: RegularFileProperty

    /** The target ids the manifest must carry, exactly. */
    @get:Input
    abstract val expectedIds: ListProperty<String>

    @get:Input
    abstract val maxJarBytes: Property<Long>

    @get:Input
    abstract val enforceReleaseSizeBudget: Property<Boolean>

    @get:Input
    abstract val maxMusicBytes: Property<Long>

    @TaskAction
    fun audit() {
        val jarFile = oneJar.get().asFile
        if (!jarFile.isFile) throw GradleException("One jar not found: ${jarFile.absolutePath}")
        if (enforceReleaseSizeBudget.get() && jarFile.length() > maxJarBytes.get()) {
            throw GradleException("${jarFile.name} is ${jarFile.length()} bytes, exceeding the ${maxJarBytes.get()} byte one-jar budget")
        }

        val prohibitedOuterPaths = listOf(
            "amp_libs/org/apache/hc/",
            "amp_libs/org/publicsuffix/",
            "amp_libs/org/bouncycastle/jcajce/provider/",
            "amp_libs/org/bouncycastle/pqc/",
        )
        val groupedSizes = mutableMapOf<String, Long>()
        var musicSize: Long? = null

        JarFile(jarFile).use { jar ->
            val manifestEntry = jar.getEntry(ImplManifestFormat.MANIFEST_ENTRY)
                ?: throw GradleException("${ImplManifestFormat.MANIFEST_ENTRY} is missing from ${jarFile.name}")
            if (manifestEntry.method != ZipEntry.STORED) throw GradleException("${ImplManifestFormat.MANIFEST_ENTRY} must be STORE, not method ${manifestEntry.method}")
            val solidEntry = jar.getEntry(ImplManifestFormat.SOLID_ENTRY)
                ?: throw GradleException("${ImplManifestFormat.SOLID_ENTRY} is missing from ${jarFile.name}")
            if (solidEntry.method != ZipEntry.STORED) throw GradleException("${ImplManifestFormat.SOLID_ENTRY} must be STORE, not method ${solidEntry.method}")

            val manifest = try {
                ImplManifestFormat.parse(jar.getInputStream(manifestEntry).readBytes())
            } catch (e: IllegalStateException) {
                throw GradleException("${ImplManifestFormat.MANIFEST_ENTRY} in ${jarFile.name} does not parse: ${e.message}")
            }
            val expected = expectedIds.get().sorted()
            val actual = manifest.entries.map { it.id }.sorted()
            if (actual != expected) {
                throw GradleException("${ImplManifestFormat.MANIFEST_ENTRY} carries ${actual.joinToString()} but the build selected ${expected.joinToString()}")
            }

            val assetNames = mutableSetOf<String>()
            jar.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val prohibitedPath = prohibitedOuterPaths.firstOrNull(entry.name::startsWith)
                if (prohibitedPath != null || entry.name == "META-INF/services/java.security.Provider" || entry.name.contains("/lowmcL")) {
                    throw GradleException("Prohibited content remains in ${jarFile.name}: ${entry.name}")
                }
                if (entry.name.startsWith(JARJAR_PREFIX)) throw GradleException("The one jar must not nest a jar under $JARJAR_PREFIX: ${entry.name}")
                if (entry.name == OLD_MUSIC_PATH || entry.name == OLD_MUSIC_LICENSE_PATH) {
                    throw GradleException("Old Bensound content remains in ${jarFile.name}: ${entry.name}")
                }
                if (entry.name == WAITING_MUSIC_PATH) musicSize = entry.size
                val compressedSize = entry.compressedSize.coerceAtLeast(0)
                groupedSizes.merge(groupName(entry.name), compressedSize) { current, added -> current + added }
                if (entry.name.startsWith("assets/")) assetNames.add(entry.name)
            }

            // Assets live on the outer once; if the outer carries none, the strip side of the pack failed too.
            if (assetNames.none { it == WAITING_MUSIC_PATH }) throw GradleException("$WAITING_MUSIC_PATH is missing from ${jarFile.name}")
        }

        val packagedMusicSize = musicSize ?: throw GradleException("$WAITING_MUSIC_PATH is missing from ${jarFile.name}")
        if (packagedMusicSize > maxMusicBytes.get()) {
            throw GradleException("Waiting music in ${jarFile.name} is $packagedMusicSize bytes, exceeding the ${maxMusicBytes.get()} byte budget")
        }

        val largestGroups = groupedSizes.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" }
        println("Audited ${jarFile.name}: ${jarFile.length()} bytes; largest groups: $largestGroups")
    }

    private fun groupName(path: String): String {
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
        private const val JARJAR_PREFIX = "META-INF/jarjar/"
        private const val WAITING_MUSIC_PATH = "assets/automodpack/sounds/music/waiting.ogg"
        private const val OLD_MUSIC_PATH = "assets/automodpack/sounds/music/theelevatorbossanova.ogg"
        private const val OLD_MUSIC_LICENSE_PATH = "assets/automodpack/sounds/music/music-license"
    }
}
