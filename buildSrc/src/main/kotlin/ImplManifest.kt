import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Build-side codec for `impl/manifest.json`, the index of the one jar's solid impl blob: the
 * generation - the SHA-1 of the UNCOMPRESSED solid, so a compressor bump cannot invalidate every
 * install's cache - and per impl its id, the Minecraft versions its target covers, its offset and
 * STORE length inside the solid, and the SHA-1 of that slice. Bare data, no version field: the
 * manifest ships inside the same jar as the parser that reads it, so reader and writer can never
 * skew. The runtime's parser (core ImplManifest) is an independent implementation of the same
 * contract, so a writer bug here fails loudly there instead of both sides agreeing on a wrong
 * format.
 */
object ImplManifestFormat {
    const val MANIFEST_ENTRY = "impl/manifest.json"
    const val SOLID_ENTRY = "impl/all.zst"

    data class Entry(val id: String, val versions: List<String>, val offset: Long, val length: Long, val sha1: String)

    private val writer = ObjectMapper().writerWithDefaultPrettyPrinter()

    fun write(generation: String, entries: List<Entry>): ByteArray =
        writer.writeValueAsBytes(
            linkedMapOf<String, Any>(
                "generation" to generation,
                "impls" to entries.map { entry ->
                    linkedMapOf<String, Any>(
                        "id" to entry.id,
                        "versions" to entry.versions,
                        "offset" to entry.offset,
                        "length" to entry.length,
                        "sha1" to entry.sha1,
                    )
                },
            ),
        )

    class ParsedManifest(val generation: String, val entries: List<Entry>)

    /** Strict parse for the audit: any missing, mistyped or malformed field fails with a reason. */
    fun parse(bytes: ByteArray): ParsedManifest {
        fun fail(reason: String): Nothing = error("impl/manifest.json is invalid: $reason")

        val root = try {
            ObjectMapper().readTree(bytes)
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
        if (root == null || !root.isObject) fail("not a JSON object")
        val generation = root.get("generation")?.takeIf { it.isTextual } ?: fail("no usable generation")
        if (!generation.textValue().matches(Regex("[0-9a-f]{40}"))) fail("generation ${generation.textValue()} is not a SHA-1 hex digest")
        val impls = root.get("impls")?.takeIf { it.isArray } ?: fail("no impls array")
        val entries = impls.mapIndexed { position, node ->
            fun field(name: String): JsonNode = node.get(name) ?: fail("impl $position carries no $name")
            val id = field("id").takeIf { it.isTextual }?.textValue() ?: fail("impl $position carries no usable id")
            val versions = field("versions").takeIf { it.isArray }?.map { version ->
                version.takeIf { it.isTextual }?.textValue() ?: fail("impl $id carries a non-string version")
            } ?: fail("impl $id carries no versions array")
            val offset = field("offset").takeIf { it.canConvertToLong() }?.longValue() ?: fail("impl $id carries no usable offset")
            val length = field("length").takeIf { it.canConvertToLong() }?.longValue() ?: fail("impl $id carries no usable length")
            if (offset < 0 || length < 0) fail("impl $id carries a negative offset or length")
            val sha1 = field("sha1").takeIf { it.isTextual }?.textValue() ?: fail("impl $id carries no usable sha1")
            if (!sha1.matches(Regex("[0-9a-f]{40}"))) fail("impl $id carries sha1 $sha1, not a SHA-1 hex digest")
            Entry(id, versions, offset, length, sha1)
        }
        return ParsedManifest(generation.textValue(), entries)
    }

    fun sha1Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
}
