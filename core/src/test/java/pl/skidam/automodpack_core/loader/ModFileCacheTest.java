package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
