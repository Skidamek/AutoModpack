package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class GeneratedBundleTest {

	@Test
	void sameInputProducesIdenticalBytes() throws IOException {
		byte[] first = GeneratedBundle.generate(List.of(new GeneratedBundle.Item("b.jar", () -> new byte[]{2}), new GeneratedBundle.Item("a.jar", () -> new byte[]{1})));
		byte[] second = GeneratedBundle.generate(List.of(new GeneratedBundle.Item("a.jar", () -> new byte[]{1}), new GeneratedBundle.Item("b.jar", () -> new byte[]{2})));
		assertArrayEquals(first, second);
	}

	@Test
	void collidingEntryNamesThrow() {
		assertThrows(IllegalArgumentException.class, () -> GeneratedBundle.generate(List.of(
				new GeneratedBundle.Item("same.jar", () -> new byte[]{1}),
				new GeneratedBundle.Item("same.jar", () -> new byte[]{2}))));
	}

	@Test
	void bundleCarriesTheManifestAndSortedStoredNestedEntries() throws IOException {
		byte[] bundle = GeneratedBundle.generate(List.of(new GeneratedBundle.Item("lib-1.0.0.jar", () -> new byte[]{1, 2, 3})));
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
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
}
