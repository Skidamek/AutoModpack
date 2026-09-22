package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class GeneratedBundleTest {

	@TempDir
	Path directory;

	@Test
	void sameInputProducesIdenticalBytes() throws IOException {
		Path first = directory.resolve("first.jar");
		Path second = directory.resolve("second.jar");
		try (OutputStream out = Files.newOutputStream(first)) {
			GeneratedBundle.generate(List.of(new GeneratedBundle.Item("b.jar", source(2)), new GeneratedBundle.Item("a.jar", source(1))), out);
		}
		try (OutputStream out = Files.newOutputStream(second)) {
			GeneratedBundle.generate(List.of(new GeneratedBundle.Item("a.jar", source(1)), new GeneratedBundle.Item("b.jar", source(2))), out);
		}
		assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
	}

	@Test
	void collidingEntryNamesThrow() {
		assertThrows(IllegalArgumentException.class, () -> GeneratedBundle.generate(List.of(
				new GeneratedBundle.Item("same.jar", source(1)),
				new GeneratedBundle.Item("same.jar", source(2))), OutputStream.nullOutputStream()));
	}

	@Test
	void bundleCarriesTheManifestAndSortedStoredNestedEntries() throws IOException {
		Path bundle = directory.resolve("bundle.jar");
		try (OutputStream out = Files.newOutputStream(bundle)) {
			GeneratedBundle.generate(List.of(new GeneratedBundle.Item("lib-1.0.0.jar", source(1, 2, 3))), out);
		}
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle))) {
			// Entries are sorted by name, so the nested jar precedes the manifest.
			ZipEntry nested = zip.getNextEntry();
			assertEquals("META-INF/jars/lib-1.0.0.jar", nested.getName());
			assertEquals(ZipEntry.STORED, nested.getMethod());
			assertArrayEquals(new byte[]{1, 2, 3}, zip.readAllBytes());
			ZipEntry manifest = zip.getNextEntry();
			assertEquals("fabric.mod.json", manifest.getName());
			JsonObject json = new Gson().fromJson(new String(zip.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
			assertEquals(1, json.get("schemaVersion").getAsInt());
			assertEquals("automodpack_generated", json.get("id").getAsString());
			assertEquals("1.0.0", json.get("version").getAsString());
			assertEquals("AutoModpack generated dependencies", json.get("name").getAsString());
			assertEquals("*", json.get("environment").getAsString());
			// Loaders only resolve nests the manifest declares in its jars array.
			JsonArray jars = json.getAsJsonArray("jars");
			assertEquals(1, jars.size());
			assertEquals("META-INF/jars/lib-1.0.0.jar", jars.get(0).getAsJsonObject().get("file").getAsString());
			assertNull(zip.getNextEntry());
		}
	}

	@Test
	void manifestEscapesPackControlledEntryNames() throws IOException {
		Path bundle = directory.resolve("bundle.jar");
		try (OutputStream out = Files.newOutputStream(bundle)) {
			GeneratedBundle.generate(List.of(new GeneratedBundle.Item("we\"ird\\jar.jar", source(1))), out);
		}
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(bundle))) {
			assertNotNull(zip.getNextEntry());
			assertEquals("fabric.mod.json", zip.getNextEntry().getName());
			JsonObject json = new Gson().fromJson(new String(zip.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
			assertEquals("META-INF/jars/we\"ird\\jar.jar", json.getAsJsonArray("jars").get(0).getAsJsonObject().get("file").getAsString());
		}
	}

	private static GeneratedBundle.Source source(int... bytes) {
		byte[] data = new byte[bytes.length];
		for (int index = 0; index < bytes.length; index++) data[index] = (byte) bytes[index];
		return () -> new ByteArrayInputStream(data);
	}
}
