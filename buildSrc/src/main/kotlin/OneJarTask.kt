import com.fasterxml.jackson.databind.ObjectMapper
import com.github.luben.zstd.Zstd
import io.airlift.compress.zstd.ZstdDecompressor
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
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Packs the ONE published jar: the optimized universal outer plus every target's optimized impl
 * jar as a solid zstd blob. Each impl jar is STORE-normalized (re-zipped with method 0 so the
 * solid compresses) and its `assets/` entries are stripped - assets ship once on the outer, taken
 * from the impls while stripping (byte-identical across targets, which is checked, not assumed).
 * `impl/all.zst` is one-shot zstd level 20 over the whole solid, compressed by the pinned zstd-jni
 * natives (single-threaded by nature, one version everywhere, no CLI on PATH); the frame header
 * carries the uncompressed size the runtime's aircompressor decode relies on. `impl/manifest.json`
 * indexes the solid. Both entries are appended AFTER the optimizer ran, and appended as STORE -
 * that ordering is what keeps them uncompressed, because the optimizer never sees the impl entries.
 * `mc-protocols.json` mirrors the covers verbatim (one key per cover, a tilde key's number
 * answering the whole patch line) for the holepunch handshake.
 */
/** One-shot level 20: the measured sweet spot on the shipped solid (-19 gave up 4.9 KB more, -21/-22 under 100 bytes each, --max 905 bytes for 12 s) and single-threaded by nature, so the bytes cannot drift with core count. */
private const val ZSTD_LEVEL = 20

/** The packed vanilla protocol table (covered Minecraft version to number), read by core's MinecraftProtocols for the holepunch handshake. */
const val PROTOCOLS_ENTRY = "mc-protocols.json"

abstract class OneJarTask : DefaultTask() {
    /** Target id (e.g. "1.20.1-fabric") to its optimized impl jar path; explicit paths so a convention drift fails loudly instead of packing stale bytes. */
    @get:Input
    abstract val implJars: MapProperty<String, String>

    /** Target id to the Minecraft versions that target covers (its `publish_versions`, exact or `~` dotted-prefix); the manifest's version-resolution source of truth. */
    @get:Input
    abstract val implVersions: MapProperty<String, List<String>>

    /** Cover to its vanilla protocol number (the covers' verbatim mirror), packed as [PROTOCOLS_ENTRY] for the holepunch handshake. */
    @get:Input
    abstract val protocols: MapProperty<String, Int>

