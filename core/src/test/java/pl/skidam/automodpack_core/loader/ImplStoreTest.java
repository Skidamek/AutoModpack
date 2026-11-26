package pl.skidam.automodpack_core.loader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.airlift.compress.zstd.ZstdOutputStream;

import pl.skidam.automodpack_core.utils.HashUtils;

class ImplStoreTest {

	@Test
	void extractsOnlyActiveImplAndReplacesPreviousOnVersionSwitch(@TempDir Path dir) throws IOException {
		byte[] fabricBytes = repeatBytes((byte) 0x41, 100_000);
		byte[] neoforgeBytes = repeatBytes((byte) 0x42, 120_000);
		Path outerJar = writeOuterJar(dir.resolve("outer.jar"), fabricBytes, neoforgeBytes, false);
		Path cacheDir = dir.resolve("impl-cache");

		Path fabricJar = ImplStore.select(outerJar, "fabric", "26.2", cacheDir);
		assertArrayEquals(fabricBytes, Files.readAllBytes(fabricJar));
		assertFalse(Files.exists(cacheDir.resolve("26.3-neoforge.jar")), "unrelated impl must not be extracted");

		// Second boot with the same version hits the stamp cache without rewriting.
		var mtimeBefore = Files.getLastModifiedTime(fabricJar);
		Path secondHit = ImplStore.select(outerJar, "fabric", "26.2", cacheDir);
		assertEqualsPath(fabricJar, secondHit);
		assertTrue(Files.getLastModifiedTime(secondHit).equals(mtimeBefore));

		// Switching loader/version extracts only the new impl and removes the previous one.
		Path neoforgeJar = ImplStore.select(outerJar, "neoforge", "26.3", cacheDir);
		assertArrayEquals(neoforgeBytes, Files.readAllBytes(neoforgeJar));
		assertFalse(Files.exists(cacheDir.resolve("26.2-fabric.jar")), "previous version impl must be removed on switch");
	}

	@Test
	void rejectsCorruptSliceSha1(@TempDir Path dir) throws IOException {
		byte[] fabricBytes = repeatBytes((byte) 0x55, 1024);
		byte[] neoforgeBytes = repeatBytes((byte) 0x66, 1024);
		Path outerJar = writeOuterJar(dir.resolve("corrupt.jar"), fabricBytes, neoforgeBytes, true);
		Path cacheDir = dir.resolve("impl-cache");

		assertThrows(IllegalStateException.class, () -> ImplStore.select(outerJar, "neoforge", "26.3", cacheDir));
		assertFalse(Files.exists(cacheDir.resolve("26.3-neoforge.jar")));
	}

	private static void assertEqualsPath(Path expected, Path actual) {
		assertTrue(expected.toAbsolutePath().normalize().equals(actual.toAbsolutePath().normalize()));
	}

	private static byte[] repeatBytes(byte value, int length) {
		byte[] out = new byte[length];
		for (int i = 0; i < length; i++) out[i] = (byte) (value + (i % 13));
		return out;
	}

	private static Path writeOuterJar(Path jar, byte[] fabricBytes, byte[] neoforgeBytes, boolean corruptSecondHash) throws IOException {
		ByteArrayOutputStream solidOut = new ByteArrayOutputStream();
		solidOut.write(fabricBytes);
		solidOut.write(neoforgeBytes);
		byte[] solid = solidOut.toByteArray();

		String solidDigest = sha1(solid);
		String fabricSha1 = sha1(fabricBytes);
		String neoforgeSha1 = corruptSecondHash ? "0000000000000000000000000000000000000000" : sha1(neoforgeBytes);
		String manifest = """
				{
				  "digest": "%s",
				  "impls": [
				    {"id": "26.2-fabric", "covers": ["~26.2"], "offset": 0, "length": %d, "sha1": "%s"},
				    {"id": "26.3-neoforge", "covers": ["~26.3"], "offset": %d, "length": %d, "sha1": "%s"}
				  ]
				}
				""".formatted(solidDigest, fabricBytes.length, fabricSha1, fabricBytes.length, neoforgeBytes.length, neoforgeSha1);

		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		try (ZstdOutputStream zstd = new ZstdOutputStream(compressed)) {
			zstd.write(solid);
		}

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("impl/manifest.json"));
			zip.write(manifest.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("impl/all.zst"));
			zip.write(compressed.toByteArray());
			zip.closeEntry();
		}
		return jar;
	}

	private static String sha1(byte[] bytes) {
		return HexFormat.of().formatHex(HashUtils.newSha1Digest().digest(bytes));
	}
}
