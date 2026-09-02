package pl.skidam.automodpack_core.utils.cache;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMetadataCacheTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void detectsContentChangesBelowMillisecondTimestampPrecision() throws Exception {
		Path file = temporaryDirectory.resolve("file.bin");
		Files.writeString(file, "one");
		FileTime firstTime = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 123_000_000L));
		FileTime secondTime = FileTime.from(firstTime.toInstant().plusNanos(999_999L));
		Files.setLastModifiedTime(file, firstTime);
		FileTime storedFirstTime = Files.getLastModifiedTime(file);
		Files.setLastModifiedTime(file, secondTime);
		FileTime storedSecondTime = Files.getLastModifiedTime(file);
		assumeTrue(!storedFirstTime.equals(storedSecondTime), "The filesystem does not expose sub-millisecond timestamps");
		Files.setLastModifiedTime(file, firstTime);

		try (FileMetadataCache cache = FileMetadataCache.open(temporaryDirectory.resolve("hash-cache.db"))) {
			String firstHash = cache.getOrComputeHash(file);
			Files.writeString(file, "two");
			Files.setLastModifiedTime(file, secondTime);

			assertNotEquals(firstHash, cache.getOrComputeHash(file));
		}
	}
}
