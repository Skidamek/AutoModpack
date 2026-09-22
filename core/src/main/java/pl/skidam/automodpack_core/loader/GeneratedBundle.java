package pl.skidam.automodpack_core.loader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the single generated dependency bundle jar: one loader-visible root carrying the selected jars as
 * nested entries, so a pack-root provider or a nested provider can serve a standard root's dependency without
 * each jar becoming its own mods-directory file. The manifest declares every nested entry in its {@code jars}
 * array - loaders only resolve nests they are told about. The output is byte-identical for an identical input
 * set - entries sorted by name, fixed timestamps, stored uncompressed - so the bundle's SHA-1 is a content
 * token and any bundled content change lands as a new install.
 */
public final class GeneratedBundle {

	private GeneratedBundle() {}

	/** Supplies one entry's bytes; the generator reads lazily so callers can hash-and-discard as they go. */
	@FunctionalInterface
	public interface Bytes {
		byte[] get() throws IOException;
	}

	/** One jar inside the bundle's {@code META-INF/jars/}: the entry name it lands under plus its bytes. */
	public record Item(String entryName, Bytes bytes) {}

	public static final String MOD_ID = "automodpack_generated";

	private static final String MANIFEST_ENTRY = "fabric.mod.json";
	private static final String NESTED_PREFIX = "META-INF/jars/";
	private static final long FIXED_TIMESTAMP = 0;

	/** The bundle's manifest: the declared nested {@code jars} array must name every nested entry or the loader never resolves them. */
	private static String manifest(List<String> entryNames) {
		StringBuilder jars = new StringBuilder();
		for (String entryName : entryNames) {
			if (jars.length() > 0) jars.append(',');
			jars.append("{\"file\":\"").append(NESTED_PREFIX).append(entryName).append("\"}");
		}
		return "{\"schemaVersion\":1,\"id\":\"" + MOD_ID + "\",\"version\":\"1.0.0\",\"name\":\"AutoModpack generated dependencies\",\"environment\":\"*\",\"jars\":[" + jars + "]}";
	}

	/** Produces deterministic bundle bytes; two items naming the same entry throw instead of silently dropping one. */
	public static byte[] generate(List<Item> items) throws IOException {
		List<String> entryNames = new ArrayList<>();
		TreeMap<String, Bytes> entries = new TreeMap<>();
		for (Item item : items) {
			if (entries.putIfAbsent(NESTED_PREFIX + item.entryName(), item.bytes()) != null)
				throw new IllegalArgumentException("Generated bundle has colliding entry names: " + item.entryName());
			entryNames.add(item.entryName());
		}
		Collections.sort(entryNames);
		entries.put(MANIFEST_ENTRY, () -> manifest(entryNames).getBytes(StandardCharsets.UTF_8));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (var entry : entries.entrySet()) write(zip, entry.getKey(), entry.getValue().get());
		}
		return out.toByteArray();
	}

	private static void write(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipOutputStream.STORED);
		entry.setTime(FIXED_TIMESTAMP);
		CRC32 crc = new CRC32();
		crc.update(bytes);
		entry.setCrc(crc.getValue());
		entry.setSize(bytes.length);
		entry.setCompressedSize(bytes.length);
		zip.putNextEntry(entry);
		zip.write(bytes);
		zip.closeEntry();
	}
}
