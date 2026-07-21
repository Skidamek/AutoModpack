package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.update.UpdatePlan.*;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

class UpdateTransactionExecutorTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void commitsFromCasAndPublishesManifestLastState() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "target-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Files.createDirectories(paths.game());
		Path oldFile = Files.writeString(paths.game().resolve("old.txt"), "old");
		String oldHash = HashUtils.getHash(oldFile);

		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId,
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null),
						new Operation(Root.GAME_DIR, "old.txt", OperationType.DELETE, null, -1, oldHash)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length),
						new ProjectedFile(Root.GAME_DIR, "old.txt", false, null, -1)),
				clientConfig(manifest.modpackId), Set.of("timestamp-1"), Set.of(RestartReason.REMOVED_NON_MODPACK_FILES), List.of());

		UpdateTransactionExecutor.Execution result = executor(paths, null).commit(plan, manifest);

		assertTrue(result.success());
		assertFalse(Files.exists(paths.transaction()));
		assertFalse(Files.exists(oldFile));
		assertArrayEquals(bytes, Files.readAllBytes(paths.modpack().resolve("mods/new.jar")));
		assertEquals(manifest.modpackId, ModpackContentTools.read(paths.manifest()).modpackId);
		assertEquals(manifest.modpackId, ConfigTools.read(paths.clientConfig(), Jsons.ClientConfigFieldsV3.class).orElseThrow().selectedModpackId);
		assertTrue(ConfigTools.read(paths.timestamps(), Jsons.ClientDeletedNonModpackFilesTimestamps.class).orElseThrow().timestamps.contains("timestamp-1"));
	}

	@Test
	void recoveryAfterManifestPublicationConvergesAndClearsTransaction() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = "published-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		UpdatePlan plan = new UpdatePlan(manifest.modpackId,
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of(), Set.of(), List.of());
		UpdateTransactionExecutor executor = executor(paths, null);
		UpdateTransactionExecutor.Execution committed = executor.commit(plan, manifest);
		ConfigTools.writeAtomic(paths.transaction(), committed.transaction());

		UpdateTransactionExecutor.Execution recovered = executor.recover(committed.transaction());

		assertTrue(recovered.success());
		assertFalse(Files.exists(paths.transaction()));
		assertEquals(manifest.modpackId, ModpackContentTools.read(paths.manifest()).modpackId);
	}

	@Test
	void completeFinalStateDetectsAFileChangedDuringCommitAndKeepsTransaction() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.modpack().resolve("config"));
		Path unchanged = Files.writeString(paths.modpack().resolve("config/settings.json"), "planned");
		String hash = HashUtils.getHash(unchanged);
		Jsons.ModpackContentFields manifest = editableManifest(hash, Files.size(unchanged));
		UpdatePlan plan = new UpdatePlan(manifest.modpackId, List.of(),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "config/settings.json", true, hash, Files.size(unchanged))), clientConfig(manifest.modpackId), Set.of(),
				Set.of(), List.of());

		assertThrows(Exception.class, () -> executor(paths, ignored -> Files.writeString(unchanged, "changed during commit")).commit(plan, manifest));
		assertTrue(Files.exists(paths.transaction()));
		assertFalse(Files.exists(paths.manifest()));
	}

	@Test
	void rejectsTraversalAndTamperedProjectionBeforeMutation() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		byte[] bytes = {1};
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = manifest(hash, bytes.length);
		UpdatePlan traversal = new UpdatePlan(manifest.modpackId,
				List.of(new Operation(Root.MODPACK_DIR, "../escape.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of(), Set.of(), List.of());
		assertThrows(Exception.class, () -> executor(paths, null).commit(traversal, manifest));

		UpdatePlan valid = new UpdatePlan(manifest.modpackId,
				List.of(new Operation(Root.MODPACK_DIR, "mods/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of(), Set.of(), List.of());
		UpdateTransaction tampered = UpdateTransaction.create(valid, manifest, paths.modpack());
		tampered.projectedFinalState = List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, "f".repeat(40), bytes.length));
		assertThrows(Exception.class, () -> executor(paths, null).recover(tampered));

		UpdateTransaction aliased = UpdateTransaction.create(valid, manifest, paths.modpack());
		aliased.projectedFinalState = List.of(new ProjectedFile(Root.MODPACK_DIR, "mods/new.jar", true, hash, bytes.length),
				new ProjectedFile(Root.AUTOMODPACK_DIR, "modpacks/abc1234/mods/new.jar", true, hash, bytes.length));
		assertThrows(Exception.class, () -> executor(paths, null).recover(aliased));
		assertFalse(Files.exists(paths.transaction()));
		assertFalse(Files.exists(paths.modpack().resolve("mods/new.jar")));
	}

	@Test
	void rejectsSymlinkedParentEscapingConstrainedRoot() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.modpack());
		Path outside = temporaryDirectory.resolve("outside");
		Files.createDirectories(outside);
		try {
			Files.createSymbolicLink(paths.modpack().resolve("linked"), outside);
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e);
		}
		byte[] bytes = "escaped-object".getBytes(StandardCharsets.UTF_8);
		String hash = store(paths, bytes);
		Jsons.ModpackContentFields manifest = new Jsons.ModpackContentFields(Set.of(
				new Jsons.ModpackContentFields.ModpackContentItem("/linked/new.jar", String.valueOf(bytes.length), "mod", false, false, false, hash, "0")));
		manifest.modpackId = "abc1234";
		UpdatePlan plan = new UpdatePlan(manifest.modpackId,
				List.of(new Operation(Root.MODPACK_DIR, "linked/new.jar", OperationType.INSTALL_OBJECT, hash, bytes.length, null)),
				List.of(new ProjectedFile(Root.MODPACK_DIR, "linked/new.jar", true, hash, bytes.length)), clientConfig(manifest.modpackId), Set.of(), Set.of(), List.of());

		assertThrows(IOException.class, () -> executor(paths, null).commit(plan, manifest));
		assertFalse(Files.exists(outside.resolve("new.jar")));
		assertFalse(Files.exists(paths.transaction()));
	}

	@Test
	void selfUpdateUsesConstrainedCasOperationsWithoutPublishingModpackState() throws Exception {
		Paths paths = paths();
		Files.createDirectories(paths.store());
		Files.createDirectories(paths.mods());
		Path currentJar = Files.writeString(paths.mods().resolve("automodpack-old.jar"), "old");
		String currentHash = HashUtils.getHash(currentJar);
		byte[] replacement = "official-update".getBytes(StandardCharsets.UTF_8);
		String replacementHash = store(paths, replacement);
		UpdateTransaction transaction = UpdateTransaction.createSelfUpdate(currentJar.getFileName().toString(), "automodpack-new.jar", replacementHash,
				replacement.length, currentHash);

		UpdateTransactionExecutor.Execution execution = executor(paths, null).commit(transaction);

		assertTrue(execution.success());
		assertFalse(Files.exists(currentJar));
		assertArrayEquals(replacement, Files.readAllBytes(paths.mods().resolve("automodpack-new.jar")));
		assertFalse(Files.exists(paths.manifest()));
		assertFalse(Files.exists(paths.clientConfig()));
		assertFalse(Files.exists(paths.transaction()));

		UpdateTransaction tampered = UpdateTransaction.createSelfUpdate("automodpack-old.jar", "../outside.jar", replacementHash, replacement.length, currentHash);
		assertThrows(IOException.class, () -> executor(paths, null).validate(tampered));
	}

	private UpdateTransactionExecutor executor(Paths paths, UpdateTransactionExecutor.CommitAction action) {
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(paths.game(), paths.modpack(), paths.mods(), paths.store(), paths.automodpack(),
				paths.transaction(), paths.result(), paths.clientConfig(), paths.timestamps(), paths.manifest(), action));
	}

	private Paths paths() {
		Path game = temporaryDirectory.resolve("game");
		Path automodpack = game.resolve("automodpack");
		Path modpack = automodpack.resolve("modpacks/abc1234");
		return new Paths(game, modpack, game.resolve("mods"), automodpack.resolve("store"), automodpack,
				automodpack.resolve(".private/update-transaction.json"), automodpack.resolve(".private/update-transaction-result.json"),
				automodpack.resolve("automodpack-client.json"), automodpack.resolve("automodpack-deletion-timestamps-files.json"),
				modpack.resolve("automodpack-content.json"));
	}

	private static String store(Paths paths, byte[] bytes) throws Exception {
		Path temporaryObject = Files.write(paths.store().resolve("object.tmp"), bytes);
		String hash = HashUtils.getHash(temporaryObject);
		Files.move(temporaryObject, paths.store().resolve(hash));
		return hash;
	}

	private static Jsons.ClientConfigFieldsV3 clientConfig(String modpackId) {
		Jsons.ClientConfigFieldsV3 config = new Jsons.ClientConfigFieldsV3();
		config.selectedModpackId = modpackId;
		config.modpackConnections.put(modpackId,
				new Jsons.ConnectionInfo(InetSocketAddress.createUnresolved("origin.example", 25565), InetSocketAddress.createUnresolved("endpoint.example", 25564),
						true, null, null));
		return config;
	}

	private static Jsons.ModpackContentFields manifest(String hash, long size) {
		Jsons.ModpackContentFields manifest = new Jsons.ModpackContentFields(Set.of(
				new Jsons.ModpackContentFields.ModpackContentItem("/mods/new.jar", String.valueOf(size), "mod", false, false, false, hash, "0")));
		manifest.modpackId = "abc1234";
		manifest.modpackName = "Test";
		return manifest;
	}

	private static Jsons.ModpackContentFields editableManifest(String hash, long size) {
		Jsons.ModpackContentFields manifest = new Jsons.ModpackContentFields(Set.of(new Jsons.ModpackContentFields.ModpackContentItem("/config/settings.json",
				String.valueOf(size), "config", true, false, false, hash, "0")));
		manifest.modpackId = "abc1234";
		return manifest;
	}

	private record Paths(Path game, Path modpack, Path mods, Path store, Path automodpack, Path transaction, Path result, Path clientConfig,
			Path timestamps, Path manifest) {}
}
