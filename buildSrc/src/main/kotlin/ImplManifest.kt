import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Build-side codec for `impl/manifest.bin`, the little-endian index of the one jar's solid impl
 * blob: magic `AMP1`, the 20-byte SHA-1 generation of `impl/all.zst`, a u16 entry count, then per
 * entry the u16-length-prefixed id, a u16 count of u16-length-prefixed Minecraft versions that
 * target covers, a u32 offset and u32 STORE length inside the uncompressed solid, and the 20-byte
 * SHA-1 of that slice. The runtime's parser (core ImplManifest) is an independent implementation of
 * the same contract, so a writer bug here fails loudly there instead of both sides agreeing on a
 * wrong format.
 */
object ImplManifestFormat {
    const val MANIFEST_ENTRY = "impl/manifest.bin"
    const val SOLID_ENTRY = "impl/all.zst"
    val MAGIC: List<Byte> = listOf('A'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
    const val SHA1_BYTES = 20

    data class Entry(val id: String, val versions: List<String>, val offset: Long, val length: Long, val sha1: ByteArray)

    fun write(generationSha1: ByteArray, entries: List<Entry>): ByteArray {
        val versionsBytes = entries.sumOf { entry -> 2 + entry.versions.sumOf { it.length + 2 } }
        val buffer = ByteBuffer.allocate(64 + entries.sumOf { it.id.length + 2 + 8 + SHA1_BYTES } + versionsBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray()).put(generationSha1).putShort(entries.size.toShort())
        for (entry in entries) {
            val id = entry.id.toByteArray(StandardCharsets.UTF_8)
            buffer.putShort(id.size.toShort()).put(id)
            buffer.putShort(entry.versions.size.toShort())
            for (version in entry.versions) {
                val encoded = version.toByteArray(StandardCharsets.UTF_8)
                buffer.putShort(encoded.size.toShort()).put(encoded)
            }
            buffer.putInt(entry.offset.toInt()).putInt(entry.length.toInt()).put(entry.sha1)
        }
        return buffer.array().copyOf(buffer.position())
    }

    class ParsedManifest(val generationSha1: ByteArray, val entries: List<Entry>) {
        fun generationHex(): String = generationSha1.toHex()
    }

    /** Strict parse for the audit: bad magic, truncation or trailing garbage all fail with a reason. */
    fun parse(bytes: ByteArray): ParsedManifest {
        fun fail(reason: String): Nothing = error("impl/manifest.bin is invalid: $reason")
        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(MAGIC.size)
            buffer.get(magic)
            if (magic.toList() != MAGIC) fail("bad magic ${magic.toHex()}")
            val generation = ByteArray(SHA1_BYTES)
            buffer.get(generation)
            val count = buffer.short.toUnsignedInt()
            val entries = ArrayList<Entry>(count)
            repeat(count) {
                val idLength = buffer.short.toUnsignedInt()
                val id = ByteArray(idLength)
                buffer.get(id)
                val versionCount = buffer.short.toUnsignedInt()
                val versions = ArrayList<String>(versionCount)
                repeat(versionCount) {
                    val versionLength = buffer.short.toUnsignedInt()
                    val version = ByteArray(versionLength)
                    buffer.get(version)
                    versions.add(String(version, StandardCharsets.UTF_8))
                }
                val offset = buffer.int.toUnsignedLong()
                val length = buffer.int.toUnsignedLong()
                val sha1 = ByteArray(SHA1_BYTES)
                buffer.get(sha1)
                entries.add(Entry(String(id, StandardCharsets.UTF_8), versions, offset, length, sha1))
            }
            if (buffer.hasRemaining()) fail("${buffer.remaining()} trailing bytes after $count entries")
            return ParsedManifest(generation, entries)
        } catch (e: java.nio.BufferUnderflowException) {
            fail("truncated at ${bytes.size} bytes")
        }
    }

    private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun Short.toUnsignedInt() = toInt() and 0xFFFF

    private fun Int.toUnsignedLong() = toLong() and 0xFFFFFFFFL
}
