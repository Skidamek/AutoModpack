package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.FileInspection;

class ModFileCacheTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void reusesMetadataForTheSameContentAtAnotherPath() throws Exception {
		Path first = temporaryDirectory.resolve("first.jar");
		Path second = temporaryDirectory.resolve("second.jar");
		writeMod(first);
		Files.copy(first, second);

		try (FileMetadataCache hashCache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"));
				ModFileCache modCache = ModFileCache.open(temporaryDirectory.resolve("mod-metadata"))) {
			FileInspection.Mod firstMod = modCache.getOrComputeMod(first, hashCache);
			FileInspection.Mod secondMod = modCache.getOrComputeMod(second, hashCache);

			assertNotNull(firstMod);
			assertNotNull(secondMod);
			assertEquals(firstMod.hash(), secondMod.hash());
			assertEquals(second.toAbsolutePath().normalize(), secondMod.path());
			assertEquals("example", secondMod.IDs().iterator().next());
		}
	}

	@Test
	void inspectsAnExtensionlessContentAddressedArchive() throws Exception {
		Path source = temporaryDirectory.resolve("source.jar");
		Path contentAddressedObject = temporaryDirectory.resolve("objects").resolve("content-hash");
		writeMod(source);
		Files.createDirectories(contentAddressedObject.getParent());
		Files.copy(source, contentAddressedObject);

		try (FileMetadataCache hashCache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata"));
				ModFileCache modCache = ModFileCache.open(temporaryDirectory.resolve("mod-metadata"))) {
			FileInspection.Mod mod = modCache.getOrComputeMod(contentAddressedObject, hashCache);

			assertNotNull(mod);
			assertEquals(contentAddressedObject.toAbsolutePath().normalize(), mod.path());
			assertEquals("example", mod.IDs().iterator().next());
		}
	}

	@Test
	void forcedReinspectionReplacesContentMetadataWithoutTrustingItsRecord() throws Exception {
		Path source = temporaryDirectory.resolve("source.jar");
		writeMod(source);
		String hash = pl.skidam.automodpack_core.utils.HashUtils.getHash(source);
		Path records = temporaryDirectory.resolve("mod-metadata");
		Path record = records.resolve(hash.substring(0, 2)).resolve(hash + ".json");
		Files.createDirectories(record.getParent());
		Files.writeString(record, "{\"IDs\":[\"wrong\"],\"hash\":\"" + hash + "\",\"version\":\"9.9.9\",\"deps\":[],\"nestedMods\":[]}", StandardCharsets.UTF_8);

		try (FileMetadataCache hashCache = FileMetadataCache.open(temporaryDirectory.resolve("file-metadata")); ModFileCache modCache = ModFileCache.open(records)) {
			assertEquals(Set.of("wrong"), modCache.getOrComputeMod(source, hashCache).IDs());
			assertEquals(Set.of("example"), modCache.reinspectMod(source, hashCache).IDs());
			assertEquals(Set.of("example"), modCache.getOrComputeMod(source, hashCache).IDs());
		}
	}

	private static void writeMod(Path path) throws Exception {
		try (OutputStream output = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(output)) {
			jar.putNextEntry(new JarEntry("fabric.mod.json"));
			jar.write("{\"id\":\"example\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
	}
}
