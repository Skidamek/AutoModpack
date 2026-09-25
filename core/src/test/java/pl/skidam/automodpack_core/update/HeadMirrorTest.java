package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.storage.TestDataRoot;

class HeadMirrorTest {
	private static final String MODPACK_ID = "abc1234";

	@TempDir
	Path temporaryDirectory;

	@Test
	void firstSwapCreatesTheHistoryPackDirectoryAndConsumesTheFetchedFile() throws Exception {
		ClientStorage storage = storage();
		HeadMirror mirror = new HeadMirror(storage);
		Path fetched = fetchedHead(storage, "first-pack");

		assertFalse(Files.exists(storage.historyHeadFile(MODPACK_ID).getParent()), "precondition: a fresh pack has no history directory yet");

		mirror.replaceFrom(fetched);

		assertEquals(headJson("first-pack"), Files.readString(storage.historyHeadFile(MODPACK_ID), StandardCharsets.UTF_8));
		assertFalse(Files.exists(fetched), "The fetched file is consumed by the atomic swap");
	}

	@Test
	void installedSha1VouchesOnlyForTheActiveGeneration() throws Exception {
		ClientStorage storage = storage();
		HeadMirror mirror = new HeadMirror(storage);
		ClientGenerationStore generations = new ClientGenerationStore(storage);

		assertNull(mirror.installedSha1(generations, MODPACK_ID), "No mirror, no vouch");

		Path fetched = fetchedHead(storage, "mirror-content");
		mirror.replaceFrom(fetched);
		assertNull(mirror.installedSha1(generations, MODPACK_ID), "A mirror naming a foreign generation never counts as installed");
	}

	@Test
	void unreadableMirrorReadsAsAbsentAndIsSetAside() throws Exception {
		ClientStorage storage = storage();
		HeadMirror mirror = new HeadMirror(storage);
		mirror.replaceFrom(fetchedHead(storage, "soon-corrupt"));
		Files.writeString(storage.historyHeadFile(MODPACK_ID), "{not a head document", StandardCharsets.UTF_8);

		assertNull(mirror.read(MODPACK_ID));
		assertTrue(Files.exists(asideEvidence(storage)), "The unusable mirror is set aside as evidence");
	}

	private static Path asideEvidence(ClientStorage storage) throws IOException {
		try (var files = Files.list(storage.historyHeadFile(MODPACK_ID).getParent())) {
			return files.filter(path -> path.getFileName().toString().contains(".corrupt-")).findFirst().orElseThrow();
		}
	}

	private static String headJson(String contentTokenSeed) {
		GroupManifest manifest = TestPacks.manifest("head mirror test", "config/example.txt", contentTokenSeed);
		return ConfigTools.GSON.toJson(TestPacks.head(manifest));
	}

	private Path fetchedHead(ClientStorage storage, String contentTokenSeed) throws IOException {
		Files.createDirectories(storage.modsDirectory());
		return Files.writeString(storage.modsDirectory().resolve("fetched-head.json"), headJson(contentTokenSeed), StandardCharsets.UTF_8);
	}

	private ClientStorage storage() throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}
}
