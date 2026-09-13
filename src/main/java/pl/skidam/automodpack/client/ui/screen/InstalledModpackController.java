package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.MODPACK_LOADER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedServers;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.change.ChangeBrowserProjection;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_core.update.PreservationVault;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.AddressHelpers;
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

	PackDocument activeRecord(String modpackId) {
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !modpackId.equals(state.modpackId)) return null;
			return new ClientGenerationStore(storage).activeDocument().orElse(null);
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return null;
		}
	}

	Pack pack(PackDocument record) {
		return pack(record, record.manifest().modpackId().equals(activeModpackId()), detached(record.manifest().modpackId()), connection(record.manifest().modpackId()));
	}

	void switchSelection(PackDocument record, SelectionIntent expected, SelectionIntent target, String modpackName, Runnable released) {
		InstalledModpackSwitch.start(storage, record, expected, target, modpackName, released);
	}

	Pack installedPack(String modpackId) {
		return installed().stream().filter(entry -> entry.modpackId().equals(modpackId)).findFirst().orElse(null);
	}

	List<Pack> installed() {
		String activeId = activeModpackId();
		try {
			List<Pending> pending = new ArrayList<>();
			for (String modpackId : new ClientGenerationStore(storage).installedPackIds()) {
				PackDocument record;
				try {
					record = new ClientGenerationStore(storage).newestDocument(modpackId);
				} catch (IOException | RuntimeException e) {
					discoveryFailure = e;
					continue;
				}
				ConnectionJsons.ConnectionInfo connection = connection(modpackId);
				String connectionOrigin = connectionOrigin(connection);
				pending.add(new Pending(record, modpackId.equals(activeId), detached(modpackId), connection, displayName(record, connectionOrigin)));
			}
			pending.sort(Comparator.comparing(Pending::displayName, String.CASE_INSENSITIVE_ORDER));
			return pending.stream().map(entry -> pack(entry.record(), entry.active(), entry.detached(), entry.connection())).toList();
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

	/** The stored compaction boundary markers of every installed pack; display state only, so failures degrade to none. */
	List<ClientGenerationStore.CompactionReceipt> compactionReceipts() {
		try {
			ClientGenerationStore generations = new ClientGenerationStore(storage);
			List<ClientGenerationStore.CompactionReceipt> receipts = new ArrayList<>();
			for (String modpackId : generations.installedPackIds()) generations.compactionReceipt(modpackId).ifPresent(receipts::add);
			return receipts;
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return List.of();
		}
	}

	List<PreservationVault.Snapshot> preservedFiles() throws IOException {
		return PreservationVault.snapshots(storage);
	}

	/** One installed pack this origin no longer serves, with its player-facing name or raw id. */
	record StalePack(String modpackId, String name) {}

	/** Installed packs whose saved origin now serves the active pack instead: the leftovers of a republished server. */
	List<StalePack> stalePacks() {
		String activeId = activeModpackId();
		if (activeId.isBlank()) return List.of();
		try {
			List<StalePack> stale = new ArrayList<>();
			for (String modpackId : ConnectionStore.staleSameOriginPackIds(storage, activeId)) stale.add(new StalePack(modpackId, stalePackName(modpackId)));
			return List.copyOf(stale);
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return List.of();
		}
	}

	private String stalePackName(String modpackId) {
		try {
			PackDocument record = new ClientGenerationStore(storage).newestDocument(modpackId);
			if (record != null) return displayName(record, connectionOrigin(connection(modpackId)));
		} catch (IOException | RuntimeException ignored) {
			// The name is cosmetic; the raw id is the honest fallback, not a discovery failure.
		}
		return modpackId;
	}

	/** The restart footer only offers the action; consent is the same removal preview the pack manager uses, one pack at a time. */
	void offerStalePackRemoval(Runnable completed) {
		previewStaleRemoval(stalePacks(), 0, completed);
	}

	private void previewStaleRemoval(List<StalePack> stale, int index, Runnable completed) {
		if (index >= stale.size()) {
			completed.run();
			return;
		}
		StalePack pack = stale.get(index);
		try {
			PackDocument record = new ClientGenerationStore(storage).newestDocument(pack.modpackId());
			if (record == null) throw new IOException("Stale pack has no installed generation: " + pack.modpackId());
			UpdatePlan plan = new UpdatePlan(pack.modpackId(), PackTarget.from(record), List.of(), List.of(), null, Set.of(), List.of(), List.of(), List.of(), List.of(),
					ChangeSet.catalogue(record.manifest(), ChangeSet.Kind.REMOVED));
			UpdatePreview preview = UpdatePreview.create(plan, null, UpdatePreview.Mode.REMOVAL).withFeatureManifest(record.manifest());
			boolean shown = ScreenManager.preview(preview, pack.name(), null,
					(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> {
						try {
							new ClientGenerationStore(storage).forgetModpack(pack.modpackId());
							releaseOnClient(() -> previewStaleRemoval(stale, index + 1, completed));
						} catch (Exception e) {
							releaseOnClient(completed);
							failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
						}
					}), completed);
			if (!shown) completed.run();
		} catch (Exception e) {
			completed.run();
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
		}
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
					GenerationJsons.HeadDocumentFields advertised = connection.advertisedFields();
					SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
					target = savedSelection == null
							? SelectedModpackTarget.prepareDefault(advertised, ClientPlatform.effective(savedSelection))
							: SelectedModpackTarget.prepare(advertised, savedSelection, savedSelection, ClientPlatform.effective(savedSelection));
					updater = connection.newUpdater(target, storage);
				}
				// An explicitly requested sync ends attached whatever it had to do; for an attached pack both clears are no-ops.
				updater.requestAttach();
				ModpackUtils.UpdateCheckResult updateResult = ModpackUtils.isUpdate(target.flatTarget(), storage);
				ModpackUtils.reprotectActiveFiles(target.flatTarget(), storage);
				if (!updater.requiresUpdateBeforeLogin(updateResult)) {
					updater.finishAttachWithoutChanges();
					updater.close();
					releaseOnClient(() -> completed.accept(true));
					return;
				}
				// The outcome is honest: APPLIED only when the update ran inline; a review owns the screen, a restart was deferred, or the flow failed otherwise.
				ModpackUpdater.UpdateOutcome outcome = updater.processModpackUpdate(false);
				releaseOnClient(() -> completed.accept(outcome == ModpackUpdater.UpdateOutcome.APPLIED));
			} catch (Exception e) {
				if (updater != null) updater.close();
				releaseOnClient(() -> completed.accept(false));
				failure(e, "automodpack.error.update", FailureCategory.UPDATE);
			}
		});
	}

	/** Declares local sovereignty for the active pack: a pure local statement that stops every sync until the player explicitly syncs again. */
	void stopSyncing(Pack pack, Runnable completed) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				new ClientGenerationStore(storage).declareDetached(pack.modpackId());
				releaseOnClient(completed);
			} catch (Exception e) {
				releaseOnClient(completed);
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
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
			UpdatePlan plan = new UpdatePlan(pack.modpackId(), PackTarget.from(pack.record()), List.of(), List.of(), null, Set.of(), List.of(), List.of(), List.of(), List.of(), ChangeSet.empty());
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
				GenerationHistoryController.open(storage, pack.modpackId(), pack.name(), released);
			} catch (Exception e) {
				releaseOnClient(released);
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void openFiles(Screen parent, Pack pack) {
		// The source filter and the red custom mark read the platform cache, so the installed catalogue answers
		// the same question as the update preview: which of these files came from a storefront, and which never did.
		ScreenImpl.setScreen(new ChangeBrowserScreen(parent, VersionedText.translatable("automodpack.files.title", pack.name()),
				VersionedText.translatable("automodpack.files.description"), ChangeSet.catalogue(pack.record().manifest()), groupNames(pack.record().manifest()), null, List.of(), true, 0, ""));
	}

	/** Display names for the active pack's groups, for browsers that only see group ids, like the changelog. */
	Map<String, String> activeGroupNames() {
		PackDocument record = activeRecord(activeModpackId());
		return record == null ? Map.of() : groupNames(record.manifest());
	}

	/** A group id to display-name map for a manifest; unnamed groups fall back to "Unknown group". */
	static Map<String, String> groupNames(GroupManifest manifest) {
		Map<String, String> names = new TreeMap<>();
		manifest.groups().forEach((groupId, group) -> names.put(groupId,
				group.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownGroup").getString() : group.displayName()));
		return Map.copyOf(names);
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

	private boolean detached(String modpackId) {
		try {
			return storage.isDetached(modpackId);
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return false;
		}
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

	private ConnectionJsons.ConnectionInfo connection(String modpackId) {
		try {
			ConnectionJsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
			return fields.connection != null && fields.connection.isComplete() ? fields.connection : null;
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return null;
		}
	}

	private static String connectionOrigin(ConnectionJsons.ConnectionInfo connection) {
		return connection == null ? null : PackConfirmCopy.displayOrigin(AddressHelpers.formatAddress(connection.origin));
	}

	private static String connectionDetail(ConnectionJsons.ConnectionInfo connection) {
		return connection == null ? null : AddressHelpers.formatAddress(connection.endpoint) + " · " + connection.connectionMode.name().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	/** Player-facing pack name: explicit name, else the vanilla server list entry, else the address, else the raw id. */
	private static String displayName(PackDocument record, String connectionOrigin) {
		String name = record.manifest().modpackName();
		if (!name.isBlank()) return name;
		if (connectionOrigin != null) {
			String server = VersionedServers.entryName(connectionOrigin);
			if (!server.isBlank()) return server;
			return connectionOrigin;
		}
		return record.manifest().modpackId();
	}

	private void releaseOnClient(Runnable released) {
		Minecraft.getInstance().execute(released);
	}

	private void failure(Throwable cause, String messageKey, FailureCategory category) {
		ScreenManager.failure(FailureRequest.of(cause, messageKey, category, FailureDestination.CURRENT_SCREEN, null));
	}

	private static Pack pack(PackDocument record, boolean active, boolean detached, ConnectionJsons.ConnectionInfo connection) {
		ChangeBrowserProjection.Aggregate aggregate = ChangeBrowserProjection.project(ChangeSet.catalogue(record.manifest()), ChangeBrowserProjection.Mode.LIST).total();
		return new Pack(record, active, detached, connectionOrigin(connection), connectionDetail(connection), Math.toIntExact(aggregate.fileCount()), aggregate.byteCount());
	}

	private record Pending(PackDocument record, boolean active, boolean detached, ConnectionJsons.ConnectionInfo connection, String displayName) {}

	record Pack(PackDocument record, boolean active, boolean detached, String connectionOrigin, String connectionDetail, int fileCount, long fileBytes) {
		boolean connectionAvailable() {
			return connectionOrigin != null;
		}

		String modpackId() {
			return record.manifest().modpackId();
		}

		String name() {
			return InstalledModpackController.displayName(record, connectionOrigin);
		}

		int groupCount() {
			return record.manifest().groups().size();
		}

	}
}
