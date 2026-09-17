import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packs the ONE published jar: the optimized universal outer plus every target's optimized impl
 * jar as a solid zstd blob. Each impl jar is STORE-normalized (re-zipped with method 0 so the
 * solid compresses) and its `assets/` entries are stripped - assets ship once on the outer, taken
 * from the impls while stripping (byte-identical across targets, which is checked, not assumed).
 * `impl/all.zst` is `zstd --ultra -20 -T1` over a FILE so the frame header carries the uncompressed size the
 * runtime's aircompressor decode relies on; `impl/manifest.bin` indexes the solid. Both entries
 * are appended AFTER the optimizer ran, and appended as STORE - that ordering is what keeps them
 * uncompressed, because the optimizer never sees the impl entries.
 */
abstract class OneJarTask : DefaultTask() {
    /** Target id (e.g. "1.20.1-fabric") to its optimized impl jar path; explicit paths so a convention drift fails loudly instead of packing stale bytes. */
    @get:Input
    abstract val implJars: MapProperty<String, String>

    /** Target id to the exact Minecraft versions that target covers (its `publish_versions`); the manifest's version-resolution source of truth. */
    @get:Input
    abstract val implVersions: MapProperty<String, List<String>>

    /** The same files as implJars, for content tracking. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val implJarFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val outerJar: RegularFileProperty

    /** Release or autotest: the mode changes the impl jars' contents. */
    @get:Input
    abstract val buildMode: Property<String>

    @get:OutputFile
    abstract val oneJar: RegularFileProperty

    /** The zstd release that produced the solid; logged so cross-machine size deltas are explainable. */
    private var zstdVersion: String = "unknown"

    @TaskAction
    fun pack() {
        val startTime = System.currentTimeMillis()
        val implJarsById = implJars.get().mapValues { File(it.value) }
        val missing = implJarsById.filterValues { jar -> !jar.isFile }
        if (missing.isNotEmpty()) throw GradleException("Missing optimized impl jars for ${missing.keys.sorted()}; build the targets first")
        val versionsById = implVersions.get()
        val uncovered = implJarsById.keys - versionsById.keys
        if (uncovered.isNotEmpty()) throw GradleException("No covered Minecraft versions registered for ${uncovered.sorted()}; the manifest could not say who mounts them")

        val solid = ByteArrayOutputStream()
        val manifestEntries = mutableListOf<ImplManifestFormat.Entry>()
        // assets/ entries stripped from the impls, repacked once on the outer; CRC-identical across targets is asserted.
        val outerAssets = linkedMapOf<String, ByteArray>()
        val outerAssetsCrcs = hashMapOf<String, Long>()

        for (id in implJarsById.keys.sorted()) {
            val normalized = ByteArrayOutputStream()
            ZipOutputStream(normalized).use { output ->
                ZipInputStream(FileInputStream(implJarsById.getValue(id))).use { input ->
                    generateSequence { input.nextEntry }.forEach { entry ->
                        if (entry.isDirectory) return@forEach
                        val bytes = input.readBytes()
                        if (entry.name.startsWith("assets/")) {
                            val crc = CRC32().apply { update(bytes) }.value
                            outerAssetsCrcs[entry.name]?.let { known ->
                                if (known != crc) throw GradleException("Asset ${entry.name} differs between impl jars - assets must be byte-identical across targets")
                            }
                            outerAssetsCrcs[entry.name] = crc
                            outerAssets.putIfAbsent(entry.name, bytes)
                            return@forEach
                        }
                        putStored(output, ZipEntry(entry.name).apply { time = entry.time }, bytes)
                    }
                }
            }
            val bytes = normalized.toByteArray()
            manifestEntries.add(ImplManifestFormat.Entry(id, versionsById.getValue(id), solid.size().toLong(), bytes.size.toLong(), sha1(bytes)))
            solid.write(bytes)
        }
        if (outerAssets.isEmpty()) throw GradleException("No impl jar carried assets/ - the outer would ship without lang, textures or sounds")

        // The manifest's covered versions are the runtime's ONLY version-to-target resolution, so an overlap
        // would make that resolution order-dependent: a launch could mount either impl depending on sort order.
        val coveredBy = hashMapOf<String, String>()
        for (entry in manifestEntries) {
            val loader = entry.id.substringAfterLast('-')
            for (version in entry.versions) {
                val previous = coveredBy.putIfAbsent("$version-$loader", entry.id)
                if (previous != null && previous != entry.id) {
                    throw GradleException("Minecraft $version on $loader is covered by both $previous and ${entry.id} - impl selection would be ambiguous")
                }
            }
        }

        val solidBytes = solid.toByteArray()
        val zstdBinary = runZstd(solidBytes)
        val generation = sha1(zstdBinary)
        val manifest = ImplManifestFormat.write(generation, manifestEntries)

        val outputFile = oneJar.get().asFile
        // The one jar owns merged/: drop stale versions of itself, never the autotest fixtures beside them.
        outputFile.parentFile.listFiles()?.filter { it.name.startsWith("automodpack-") && it.name.endsWith(".jar") }?.forEach { it.delete() }
        outputFile.parentFile.mkdirs()
        ZipOutputStream(FileOutputStream(outputFile).buffered()).use { output ->
            copyOuter(output)
            // Assets deflate: they are the same lang/texture bytes that used to ship deflate-compressed inside every impl, and only the impl slices must stay STORE.
            for ((name, bytes) in outerAssets.entries.sortedBy { it.key }) putDeflated(output, ZipEntry(name).apply { time = 0L }, bytes)
            putStored(output, ZipEntry(ImplManifestFormat.MANIFEST_ENTRY).apply { time = 0L }, manifest)
            putStored(output, ZipEntry(ImplManifestFormat.SOLID_ENTRY).apply { time = 0L }, zstdBinary)
        }
        println(
            "Packed ${outputFile.name}: ${outputFile.length()} bytes, ${manifestEntries.size} impls, solid ${solidBytes.size} bytes -> zstd ${zstdBinary.size} bytes (${zstdVersion})" +
                " (${manifestEntries.sumOf { it.length }} impl bytes), took ${System.currentTimeMillis() - startTime}ms",
        )
    }

