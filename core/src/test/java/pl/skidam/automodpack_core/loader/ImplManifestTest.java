package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class ImplManifestTest {
	private static final String DIGEST = "a".repeat(40);
	private static final String SLICE_A_SHA1 = "b".repeat(40);
	private static final String SLICE_B_SHA1 = "c".repeat(40);

	@Test
	void parsesEntries() {
		ImplManifest manifest = parse(manifestJson(2));

		assertEquals(DIGEST, manifest.digest());
		assertEquals(2, manifest.entries().size());
		ImplManifest.Entry first = manifest.entry("1.20.1-fabric");
		assertEquals(List.of("1.20", "1.20.1"), first.versions());
		assertEquals(1024, first.offset());
		assertEquals(8192, first.length());
		assertEquals(SLICE_A_SHA1, first.sha1());
		ImplManifest.Entry second = manifest.entry("26.1-fabric");
		assertEquals(List.of("26.1", "26.1.1", "26.1.2"), second.versions());
		assertEquals(1024L + 8192L, second.offset());
		assertEquals(1, second.length());
		assertEquals(8192L + 1, manifest.totalSize());
	}

	@Test
	void resolvesPatchReleasesToTheCoveringTarget() {
		ImplManifest manifest = parse(manifestJson(2));

		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1.2").id());
		assertEquals("1.20.1-fabric", manifest.entryFor("fabric", "1.20.1").id());
	}

	@Test
	void uncoveredVersionsCrashWithTheCoverage() {
		ImplManifest manifest = parse(manifestJson(2));

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> manifest.entryFor("neoforge", "1.20.1"));
		assertTrue(thrown.getMessage().contains("1.20.1") && thrown.getMessage().contains("26.1-fabric [26.1, 26.1.1, 26.1.2]"));
	}

	@Test
	void unknownIdCrashesWithTheManifestIds() {
		ImplManifest manifest = parse(manifestJson(2));

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> manifest.entry("1.12.2-forge"));
		assertTrue(thrown.getMessage().contains("1.20.1-fabric") && thrown.getMessage().contains("26.1-fabric"));
	}

	@Test
	void rejectsMalformedManifests() {
		assertThrows(IllegalStateException.class, () -> parse("not json"));
		assertThrows(IllegalStateException.class, () -> parse("{\"digest\":\"a\"}"));
		assertThrows(IllegalStateException.class, () -> parse("{\"impls\":[]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"versions\":[],\"offset\":0}]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"versions\":[],\"offset\":-1,\"length\":1,\"sha1\":\"" + SLICE_A_SHA1 + "\"}]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"zz\",\"impls\":[]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"versions\":[],\"offset\":0,\"length\":1,\"sha1\":\"zz\"}]}"));
	}

	private static ImplManifest parse(String json) {
		return ImplManifest.parse(json.getBytes(StandardCharsets.UTF_8));
	}

	/** One manifest with {@code count} impls of the fixed test shapes. */
	private static String manifestJson(int count) {
		StringBuilder json = new StringBuilder();
		json.append("{\"digest\":\"").append(DIGEST).append("\",\"impls\":[");
		entry(json, "1.20.1-fabric", List.of("1.20", "1.20.1"), 1024, 8192, SLICE_A_SHA1);
		if (count > 1) {
			json.append(',');
			entry(json, "26.1-fabric", List.of("26.1", "26.1.1", "26.1.2"), 1024 + 8192, 1, SLICE_B_SHA1);
		}
		return json.append("]}").toString();
	}

	private static void entry(StringBuilder json, String id, List<String> versions, long offset, long length, String sha1) {
		json.append("{\"id\":\"").append(id).append("\",\"versions\":[");
		for (int i = 0; i < versions.size(); i++) {
			if (i > 0) json.append(',');
			json.append('"').append(versions.get(i)).append('"');
		}
		json.append("],\"offset\":").append(offset).append(",\"length\":").append(length).append(",\"sha1\":\"").append(sha1).append("\"}");
	}
}