    /** The pinned zstd-jni release; an input, so bumping the compressor repacks the jar. */
    @get:Input
    abstract val zstdVersion: Property<String>

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
                    val entries = generateSequence { input.nextEntry }.mapNotNull { entry ->
                        if (entry.isDirectory) return@mapNotNull null
                        val bytes = input.readBytes()
                        if (entry.name.startsWith("assets/")) {
                            val crc = CRC32().apply { update(bytes) }.value
                            outerAssetsCrcs[entry.name]?.let { known ->
                                if (known != crc) throw GradleException("Asset ${entry.name} differs between impl jars - assets must be byte-identical across targets")
                            }
                            outerAssetsCrcs[entry.name] = crc
                            outerAssets.putIfAbsent(entry.name, bytes)
                            return@mapNotNull null
                        }
                        entry.name to bytes
                    }.toMap()
                    // Sorted names and zeroed timestamps: the producers stamp file mtimes and fs
                    // order into their zips, and the packer normalizes both away so the solid - and
                    // everything hashed from it - is a pure function of the impl contents.
                    for ((name, bytes) in entries.toSortedMap()) {
                        putStored(output, ZipEntry(name).apply { time = 0L }, bytes)
                    }
                }
            }
            val bytes = normalized.toByteArray()
            manifestEntries.add(ImplManifestFormat.Entry(id, versionsById.getValue(id), solid.size().toLong(), bytes.size.toLong(), ImplManifestFormat.sha1Hex(bytes)))
            solid.write(bytes)
        }
        if (outerAssets.isEmpty()) throw GradleException("No impl jar carried assets/ - the outer would ship without lang, textures or sounds")

        // The manifest's covers are the runtime's ONLY version-to-target resolution, so two targets whose
        // match sets intersect would make that resolution order-dependent: a launch could mount either impl
        // depending on sort order. Within one target a cover subsumed by a sibling is dead weight. Both fail.
        manifestEntries.flatMap { it.covers }.forEach(Cover::parse)
        for ((loader, targets) in manifestEntries.groupBy { it.id.substringAfterLast('-') }) {
            for ((i, a) in targets.withIndex()) {
                for (b in targets.subList(i + 1, targets.size)) {
                    for (coverA in a.covers) {
                        for (coverB in b.covers) {
                            if (Cover.parse(coverA).intersects(Cover.parse(coverB))) {
                                throw GradleException("Minecraft $coverA and $coverB on $loader are covered by both ${a.id} and ${b.id} - impl selection would be ambiguous")
                            }
                        }
                    }
                }
            }
        }
        for (entry in manifestEntries) {
            for ((i, coverA) in entry.covers.withIndex()) {
                for (coverB in entry.covers.subList(i + 1, entry.covers.size)) {
                    if (Cover.parse(coverA).intersects(Cover.parse(coverB))) throw GradleException("Target ${entry.id} covers $coverA and $coverB - one subsumes the other, delete the narrower one")
                }
            }
        }
        // The protocol table mirrors the covers verbatim - a tilde key's number answers the whole patch
        // line, which the runtime walks a patch's dotted prefix onto. Drift either way ships a cover whose
        // handshake has no number to send, or packs a dead key.
        val expectedProtocols = manifestEntries.flatMap { it.covers }.toSet()
        val packedProtocols = protocols.get().keys
        val protocolDiff = expectedProtocols - packedProtocols
        val protocolExtra = packedProtocols - expectedProtocols
        if (protocolDiff.isNotEmpty() || protocolExtra.isNotEmpty()) {
            throw GradleException("The [protocols] table must mirror the covers 1:1 - no protocol for ${protocolDiff.sorted()}, protocol for uncovered ${protocolExtra.sorted()}")
        }

        val solidBytes = solid.toByteArray()
        val zstdBinary = Zstd.compress(solidBytes, ZSTD_LEVEL)
        verifyRuntimeDecodable(zstdBinary, solidBytes)
        // The digest is the UNCOMPRESSED solid's hash: it changes only when the impls change, so
        // bumping zstd-jni or the level repacks the jar without wiping every install's impl cache.
        val digest = ImplManifestFormat.sha1Hex(solidBytes)
        val manifest = ImplManifestFormat.write(digest, manifestEntries)

        val outputFile = oneJar.get().asFile
        // The one jar owns merged/: drop stale jars that are not this output, never the autotest
        // fixtures beside them (those live in a subdirectory listFiles never returns).
        outputFile.parentFile.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") && it.name != outputFile.name }?.forEach { it.delete() }
        outputFile.parentFile.mkdirs()
        ZipOutputStream(FileOutputStream(outputFile).buffered()).use { output ->
            copyOuter(output)
            // Assets deflate: they are the same lang/texture bytes that used to ship deflate-compressed inside every impl, and only the impl slices must stay STORE.
            for ((name, bytes) in outerAssets.entries.sortedBy { it.key }) putDeflated(output, ZipEntry(name).apply { time = 0L }, bytes)
            putStored(output, ZipEntry(ImplManifestFormat.MANIFEST_ENTRY).apply { time = 0L }, manifest)
            putStored(output, ZipEntry(ImplManifestFormat.SOLID_ENTRY).apply { time = 0L }, zstdBinary)
            putStored(output, ZipEntry(PROTOCOLS_ENTRY).apply { time = 0L }, ObjectMapper().writeValueAsString(TreeMap(protocols.get())).toByteArray(StandardCharsets.UTF_8))
        }
        val percentSmaller = "%.1f%%".format(Locale.ROOT, (1 - zstdBinary.size.toDouble() / solidBytes.size) * 100)
        println(
            "Packed ${outputFile.parentFile.name}/${outputFile.name}: ${outputFile.length()} bytes, ${manifestEntries.size} impls, solid ${solidBytes.size} bytes -> zstd ${zstdBinary.size} bytes ($percentSmaller smaller, zstd-jni ${zstdVersion.get()}, level $ZSTD_LEVEL)" +
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
                    time = 0L
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

    /** The runtime unpacks the solid with aircompressor, whose zstd decoder accepts a narrower frame set than the zstd CLI (streaming level-20 frames, for one, use a window it rejects). One-shot through the pinned zstd-jni stays inside that set, and this check is the tripwire that keeps it there: a frame the client would fail to decode fails the build here instead. */
    private fun verifyRuntimeDecodable(frame: ByteArray, solid: ByteArray) {
        val decoded = ByteArray(solid.size)
        val written = ZstdDecompressor().decompress(frame, 0, frame.size, decoded, 0, decoded.size)
        if (written != solid.size || !decoded.contentEquals(solid)) {
            throw GradleException("The packed solid is not decodable by the runtime's aircompressor decoder (got $written of ${solid.size} bytes)")
        }
    }

}

/** A manifest cover: an exact version (`1.20.1`, matching only it) or a tilde patch line (`~26.3`, matching everything from the base below its bumped minor - the author's claim that the whole line rides one impl). */
private data class Cover(val tilde: Boolean, val parts: List<Long>) {
    fun intersects(other: Cover): Boolean = when {
        !tilde && !other.tilde -> compare(parts, other.parts) == 0
        !tilde -> other.contains(parts)
        !other.tilde -> contains(other.parts)
        else -> compare(parts, other.upperBound()) < 0 && compare(other.parts, upperBound()) < 0
    }

    private fun contains(candidate: List<Long>): Boolean = compare(candidate, parts) >= 0 && compare(candidate, upperBound()) < 0

    /** The tilde's exclusive end - the second-to-last component bumped and the last dropped (`~26.3.1` ends at `26.4`), the base padded to three components first. */
    private fun upperBound(): List<Long> {
        val padded = if (parts.size < 3) parts + List(3 - parts.size) { 0L } else parts
        return padded.dropLast(1).toMutableList().also { it[it.lastIndex] += 1 }
    }

    private fun compare(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0L }
            val right = b.getOrElse(i) { 0L }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        private val SHAPE = Regex("~?\\d+(\\.\\d+){1,3}")

        fun parse(cover: String): Cover {
            if (!SHAPE.matches(cover)) throw GradleException("Cover $cover is neither an exact Minecraft version nor a ~ patch line")
            return Cover(cover.startsWith("~"), cover.removePrefix("~").split('.').map { it.toLong() })
        }
    }
}
