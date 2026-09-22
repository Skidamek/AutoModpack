package pl.skidam.automodpack_core.loader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Writes the single generated dependency bundle jar: one loader-visible root carrying the selected jars as nested
 * entries, so a pack-root provider or a nested provider can serve a standard root's dependency without each jar
 * becoming its own mods-directory file. The manifest declares every nested entry in its {@code jars} array -
 * loaders only resolve nests they are told about. The output is byte-identical for an identical input set - entries
 * sorted by name, fixed timestamps, stored uncompressed - so the bundle's SHA-1 is a content token and any bundled
 * content change lands as a new install. Bytes stream from the entry sources straight into the caller's stream;
 * nothing is held in memory per entry beyond a copy buffer.
 */
public final class GeneratedBundle {

	private GeneratedBundle() {}

	/** Supplies one entry's bytes lazily; the generator opens each source twice - a measuring pass, then the write pass. */
	@FunctionalInterface
	public interface Source {
		InputStream open() throws IOException;
	}

	/** One jar inside the bundle's {@code META-INF/jars/}: the entry name it lands under plus its lazily-opened source. */
	public record Item(String entryName, Source source) {}

	/** A source reading {@code path} fresh on every open. */
	public static Source source(Path path) {
		return () -> Files.newInputStream(path);
	}

	public static final String MOD_ID = "automodpack_generated";

	private static final String MANIFEST_ENTRY = "fabric.mod.json";
	private static final String NESTED_PREFIX = "META-INF/jars/";
	private static final long FIXED_TIMESTAMP = 0;
	private static final int COPY_BUFFER = 64 * 1024;

	private record Measurements(long size, long crc) {}

	/** The bundle's manifest: the declared nested {@code jars} array must name every nested entry or the loader never resolves them. */
	private static byte[] manifest(List<String> entryNames) {
		JsonObject json = new JsonObject();
		json.addProperty("schemaVersion", 1);
		json.addProperty("id", MOD_ID);
		json.addProperty("version", "1.0.0");
		json.addProperty("name", "AutoModpack generated dependencies");
		json.addProperty("environment", "*");
		JsonArray jars = new JsonArray();
		for (String entryName : entryNames) {
			JsonObject jar = new JsonObject();
			jar.addProperty("file", NESTED_PREFIX + entryName);
			jars.add(jar);
		}
		json.add("jars", jars);
		return new Gson().toJson(json).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Writes the deterministic bundle to {@code out}, which the caller owns and closes. STORED entries must
	 * declare size and CRC before their bytes are written, so every source is measured by a first streaming pass
	 * and read a second time for the write; a source disagreeing between the two passes fails the entry.
	 */
	public static void generate(List<Item> items, OutputStream out) throws IOException {
		TreeMap<String, Source> entries = new TreeMap<>();
		List<String> entryNames = new ArrayList<>();
		for (Item item : items) {
			if (entries.putIfAbsent(NESTED_PREFIX + item.entryName(), item.source()) != null)
				throw new IllegalArgumentException("Generated bundle has colliding entry names: " + item.entryName());
			entryNames.add(item.entryName());
		}
		Collections.sort(entryNames);
		byte[] manifest = manifest(entryNames);
		entries.put(MANIFEST_ENTRY, () -> new ByteArrayInputStream(manifest));
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (var entry : entries.entrySet()) write(zip, entry.getKey(), entry.getValue(), measure(entry.getValue()));
		}
	}

	private static Measurements measure(Source source) throws IOException {
		CRC32 crc = new CRC32();
		long size = 0;
		try (InputStream in = source.open()) {
			byte[] buffer = new byte[COPY_BUFFER];
			int read;
			while ((read = in.read(buffer)) != -1) {
				crc.update(buffer, 0, read);
				size += read;
			}
		}
		return new Measurements(size, crc.getValue());
	}

	private static void write(ZipOutputStream zip, String name, Source source, Measurements measurements) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipOutputStream.STORED);
		entry.setTime(FIXED_TIMESTAMP);
		entry.setCrc(measurements.crc());
		entry.setSize(measurements.size());
		entry.setCompressedSize(measurements.size());
		zip.putNextEntry(entry);
		try (InputStream in = source.open()) {
			byte[] buffer = new byte[COPY_BUFFER];
			int read;
			while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
		}
		zip.closeEntry();
	}
}
