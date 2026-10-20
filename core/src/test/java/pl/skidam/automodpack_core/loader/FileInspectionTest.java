package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.cache.FileCache;

class FileInspectionTest {

	@Test
	void tomlDependenciesKeepOnlyRequiredEntries() throws IOException {
		Path jar = Files.createTempDirectory("toml-metadata-").resolve("tomlmod.jar");
		String toml = """
				modLoader = "javafml"
				loaderVersion = "[47,)"
				[[mods]]
				modId = "tomlmod"
				version = "1.0.0"
				[[dependencies.tomlmod]]
				modId = "required_default"
				[[dependencies.tomlmod]]
				modId = "mandatory_explicit"
				mandatory = true
				[[dependencies.tomlmod]]
				modId = "optional_mandatory_false"
				mandatory = false
				[[dependencies.tomlmod]]
				modId = "optional_type"
				type = "optional"
				[[dependencies.tomlmod]]
				modId = "incompatible_type"
				type = "incompatible"
				[[dependencies.tomlmod]]
				modId = "discouraged_type"
				type = "discouraged"
				[[dependencies.tomlmod]]
				modId = "required_type"
				type = "required"
				[[dependencies.tomlmod]]
				modId = "minecraft"
				side = "client"
				""";

		try {
			Files.write(jar, jar(Map.of("META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8))));
			FileInspection.Mod mod = inspect(jar);

			assertEquals(Set.of("required_default", "mandatory_explicit", "required_type", "minecraft"), mod.deps());
			assertEquals(ModEnvironment.UNIVERSAL, mod.environment(), "a minecraft dependency side is not this jar's environment");
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void tomlDependencySemanticsFollowTheMetadataFile() throws IOException {
		String toml = """
				modLoader = "javafml"
				[[mods]]
				modId = "tomlmod"
				[[dependencies.tomlmod]]
				modId = "legacy_optional"
				mandatory = false
				""";

		Path forge = Files.createTempDirectory("toml-metadata-").resolve("forgemod.jar");
		Path neoforge = Files.createTempDirectory("toml-metadata-").resolve("neoforgemod.jar");

		try {
			// Forge reads META-INF/mods.toml and honors mandatory; NeoForge reads neoforge.mods.toml and ignores
			// it - an absent type is required. Which file the jar carries decides, not the running loader.
			Files.write(forge, jar(Map.of("META-INF/mods.toml", toml.getBytes(StandardCharsets.UTF_8))));
			assertEquals(Set.of(), inspect(forge).deps());
			Files.write(neoforge, jar(Map.of("META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8))));
			assertEquals(Set.of("legacy_optional"), inspect(neoforge).deps());
		} finally {
			Files.deleteIfExists(forge);
			Files.deleteIfExists(neoforge);
		}
	}

	@Test
	void displayTestDecidesWhetherAClientMayLackTheJar() throws IOException {
		Path forge = Files.createTempDirectory("display-test-").resolve("forgemod.jar");
		Path neoforge = Files.createTempDirectory("display-test-").resolve("neoforgemod.jar");

		try {
			Files.write(forge, jar(Map.of("META-INF/mods.toml", toml("").getBytes(StandardCharsets.UTF_8))));
			FileInspection.Inspection matched = inspection(forge);
			assertFalse(matched.clientMayBeAbsent(), "an absent displayTest is Forge's MATCH_VERSION default");
			assertFalse(matched.declaresServerOnly());

			Files.write(forge, jar(Map.of("META-INF/mods.toml", toml("displayTest = \"IGNORE_SERVER_VERSION\"\n").getBytes(StandardCharsets.UTF_8))));
			FileInspection.Inspection serverOnly = inspection(forge);
			assertTrue(serverOnly.clientMayBeAbsent());
			assertTrue(serverOnly.declaresServerOnly());

			Files.write(forge, jar(Map.of("META-INF/mods.toml", toml("displayTest = \"IGNORE_ALL_VERSION\"\n").getBytes(StandardCharsets.UTF_8))));
			FileInspection.Inspection indifferent = inspection(forge);
			assertTrue(indifferent.clientMayBeAbsent());
			assertFalse(indifferent.declaresServerOnly(), "only a client-side mod uses IGNORE_ALL_VERSION");

			Files.write(forge, jar(Map.of("META-INF/mods.toml", toml("displayTest = \"NONE\"\n").getBytes(StandardCharsets.UTF_8))));
			assertFalse(inspection(forge).clientMayBeAbsent(), "NONE keeps a custom check, so nothing is promised");

			// NeoForge dropped the key, so a neoforge.mods.toml carrying it promises nothing.
			Files.write(neoforge, jar(Map.of("META-INF/neoforge.mods.toml", toml("displayTest = \"IGNORE_SERVER_VERSION\"\n").getBytes(StandardCharsets.UTF_8))));
			FileInspection.Inspection neo = inspection(neoforge);
			assertTrue(neo.clientMayBeAbsent());
			assertFalse(neo.declaresServerOnly());
		} finally {
			Files.deleteIfExists(forge);
			Files.deleteIfExists(neoforge);
		}
	}

	@Test
	void nestedJarJarsWithholdTheDisplayTestPromise() throws IOException {
		Path jar = Files.createTempDirectory("display-test-nested-").resolve("nesting.jar");
		String nestedJson = "{\"id\": \"nested\", \"version\": \"1.0.0\"}";

		try {
			Files.write(jar, jar(Map.of("META-INF/mods.toml", toml("displayTest = \"IGNORE_SERVER_VERSION\"\n").getBytes(StandardCharsets.UTF_8),
					"META-INF/jarjar/nested.jar", jar(Map.of("fabric.mod.json", nestedJson.getBytes(StandardCharsets.UTF_8))))));
			FileInspection.Inspection inspection = inspection(jar);

			assertFalse(inspection.clientMayBeAbsent(), "the root entry cannot speak for nested jars");
			assertFalse(inspection.declaresServerOnly());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void fabricJsonEnvironmentMatchesMetadata() throws IOException {
		Path jar = Files.createTempDirectory("json-metadata-").resolve("jsonmod.jar");
		String json = "{\"id\": \"jsonmod\", \"version\": \"2.0.0\", \"environment\": \"client\", \"depends\": {\"fabric-api\": \"*\"}}";

		try {
			Files.write(jar, jar(Map.of("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8))));
			FileInspection.Mod mod = inspect(jar);

			assertEquals("2.0.0", mod.version());
			assertEquals(Set.of("jsonmod"), mod.IDs());
			assertEquals(Set.of("fabric-api"), mod.deps());
			assertEquals(ModEnvironment.CLIENT, mod.environment());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void nestedTreesStayReadableAtDepthTwo() throws IOException {
		Path jar = Files.createTempDirectory("nested-depth-").resolve("rootmod.jar");
		String deepJson = "{\"id\": \"depthmod\", \"version\": \"1.0.0\"}";
		String midJson = "{\"id\": \"depthmod\", \"version\": \"1.0.0\", \"jars\": [{\"file\": \"META-INF/jars/deep.jar\"}]}";
		String rootJson = "{\"id\": \"depthmod\", \"version\": \"1.0.0\", \"jars\": [{\"file\": \"META-INF/jars/mid.jar\"}]}";

		try {
			Files.write(jar, jar(Map.of("fabric.mod.json", rootJson.getBytes(StandardCharsets.UTF_8),
					"META-INF/jars/mid.jar", jar(Map.of("fabric.mod.json", midJson.getBytes(StandardCharsets.UTF_8),
							"META-INF/jars/deep.jar", jar(Map.of("fabric.mod.json", deepJson.getBytes(StandardCharsets.UTF_8))))))));
			FileInspection.Mod mod = inspect(jar);
			FileInspection.Mod mid = onlyNested(mod);

			// Detection bundles depth-two jars by entry chain, so the inspection tree must reach them with paths intact.
			assertEquals("depthmod", onlyNested(mid).id());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void undeclaredJarEntriesAreNotModNests() throws IOException {
		Path jar = Files.createTempDirectory("undeclared-nest-").resolve("rootmod.jar");
		String rootJson = "{\"id\": \"rootmod\", \"version\": \"1.0.0\"}";
		String stowawayJson = "{\"id\": \"stowaway\", \"version\": \"1.0.0\"}";

		try {
			Files.write(jar, jar(Map.of("fabric.mod.json", rootJson.getBytes(StandardCharsets.UTF_8),
					"META-INF/jars/stowaway.jar", jar(Map.of("fabric.mod.json", stowawayJson.getBytes(StandardCharsets.UTF_8))))));
			FileInspection.Mod mod = inspect(jar);

			assertEquals(Set.of(), mod.nestedMods());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	/** A loader-service jar is a mod with no metadata: the fact has to survive the inspection or the file is re-read forever. */
	@Test
	void aServiceOnlyJarIsAModWithoutMetadata() throws IOException {
		Path jar = Files.createTempDirectory("service-only-").resolve("service.jar");

		try {
			Files.write(jar, jar(Map.of(LoaderServicePaths.FORGE_MOD_LOCATOR, new byte[0])));
			FileInspection.Inspection inspection = inspection(jar);

			assertTrue(inspection.mod());
			assertNull(inspection.metadata());
			assertEquals(ModEnvironment.UNKNOWN, inspection.environment());
			assertTrue(inspection.providesAny(Set.of(LoaderServicePaths.FORGE_MOD_LOCATOR)));
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void incompleteMetadataWithoutServicesIsNotAMod() throws IOException {
		Path jar = Files.createTempDirectory("incomplete-").resolve("broken.jar");

		try {
			Files.write(jar, jar(Map.of("fabric.mod.json", "{\"version\": \"1.0.0\"}".getBytes(StandardCharsets.UTF_8))));
			FileInspection.Inspection inspection = inspection(jar);

			assertFalse(inspection.mod());
			assertNull(inspection.metadata());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void aPlainArchiveIsNotAMod() throws IOException {
		Path archive = Files.createTempDirectory("plain-").resolve("pack.zip");

		try {
			Files.write(archive, jar(Map.of("data/x.txt", new byte[0])));

			assertFalse(inspection(archive).mod());
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	/** Zipfs throws ProviderNotFoundException for a truncated zip whose path does not end with .jar/.zip; that has to read as none or login dies. */
	@Test
	void aTruncatedArchiveWithANonJarNameIsNotAMod() throws IOException {
		Path archive = Files.createTempDirectory("truncated-disabled-").resolve("mod.jar.disabled");

		try {
			Files.write(archive, new byte[]{'P', 'K', 3, 4, 0, 0});
			FileInspection.Inspection inspection = inspection(archive);

			assertFalse(inspection.mod());
			assertNull(inspection.metadata());
		} finally {
			Files.deleteIfExists(archive);
		}
	}

	private static String toml(String displayTestLine) {
		return "modLoader = \"javafml\"\n[[mods]]\nmodId = \"tomlmod\"\nversion = \"1.0.0\"\n" + displayTestLine;
	}

	private static FileInspection.Mod onlyNested(FileInspection.Mod mod) {
		assertEquals(1, mod.nestedMods().size());
		return mod.nestedMods().iterator().next();
	}

	private static FileInspection.Mod inspect(Path jar) throws IOException {
		return inspection(jar).metadata();
	}

	private static FileInspection.Inspection inspection(Path jar) throws IOException {
		Path cacheDirectory = Files.createTempDirectory("file-cache-");
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			return FileInspection.inspect(jar, cache);
		} finally {
			FileTrees.delete(cacheDirectory);
		}
	}

	private static byte[] jar(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}
}
