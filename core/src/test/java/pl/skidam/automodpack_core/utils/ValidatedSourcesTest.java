package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.utils.cache.FileCache;

/** One transaction's source records: reuse only while the source's own fingerprint still agrees, never a global trust. */
class ValidatedSourcesTest {
	@TempDir
	Path temporaryDirectory;

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void theSealedVerdictComesFromTheSameStatAsTheFingerprint() throws Exception {
		Assumptions.assumeTrue(PlatformUtils.operatingSystem() != PlatformUtils.OperatingSystem.WINDOWS);
		Path source = temporaryDirectory.resolve("object");
		Files.write(source, bytes("sealed bytes"));
		FileCache.SealedSnapshot sealed = FileCache.statSnapshotAndSealed(source);
		assertTrue(sealed != null, "a unix filesystem answers the fingerprint and the policy from one fused read");
		assertFalse(sealed.sealed(), "a freshly written file is writable");
		ImmutableFiles.protect(source);
		assertTrue(FileCache.statSnapshotAndSealed(source).sealed(), "the protected file reads back as sealed");
	}

	@Test
	void oneRecordServesRepeatedAsksAndKeepsTheSourceSealed() throws Exception {
		Path source = temporaryDirectory.resolve("object");
		byte[] content = bytes("verified bytes");
		Files.write(source, content);
		String hash = HashUtils.sha1(content);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			ValidatedSources.Source first = sources.require(source, content.length, hash);
			assertTrue(first.sealed(), "the first proof applies the read-only policy");
			assertTrue(ImmutableFiles.isProtected(source), "the first proof applies the read-only policy");

			ValidatedSources.Source second = sources.require(source, content.length, hash);
			assertEquals(first.fingerprint(), second.fingerprint());
			assertTrue(sources.matches(source, content.length, hash));
		}
	}

	@Test
	void aChangedFingerprintRevalidatesInsteadOfTrustingTheRecord() throws Exception {
		Path source = temporaryDirectory.resolve("object");
		byte[] content = bytes("first bytes");
		Files.write(source, content);
		String hash = HashUtils.sha1(content);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			assertTrue(sources.matches(source, content.length, hash));

			FileTime mtime = Files.getLastModifiedTime(source);
			Path replacement = temporaryDirectory.resolve("replacement");
			Files.write(replacement, bytes("other bytes"));
			Files.setLastModifiedTime(replacement, mtime);
			Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
			assertFalse(sources.matches(source, content.length, hash), "a replaced inode invalidates the record");
			assertThrows(IOException.class, () -> sources.require(source, content.length, hash));
		}
	}

	@Test
	void aReplacedSourceIsRevalidatedAndCanStillPass() throws Exception {
		Path source = temporaryDirectory.resolve("object");
		byte[] content = bytes("same bytes");
		Files.write(source, content);
		String hash = HashUtils.sha1(content);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			assertTrue(sources.matches(source, content.length, hash));
			Path replacement = temporaryDirectory.resolve("replacement");
			Files.write(replacement, content);
			Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
			assertTrue(sources.matches(source, content.length, hash), "a new inode with the right bytes is proved again");
		}
	}

	@Test
	void aDifferentPathWithTheSameHashIsNotAnsweredFromAnotherPathRecord() throws Exception {
		Path first = temporaryDirectory.resolve("first");
		Path second = temporaryDirectory.resolve("second");
		byte[] content = bytes("shared content");
		Files.write(first, content);
		Files.write(second, content);
		String hash = HashUtils.sha1(content);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			assertEquals(first.toAbsolutePath().normalize(), sources.require(first, content.length, hash).path());
			assertEquals(second.toAbsolutePath().normalize(), sources.require(second, content.length, hash).path());
			assertTrue(sources.matches(second, content.length, hash), "a path is answered by its own record, not the first path's");
		}
	}

	@Test
	void aCorruptSourceIsRejectedAndNotRemembered() throws Exception {
		Path source = temporaryDirectory.resolve("object");
		byte[] content = bytes("right bytes");
		Files.write(source, content);
		String hash = HashUtils.sha1(content);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			Files.write(source, bytes("wrong bytes"));
			assertThrows(IOException.class, () -> sources.require(source, content.length, hash));
			Files.write(source, content);
			Files.setLastModifiedTime(source, FileTime.fromMillis(1_000));
			assertTrue(sources.matches(source, content.length, hash), "a corrected source is proved on the next ask");
		}
	}

	@Test
	void anExpectedSizeThatContradictsTheSourceIsRejected() throws Exception {
		Path source = temporaryDirectory.resolve("object");
		byte[] content = bytes("sized bytes");
		Files.write(source, content);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			assertThrows(IOException.class, () -> sources.require(source, content.length + 1, HashUtils.sha1(content)));
			assertFalse(sources.matches(source, content.length + 1, HashUtils.sha1(content)));
		}
	}

	@Test
	void withoutACacheTheBytesAreHashedNotJustSized() throws Exception {
		Path source = temporaryDirectory.resolve("object");
		byte[] content = bytes("hash me");
		Files.write(source, content);
		String hash = HashUtils.sha1(content);
		ValidatedSources sources = new ValidatedSources(null);
		assertTrue(sources.matches(source, content.length, hash));
		ImmutableFiles.unprotect(source);
		Files.write(source, bytes("not me!"));
		assertFalse(sources.matches(source, content.length, hash), "same size alone never answers for a cacheless source");
		assertTrue(sources.matches(source, content.length, HashUtils.sha1(bytes("not me!"))));
	}

	@Test
	void aSourceThatLostItsSealIsReprotectedOnTheNextProof() throws Exception {
		Path source = temporaryDirectory.resolve("object");
		byte[] content = bytes("sealed");
		Files.write(source, content);
		String hash = HashUtils.sha1(content);
		try (FileCache cache = FileCache.open(temporaryDirectory.resolve("cache"))) {
			ValidatedSources sources = new ValidatedSources(cache);
			assertTrue(sources.require(source, content.length, hash).sealed());
			ImmutableFiles.unprotect(source);
			ValidatedSources.Source reproved = sources.require(source, content.length, hash);
			assertTrue(reproved.sealed());
			assertTrue(ImmutableFiles.isProtected(source));
		}
	}
}
