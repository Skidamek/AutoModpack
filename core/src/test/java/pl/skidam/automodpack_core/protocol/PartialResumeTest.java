package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.HashUtils;

class PartialResumeTest {

	@Test
	void remainingRangesSkipCompleteTilesAndResumeShortOnes(@TempDir Path directory) throws Exception {
		String sha1 = HashUtils.sha1("object");
		Path dir = PartialResume.directory(directory, sha1);
		long size = NetUtils.WIRE_CHUNK_BYTES * 2L + 100;
		Files.createDirectories(dir);
		Files.write(PartialResume.sliceFile(dir, 0), new byte[NetUtils.WIRE_CHUNK_BYTES]);
		Files.write(PartialResume.sliceFile(dir, NetUtils.WIRE_CHUNK_BYTES), new byte[50]);

		List<long[]> remaining = PartialResume.remaining(dir, size);
		assertEquals(2, remaining.size());
		assertEquals(NetUtils.WIRE_CHUNK_BYTES + 50, remaining.get(0)[0]);
		assertEquals(NetUtils.WIRE_CHUNK_BYTES * 2L - 1, remaining.get(0)[1]);
		assertEquals(NetUtils.WIRE_CHUNK_BYTES * 2L, remaining.get(1)[0]);
		assertEquals(size - 1, remaining.get(1)[1]);
		assertEquals(NetUtils.WIRE_CHUNK_BYTES + 50L, PartialResume.presentBytes(dir, size));
		assertEquals(size - (NetUtils.WIRE_CHUNK_BYTES + 50L), PartialResume.remainingBytes(directory, sha1, size));
		assertEquals(size, PartialResume.remainingBytes(directory, HashUtils.sha1("absent"), size));
	}

	@Test
	void assembleConcatenatesTilesInOffsetOrder(@TempDir Path directory) throws Exception {
		String sha1 = HashUtils.sha1("assemble");
		Path dir = PartialResume.directory(directory, sha1);
		byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES + 1234];
		for (int i = 0; i < object.length; i++) object[i] = (byte) i;
		Files.createDirectories(dir);
		Files.write(PartialResume.sliceFile(dir, 0), Arrays.copyOf(object, NetUtils.WIRE_CHUNK_BYTES));
		Files.write(PartialResume.sliceFile(dir, NetUtils.WIRE_CHUNK_BYTES), Arrays.copyOfRange(object, NetUtils.WIRE_CHUNK_BYTES, object.length));

		Path assembled = directory.resolve("out");
		PartialResume.assemble(dir, assembled, object.length);
		assertArrayEquals(object, Files.readAllBytes(assembled));
	}

	@Test
	void sequentialWriterFillsTilesFromAShortPrefix(@TempDir Path directory) throws Exception {
		String sha1 = HashUtils.sha1("seq");
		Path dir = PartialResume.directory(directory, sha1);
		byte[] object = "hello-world-partial-resume-slices".getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(dir);
		Files.write(PartialResume.sliceFile(dir, 0), Arrays.copyOf(object, 5));
		try (var out = PartialResume.writer(dir, object.length, 5)) {
			out.write(object, 5, object.length - 5);
		}
		assertTrue(PartialResume.complete(dir, object.length));
		Path assembled = directory.resolve("out");
		PartialResume.assemble(dir, assembled, object.length);
		assertArrayEquals(object, Files.readAllBytes(assembled));
	}

	@Test
	void keepOnlyDropsUnneededSliceDirectories(@TempDir Path directory) throws Exception {
		String keep = HashUtils.sha1("keep");
		String drop = HashUtils.sha1("drop");
		Files.createDirectories(PartialResume.directory(directory, keep).resolve("nested-not-used"));
		Files.createDirectories(PartialResume.directory(directory, drop));
		Files.write(PartialResume.sliceFile(PartialResume.directory(directory, drop), 0), new byte[]{1});

		PartialResume.keepOnly(directory, Set.of(keep));
		assertTrue(Files.isDirectory(PartialResume.directory(directory, keep)));
		assertFalse(Files.exists(PartialResume.directory(directory, drop)));
	}
}
