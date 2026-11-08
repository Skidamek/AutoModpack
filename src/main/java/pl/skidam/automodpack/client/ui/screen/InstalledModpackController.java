package pl.skidam.automodpack.client.ui.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedServers;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.client.ClientOfflineRepair;
import pl.skidam.automodpack_core.client.InstalledPacks;
import pl.skidam.automodpack_core.client.LaunchApplyState;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.ModpackUtils;
import pl.skidam.automodpack_core.client.SessionUpdateState;
import pl.skidam.automodpack_core.client.StoredModpackConnection;
import pl.skidam.automodpack_core.client.SwitchFlow;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.loader.LoaderServices;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.PreviewPayload;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStateJournal;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_core.update.RecoveredFiles;
import pl.skidam.automodpack_core.update.RelayConsent;
import pl.skidam.automodpack_core.update.RestartDemand;
import pl.skidam.automodpack_core.update.StateHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.UriOpener;

/**
 * Owns local installed-pack discovery and lifecycle operations used by the pack manager screens.
 * The screens only choose an action and render its result; storage and update invariants stay here.
 */
final class InstalledModpackController {
	private static final Logger LOGGER = LogManager.getLogger(InstalledModpackController.class);

	/** The one thing discovery needs from the game: the vanilla server-list name for a saved origin. */
	private static final InstalledPacks.ServerNames SERVER_NAMES = VersionedServers::entryName;
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

	/** The selection the active pointer committed under; the prior every switch, activation, and update plans against. */
	private SelectionIntent appliedSelection(String modpackId) throws IOException {
		return new ClientGenerationStore(storage).appliedSelection(modpackId).orElse(null);
	}

	/** One applied selection change, the conflict the player must settle first, or the player-facing reason the change cannot apply. */
	record SelectionChange(SelectionIntent intent, ResolvedSelection resolution, GroupSelectionResolver.ConflictReplacement conflict, String failure) {}

	/**
	 * The outcome of applying one selection change: the resolver's answer when it holds, otherwise the conflict offer that
	 * can settle it, otherwise the reason it cannot. Screens route between the outcomes; the resolver policy stays here.
	 */
	SelectionChange planSelectionChange(GroupManifest manifest, SelectionIntent next, Set<String> preferredGroups, String preferredName, ClientPlatform platform) {
		try {
			return new SelectionChange(next, GroupSelectionResolver.resolve(manifest, next, platform), null, null);
		} catch (SelectionResolutionException exception) {
			GroupSelectionResolver.ConflictReplacement replacement = GroupSelectionResolver.replaceConflicts(manifest, next, preferredGroups, platform, exception.resolution()).orElse(null);
			if (replacement != null) return new SelectionChange(next, null, replacement, null);
			return new SelectionChange(next, null, null, preferredGroups.isEmpty()
					? VersionedText.str("automodpack.selection.changeInvalid")
					: VersionedText.str("automodpack.selection.cannotSelect", preferredName));
		}
	}

	boolean relayed(InstalledPacks.Pack pack) {
		try {
			return RelayConsent.isRelayed(storage, pack.modpackId());
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return false;
		}
	}

