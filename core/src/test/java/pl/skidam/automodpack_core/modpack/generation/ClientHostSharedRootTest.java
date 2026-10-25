package pl.skidam.automodpack_core.modpack.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.candidate.ModpackCandidate;
import pl.skidam.automodpack_core.modpack.candidate.StagedObject;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.HashUtils;

/** Client-hosted generation stores on the shared platform data root, each owning its objects under its own instance identity. */
class ClientHostSharedRootTest {
	@TempDir
	Path tmp;

	@Test
	void twoClientHostsOnOneSharedRootNeverCollectEachOther() throws Exception {
		Path shared = tmp.resolve("shared-data");
		Path objects = shared.resolve("objects");
		Path instanceA = tmp.resolve("instA/automodpack/server");
		Path instanceB = tmp.resolve("instB/automodpack/server");
		GenerationStore a = new GenerationStore(instanceA, objects, location(shared, "instA"), null);
		GenerationStore b = new GenerationStore(instanceB, objects, location(shared, "instB"), null);
		b.publish(candidate(instanceB, "aaaaaa1", "bytes-of-B"), "B root", null);
		a.publish(candidate(instanceA, "bbbbbb2", "bytes-of-A"), "A root", null);
		b.collectUnreachable();
		a.collectUnreachable();

		try (var receipts = Files.list(shared.resolve("object-owners"))) {
			assertEquals(2, receipts.filter(path -> path.getFileName().toString().endsWith(".server.json")).count());
		}
		for (Path served : new GenerationStore(instanceB, objects, location(shared, "instB"), null).hosting().asMap().values())
			assertTrue(Files.isRegularFile(served), "B's served file survives A's collect: " + served);
	}

	@Test
	void aClientCollectKeepsWhatAClientHostServes() throws Exception {
		Path shared = tmp.resolve("shared-data");
		Path objects = shared.resolve("objects");
		Path hostState = tmp.resolve("hostInst/automodpack/server");
		GenerationStore host = new GenerationStore(hostState, objects, location(shared, "hostInst"), null);
		GenerationStore.Publication publication = host.publish(candidate(hostState, "ccccccc", "hosted-bytes"), "root", null);

		ClientStorage other = TestDataRoot.open(tmp.resolve("otherInst"), shared);
		ClientObjectStore.collectUnreachableObjects(other, Set.of());
		assertTrue(Files.exists(DataRootResolver.objectFile(objects, sha1("hosted-bytes"))));
		assertTrue(Files.exists(DataRootResolver.objectFile(objects, publication.entry().policySha1())));

		Files.deleteIfExists(hostState.resolve("current-projection.json"));
		assertTrue(new GenerationStore(hostState, objects, location(shared, "hostInst"), null).loadCurrent().isPresent());
	}

	@Test
	void aRestoredGenerationIsPinnedBeforeItBecomesTheHead() throws Exception {
		Path shared = tmp.resolve("shared-data");
		Path objects = shared.resolve("objects");
		Path hostState = tmp.resolve("hostInst/automodpack/server");
		GenerationStore host = new GenerationStore(hostState, objects, location(shared, "hostInst"), null);
		host.publish(candidate(hostState, "ccccccc", "first-bytes"), "root", null);
		host.publish(candidate(hostState, "ccccccc", "second-bytes"), "second", null);
		new GenerationStore(hostState, objects, location(shared, "hostInst"), null).loadCurrent();
		new GenerationStore(hostState, objects, location(shared, "hostInst"), null).publishRestore(1, "back");

		ClientObjectStore.collectUnreachableObjects(TestDataRoot.open(tmp.resolve("otherInst"), shared), Set.of());
		assertTrue(Files.exists(DataRootResolver.objectFile(objects, sha1("first-bytes"))));
	}

	@Test
	void anUpgradedStorePinsItselfAtBootAndRetiresItsOldReceiptOnlyOnItsOwnRoot() throws Exception {
		Path instance = tmp.resolve("server");
		Path ownRoot = instance.resolve("automodpack/server/data");
		Path objects = ownRoot.resolve("objects");
		Path state = instance.resolve("automodpack/server");
		new GenerationStore(state, objects, (Path) null).publish(candidate(state, "ddddddd", "legacy-bytes"), "root", null);
		new GenerationStore(state, objects, (Path) null).collectUnreachable();
		assertEquals(List.of(legacyReceipt(objects)), receipts(ownRoot));

		DataRootResolver.Location own = new DataRootResolver.Location(ownRoot, HashUtils.sha1("server"), instance);
		new GenerationStore(state, objects, own, null).loadPinned();
		assertEquals(List.of(own.ownerId() + ".server.json"), receipts(ownRoot));

		Path shared = tmp.resolve("shared-data");
		Path sharedObjects = shared.resolve("objects");
		Path clientState = tmp.resolve("client/automodpack/server");
		new GenerationStore(clientState, sharedObjects, (Path) null).publish(candidate(clientState, "eeeeeee", "client-bytes"), "root", null);
		new GenerationStore(clientState, sharedObjects, (Path) null).collectUnreachable();
		new GenerationStore(clientState, sharedObjects, location(shared, "client"), null).loadPinned();
		assertTrue(receipts(shared).contains(legacyReceipt(sharedObjects)), "a shared root keeps the receipt another instance may still rely on");
	}

	private DataRootResolver.Location location(Path shared, String instance) {
		return new DataRootResolver.Location(shared, HashUtils.sha1(instance), tmp.resolve(instance));
	}

	private static String legacyReceipt(Path objects) {
		return HashUtils.sha1("generation-store:" + objects.toAbsolutePath().normalize()) + ".server.json";
	}

	private static List<String> receipts(Path root) throws IOException {
		try (var receipts = Files.list(root.resolve("object-owners"))) {
			return receipts.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private static ModpackCandidate candidate(Path state, String modpackId, String content) throws Exception {
		Path staging = Files.createDirectories(state.resolve("staging"));
		Path staged = Files.createTempFile(staging, "c-", ".staged");
		Files.writeString(staged, content, StandardCharsets.UTF_8);
		String hash = sha1(content);
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = modpackId;
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		group.defaultSelected = true;
		group.description = "g";
		group.files = Map.of("config/x.txt", new ModpackJsons.CompleteModpackContentFields.GroupFileFields(String.valueOf(content.length()), false, hash, null));
		fields.categories = Map.of("General", Map.of("main", group));
		return new ModpackCandidate(GroupManifestValidator.validate(fields), new TreeMap<>(Map.of(hash, new StagedObject(hash, content.length(), staged))), new TreeMap<>(), List.of());
	}

	private static String sha1(String s) {
		return HashUtils.sha1(s.getBytes(StandardCharsets.UTF_8));
	}
}
