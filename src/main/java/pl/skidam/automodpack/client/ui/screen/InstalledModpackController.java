package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.MODPACK_LOADER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.change.ChangeBrowserProjection;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_core.update.PreservationVault;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_loader_core.client.ClientOfflineRepair;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.client.StoredModpackConnection;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Owns local installed-pack discovery and lifecycle operations used by the pack manager screens.
 * The screens only choose an action and render its result; storage and update invariants stay here.
 */
final class InstalledModpackController {
	private final ClientStorage storage;
	private Throwable discoveryFailure;

	InstalledModpackController() {
		this(ClientStorage.open(GameDirectory.current()));
	}

	InstalledModpackController(ClientStorage storage) {
		this.storage = storage;
	}

	SelectionIntent savedSelection(String modpackId) {
		try {
			return new ClientSelectionStore(storage.selectionFile()).get(modpackId).orElse(null);
		} catch (RuntimeException e) {
			discoveryFailure = e;
			return null;
		}
	}

	void saveSelection(String modpackId, SelectionIntent expected, SelectionIntent target) throws IOException {
		new ClientSelectionStore(storage.selectionFile()).compareAndSet(modpackId, expected, target);
	}

	GenerationRecord activeRecord(String modpackId) {
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !modpackId.equals(state.modpackId)) return null;
			return new ClientGenerationStore(storage).read(state.generationId).orElse(null);
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return null;
		}
	}

	Pack pack(GenerationRecord record) {
		return pack(record, record.manifest().modpackId().equals(activeModpackId()), connectionOrigin(record.manifest().modpackId()));
	}

	void switchSelection(GenerationRecord record, SelectionIntent expected, SelectionIntent target, String modpackName, Runnable released) {
		InstalledModpackSwitch.start(storage, record, expected, target, modpackName, released);
	}

	Pack installedPack(String modpackId) {
		return installed().stream().filter(entry -> entry.modpackId().equals(modpackId)).findFirst().orElse(null);
	}

	List<Pack> installed() {
		String activeId = activeModpackId();
		try {
			return new ClientGenerationStore(storage).installedRecords().stream()
					.sorted(Comparator.comparing(InstalledModpackController::name, String.CASE_INSENSITIVE_ORDER))
					.map(record -> pack(record, record.manifest().modpackId().equals(activeId), connectionOrigin(record.manifest().modpackId())))
					.toList();
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return List.of();
		}
	}

	Throwable discoveryFailure() {
		return discoveryFailure;
	}

	int preservedClaimCount() {
		try {
			int count = 0;
			for (PreservationVault.Snapshot snapshot : PreservationVault.snapshots(storage)) count += snapshot.claims().size();
			return count;
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return 0;
		}
	}

	ClientObjectStore.StorageReport validateStorage() throws IOException {
		return ClientObjectStore.validate(storage);
	}

	ClientGenerationStore.CompactionResult compactStorage() throws IOException {
		return new ClientGenerationStore(storage).compact();
	}

	List<PreservationVault.Snapshot> preservedFiles() throws IOException {
		return PreservationVault.snapshots(storage);
	}

	Path restorePreservedFile(String modpackId, String claimId) throws IOException {
		return PreservationVault.restoreOriginal(storage, modpackId, claimId);
	}

	Path savePreservedCopy(String modpackId, String claimId) throws IOException {
		return PreservationVault.saveCopy(storage, modpackId, claimId);
	}

	void deletePreservedFile(String modpackId, String claimId) throws IOException {
		PreservationVault.delete(storage, modpackId, claimId);
	}

	void update(Pack pack, Consumer<Boolean> completed) {
		if (!pack.active() || !pack.connectionAvailable()) {
			releaseOnClient(() -> completed.accept(false));
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ModpackUpdater updater = null;
			try {
				SelectedModpackTarget target;
				try (StoredModpackConnection connection = StoredModpackConnection.open(storage, pack.modpackId(), true)) {
					ModpackJsons.CompleteModpackContentFields advertised = connection.advertisedFields();
					SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
					target = savedSelection == null
							? SelectedModpackTarget.prepareDefault(advertised, ClientPlatform.effective(savedSelection))
							: SelectedModpackTarget.prepare(advertised, savedSelection, savedSelection, ClientPlatform.effective(savedSelection));
					updater = connection.newUpdater(target, storage);
				}
				ModpackUtils.UpdateCheckResult updateResult = ModpackUtils.isUpdate(target.flatTarget(), storage);
				if (!updater.requiresUpdateBeforeLogin(updateResult)) {
					updater.close();
					releaseOnClient(() -> completed.accept(true));
					return;
				}
				updater.processModpackUpdate(updateResult, false);
				releaseOnClient(() -> completed.accept(false));
			} catch (Exception e) {
				if (updater != null) updater.close();
				releaseOnClient(() -> completed.accept(false));
				failure(e, "automodpack.error.update", FailureCategory.UPDATE);
			}
		});
	}

	void repair(Screen parent, Pack pack, Consumer<Boolean> completed) {
		if (!pack.active()) {
			releaseOnClient(() -> completed.accept(false));
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				ClientOfflineRepair repair = new ClientOfflineRepair(storage, MODPACK_LOADER);
				OfflineRepair.Prepared prepared = repair.inspect();
				releaseOnClient(() -> ScreenImpl.setScreen(new OfflineRepairScreen(parent, pack.name(), repair, prepared,
						pack.connectionAvailable() ? () -> update(pack, completed) : null, () -> completed.accept(false))));
			} catch (Exception e) {
				releaseOnClient(() -> completed.accept(false));
				failure(e, "automodpack.error.repair", FailureCategory.STORAGE);
			}
		});
	}

	void activate(Pack pack, Runnable released) {
		try {
			SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
			SelectionIntent targetSelection = savedSelection == null ? GroupSelectionResolver.defaultIntent(pack.record().manifest()) : savedSelection;
			InstalledModpackSwitch.start(storage, pack.record(), savedSelection, targetSelection, pack.name(), released);
		} catch (RuntimeException e) {
			released.run();
			failure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
		}
	}

	void deactivate(Pack pack, Runnable released, Runnable completed) {
		removeActive(pack, true, released, completed);
	}

	void remove(Pack pack, Runnable released, Runnable removed) {
		if (pack.active()) {
			removeActive(pack, false, released, removed);
			return;
		}
		try {
			UpdatePlan plan = new UpdatePlan(pack.modpackId(), GenerationTarget.from(pack.record()), List.of(), List.of(), null, Set.of(), List.of(), List.of(), List.of(), List.of(), ChangeSet.empty());
			UpdatePreview preview = UpdatePreview.create(plan, null, UpdatePreview.Mode.REMOVAL).withFeatureManifest(pack.record().manifest());
			boolean shown = ScreenManager.preview(preview, pack.name(), null,
					(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> forget(pack, released, removed)),
					released);
			if (!shown) released.run();
		} catch (Exception e) {
			released.run();
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
		}
	}

	void openHistory(Pack pack, Runnable released) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				String generationId = historyGenerationId(pack);
				GenerationHistoryController.open(storage, pack.modpackId(), generationId, pack.name(), released);
			} catch (Exception e) {
				releaseOnClient(released);
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void openPatchNotes(Screen parent, Pack pack, Runnable released) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				List<GenerationPatchNoteHistory.Entry> notes = new ClientGenerationStore(storage).patchNotesHistory(historyGenerationId(pack));
				releaseOnClient(() -> ScreenImpl.setScreen(new PatchNotesHistoryScreen(parent, notes, pack.name(), released)));
			} catch (Exception e) {
				releaseOnClient(released);
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void openFiles(Screen parent, Pack pack) {
		Map<String, String> featureNames = new TreeMap<>();
		pack.record().manifest().groups().forEach((groupId, group) -> featureNames.put(groupId,
				group.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownFeature").getString() : group.displayName()));
		ScreenImpl.setScreen(new ChangeBrowserScreen(parent, VersionedText.translatable("automodpack.files.title", pack.name()),
				VersionedText.translatable("automodpack.files.description"), ChangeSet.catalogue(pack.record().manifest()), featureNames));
	}

	void openPreservedFiles(Screen parent, Runnable released) {
		ScreenImpl.setScreen(new PreservationVaultScreen(parent, this, released));
	}

	private void removeActive(Pack pack, boolean deactivation, Runnable released, Runnable removed) {
		ModpackUpdater updater;
		try {
			updater = new ModpackUpdater(null, null, storage);
		} catch (RuntimeException e) {
			released.run();
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			return;
		}
		ModpackUpdater removalUpdater = updater;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				UpdatePreview preview = deactivation ? removalUpdater.previewDeactivation() : removalUpdater.previewRemoval();
				boolean shown = ScreenManager.preview(preview, pack.name(), removalUpdater,
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> executeActiveRemoval(removalUpdater, deactivation, released, removed)),
						released);
				if (!shown) {
					removalUpdater.close();
					releaseOnClient(released);
				}
			} catch (Exception e) {
				removalUpdater.close();
				releaseOnClient(released);
				failure(e, "automodpack.error.update", FailureCategory.UPDATE);
			}
		});
	}

	private void executeActiveRemoval(ModpackUpdater updater, boolean deactivation, Runnable released, Runnable removed) {
		boolean finishedWithoutRestart = false;
		try {
			ModpackUpdater.LifecycleApply apply = deactivation ? updater.deactivateModpack() : updater.removeModpack();
			if (!apply.success()) {
				String error = deactivation ? "automodpack.error.deactivationIncomplete" : "automodpack.error.removalIncomplete";
				failure(new IllegalStateException(error), error, FailureCategory.UPDATE);
			} else {
				finishedWithoutRestart = removed != null && (!deactivation || !apply.restartRequired());
			}
		} catch (Exception e) {
			failure(e, "automodpack.error.update", FailureCategory.UPDATE);
		} finally {
			updater.close();
			boolean navigate = finishedWithoutRestart;
			releaseOnClient(() -> {
				released.run();
				if (navigate) removed.run();
			});
		}
	}

	private void forget(Pack pack, Runnable released, Runnable removed) {
		try {
			new ClientGenerationStore(storage).forgetModpack(pack.modpackId());
			releaseOnClient(() -> {
				released.run();
				removed.run();
			});
		} catch (Exception e) {
			releaseOnClient(released);
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
		}
	}

	private String historyGenerationId(Pack pack) throws IOException {
		if (pack.active()) {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !pack.modpackId().equals(state.modpackId)) throw new IOException("Active generation is unavailable");
			return state.generationId;
		}
		return pack.record().metadata().generationId();
	}

	private String activeModpackId() {
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			return state == null || state.modpackId == null ? "" : state.modpackId;
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return "";
		}
	}

	private String connectionOrigin(String modpackId) {
		try {
			ConnectionJsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
			return fields.connection != null && fields.connection.isComplete() ? PackConfirmCopy.displayOrigin(AddressHelpers.formatAddress(fields.connection.origin)) : null;
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return null;
		}
	}

	private static String name(GenerationRecord record) {
		return record.manifest().modpackName().isBlank() ? record.manifest().modpackId() : record.manifest().modpackName();
	}

	private void releaseOnClient(Runnable released) {
		Minecraft.getInstance().execute(released);
	}

	private void failure(Throwable cause, String messageKey, FailureCategory category) {
		ScreenManager.failure(FailureRequest.of(cause, messageKey, category, FailureDestination.CURRENT_SCREEN, null));
	}

	private static Pack pack(GenerationRecord record, boolean active, String connectionOrigin) {
		ChangeBrowserProjection.Aggregate aggregate = ChangeBrowserProjection.project(ChangeSet.catalogue(record.manifest()), ChangeBrowserProjection.Mode.LIST).total();
		return new Pack(record, active, connectionOrigin, Math.toIntExact(aggregate.fileCount()), aggregate.byteCount());
	}

	record Pack(GenerationRecord record, boolean active, String connectionOrigin, int fileCount, long fileBytes) {
		boolean connectionAvailable() {
			return connectionOrigin != null;
		}

		String modpackId() {
			return record.manifest().modpackId();
		}

		String name() {
			return InstalledModpackController.name(record);
		}

		int groupCount() {
			return record.manifest().groups().size();
		}

	}
}
