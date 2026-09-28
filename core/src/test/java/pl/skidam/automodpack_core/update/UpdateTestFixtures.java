package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ReconfConfigs;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;

/** The fixture layer shared by the update transaction tests; helpers here must stay behavior-identical for every user. */
final class UpdateTestFixtures {
	private UpdateTestFixtures() {}

	static ClientStorage storage(Path temporaryDirectory) throws Exception {
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game"), temporaryDirectory.resolve("data"));
		Files.createDirectories(storage.modsDirectory());
		return storage;
	}

	static UpdateTransactionExecutor executor(ClientStorage storage) {
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(storage, null));
	}

	static UpdateTransactionExecutor.Execution commit(ClientStorage storage, UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		return executor(storage).commit(createTransaction(storage, plan, target), target);
	}

	static UpdatePlan plan(SelectedModpackTarget target, ClientConfigJsons.ClientConfigFieldsV3 config, List<Operation> operations, List<ProjectedFile> finalState) {
		return new UpdatePlan(target.manifest().modpackId(), target.packTarget(), operations, finalState, config, target.manifest().modpackId(), Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(),
				List.of(), List.of(), ChangeSet.empty());
	}

	static UpdatePlan plan(SelectedModpackTarget target, List<Operation> operations, List<ProjectedFile> finalState) {
		return plan(target, new ClientConfigJsons.ClientConfigFieldsV3(), operations, finalState);
	}

	static SelectedModpackTarget target(ClientStorage storage, String path, String type, boolean editable, String hash, long size) throws IOException {
		PackDocument document = TestPacks.document(GroupManifestValidator.validate(fields(path, type, editable, hash, size)));
		TestPacks.stageGeneration(storage, document);
		return SelectedModpackTarget.prepare(document, null, new SelectionIntent(Set.of("main")), ClientPlatform.LINUX);
	}

	static ModpackJsons.CompleteModpackContentFields fields(String path, String type, boolean editable, String hash, long size) {
		ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
		fields.modpackId = "abc1234";
		fields.modpackName = "Test";
		ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
		group.required = true;
		ModpackJsons.CompleteModpackContentFields.GroupFileFields file = new ModpackJsons.CompleteModpackContentFields.GroupFileFields();
		file.size = String.valueOf(size);
		file.type = type;
		file.editable = editable;
		file.sha1 = hash;
		file.murmur = "0";
		group.files = Map.of(path, file);
		fields.categories = Map.of("General", Map.of("main", group));
		return fields;
	}

	static UpdateTransaction createTransaction(ClientStorage storage, UpdatePlan plan, SelectedModpackTarget target) throws IOException {
		ModpackJsons.ModpackContentFields installed = ClientProjectionView.observe(storage).target();
		return createTransaction(storage, plan, target, installed == null ? null : installed.ownershipLedger);
	}

	static UpdateTransaction createTransaction(ClientStorage storage, UpdatePlan plan, SelectedModpackTarget target, GenerationJsons.OwnershipLedgerFields installedLedger) throws IOException {
		ClientConfigJsons.ClientConfigFieldsV3 expected = ReconfConfigs.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		// Every real commit is a consent moment: the fixture records the desire the same way before the transaction.
		new ClientSelectionStore(storage.selectionFile()).put(target.manifest().modpackId(), target.selection().intent());
		return UpdateTransaction.create(plan, target, storage.overlayDigest(target.manifest().modpackId()), new PlannedAgainst(expected, "", installedLedger));
	}
}
