import com.fasterxml.jackson.databind.ObjectMapper
import com.github.luben.zstd.ZstdCompressCtx
import io.airlift.compress.zstd.ZstdInputStream
import lu.luz.jzopfli.ZopfliH
import lu.luz.jzopfli.Zopfli_lib
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
import java.io.ByteArrayInputStream
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

/**
 * Packs the ONE published jar: the optimized universal outer plus every target's optimized impl
 * jar as a solid zstd blob. Each impl jar (and any nested jarjar inside it) is STORE-normalized
 * (re-zipped with method 0 so the solid compresses) and its `assets/` entries are stripped - assets
 * ship once on the outer, taken from the impls while stripping (byte-identical across targets, which
 * is checked, not assumed). `impl/all.zst` is one-shot zstd level 20 over the whole solid,
 * compressed by the pinned zstd-jni natives (single-threaded by nature, one version everywhere, no
 * CLI on PATH) with its window capped at 8 MiB, the widest the runtime's aircompressor stream
 * decodes, and a frame header carrying the uncompressed size that decode relies on.
 * `impl/manifest.json` indexes the solid. Outer deflated entries use pure-Java Zopfli
 * and [TightZipWriter] (DOS-epoch timestamps, no 0x5455 extra fields, no bit-3 data descriptors).
 * `mc-protocols.json` mirrors the covers verbatim (one key per cover, a tilde key's number
 * answering the whole patch line) for the holepunch handshake.
 */
/** One-shot level 20: the measured sweet spot on the shipped solid (-19 gave up 4.9 KB more, -21/-22 under 100 bytes each, --max 905 bytes for 12 s) and single-threaded by nature, so the bytes cannot drift with core count. */
internal const val ZSTD_LEVEL = 20

/**
 * The frame's window, capped: the runtime unwraps the solid with aircompressor's *streaming* decoder, whose window
 * ceiling is 8 MiB (windowLog 23). A one-shot frame over a 27 MB solid otherwise takes the whole solid as its window and
 * the client cannot decode a byte of it. The cap costs 28 KB on the shipped solid; [OneJarTask] proves every packed
 * frame decodes through that same streaming decoder.
 */
internal const val ZSTD_WINDOW_LOG = 23