    /** Copies the optimized outer's entries, preserving each entry's own compression method. Directory entries are dropped: zip readers synthesize them and they are 583 dead zero-byte entries otherwise. */
    private fun copyOuter(output: ZipOutputStream) {
        val seen = mutableSetOf<String>()
        ZipInputStream(FileInputStream(outerJar.get().asFile)).use { input ->
            generateSequence { input.nextEntry }.forEach { entry ->
                if (entry.isDirectory) return@forEach
                if (!seen.add(entry.name)) throw GradleException("Duplicate entry ${entry.name} in the outer jar")
                val bytes = input.readBytes()
                val copy = ZipEntry(entry.name).apply {
                    time = entry.time
                }
                if (entry.method == ZipEntry.STORED) {
                    putStored(output, copy, bytes)
                } else {
                    // Everything else re-enters as DEFLATE: the optimized outer is STORE/DEFLATE only (method 93 is unreadable on Java 17).
                    copy.method = ZipEntry.DEFLATED
                    output.putNextEntry(copy)
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
    }

    private fun putStored(output: ZipOutputStream, entry: ZipEntry, bytes: ByteArray) {
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private fun putDeflated(output: ZipOutputStream, entry: ZipEntry, bytes: ByteArray) {
        entry.method = ZipEntry.DEFLATED
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    /** `zstd --ultra -20 -T1` over a real file so the frame header carries the uncompressed size. The level is a measured point, not a preference: on the shipped 22 MB solid -19 packed 924800 bytes in 1.3s, -20 packed 919915 in 1.6s, -21/-22 bought under 100 more bytes and --max bought 919010 for 18.5s, so -20 is the whole curve worth paying for. -T1 pins single-threading because threaded zstd changes its output with core count, and the version rides in the pack log: zstd does not promise byte-identical output across its own releases, so a size delta between machines needs both receipts. */
    private fun runZstd(solidBytes: ByteArray): ByteArray {
        val version = try {
            val probe = ProcessBuilder("zstd", "--version").start()
            if (probe.waitFor() != 0) throw GradleException("zstd --version failed with exit code ${probe.exitValue()}")
            Regex("(\\d+\\.\\d+\\.\\d+)").find(String(probe.inputStream.readAllBytes(), StandardCharsets.UTF_8))?.groupValues?.get(1) ?: "unknown"
        } catch (e: IOException) {
            throw GradleException("The zstd binary is required to pack the one jar but is not on PATH - install it (linux: the zstd package, macOS: brew install zstd, windows: winget install YannCollet.Zstandard)", e)
        }
        val solidFile = File.createTempFile("automodpack-solid-", ".bin")
        val zstFile = File.createTempFile("automodpack-solid-", ".zst")
        try {
            solidFile.writeBytes(solidBytes)
            val process = ProcessBuilder("zstd", "--ultra", "-20", "-T1", "-q", "-f", solidFile.absolutePath, "-o", zstFile.absolutePath).redirectErrorStream(true).start()
            val output = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
            if (process.waitFor() != 0) throw GradleException("zstd -20 failed: ${output.trim()}")
            zstdVersion = version
            return zstFile.readBytes()
        } finally {
            solidFile.delete()
            zstFile.delete()
        }
    }

    private fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)
}
