package pl.skidam.automodpack.client.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.change.ChangeBrowserProjection;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
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
import pl.skidam.automodpack_core.update.QuarantineArchive;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
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
		this(ClientStorage.fromGameDirectory(GameDirectory.current()));
	}

	InstalledModpackController(ClientStorage storage) {
		this.storage = storage;
	}

	ClientStorage storage() {
		return storage;
	}

	List<Pack> installed() {
		String activeId = activeModpackId();
		try {
			return new ClientGenerationStore(storage).installedRecords().stream()
					.sorted(Comparator.comparing(InstalledModpackController::name, String.CASE_INSENSITIVE_ORDER))
					.map(record -> pack(record, record.manifest().modpackId().equals(activeId), hasConnection(record.manifest().modpackId())))
					.toList();
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return List.of();
		}
	}

	Throwable discoveryFailure() {
		return discoveryFailure;
	}

	boolean hasRecovery(Pack pack) {
		try {
			Path root = storage.recoveryDirectory(pack.modpackId());
			if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
			try (var paths = Files.list(root)) {
				return paths.findAny().isPresent();
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	boolean hasQuarantine(Pack pack) {
		try {
			return QuarantineArchive.hasEntries(storage, pack.modpackId());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	boolean hasHistory(Pack pack) {
		try {
			String generationId = historyGenerationId(pack);
			ClientGenerationStore generationStore = new ClientGenerationStore(storage);
			boolean indexedHistory = generationStore.historyIndex(generationId).map(index -> index.entries().size() > 1).orElse(false);
			return indexedHistory || generationStore.availableLineage(pack.modpackId(), generationId).size() > 1 || GenerationPatchNoteHistory.containsNotes(generationStore.patchNotesHistory(generationId));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	void update(Pack pack, Runnable released) {
		if (!pack.active() || !pack.connectionAvailable()) {
			releaseOnClient(released);
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ModpackUpdater updater = null;
			try {
				SelectedModpackTarget target;
				try (StoredModpackConnection connection = StoredModpackConnection.open(storage, pack.modpackId(), true)) {
					GenerationRecord downloaded = connection.advertisedRecord();
					SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
					target = savedSelection == null
							? SelectedModpackTarget.prepareDefault(downloaded.toFields(), ClientPlatform.current())
							: SelectedModpackTarget.prepare(downloaded.toFields(), savedSelection, savedSelection, ClientPlatform.current());
					updater = connection.newUpdater(target, storage);
				}
				ModpackUtils.UpdateCheckResult updateResult = ModpackUtils.isUpdate(target.flatTarget(), storage);
				if (!updater.requiresUpdateBeforeLogin(updateResult)) {
					updater.close();
					releaseOnClient(released);
					return;
				}
				updater.processModpackUpdate(updateResult);
				releaseOnClient(released);
			} catch (Exception e) {
				if (updater != null) updater.close();
				releaseOnClient(released);
				failure(e, "automodpack.error.update", FailureCategory.UPDATE);
			}
		});
	}

	void activate(Pack pack, Runnable released) {
		try {
			SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
			SelectionIntent targetSelection = savedSelection == null ? GroupSelectionResolver.defaultIntent(pack.record().manifest()) : savedSelection;
			InstalledModpackSwitch.start(storage, pack.record(), savedSelection, targetSelection, pack.name(), false, released);
		} catch (RuntimeException e) {
			released.run();
			failure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
		}
	}

	void deactivate(Pack pack, Runnable released) {
		removeActive(pack, true, released);
	}

	void remove(Pack pack, Runnable released, Runnable removed) {
		if (pack.active()) {
			removeActive(pack, false, released, removed);
			return;
		}
		try {
			UpdatePlan plan = new UpdatePlan(pack.modpackId(), GenerationTarget.from(pack.record()), List.of(), List.of(), null, Set.of(), List.of(), List.of(), List.of(), List.of());
			UpdatePreview preview = new UpdatePreview(plan, List.of(), new UpdatePreview.GroupConsequences(Set.of(), Set.of(), Set.of()), "", List.of(), UpdatePreview.Mode.REMOVAL);
			new ScreenManager().preview(preview, pack.name(),
					(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> forget(pack, released, removed)),
					released, false, Map.of());
		} catch (Exception e) {
			released.run();
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
		}
	}

	void openHistory(Pack pack, Runnable released) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				ClientGenerationStore generationStore = new ClientGenerationStore(storage);
				String generationId = historyGenerationId(pack);
				List<GenerationRecord> availableLineage = generationStore.availableLineage(pack.modpackId(), generationId);
				List<GenerationPatchNoteHistory.Entry> patchNotesHistory = generationStore.patchNotesHistory(generationId);
				new ScreenManager().history(availableLineage, pack.name(), patchNotesHistory, released);
			} catch (Exception e) {
				releaseOnClient(released);
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void openFiles(Screen parent, Pack pack) {
		ScreenImpl.setScreen(new PagedTextScreen(parent,
				VersionedText.translatable("automodpack.files.title", pack.name()),
				VersionedText.translatable("automodpack.files.description"), GenerationCatalogueLines.files(pack.record())));
	}

	void openRecovery(Screen parent, Pack pack, Runnable released) {
		ModpackUpdater updater;
		try {
			updater = new ModpackUpdater(null, null, storage);
		} catch (RuntimeException e) {
			released.run();
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			return;
		}
		ModpackUpdater recoveryUpdater = updater;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				new ScreenManager().recovery(recoveryUpdater, recoveryUpdater.recoverySnapshot(), pack.name(), released);
			} catch (Exception e) {
				recoveryUpdater.close();
				releaseOnClient(released);
				failure(e, "automodpack.error.update", FailureCategory.UPDATE);
			}
		});
	}

	private void removeActive(Pack pack, boolean deactivation, Runnable released) {
		removeActive(pack, deactivation, released, null);
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
				new ScreenManager().preview(preview, pack.name(),
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> executeActiveRemoval(removalUpdater, deactivation, released, removed)),
						released, false, Map.of());
			} catch (Exception e) {
				removalUpdater.close();
				releaseOnClient(released);
				failure(e, "automodpack.error.update", FailureCategory.UPDATE);
			}
		});
	}

	private void executeActiveRemoval(ModpackUpdater updater, boolean deactivation, Runnable released, Runnable removed) {
		boolean success = false;
		try {
			if ((deactivation ? updater.deactivateModpack() : updater.removeModpack()).success()) {
				success = true;
			} else {
				String error = deactivation ? "automodpack.error.deactivationIncomplete" : "automodpack.error.removalIncomplete";
				failure(new IllegalStateException(error), error, FailureCategory.UPDATE);
			}
		} catch (Exception e) {
			failure(e, "automodpack.error.update", FailureCategory.UPDATE);
		} finally {
			updater.close();
			boolean removedSuccessfully = success && !deactivation && removed != null;
			releaseOnClient(() -> {
				released.run();
				if (removedSuccessfully) removed.run();
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

	private boolean hasConnection(String modpackId) {
		try {
			ConnectionJsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
			return fields.connection != null && fields.connection.isComplete();
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return false;
		}
	}

	private static String name(GenerationRecord record) {
		return record.manifest().modpackName().isBlank() ? record.manifest().modpackId() : record.manifest().modpackName();
	}

	private void releaseOnClient(Runnable released) {
		Minecraft.getInstance().execute(released);
	}

	private void failure(Throwable cause, String messageKey, FailureCategory category) {
		new ScreenManager().failure(FailureRequest.of(cause, messageKey, category, FailureDestination.CURRENT_SCREEN, null));
	}

	private static Pack pack(GenerationRecord record, boolean active, boolean connectionAvailable) {
		ChangeBrowserProjection.Aggregate aggregate = ChangeBrowserProjection.project(ChangeSet.catalogue(record.manifest()), ChangeBrowserProjection.Mode.LIST).total();
		return new Pack(record, active, connectionAvailable, Math.toIntExact(aggregate.fileCount()), aggregate.byteCount());
	}

	record Pack(GenerationRecord record, boolean active, boolean connectionAvailable, int fileCount, long fileBytes) {
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
