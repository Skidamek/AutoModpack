package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerListFileTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void upsertCreatesAndDoesNotDuplicate() throws Exception {
		Path file = temporaryDirectory.resolve("servers.dat");
		ServerListFile.upsert(file, "One", "play.example.com");
		ServerListFile.upsert(file, "Two", "Play.Example.com:25565");
		ServerListFile.upsert(file, "Other", "other.example.com:25566");
		List<ServerListFile.Entry> entries = ServerListFile.read(file);
		assertEquals(2, entries.size());
		assertEquals("One", entries.get(0).name());
		assertEquals("play.example.com:25565", entries.get(0).ip());
		assertEquals("Other", entries.get(1).name());
		assertEquals("other.example.com:25566", entries.get(1).ip());
	}
}