/** Zopfli iterations for outer deflated entries: deterministic per-entry, parallelized across entries. */
internal const val ZOPFLI_ITERATIONS = 5

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

    private data class PendingEntry(
        val name: String,
        val raw: ByteArray,
        val forceStore: Boolean,
    )

    private data class PreparedEntry(
        val name: String,
        val method: Int,
        val crc: Long,
        val uncompressedSize: Int,
        val payload: ByteArray,
    )

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
            TightZipWriter(normalized).use { output ->
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
                        // STORE-normalize nested jars (e.g. Forge's mixinextras jarjar) so zstd sees uncompressed bytecode instead of deflate noise.
                        val normalizedBytes = if (entry.name.endsWith(".jar")) storeNormalizeJar(bytes) else bytes
                        entry.name to normalizedBytes
                    }.toMap()
                    // Sorted names and DOS-epoch timestamps so the solid - and everything hashed from it - is a pure function of the impl contents.
                    for ((name, bytes) in entries.toSortedMap()) {
                        output.writeStored(name, bytes)
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
        val zstdBinary = ZstdCompressCtx().use { it.setLevel(ZSTD_LEVEL).setWindowLog(ZSTD_WINDOW_LOG).compress(solidBytes) }
        verifyRuntimeDecodable(zstdBinary, solidBytes)
        // The digest is the UNCOMPRESSED solid's hash: it changes only when the impls change, so
        // bumping zstd-jni or the level repacks the jar without wiping every install's impl cache.
        val digest = ImplManifestFormat.sha1Hex(solidBytes)
        val manifest = ImplManifestFormat.write(digest, manifestEntries)

        val pending = mutableListOf<PendingEntry>()
        collectOuter(pending)
        for ((name, bytes) in outerAssets.entries.sortedBy { it.key }) {
            pending.add(PendingEntry(name, bytes, forceStore = false))
        }
        pending.add(PendingEntry(ImplManifestFormat.MANIFEST_ENTRY, manifest, forceStore = true))
        pending.add(PendingEntry(ImplManifestFormat.SOLID_ENTRY, zstdBinary, forceStore = true))
        pending.add(PendingEntry(PROTOCOLS_ENTRY, ObjectMapper().writeValueAsString(TreeMap(protocols.get())).toByteArray(StandardCharsets.UTF_8), forceStore = true))

        // Per-entry Zopfli is pure and independent, so parallelStream keeps byte-identical output in insertion order.
        val prepared = pending.parallelStream().map(::prepareEntry).toList()

        val outputFile = oneJar.get().asFile
        // The one jar owns merged/: drop stale jars that are not this output, never the autotest
        // fixtures beside them (those live in a subdirectory listFiles never returns).
        outputFile.parentFile.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") && it.name != outputFile.name }?.forEach { it.delete() }
        outputFile.parentFile.mkdirs()
        TightZipWriter(FileOutputStream(outputFile).buffered()).use { output ->
            for (entry in prepared) {
                output.writePrecompressed(entry.name, entry.method, entry.crc, entry.uncompressedSize, entry.payload)
            }
        }
        val percentSmaller = "%.1f%%".format(Locale.ROOT, (1 - zstdBinary.size.toDouble() / solidBytes.size) * 100)
        println(
            "Packed ${outputFile.parentFile.name}/${outputFile.name}: ${outputFile.length()} bytes, ${manifestEntries.size} impls, solid ${solidBytes.size} bytes -> zstd ${zstdBinary.size} bytes ($percentSmaller smaller, zstd-jni ${zstdVersion.get()}, level $ZSTD_LEVEL)" +
                " (${manifestEntries.sumOf { it.length }} impl bytes), took ${System.currentTimeMillis() - startTime}ms",
        )
    }

    private fun collectOuter(pending: MutableList<PendingEntry>) {
        val seen = mutableSetOf<String>()
        ZipInputStream(FileInputStream(outerJar.get().asFile)).use { input ->
            generateSequence { input.nextEntry }.forEach { entry ->
                if (entry.isDirectory) return@forEach
                if (!seen.add(entry.name)) throw GradleException("Duplicate entry ${entry.name} in the outer jar")
                val rawBytes = input.readBytes()
                val bytes = if (entry.name.endsWith(".class")) ClassLocalVarStripper.strip(rawBytes) else rawBytes
                val forceStore = entry.method == ZipEntry.STORED || entry.name.endsWith(".zst")
                pending.add(PendingEntry(entry.name, bytes, forceStore))
            }
        }
    }

    private fun prepareEntry(entry: PendingEntry): PreparedEntry {
        val crc = CRC32().apply { update(entry.raw) }.value
        if (entry.forceStore || entry.raw.isEmpty()) {
            return PreparedEntry(entry.name, ZipEntry.STORED, crc, entry.raw.size, entry.raw)
        }
        val deflated = zopfliDeflate(entry.raw)
        return if (deflated.size < entry.raw.size) {
            PreparedEntry(entry.name, ZipEntry.DEFLATED, crc, entry.raw.size, deflated)
        } else {
            PreparedEntry(entry.name, ZipEntry.STORED, crc, entry.raw.size, entry.raw)
        }
    }

    private fun zopfliDeflate(raw: ByteArray): ByteArray {
        val options = ZopfliH.ZopfliOptions().apply {
            numiterations = ZOPFLI_ITERATIONS
        }
        val out = arrayOf<ByteArray?>(null)
        val outSize = intArrayOf(0)
        Zopfli_lib.ZopfliCompress(options, ZopfliH.ZopfliFormat.ZOPFLI_FORMAT_DEFLATE, raw, raw.size, out, outSize)
        return out[0]!!.copyOf(outSize[0])
    }

    private fun storeNormalizeJar(jarBytes: ByteArray): ByteArray {
        val entries = TreeMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { input ->
            generateSequence { input.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) entries[entry.name] = input.readBytes()
            }
        }
        val out = ByteArrayOutputStream(jarBytes.size * 2)
        TightZipWriter(out).use { writer ->
            for ((name, bytes) in entries) writer.writeStored(name, bytes)
        }
        return out.toByteArray()
    }

    /**
     * The client unpacks the solid with aircompressor's streaming decoder (ImplStore's restage reads the ZIP entry as a
     * stream), not its one-shot twin: the two disagree on which frames they accept, so this decodes through the streaming
     * one, from the same byte stream the client sees. A frame it would refuse fails the build here instead of on install.
     */
    private fun verifyRuntimeDecodable(frame: ByteArray, solid: ByteArray) {
        val decoded = ZstdInputStream(ByteArrayInputStream(frame)).use { it.readAllBytes() }
        if (!decoded.contentEquals(solid)) {
            throw GradleException("The packed solid is not decodable by the client's streaming aircompressor decoder (got ${decoded.size} of ${solid.size} bytes)")
        }
    }
}