	void setRelayed(InstalledPacks.Pack pack, boolean relayed) {
		try {
			RelayConsent.setRelayed(storage, pack.modpackId(), relayed);
		} catch (IOException | RuntimeException e) {
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
		}
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

	/** Save is the consent moment: the desire lands first, so backing out of the preview still keeps the intent. */
	void switchSelection(PackDocument record, SelectionIntent target, String modpackName, Runnable released) {
		try {
			// The prior is read before the desire lands: legacy pointers fall back to this store for their applied selection.
			SelectionIntent applied = appliedSelection(record.manifest().modpackId());
			new ClientSelectionStore(storage.selectionFile()).put(record.manifest().modpackId(), target);
			SwitchFlow.start(storage, record, applied, target, modpackName, released, LoaderServices.modpackLoader());
		} catch (IOException e) {
			released.run();
			failure(e, "automodpack.error.update", FailureCategory.UPDATE);
		}
	}

	InstalledPacks.Pack installedPack(String modpackId) {
		for (InstalledPacks.Pack pack : installed()) if (pack.modpackId().equals(modpackId)) return pack;
		return null;
	}

	/** The whole catalogue, with the first read failure that hid any of it recorded for the screens. */
	List<InstalledPacks.Pack> installed() {
		InstalledPacks.Catalogue catalogue = InstalledPacks.read(storage, SERVER_NAMES);
		if (catalogue.failure() != null) discoveryFailure = catalogue.failure();
		return catalogue.packs();
	}

	Throwable discoveryFailure() {
		return discoveryFailure;
	}

	ClientObjectStore.StorageReport inspectStorage() throws IOException {
		return ClientObjectStore.measure(storage);
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

	/** Installed packs whose saved origin now serves the active pack instead: the leftovers of a republished server. */
	List<InstalledPacks.Stale> stalePacks() {
		try {
			return InstalledPacks.stale(storage, SERVER_NAMES);
		} catch (IOException | RuntimeException e) {
			discoveryFailure = e;
			return List.of();
		}
	}

	/** The light name lookup: one document read, no catalogue projection. For labels; never for pack actions. */
	String packName(String modpackId) {
		return InstalledPacks.nameOf(storage, modpackId, SERVER_NAMES);
	}

	/** The restart footer only offers the action; consent is the same removal preview the pack manager uses, one pack at a time. */
	void offerStalePackRemoval(Runnable completed) {
		ScreenManager.background(() -> previewStaleRemoval(stalePacks(), 0, completed));
	}

	/** Builds one pack's removal preview off the player thread and offers it on the client thread; the forget callback recurses to the next pack. */
	private void previewStaleRemoval(List<InstalledPacks.Stale> stale, int index, Runnable completed) {
		if (index >= stale.size()) {
			completed.run();
			return;
		}
		InstalledPacks.Stale pack = stale.get(index);
		try {
			PackDocument record = new ClientGenerationStore(storage).newestDocument(pack.modpackId());
			if (record == null) throw new IOException("Stale pack has no installed generation: " + pack.modpackId());
			UpdatePreview preview = removalPreview(pack.modpackId(), record, ChangeSet.catalogue(record.manifest(), ChangeSet.Kind.REMOVED));
			String origin = originFor(pack.modpackId());
			releaseOnClient(() -> showStaleRemoval(stale, index, pack, preview, origin, completed));
		} catch (Exception e) {
			releaseOnClient(() -> {
				completed.run();
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			});
		}
	}

	private void showStaleRemoval(List<InstalledPacks.Stale> stale, int index, InstalledPacks.Stale pack, UpdatePreview preview, String origin, Runnable completed) {
		boolean shown = ScreenManager.preview(PreviewPayload.storageRemoval(preview, pack.name(), origin, null,
				(Runnable) () -> ScreenManager.background(() -> {
					try {
						new ClientGenerationStore(storage).forgetModpack(pack.modpackId());
						releaseOnClient(() -> previewStaleRemoval(stale, index + 1, completed));
					} catch (Exception e) {
						releaseOnClient(completed);
						failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
					}
				}), completed));
		if (!shown) completed.run();
	}

	List<StateHistory.SnapshotView> stateViews() throws IOException {
		return StateHistory.views(storage);
	}

	StateHistory.FileGate stateFileGate(UpdatePlan.Root root, String path) throws IOException {
		return StateHistory.fileRestoreGate(storage, root, path);
	}

	Path restoreStateFile(long seq, UpdatePlan.Root root, String path) throws IOException {
		return StateHistory.restoreFile(storage, seq, root, path);
	}

	Path saveStateFileCopy(long seq, UpdatePlan.Root root, String overlayPackId, String path) throws IOException {
		return StateHistory.saveFileCopy(storage, seq, root, overlayPackId, path);
	}

	/** The recovered-copies folder is where saved copies land; opening it spares the player a path too long for any screen. */
	void openRecoveredFolder() {
		ScreenManager.background(() -> {
			try {
				Path folder = RecoveredFiles.directory(storage);
				Files.createDirectories(folder);
				UriOpener.openPath(folder);
			} catch (Exception e) {
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void restoreState(ClientStateJournal.Snapshot snapshot, Runnable released) {
		ScreenManager.background(() -> {
			try {
				StateHistory.CheckoutResult result = StateHistory.checkout(storage, snapshot.seq());
				Changelogs changelogs = new Changelogs();
				changelogs.replaceWith(result.changes());
				if (!result.changes().changes().isEmpty()) SessionUpdateState.markAppliedContentNotLoaded();
				LOGGER.info("Restored instance timeline snapshot {} ({} changed paths; restart demand {})", snapshot.seq(), result.changes().changes().size(), result.restartDemand());
				releaseOnClient(() -> {
					released.run();
					if (result.restartDemand() != RestartDemand.NONE) ScreenManager.restart(UpdateType.UPDATE, changelogs);
				});
			} catch (Exception e) {
				released.run();
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void forgetOlderThan(long seq, Runnable released) {
		ScreenManager.background(() -> {
			try {
				StateHistory.forgetOlderThan(storage, seq);
				releaseOnClient(released);
			} catch (Exception e) {
				released.run();
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void openStateHistory(Screen parent, Runnable released) {
		ScreenImpl.setScreen(new StateHistoryScreen(parent, this, released));
	}

	/** Whether the saved desire composes a different pack than the applied one: pending work the update button must still offer. */
	boolean pendingGroupChanges(InstalledPacks.Pack pack) {
		try {
			SelectionIntent desired = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
			return desired != null && !desired.equals(appliedSelection(pack.modpackId()));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	void update(InstalledPacks.Pack pack, Consumer<Boolean> completed) {
		if (!pack.active() || !pack.connectionAvailable()) {
			releaseOnClient(() -> completed.accept(false));
			return;
		}
		ScreenManager.background(() -> {
			ModpackUpdater updater = null;
			try {
				SelectedModpackTarget target;
				try (StoredModpackConnection connection = StoredModpackConnection.open(storage, pack.modpackId(), true)) {
					GenerationJsons.HeadDocumentFields advertised = connection.advertisedFields();
					SelectionIntent desired = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
					SelectionIntent applied = appliedSelection(pack.modpackId());
					target = desired == null
							? SelectedModpackTarget.prepareDefault(advertised, ClientPlatform.effective(desired))
							: SelectedModpackTarget.prepare(advertised, applied, desired, ClientPlatform.effective(desired));
					updater = connection.newUpdater(target, storage, LoaderServices.modpackLoader());
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
	void stopSyncing(InstalledPacks.Pack pack, Runnable completed) {
		ScreenManager.background(() -> {
			try {
				new ClientGenerationStore(storage).declareDetached(pack.modpackId());
				releaseOnClient(completed);
			} catch (Exception e) {
				releaseOnClient(completed);
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void repair(Screen parent, InstalledPacks.Pack pack, Consumer<Boolean> completed) {
		if (!pack.active()) {
			releaseOnClient(() -> completed.accept(false));
			return;
		}
		ScreenManager.background(() -> {
			try {
				ClientOfflineRepair repair = new ClientOfflineRepair(storage, LoaderServices.modpackLoader());
				OfflineRepair.Prepared prepared = repair.inspect();
				releaseOnClient(() -> ScreenImpl.setScreen(new OfflineRepairScreen(parent, pack.name(), repair, prepared,
						pack.connectionAvailable() ? () -> update(pack, completed) : null, () -> completed.accept(false))));
			} catch (Exception e) {
				releaseOnClient(() -> completed.accept(false));
				failure(e, "automodpack.error.repair", FailureCategory.STORAGE);
			}
		});
	}

	void activate(InstalledPacks.Pack pack, Runnable released) {
		try {
			SelectionIntent desired = new ClientSelectionStore(storage.selectionFile()).get(pack.modpackId()).orElse(null);
			SelectionIntent targetSelection = desired == null ? GroupSelectionResolver.defaultIntent(pack.record().manifest()) : desired;
			SwitchFlow.start(storage, pack.record(), appliedSelection(pack.modpackId()), targetSelection, pack.name(), released, LoaderServices.modpackLoader());
		} catch (IOException | RuntimeException e) {
			released.run();
			failure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
		}
	}

	void deactivate(InstalledPacks.Pack pack, Runnable released, Runnable completed) {
		removeActive(pack, true, released, completed);
	}

	void remove(InstalledPacks.Pack pack, Runnable released, Runnable removed) {
		if (pack.active()) {
			removeActive(pack, false, released, removed);
			return;
		}
		try {
			UpdatePreview preview = removalPreview(pack.modpackId(), pack.record(), ChangeSet.empty());
			boolean shown = ScreenManager.preview(PreviewPayload.storageRemoval(preview, pack.name(), originFor(pack.modpackId()), null,
					(Runnable) () -> ScreenManager.background(() -> forget(pack, released, removed)), released));
			if (!shown) released.run();
		} catch (Exception e) {
			released.run();
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
		}
	}

	boolean launchWritesUnverified(InstalledPacks.Pack pack) {
		return LaunchApplyState.allowsUnverifiedLaunchWrites(storage, pack.modpackId());
	}

	void setLaunchWritesUnverified(InstalledPacks.Pack pack, boolean allowed) {
		LaunchApplyState.setAllowsUnverifiedLaunchWrites(storage, pack.modpackId(), allowed);
	}

	void openHistory(InstalledPacks.Pack pack, Runnable released) {
		ScreenManager.background(() -> {
			try {
				GenerationHistoryController.open(storage, pack.modpackId(), pack.name(), released);
			} catch (Exception e) {
				releaseOnClient(released);
				failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			}
		});
	}

	void openFiles(Screen parent, InstalledPacks.Pack pack) {
		ScreenImpl.setScreen(new ChangeBrowserScreen(parent, VersionedText.text("automodpack.files.title", pack.name()),
				VersionedText.text("automodpack.files.description"), ChangeSet.catalogue(pack.record().manifest()), groupNames(pack.record().manifest()), null, List.of(), 0, ""));
	}

	/** Display names for the active pack's groups, for browsers that only see group ids, like the changelog. */
	Map<String, String> activeGroupNames() {
		PackDocument record = activeRecord(activeModpackId());
		return record == null ? Map.of() : groupNames(record.manifest());
	}

	/** A group id to display-name map for a manifest; unnamed groups fall back to their id. */
	static Map<String, String> groupNames(GroupManifest manifest) {
		Map<String, String> names = new TreeMap<>();
		manifest.groups().forEach((groupId, group) -> names.put(groupId,
				group.displayName().isBlank() ? groupId : group.displayName()));
		return Map.copyOf(names);
	}

	private void removeActive(InstalledPacks.Pack pack, boolean deactivation, Runnable released, Runnable removed) {
		ModpackUpdater updater;
		try {
			updater = new ModpackUpdater(null, null, storage, LoaderServices.modpackLoader());
		} catch (RuntimeException e) {
			released.run();
			failure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			return;
		}
		updater.removeOrDeactivate(deactivation, pack.name(), released, removed);
	}

	private void forget(InstalledPacks.Pack pack, Runnable released, Runnable removed) {
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

	private static boolean detached(ClientStorageJsons.ClientGenerationStateFields activeState, String modpackId) {
		return activeState != null && activeState.modpackId.equals(modpackId) && activeState.detached;
	}

	private static String activeId(ClientStorageJsons.ClientGenerationStateFields state) {
		return state == null || state.modpackId == null ? "" : state.modpackId;
	}

	String activeModpackId() {
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

	/** The display origin recorded for this pack, or "" when the pack has none (for example a locally imported record). */
	public String originFor(String modpackId) {
		ConnectionJsons.ConnectionInfo connection = connection(modpackId);
		return connection == null ? "" : InstalledPacks.displayOrigin(AddressHelpers.formatAddress(connection.origin));
	}

	/** The forget-this-pack preview: a removal plan over the installed record with no operations of its own. */
	private static UpdatePreview removalPreview(String modpackId, PackDocument record, ChangeSet changes) {
		UpdatePlan plan = new UpdatePlan(modpackId, PackTarget.from(record), List.of(), List.of(), null, Set.of(), List.of(), List.of(), List.of(), List.of(), changes);
		return UpdatePreview.create(plan, null, UpdatePreview.Mode.REMOVAL).withFeatureManifest(record.manifest());
	}

	private static String connectionOrigin(ConnectionJsons.ConnectionInfo connection) {
		return connection == null ? null : PackConfirmCopy.displayOrigin(AddressHelpers.formatAddress(connection.origin));
	}

	private static String connectionDetail(ConnectionJsons.ConnectionInfo connection) {
		return connection == null ? null : AddressHelpers.formatAddress(connection.endpoint) + " · " + connection.connectionMode.name().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private void releaseOnClient(Runnable released) {
		Minecraft.getInstance().execute(released);
	}

	WaitingMusicPlayer waitingMusicPlayer() {
		return new WaitingMusicPlayer(storage);
	}

	private void failure(Throwable cause, String messageKey, FailureCategory category) {
		ScreenManager.failure(FailureRequest.of(cause, messageKey, category, FailureDestination.CURRENT_SCREEN, null));
	}

}
