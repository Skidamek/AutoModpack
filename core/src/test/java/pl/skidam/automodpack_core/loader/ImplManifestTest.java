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
		ImplManifest manifest = parse(manifestJson());

		assertEquals(DIGEST, manifest.digest());
		assertEquals(2, manifest.entries().size());
		ImplManifest.Entry first = manifest.entry("1.20.1-fabric");
		assertEquals(List.of("1.20", "1.20.1"), first.covers());
		assertEquals(1024, first.offset());
		assertEquals(8192, first.length());
		assertEquals(SLICE_A_SHA1, first.sha1());
		ImplManifest.Entry second = manifest.entry("26.1-fabric");
		assertEquals(List.of("~26.1"), second.covers());
		assertEquals(1024L + 8192L, second.offset());
		assertEquals(1, second.length());
		assertEquals(8192L + 1, manifest.totalSize());
	}

	@Test
	void resolvesPatchReleasesToTheCoveringTarget() {
		ImplManifest manifest = parse(manifestJson());

		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1").id());
		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1.2").id());
		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1.3").id());
		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1.2-rc1").id());
		assertEquals("1.20.1-fabric", manifest.entryFor("fabric", "1.20.1").id());
		assertEquals("1.20.1-fabric", manifest.entryFor("fabric", "1.20").id());
	}

	@Test
	void uncoveredVersionsCrashWithTheCoverage() {
		ImplManifest manifest = parse(manifestJson());

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> manifest.entryFor("neoforge", "1.20.1"));
		assertTrue(thrown.getMessage().contains("1.20.1") && thrown.getMessage().contains("26.1-fabric [~26.1]"));
		assertThrows(IllegalStateException.class, () -> manifest.entryFor("fabric", "26.2"));
	}

	@Test
	void ambiguousCoverageCrashes() {
		ImplManifest manifest = parse(ambiguousManifestJson());

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> manifest.entryFor("fabric", "26.1.1"));
		assertTrue(thrown.getMessage().contains("26.1-fabric") && thrown.getMessage().contains("26.1.1-fabric") && thrown.getMessage().contains("ambiguous"));
		assertThrows(IllegalStateException.class, () -> manifest.entryFor("fabric", "26.1.1.5"));
		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1").id());
	}

	@Test
	void splitOffPatchLineResolvesWithoutAmbiguity() {
		ImplManifest manifest = parse(splitManifestJson());

		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1").id());
		assertEquals("26.1.1-fabric", manifest.entryFor("fabric", "26.1.1").id());
		assertEquals("26.1.1-fabric", manifest.entryFor("fabric", "26.1.2").id());
		assertEquals("26.1.1-fabric", manifest.entryFor("fabric", "26.1.9.9").id());
		assertThrows(IllegalStateException.class, () -> manifest.entryFor("fabric", "26.2"));
		assertEquals("26.1-fabric", manifest.entryFor("fabric", "26.1-rc1").id());
	}

	@Test
	void unknownIdCrashesWithTheManifestIds() {
		ImplManifest manifest = parse(manifestJson());

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> manifest.entry("1.12.2-forge"));
		assertTrue(thrown.getMessage().contains("1.20.1-fabric") && thrown.getMessage().contains("26.1-fabric"));
	}

	@Test
	void rejectsMalformedManifests() {
		assertThrows(IllegalStateException.class, () -> parse("not json"));
		assertThrows(IllegalStateException.class, () -> parse("{\"digest\":\"a\"}"));
		assertThrows(IllegalStateException.class, () -> parse("{\"impls\":[]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"covers\":[],\"offset\":0}]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"covers\":[],\"offset\":-1,\"length\":1,\"sha1\":\"" + SLICE_A_SHA1 + "\"}]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"zz\",\"impls\":[]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"covers\":[],\"offset\":0,\"length\":1,\"sha1\":\"zz\"}]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"covers\":[\"26\"],\"offset\":0,\"length\":1,\"sha1\":\"" + SLICE_A_SHA1 + "\"}]}"));
		assertThrows(IllegalStateException.class,
				() -> parse("{\"digest\":\"" + DIGEST + "\",\"impls\":[{\"id\":\"x\",\"covers\":[\"~\"],\"offset\":0,\"length\":1,\"sha1\":\"" + SLICE_A_SHA1 + "\"}]}"));
	}

	private static ImplManifest parse(String json) {
		return ImplManifest.parse(json.getBytes(StandardCharsets.UTF_8));
	}

	/** One manifest with the two fixed test impls: the legacy exact covers and the 26.1 patch line. */
	private static String manifestJson() {
		StringBuilder json = new StringBuilder();
		json.append("{\"digest\":\"").append(DIGEST).append("\",\"impls\":[");
		entry(json, "1.20.1-fabric", List.of("1.20", "1.20.1"), 1024, 8192, SLICE_A_SHA1);
		json.append(',');
		entry(json, "26.1-fabric", List.of("~26.1"), 1024 + 8192, 1, SLICE_B_SHA1);
		return json.append("]}").toString();
	}

	/** A tampered manifest the build would reject - nested tildes - which the runtime guard must still crash on. */
	private static String ambiguousManifestJson() {
		StringBuilder json = new StringBuilder();
		json.append("{\"digest\":\"").append(DIGEST).append("\",\"impls\":[");
		entry(json, "26.1-fabric", List.of("~26.1"), 0, 1, SLICE_A_SHA1);
		json.append(',');
		entry(json, "26.1.1-fabric", List.of("~26.1.1"), 1, 8191, SLICE_B_SHA1);
		return json.append("]}").toString();
	}

	/** The recovery layout for a broken 26.1.1: the parent narrowed to exact, the split-off patch line on a tilde. */
	private static String splitManifestJson() {
		StringBuilder json = new StringBuilder();
		json.append("{\"digest\":\"").append(DIGEST).append("\",\"impls\":[");
		entry(json, "26.1-fabric", List.of("26.1"), 0, 1, SLICE_A_SHA1);
		json.append(',');
		entry(json, "26.1.1-fabric", List.of("~26.1.1"), 1, 8191, SLICE_B_SHA1);
		return json.append("]}").toString();
	}

	private static void entry(StringBuilder json, String id, List<String> covers, long offset, long length, String sha1) {
		json.append("{\"id\":\"").append(id).append("\",\"covers\":[");
		for (int i = 0; i < covers.size(); i++) {
			if (i > 0) json.append(',');
			json.append('"').append(covers.get(i)).append('"');
		}
		json.append("],\"offset\":").append(offset).append(",\"length\":").append(length).append(",\"sha1\":\"").append(sha1).append("\"}");
	}
}
