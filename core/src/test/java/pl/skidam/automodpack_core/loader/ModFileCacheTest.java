package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.utils.cache.FileCache;

class ModFileCacheTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void reusesMetadataForTheSameContentAtAnotherPath() throws Exception {
		Path first = temporaryDirectory.resolve("first.jar");
		Path second = temporaryDirectory.resolve("second.jar");
		writeMod(first);
		Files.copy(first, second);

		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache"));
				ModFileCache modCache = ModFileCache.open(temporaryDirectory.resolve("mod-cache"))) {
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
	void cachedNestedMetadataKeepsItsEntryPathForDependencyCopies() throws Exception {
		Path pack = temporaryDirectory.resolve("pack.jar");
		Path dependent = temporaryDirectory.resolve("dependent.jar");
		Path records = temporaryDirectory.resolve("mod-cache");
		writeNestedPack(pack);
		writeFabricMod(dependent, "nesteddep_user", "\"nesteddep_lib\":\"*\"");
		String packHash;

		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(records)) {
			packHash = modCache.getOrComputeMod(pack, hashCache).hash();
			modCache.getOrComputeMod(dependent, hashCache);
		}
		Path packRecord = records.resolve(packHash.substring(0, 2)).resolve(packHash.substring(2) + ".json");
		JsonObject oldRecord = JsonParser.parseString(Files.readString(packRecord, StandardCharsets.UTF_8)).getAsJsonObject();
		oldRecord.getAsJsonArray("nestedMods").get(0).getAsJsonObject().remove("entryPath");
		Files.writeString(packRecord, oldRecord.toString(), StandardCharsets.UTF_8);

		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(records)) {
			FileInspection.Mod cachedPack = modCache.getOrComputeMod(pack, hashCache);
			FileInspection.Mod cachedDependent = modCache.getOrComputeMod(dependent, hashCache);
			FileInspection.Mod nested = cachedPack.nestedMods().iterator().next();

			assertEquals(Path.of("META-INF/jars/nesteddep-lib.jar"), nested.path());
			assertEquals(List.of(), setAsideCopies(records));
			assertEquals(List.of("mods/pack.jar/META-INF/jars/nesteddep-lib.jar"), NestedConflicts.detect(
					List.of(new NestedConflicts.PackRoot("mods/pack.jar", cachedPack)),
					List.of(new NestedConflicts.StandardRoot("mods/dependent.jar", cachedDependent)), cachedPack.IDs()).stream().map(NestedConflicts.Candidate::entryName).toList());
		}
	}

	@Test
	void inspectsAnExtensionlessContentAddressedArchive() throws Exception {
		Path source = temporaryDirectory.resolve("source.jar");
		Path contentAddressedObject = temporaryDirectory.resolve("objects").resolve("content-hash");
		writeMod(source);
		Files.createDirectories(contentAddressedObject.getParent());
		Files.copy(source, contentAddressedObject);

		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache"));
				ModFileCache modCache = ModFileCache.open(temporaryDirectory.resolve("mod-cache"))) {
			FileInspection.Mod mod = modCache.getOrComputeMod(contentAddressedObject, hashCache);

			assertNotNull(mod);
			assertEquals(contentAddressedObject.toAbsolutePath().normalize(), mod.path());
			assertEquals("example", mod.IDs().iterator().next());
		}
	}

	/**
	 * A loader-service jar carries no metadata, so it is recorded as a mod without one. The copy answers from a directory the
	 * process has never read, so it fails unless the verdict was persisted and validates.
	 */
	@Test
	void persistsTheServiceOnlyJarItCannotDescribe() throws Exception {
		Path jar = temporaryDirectory.resolve("service.jar");
		Path records = temporaryDirectory.resolve("mod-cache");
		try (OutputStream output = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(output)) {
			zip.putNextEntry(new JarEntry(LoaderServicePaths.FORGE_MOD_LOCATOR));
			zip.closeEntry();
		}

		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(records)) {
			assertTrue(modCache.inspect(jar, hashCache).mod());
		}

		Path copied = temporaryDirectory.resolve("mod-cache-copy");
		copyTree(records, copied);
		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(copied)) {
			FileInspection.Inspection cached = modCache.inspect(jar, hashCache);

			assertTrue(cached.mod(), "a persisted service-only jar still reads as a mod");
			assertNull(cached.metadata());
			assertTrue(cached.providesAny(Set.of(LoaderServicePaths.FORGE_MOD_LOCATOR)), "the services that jar ships are the cached identity");
		}
	}

	/**
	 * Every shape change to the record turns every persisted record into a rebuild. That is the normal cost of adding a
	 * field, so it must read as a cache miss: the record is overwritten in place and nothing is filed as corruption. A
	 * build that mistakes a stale shape for corruption sets every record aside with an error and a stack trace instead.
	 */
	@Test
	void rebuildsARecordFromAnOlderShapeWithoutFilingItAsCorruption() throws Exception {
		Path jar = temporaryDirectory.resolve("mod.jar");
		Path records = temporaryDirectory.resolve("mod-cache");
		writeMod(jar);
		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(records)) {
			assertNotNull(modCache.getOrComputeMod(jar, hashCache));
		}

		Path record = onlyRecord(records);
		JsonObject persisted = JsonParser.parseString(Files.readString(record, StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject stale = persisted.deepCopy();
		stale.remove("isMod");
		stale.remove("environment");
		assertEquals(2, persisted.size() - stale.size(), "the persisted record no longer has the fields this test strips, so it is no longer testing a stale shape");
		Files.writeString(record, stale.toString(), StandardCharsets.UTF_8);

		// The copy answers from a directory this process has never read, so the on-disk record is the only candidate.
		Path copied = temporaryDirectory.resolve("mod-cache-copy");
		copyTree(records, copied);
		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(copied)) {
			assertNotNull(modCache.getOrComputeMod(jar, hashCache));
			assertEquals(List.of(), setAsideCopies(copied), "a stale shape is a rebuild, not corruption");
		}
	}

	@Test
	void unreadableRecordIsDeletedNotSetAside() throws Exception {
		Path jar = temporaryDirectory.resolve("mod.jar");
		Path records = temporaryDirectory.resolve("mod-cache");
		writeMod(jar);
		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(records)) {
			assertNotNull(modCache.getOrComputeMod(jar, hashCache));
		}

		Path record = onlyRecord(records);
		Files.writeString(record, "not-json", StandardCharsets.UTF_8);

		Path copied = temporaryDirectory.resolve("mod-cache-unreadable");
		copyTree(records, copied);
		try (FileCache hashCache = FileCache.open(temporaryDirectory.resolve("file-cache")); ModFileCache modCache = ModFileCache.open(copied)) {
			assertNotNull(modCache.getOrComputeMod(jar, hashCache));
			assertEquals(List.of(), setAsideCopies(copied), "an unreadable cache record is discarded, not filed as corruption");
		}
	}

	private static Path onlyRecord(Path records) throws IOException {
		try (Stream<Path> walk = Files.walk(records)) {
			List<Path> found = walk.filter(Files::isRegularFile).toList();
			assertEquals(1, found.size(), "the inspection cache persisted exactly one record");
			return found.get(0);
		}
	}

	private static List<Path> setAsideCopies(Path records) throws IOException {
		try (Stream<Path> walk = Files.walk(records)) {
			return walk.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path source : walk.toList()) {
				Path destination = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) Files.createDirectories(destination);
				else {
					Files.createDirectories(destination.getParent());
					Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void writeMod(Path path) throws Exception {
		try (OutputStream output = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(output)) {
			jar.putNextEntry(new JarEntry("fabric.mod.json"));
			jar.write("{\"id\":\"example\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
		}
	}

	private static void writeNestedPack(Path path) throws Exception {
		byte[] nested = jar(Map.of("fabric.mod.json", "{\"id\":\"nesteddep_lib\",\"version\":\"1.0.0\"}".getBytes(StandardCharsets.UTF_8)));
		try (OutputStream output = Files.newOutputStream(path); JarOutputStream zip = new JarOutputStream(output)) {
			zip.putNextEntry(new JarEntry("fabric.mod.json"));
			zip.write("{\"id\":\"nesteddep_pack\",\"version\":\"1.0.0\",\"jars\":[{\"file\":\"META-INF/jars/nesteddep-lib.jar\"}]}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new JarEntry("META-INF/jars/nesteddep-lib.jar"));
			zip.write(nested);
			zip.closeEntry();
		}
	}

	private static void writeFabricMod(Path path, String modId, String dependencies) throws Exception {
		try (OutputStream output = Files.newOutputStream(path); JarOutputStream zip = new JarOutputStream(output)) {
			zip.putNextEntry(new JarEntry("fabric.mod.json"));
			zip.write(("{\"id\":\"" + modId + "\",\"version\":\"1.0.0\",\"depends\":{" + dependencies + "}}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	private static byte[] jar(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (JarOutputStream zip = new JarOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new JarEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}
}
