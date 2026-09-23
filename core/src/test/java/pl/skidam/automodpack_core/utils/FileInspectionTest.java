package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
	void fabricJsonEnvironmentMatchesMetadata() throws IOException {
		Path jar = Files.createTempDirectory("json-metadata-").resolve("jsonmod.jar");
		String json = "{\"id\": \"jsonmod\", \"version\": \"2.0.0\", \"environment\": \"client\", \"depends\": {\"fabric-api\": \"*\"}}";

		try {
			Files.write(jar, jar(Map.of("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8))));
			FileInspection.Mod mod = inspect(jar);

			assertEquals("2.0.0", mod.version());
			assertEquals(Set.of("jsonmod"), mod.IDs());
			assertEquals(Set.of("fabric-api"), mod.deps());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void nestedTreesStayReadableAtDepthTwo() throws IOException {
		Path jar = Files.createTempDirectory("nested-depth-").resolve("rootmod.jar");
		String json = "{\"id\": \"depthmod\", \"version\": \"1.0.0\"}";

		try {
			Files.write(jar, jar(Map.of("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8),
					"META-INF/jars/mid.jar", jar(Map.of("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8),
							"META-INF/jars/deep.jar", jar(Map.of("fabric.mod.json", json.getBytes(StandardCharsets.UTF_8))))))));
			FileInspection.Mod mod = inspect(jar);
			FileInspection.Mod mid = onlyNested(mod);

			// Detection bundles depth-two jars by entry chain, so the inspection tree must reach them with paths intact.
			assertEquals("depthmod", onlyNested(mid).id());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	private static FileInspection.Mod onlyNested(FileInspection.Mod mod) {
		assertEquals(1, mod.nestedMods().size());
		return mod.nestedMods().iterator().next();
	}

	private static FileInspection.Mod inspect(Path jar) throws IOException {
		Path cacheDirectory = Files.createTempDirectory("file-cache-");
		try (FileCache cache = FileCache.open(cacheDirectory)) {
			return FileInspection.getMod(jar, cache);
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
