package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class JarUtilsTest {

	@Test
	void opensANestedJarAtDepthOne() throws IOException {
		Path jar = temporaryJar();
		byte[] inner = jar(Map.of("inner.txt", "one".getBytes()));

		try (var in = JarUtils.openNestedJar(jar, List.of("META-INF/jars/inner.jar"))) {
			assertArrayEquals(inner, in.readAllBytes());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void opensANestedJarAtDepthTwo() throws IOException {
		Path jar = temporaryJar();
		byte[] deepest = jar(Map.of("deep.txt", "two".getBytes()));

		try (var in = JarUtils.openNestedJar(jar, List.of("META-INF/jars/middle.jar", "META-INF/jars/deep.jar"))) {
			assertArrayEquals(deepest, in.readAllBytes());
		} finally {
			Files.deleteIfExists(jar);
		}
	}

	@Test
	void aMissingNestedEntryFailsLoudly() throws IOException {
		Path jar = temporaryJar();

		assertThrows(IOException.class, () -> JarUtils.openNestedJar(jar, List.of("META-INF/jars/absent.jar")));
		assertThrows(IOException.class, () -> JarUtils.openNestedJar(jar, List.of("META-INF/jars/inner.jar", "META-INF/jars/absent.jar")));
		Files.deleteIfExists(jar);
	}

	@Test
	void aCleanJarStreamsValidate() throws IOException {
		byte[] payload = jar(Map.of("a.txt", "a".getBytes(), "b.txt", "b".getBytes()));

		JarUtils.validateStreamedJar(new ByteArrayInputStream(payload));
	}

	@Test
	void aJarWithACorruptLaterEntryFailsValidation() throws IOException {
		byte[] payload = jar(Map.of("a.txt", "a".getBytes(), "b.txt", "b".getBytes()));
		byte[] corrupted = corruptSecondDataDescriptor(payload);

		assertThrows(IOException.class, () -> JarUtils.validateStreamedJar(new ByteArrayInputStream(corrupted)));
	}

	/** Flips a byte of the second entry's data descriptor, the corruption a metadata-first scan never reaches but a full zip scan trips over. */
	private static byte[] corruptSecondDataDescriptor(byte[] zip) {
		byte[] signature = {0x50, 0x4B, 0x07, 0x08};
		int occurrences = 0;
		for (int index = 0; index <= zip.length - signature.length; index++) {
			boolean match = true;
			for (int offset = 0; offset < signature.length; offset++)
				if (zip[index + offset] != signature[offset]) match = false;
			if (!match) continue;
			occurrences++;
			if (occurrences == 2) {
				zip[index] ^= 0x5A;
				return zip;
			}
		}
		throw new IllegalArgumentException("Zip has no second data descriptor");
	}

	private static Path temporaryJar() throws IOException {
		byte[] inner = jar(Map.of("inner.txt", "one".getBytes()));
		byte[] deep = jar(Map.of("deep.txt", "two".getBytes()));
		Path jar = Files.createTempDirectory("jar-utils-").resolve("root.jar");
		Files.write(jar, jar(Map.of(
				"META-INF/jars/inner.jar", inner,
				"META-INF/jars/middle.jar", jar(Map.of("META-INF/jars/deep.jar", deep)))));
		return jar;
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
