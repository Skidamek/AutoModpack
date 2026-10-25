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
		Files.write(PartialResume.sliceTemp(dir, NetUtils.WIRE_CHUNK_BYTES), new byte[50]);

		List<long[]> remaining = PartialResume.remaining(dir, size);
		assertEquals(2, remaining.size());
		assertEquals(NetUtils.WIRE_CHUNK_BYTES + 50, remaining.get(0)[0]);
		assertEquals(NetUtils.WIRE_CHUNK_BYTES * 2L - 1, remaining.get(0)[1]);
		assertEquals(NetUtils.WIRE_CHUNK_BYTES * 2L, remaining.get(1)[0]);
		assertEquals(size - 1, remaining.get(1)[1]);
		// A tile still filling is a resume point, not progress: only whole named tiles count toward the total.
		assertEquals(NetUtils.WIRE_CHUNK_BYTES, PartialResume.presentBytes(dir, size));
		assertEquals(size - NetUtils.WIRE_CHUNK_BYTES, PartialResume.remainingBytes(directory, sha1, size));
		assertEquals(size, PartialResume.remainingBytes(directory, HashUtils.sha1("absent"), size));
	}

	@Test
	void aTileTakesItsRealNameOnlyOnceItIsWholeWhicheverTempFilledIt(@TempDir Path directory) throws Exception {
		long tile = NetUtils.WIRE_CHUNK_BYTES;
		byte[] whole = new byte[(int) tile];
		Arrays.fill(whole, (byte) 7);
		byte[] half = Arrays.copyOf(whole, (int) tile - 1);
		// One directory per temp, or the second case finds the first case's tile already named and proves nothing.
		for (String who : List.of("platform", "host")) {
			Path dir = PartialResume.directory(directory, HashUtils.sha1(who));
			Files.createDirectories(dir);
			Path filling = who.equals("host") ? PartialResume.sliceHostTemp(dir, 0) : PartialResume.sliceTemp(dir, 0);
			Path named = PartialResume.sliceFile(dir, 0);

			Files.write(filling, half);
			assertFalse(PartialResume.promoteSlice(dir, 0, tile), "a short tile never takes its real name, or a reader counts a partial tile as progress: " + who);
			assertFalse(Files.exists(named), "nothing is named while the tile is still filling: " + who);

			Files.write(filling, whole);
			assertTrue(PartialResume.promoteSlice(dir, 0, tile), "a whole tile is named whichever temp filled it: " + who);
			assertArrayEquals(whole, Files.readAllBytes(named), "the named tile holds every byte of its range: " + who);
			assertFalse(Files.exists(filling), "a named tile leaves no temp behind for a reader to mistake for progress: " + who);
		}
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
	void aWriterDiscardsBytesOfATileSomebodyElseAlreadyNamed(@TempDir Path directory) throws Exception {
		String sha1 = HashUtils.sha1("skip");
		Path dir = PartialResume.directory(directory, sha1);
		byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES + 50];
		for (int i = 0; i < object.length; i++) object[i] = (byte) i;
		Files.createDirectories(dir);
		Files.write(PartialResume.sliceFile(dir, NetUtils.WIRE_CHUNK_BYTES), Arrays.copyOfRange(object, NetUtils.WIRE_CHUNK_BYTES, object.length));
		try (var out = PartialResume.writer(dir, object.length, 0)) {
			out.write(object);
		}
		assertTrue(PartialResume.complete(dir, object.length));
		Path assembled = directory.resolve("out");
		PartialResume.assemble(dir, assembled, object.length);
		assertArrayEquals(object, Files.readAllBytes(assembled));
	}

	@Test
	void aWriterDiscardsALeadingNamedTileAndKeepsTheRestAligned(@TempDir Path directory) throws Exception {
		String sha1 = HashUtils.sha1("skip-head");
		Path dir = PartialResume.directory(directory, sha1);
		byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES + 50];
		for (int i = 0; i < object.length; i++) object[i] = (byte) i;
		Files.createDirectories(dir);
		Files.write(PartialResume.sliceFile(dir, 0), Arrays.copyOf(object, NetUtils.WIRE_CHUNK_BYTES));
		try (var out = PartialResume.writer(dir, object.length, 0)) {
			out.write(object);
		}
		assertTrue(PartialResume.complete(dir, object.length));
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
		Files.write(PartialResume.sliceTemp(dir, 0), Arrays.copyOf(object, 5));
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

	@Test
	void stitchingReturnsTheHashOfExactlyTheBytesItWrote(@TempDir Path directory) throws Exception {
		byte[] object = new byte[NetUtils.WIRE_CHUNK_BYTES * 2 + 3];
		new java.util.Random(9).nextBytes(object);
		Path dir = PartialResume.directory(directory, HashUtils.sha1(object));
		try (var out = PartialResume.writer(dir, object.length, 0)) {
			out.write(object);
		}
		Path assembled = directory.resolve("assembled");
		assertEquals(HashUtils.sha1(object), PartialResume.assembleHashing(dir, assembled, object.length));
		assertArrayEquals(object, Files.readAllBytes(assembled));
		assertThrows(java.io.IOException.class, () -> PartialResume.assembleHashing(PartialResume.directory(directory, HashUtils.sha1("missing")), directory.resolve("other"), 10));
	}

	@Test
	void onlyAWholeSoleTileIsAdoptedAndAFailedMoveLeavesItForStitching(@TempDir Path directory) throws Exception {
		byte[] object = "one tile".getBytes(StandardCharsets.UTF_8);
		Path dir = PartialResume.directory(directory, HashUtils.sha1(object));
		Files.createDirectories(dir);
		Path tile = PartialResume.sliceFile(dir, 0);
		Files.write(tile, object);

		Path taken = Files.writeString(directory.resolve("taken"), "someone else's file");
		assertFalse(PartialResume.adoptSoleTile(dir, taken, object.length), "a move that cannot complete falls back to stitching");
		assertArrayEquals(object, Files.readAllBytes(tile));
		assertEquals("someone else's file", Files.readString(taken));

		assertFalse(PartialResume.adoptSoleTile(dir, directory.resolve("big"), NetUtils.WIRE_CHUNK_BYTES + 1L), "a multi-tile object is never adopted from its first tile");
		assertFalse(PartialResume.adoptSoleTile(dir, directory.resolve("empty"), 0));
		assertTrue(Files.exists(tile));

		Path adopted = directory.resolve("adopted");
		assertTrue(PartialResume.adoptSoleTile(dir, adopted, object.length));
		assertArrayEquals(object, Files.readAllBytes(adopted));
		assertFalse(Files.exists(tile));
	}
}
