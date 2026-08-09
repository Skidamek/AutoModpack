package pl.skidam.automodpack.client.ui;

import pl.skidam.automodpack_core.config.ModpackJsons;
import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.nio.file.Path;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.LocalModArchive;
import pl.skidam.automodpack_core.update.QuarantineArchive;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Lets the player pick which optional groups of a modpack they want. Deliberately built out of
 * plain buttons rather than a scrolling list widget: buttons are the one widget whose API is
 * stable across every Minecraft version this mod targets.
 *
 * Changes only take effect on the next launch, because mods are loaded during preload.
 */
public class ModpackSelectionScreen extends VersionedScreen {

	private static final int ROW_HEIGHT = 24;
	private static final int ROW_WIDTH = 320;

	private final Screen parent;
	private final GroupManifest manifest;
	private final String modpackId;
	private final String modpackName;
	private final Map<String, GroupManifest.Group> groups;
	private final ClientStorage storage;
	private final ClientSelectionStore selectionStore;
	private final SelectionIntent expectedSelection;
	private final Consumer<SelectionIntent> selectionAction;
	private final Runnable cancelAction;
	private final ModpackUpdater pendingUpdater;
	private final boolean managerEntry;
	private final boolean activeModpack;
	private final GenerationRecord localRecord;

	// What the player has actually ticked; resolved is what that implies once required groups,
	// dependencies, conflicts and platform rules are applied.
	private final Set<String> chosen = new LinkedHashSet<>();
	private final Set<String> excluded = new LinkedHashSet<>();
	private ResolvedSelection resolution;
	private List<String> resolutionErrors = List.of();
	private final List<Row> rows = new ArrayList<>();

	private int page = 0;
	private int rowsPerPage = 1;
	private boolean saved = false;
	private boolean closed;
	private boolean managementInFlight;
	private boolean switchInFlight;
	private Button removeButton;
	private Button recoveryButton;
	private Button quarantineButton;
	private Button historyButton;
	private Button localModsButton;

	public ModpackSelectionScreen(Screen parent, GroupManifest manifest) {
		this(parent, manifest, null, null, null, () -> {}, null, false, null);
	}

	public ModpackSelectionScreen(Screen parent, ModpackUpdater updater, Consumer<SelectionIntent> selectionAction) {
		this(parent, updater.getSelectedTarget().manifest(), updater.getSelectedTarget().expectedPriorIntent(), updater.getSelectedTarget().selection().intent(), selectionAction, () -> {}, updater, false, null);
	}

	public static ModpackSelectionScreen repair(Screen parent, GroupManifest manifest, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
		return new ModpackSelectionScreen(parent, manifest, savedSelection, savedSelection, selectionAction, cancelAction, null, false, null);
	}

	public static ModpackSelectionScreen forInstalledRecord(Screen parent, GenerationRecord record, boolean managerEntry) {
		return new ModpackSelectionScreen(parent, record.manifest(), null, null, null, () -> {}, null, managerEntry, record);
	}

	private ModpackSelectionScreen(Screen parent, GroupManifest manifest, SelectionIntent expectedSelection, SelectionIntent initialSelection,
			Consumer<SelectionIntent> selectionAction, Runnable cancelAction, ModpackUpdater pendingUpdater, boolean managerEntry, GenerationRecord localRecord) {
		super(VersionedText.translatable("automodpack.selection.title"));
		this.parent = parent;
		this.manifest = Objects.requireNonNull(manifest);
		this.modpackId = manifest.modpackId();
		this.modpackName = manifest.modpackName();
		this.groups = manifest.groups();
		this.storage = ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
		this.selectionStore = new ClientSelectionStore(storage.selectionFile());
		this.expectedSelection = expectedSelection == null && initialSelection == null
				? selectionStore.get(modpackId).orElse(null)
				: expectedSelection;
		this.selectionAction = selectionAction;
		this.cancelAction = cancelAction;
		this.pendingUpdater = pendingUpdater;
		this.managerEntry = managerEntry;
		this.activeModpack = activeModpack(storage, modpackId);
		this.localRecord = localRecord;
		SelectionIntent initial = initialSelection != null
				? initialSelection
				: this.expectedSelection == null ? GroupSelectionResolver.defaultIntent(manifest) : this.expectedSelection;
		this.chosen.addAll(initial.requestedGroups());
		this.excluded.addAll(initial.excludedGroups());
		try {
			this.resolution = expectedSelection == null && (initialSelection == null || pendingUpdater != null)
					? GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.current())
					: GroupSelectionResolver.resolve(manifest, initial, ClientPlatform.current());
		} catch (SelectionResolutionException e) {
			this.resolution = Objects.requireNonNull(e.resolution(), "Invalid selection did not include a partial resolution");
			this.resolutionErrors = e.errors();
		}
		rebuildRows();
	}

	/**
	 * Builds the screen for whichever modpack the client currently has selected. Returns the parent
	 * untouched when there is nothing to choose, so callers can hand the result straight to setScreen.
	 */
	public static Screen forSelectedModpack(Screen parent) {
		return forModpackId(parent, clientConfig == null ? null : clientConfig.selectedModpackId);
	}

	private static Screen forModpackId(Screen parent, String modpackId) {
		if (modpackId == null || modpackId.isBlank()) {
			LOGGER.info("No modpack selected, nothing to configure");
			return parent;
		}

		GenerationRecord record = activeGeneration(modpackId);
		GroupManifest manifest = record == null ? null : record.manifest();
		if (manifest == null) {
			LOGGER.info("Modpack {} generation record is unavailable", modpackId);
			return parent;
		}

		return new ModpackSelectionScreen(parent, manifest);
	}

	public static boolean hasModpackManagement() {
		return modpackHasGeneration(clientConfig == null ? null : clientConfig.selectedModpackId);
	}

	private static boolean modpackHasGeneration(String modpackId) {
		if (modpackId == null || modpackId.isBlank()) return false;
		return activeGeneration(modpackId) != null;
	}

	private static GenerationRecord activeGeneration(String modpackId) {
		try {
			ClientStorage storage = ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !modpackId.equals(state.modpackId)) return null;
			return new ClientGenerationStore(storage).read(state.generationId).orElse(null);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read the active generation for modpack {}", modpackId, e);
			return null;
		}
	}

	@Override
	protected void init() {
		super.init();

		// Once saved, the screen becomes a restart prompt: the new selection only takes effect after
		// a relaunch, so there is nothing left to toggle here.
		if (saved) {
			int buttonWidth = actionButtonWidth(310, 2);
			int y = this.height / 2 + 20;
			this.addRenderableWidget(buttonWidget(actionButtonX(310, 2, 0), y, buttonWidth, 20,
					VersionedText.translatable("automodpack.selection.later"), press -> ScreenImpl.setScreen(parent)));
			this.addRenderableWidget(buttonWidget(actionButtonX(310, 2, 1), y, buttonWidth, 20,
					VersionedText.translatable("automodpack.selection.restartNow").withStyle(ChatFormatting.BOLD), press -> this.minecraft.stop()));
			return;
		}

		int listTop = 64;
		List<ManagementAction> managementActions = selectionAction == null ? managementActions() : List.of();
		int managementRows = managementRowCount(managementActions.size());
		int managementTop = this.height - 80 - Math.max(0, managementRows - 1) * 28;
		int listBottom = this.height - (selectionAction == null ? (managementActions.isEmpty() ? 80 : 108 + Math.max(0, managementRows - 1) * 28) : 60);
		rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

		int pageCount = Math.max(1, (int) Math.ceil((double) rows.size() / rowsPerPage));
		if (page >= pageCount) page = pageCount - 1;

		int rowWidth = panelWidth(ROW_WIDTH);
		int x = panelLeft(ROW_WIDTH);
		int start = page * rowsPerPage;

		for (int i = start; i < Math.min(rows.size(), start + rowsPerPage); i++) {
			Row row = rows.get(i);
			int y = listTop + (i - start) * ROW_HEIGHT;
			if (row.groupId() == null) {
				Button section = buttonWidget(x, y, rowWidth, 20, sectionLabel(row), press -> {
					if (row.tagId() != null) toggleCategory(row.tagId());
				});
				section.active = row.tagId() != null && hasOptionalCategoryGroups(row.tagId());
				this.addRenderableWidget(section);
				continue;
			}

			String groupId = row.groupId();
			var group = groups.get(groupId);
			Button button = buttonWidget(x, y, rowWidth - 68, 20, rowLabel(groupId, group), press -> toggle(groupId));
			// Required groups, forced groups, dependencies and unavailable groups are shown so the player
			// can understand the target, but only optional compatible choices are togglable.
			button.active = group != null && canToggle(groupId, group);
			MutableComponent tooltip = rowTooltip(groupId, group);
			if (tooltip != null) setTooltip(button, tooltip);
			this.addRenderableWidget(button);
			Button inspect = buttonWidget(x + rowWidth - 64, y, 64, 20, VersionedText.translatable("automodpack.ui.info"), press -> inspect(groupId));
			inspect.active = group != null;
			if (tooltip != null) setTooltip(inspect, tooltip);
			this.addRenderableWidget(inspect);
		}

		if (pageCount > 1) {
			int paginationWidth = actionButtonWidth(ROW_WIDTH, 3);
			int paginationY = listBottom + 4;
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(ROW_WIDTH, 3, 3, 0), paginationY, paginationWidth, 20, VersionedText.translatable("automodpack.ui.previous"), press -> {
				if (page > 0) {
					page--;
					rebuild();
				}
			}));
			Button pageLabel = buttonWidget(centeredActionButtonX(ROW_WIDTH, 3, 3, 1), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(ROW_WIDTH, 3, 3, 2), paginationY, paginationWidth, 20, VersionedText.translatable("automodpack.ui.next"), press -> {
				if (page < pageCount - 1) {
					page++;
					rebuild();
				}
			}));
		}

		if (!managementActions.isEmpty()) {
			for (int index = 0; index < managementActions.size(); index++) {
				ManagementAction action = managementActions.get(index);
				int row = index / 3;
				int rowStart = row * 3;
				int rowCount = Math.min(3, managementActions.size() - rowStart);
				int managementWidth = actionButtonWidth(ROW_WIDTH, rowCount);
				Button button = buttonWidget(centeredActionButtonX(ROW_WIDTH, rowCount, rowCount, index - rowStart), managementTop + row * 28,
						managementWidth, 20, action.label(), press -> action.action().run());
				this.addRenderableWidget(button);
				if (action.kind() == ManagementKind.REMOVE) this.removeButton = button;
				if (action.kind() == ManagementKind.RECOVERY) this.recoveryButton = button;
				if (action.kind() == ManagementKind.QUARANTINE) this.quarantineButton = button;
				if (action.kind() == ManagementKind.LOCAL_MODS) this.localModsButton = button;
				if (action.kind() == ManagementKind.HISTORY) this.historyButton = button;
			}
			updateManagementButtons();
		}

		int actionWidth = actionButtonWidth(310, 3);
		int actionY = this.height - 28;
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 0), actionY, actionWidth, 20, VersionedText.translatable("automodpack.selection.cancel"), press -> back()));

		this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 1), actionY, actionWidth, 20, VersionedText.translatable("automodpack.selection.reset"), press -> {
			SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);
			chosen.clear();
			chosen.addAll(defaults.requestedGroups());
			excluded.clear();
			reresolveDefault();
		}));

		String saveLabel = selectionAction != null ? "automodpack.selection.preview" : managerEntry && !activeModpack ? "automodpack.packManager.reviewSwitch" : "automodpack.selection.save";
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 2), actionY, actionWidth, 20,
				VersionedText.translatable(saveLabel).withStyle(ChatFormatting.BOLD), press -> save()));
	}

	public boolean isUpdateFlow() {
		return selectionAction != null;
	}

	public boolean isConfirmationFlow() {
		return pendingUpdater != null;
	}

	@Override
	public void tick() {
		super.tick();
		if (pendingUpdater == null) return;
		ModpackUpdater.ConfirmationState state = pendingUpdater.getConfirmationState();
		if (state == ModpackUpdater.ConfirmationState.CANCELLED) ScreenImpl.multiplayer();
	}

	private boolean canToggle(String groupId, GroupManifest.Group group) {
		if (isMandatory(manifest, group)) return false;
		GroupResolution explanation = resolution.explanation(groupId);
		if (explanation == null) return group.supports(ClientPlatform.current());
		if (explanation.selected() && (resolution.requiredGroups().contains(groupId) || resolution.forcedGroups().contains(groupId)
				|| resolution.dependencyGroups().contains(groupId))) return false;
		return group.supports(ClientPlatform.current()) || excluded.contains(groupId);
	}

	/** Toggling a category requests or removes its optional groups through the persisted group intent. */
	private void toggleCategory(String category) {
		SelectionIntent previous = new SelectionIntent(chosen, excluded);
		ResolvedSelection previousResolution = resolution;
		try {
			SelectionIntent next = GroupSelectionResolver.preferCategory(manifest, previous, category, ClientPlatform.current());
			applyIntent(next);
			reresolve();
		} catch (SelectionResolutionException e) {
			restoreResolution(e, previousResolution);
			LOGGER.warn("Category preference for {} creates a conflict that needs explicit resolution: {}", category, e.getMessage());
		}
	}

	/**
	 * A group inside a selected category becomes an explicit exclusion when it is not otherwise required.
	 * Direct choices remain in the intent when they conflict, so the player can remove either choice explicitly.
	 */
	private void toggle(String groupId) {
		GroupManifest.Group group = groups.get(groupId);
		if (group == null) return;
		if (isMandatory(manifest, group)) return;
		SelectionIntent previous = new SelectionIntent(chosen, excluded);
		ResolvedSelection previousResolution = resolution;
		try {
			SelectionIntent next = GroupSelectionResolver.prefer(previous, groupId);
			applyIntent(next);
			reresolve();
		} catch (SelectionResolutionException e) {
			restoreResolution(e, previousResolution);
			LOGGER.warn("Group preference for {} creates a conflict that needs explicit resolution: {}", groupId, e.getMessage());
		}
	}

	private void applyIntent(SelectionIntent intent) {
		chosen.clear();
		chosen.addAll(intent.requestedGroups());
		excluded.clear();
		excluded.addAll(intent.excludedGroups());
	}

	private void restoreResolution(SelectionResolutionException exception, ResolvedSelection previousResolution) {
		resolution = exception.resolution() == null ? previousResolution : exception.resolution();
		resolutionErrors = exception.errors();
		rebuild();
	}

	private void reresolve() {
		resolution = GroupSelectionResolver.resolve(manifest, new SelectionIntent(chosen, excluded), ClientPlatform.current());
		resolutionErrors = List.of();
		rebuild();
	}

	private void reresolveDefault() {
		resolution = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.current());
		resolutionErrors = List.of();
		rebuild();
	}

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	private void inspect(String groupId) {
		ScreenImpl.setScreen(new GroupInspectorScreen(this, manifest, groupId));
	}

	private ModpackUpdater createUpdater() {
		try {
			return new ModpackUpdater(null, null, storage);
		} catch (RuntimeException e) {
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			return null;
		}
	}

	private void requestRemoval() {
		if (!beginManagement()) return;
		ModpackUpdater removalUpdater = createUpdater();
		if (removalUpdater == null) {
			endManagement();
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				UpdatePreview preview = removalUpdater.previewRemoval();
				new ScreenManager().preview(preview, modpackName,
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> executeRemoval(removalUpdater)),
						(Runnable) () -> {
							removalUpdater.close();
							endManagement();
						}, true, false, Map.of());
			} catch (Exception e) {
				removalUpdater.close();
				endManagement();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void requestRecovery() {
		if (!beginManagement()) return;
		ModpackUpdater recoveryUpdater = createUpdater();
		if (recoveryUpdater == null) {
			endManagement();
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				new ScreenManager().recovery(recoveryUpdater, recoveryUpdater.recoverySnapshot(), modpackName, (Runnable) this::endManagement);
			} catch (Exception e) {
				recoveryUpdater.close();
				endManagement();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void requestHistory() {
		if (!beginManagement()) return;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				ClientGenerationStore generationStore = new ClientGenerationStore(storage);
				String generationId = historyGenerationId();
				List<GenerationPatchNoteHistory.Entry> patchNotesHistory = generationStore.patchNotesHistory(generationId);
				ScreenImpl.openPatchNotesHistory(this, patchNotesHistory, modpackName, this::endManagement);
			} catch (Exception e) {
				endManagement();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void requestQuarantine() {
		if (!beginManagement()) return;
		ScreenImpl.setScreen(new QuarantineArchiveScreen(this, storage, modpackId, modpackName, activeModpack, this::endManagement));
	}

	private void requestLocalMods() {
		if (!beginManagement()) return;
		ScreenImpl.setScreen(new LocalModArchiveScreen(this, storage, this::endManagement));
	}

	private boolean beginManagement() {
		if (managementInFlight) return false;
		managementInFlight = true;
		updateManagementButtons();
		return true;
	}

	private void endManagement() {
		managementInFlight = false;
		updateManagementButtons();
	}

	private void updateManagementButtons() {
		if (removeButton != null) removeButton.active = !managementInFlight;
		if (recoveryButton != null) recoveryButton.active = !managementInFlight;
		if (quarantineButton != null) quarantineButton.active = !managementInFlight;
		if (historyButton != null) historyButton.active = !managementInFlight;
		if (localModsButton != null) localModsButton.active = !managementInFlight;
	}

	private List<ManagementAction> managementActions() {
		List<ManagementAction> actions = new ArrayList<>();
		if (activeModpack) {
			if (modpackHasGeneration(modpackId)) actions.add(new ManagementAction(ManagementKind.REMOVE, VersionedText.translatable("automodpack.management.remove"), this::requestRemoval));
			if (hasRecoveryArchive()) actions.add(new ManagementAction(ManagementKind.RECOVERY, VersionedText.translatable("automodpack.management.recovery"), this::requestRecovery));
		}
		if (hasQuarantineArchive()) actions.add(new ManagementAction(ManagementKind.QUARANTINE, VersionedText.translatable("automodpack.management.quarantine"), this::requestQuarantine));
		if (hasLocalModArchive()) actions.add(new ManagementAction(ManagementKind.LOCAL_MODS, VersionedText.translatable("automodpack.management.localMods"), this::requestLocalMods));
		if (hasHistory()) actions.add(new ManagementAction(ManagementKind.HISTORY, VersionedText.translatable("automodpack.management.history"), this::requestHistory));
		if (hasOtherInstalledPacks()) actions.add(new ManagementAction(ManagementKind.MANAGER, VersionedText.translatable("automodpack.packManager.switch"), this::requestPackManager));
		return List.copyOf(actions);
	}

	private boolean hasRecoveryArchive() {
		try {
			Path root = storage.recoveryDirectory(modpackId);
			if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
			try (var paths = Files.list(root)) {
				return paths.findAny().isPresent();
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private boolean hasQuarantineArchive() {
		try {
			return QuarantineArchive.hasEntries(storage, modpackId);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private boolean hasLocalModArchive() {
		try {
			return LocalModArchive.hasEntries(storage);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static int managementRowCount(int actionCount) {
		return actionCount == 0 ? 0 : (actionCount + 2) / 3;
	}

	private boolean hasHistory() {
		try {
			ClientGenerationStore generationStore = new ClientGenerationStore(storage);
			String generationId = historyGenerationId();
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory = generationStore.patchNotesHistory(generationId);
			return patchNotesHistory.size() > 1 || GenerationPatchNoteHistory.containsNotes(patchNotesHistory);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private String historyGenerationId() throws IOException {
		if (activeModpack) {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !modpackId.equals(state.modpackId)) throw new IOException("Active generation is unavailable");
			return state.generationId;
		}
		if (localRecord == null) throw new IOException("Installed generation record is unavailable");
		return localRecord.metadata().generationId();
	}

	private boolean hasOtherInstalledPacks() {
		try {
			return new ClientGenerationStore(storage).installedRecords().stream().anyMatch(record -> !modpackId.equals(record.manifest().modpackId()));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private boolean isActiveModpack() {
		return activeModpack;
	}

	private static boolean activeModpack(ClientStorage storage, String modpackId) {
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			return state != null && modpackId.equals(state.modpackId);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private void requestPackManager() {
		ScreenImpl.setScreen(new InstalledModpacksScreen(this));
	}

	private void executeRemoval(ModpackUpdater updater) {
		try {
			if (updater.removeModpack().success()) {
				new ScreenManager().title();
			} else {
				new ScreenManager().error("automodpack.error.critical", "automodpack.error.removalIncomplete", "automodpack.error.logs");
			}
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		} finally {
			updater.close();
		}
	}

	private void save() {
		SelectionIntent target = new SelectionIntent(chosen, excluded);
		if (!resolutionErrors.isEmpty()) {
			new ScreenManager().error("automodpack.error.critical", resolutionErrors.get(0), "automodpack.error.logs");
			return;
		}
		if (selectionAction != null) {
			try {
				selectionAction.accept(target);
			} catch (RuntimeException e) {
				LOGGER.error("Failed to prepare the selected modpack target", e);
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
			return;
		}
		if (managerEntry && !activeModpack && localRecord != null) {
			startCachedSwitch(target);
			return;
		}
		try {
			selectionStore.compareAndSet(modpackId, expectedSelection, target);
			saved = true;
			rebuild();
		} catch (IOException e) {
			LOGGER.error("Failed to save group selection for modpack {}", modpackId, e);
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}

	private void startCachedSwitch(SelectionIntent targetIntent) {
		if (switchInFlight) return;
		switchInFlight = true;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ModpackUpdater updater = null;
			try {
				ClientGenerationStore generationStore = new ClientGenerationStore(storage);
				ModpackJsons.CompleteModpackContentFields fields = generationStore.readFields(localRecord.metadata().generationId())
						.orElseThrow(() -> new IOException("Installed modpack generation record is missing"));
				SelectedModpackTarget target = SelectedModpackTarget.prepare(fields, expectedSelection, targetIntent, ClientPlatform.current());
				updater = new ModpackUpdater(target, null, null, storage);
				UpdatePreview preview = updater.previewCachedSwitch();
				ModpackUpdater finalUpdater = updater;
				if (clientConfig != null && !clientConfig.reviewUpdates) {
					new ScreenManager().waiting();
					DownloadClient.NET_EXECUTOR.execute(() -> executeCachedSwitch(finalUpdater));
				} else {
					new ScreenManager().preview(preview, modpackName,
							(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> executeCachedSwitch(finalUpdater)),
							(Runnable) () -> {
								finalUpdater.close();
								switchInFlight = false;
							}, false, true, Map.of());
				}
			} catch (Exception e) {
				if (updater != null) updater.close();
				switchInFlight = false;
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void executeCachedSwitch(ModpackUpdater updater) {
		try {
			updater.applyCachedSwitch();
		} catch (Exception e) {
			updater.close();
			switchInFlight = false;
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}

	private void back() {
		if (closed) return;
		closed = true;
		cancelAction.run();
		ScreenImpl.setScreen(parent);
	}

	private void rebuildRows() {
		rows.clear();
		rows.add(new Row(VersionedText.translatable("automodpack.ui.general").getString(), null, null));
		for (var entry : groups.entrySet()) if (entry.getValue().tag().isEmpty()) rows.add(new Row("", entry.getKey(), null));
		Set<String> categories = new TreeSet<>();
		for (GroupManifest.Group group : groups.values()) if (!group.tag().isEmpty()) categories.add(group.tag());
		for (String category : categories) {
			rows.add(new Row(categoryLabel(category), null, category));
			for (var entry : groups.entrySet()) if (category.equals(entry.getValue().tag())) rows.add(new Row("", entry.getKey(), category));
		}
	}

	private MutableComponent sectionLabel(Row row) {
		if (row.tagId() == null) return VersionedText.literal(row.section()).withStyle(ChatFormatting.BOLD);
		String title = categoryLabel(row.tagId());
		return VersionedText.literal(truncateToWidth(this.font, (categorySelected(row.tagId()) ? "[x] " : "[ ] ") + title, panelWidth(ROW_WIDTH) - 12)).withStyle(ChatFormatting.BOLD);
	}

	/** The group's metadata and the resolver explanation, shown on hover. */
	private MutableComponent rowTooltip(String groupId, GroupManifest.Group group) {
		if (group == null) return null;
		StringBuilder tooltip = new StringBuilder();
		if (!group.description().isBlank()) tooltip.append(group.description());
		GroupResolution explanation = resolution.explanation(groupId);
		if (explanation != null) appendTooltipLine(tooltip, explanation.explanation());
		if (explanation != null && !explanation.relatedGroups().isEmpty()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.related", names(explanation.relatedGroups())).getString());
		appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.category", tagLabel(group)).getString());
		if (group.required()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.requiredAlways").getString());
		if (group.defaultSelected()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.defaultSelected").getString());
		if (resolution.forcedGroups().contains(groupId)) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.forced").getString());
		if (resolution.dependencyGroups().contains(groupId)) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.dependency").getString());
		if (!group.requires().isEmpty()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.requires", names(group.requires())).getString());
		if (!group.breaksWith().isEmpty()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.conflicts", names(group.breaksWith())).getString());
		appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.files", group.files().size(), UiFormat.formatSize(groupBytes(group))).getString());
		appendTooltipLine(tooltip, VersionedText.translatable(group.supports(ClientPlatform.current()) ? "automodpack.selection.availableOn" : "automodpack.selection.unavailableOn", ClientPlatform.current().id()).getString());
		return VersionedText.literal(tooltip.toString()).withStyle(ChatFormatting.GRAY);
	}

	private String tagLabel(GroupManifest.Group group) {
		if (group.tag().isEmpty()) return VersionedText.translatable("automodpack.ui.general").getString();
		return categoryLabel(group.tag());
	}

	private static void appendTooltipLine(StringBuilder tooltip, String line) {
		if (tooltip.length() > 0) tooltip.append('\n');
		tooltip.append(line);
	}

	private MutableComponent rowLabel(String groupId, GroupManifest.Group group) {
		if (group == null) return VersionedText.literal(truncateToWidth(this.font, groupId, panelWidth(ROW_WIDTH) - 76));

		String name = group.displayName().isBlank() ? groupId : group.displayName();
		GroupResolution explanation = resolution.explanation(groupId);
		String metrics = VersionedText.translatable("automodpack.selection.metrics", group.files().size(), UiFormat.formatSize(groupBytes(group))).getString();
		if (isMandatory(manifest, group)) return rowLabel(formatRowLabel("[#] ", name, metrics, VersionedText.translatable("automodpack.selection.status.required").getString()), ChatFormatting.GRAY);
		if (explanation != null && explanation.reasons().contains(GroupResolution.Reason.EXPLICIT_REQUEST_UNAVAILABLE))
			return rowLabel(formatRowLabel("[-] ", name, metrics, VersionedText.translatable("automodpack.selection.status.requestedUnavailable").getString()), ChatFormatting.RED);
		if (explanation != null && explanation.status() == GroupResolution.Status.UNAVAILABLE)
			return rowLabel(formatRowLabel("[-] ", name, metrics, VersionedText.translatable("automodpack.selection.status.unavailable").getString()), ChatFormatting.RED);
		if (explanation != null && explanation.status() == GroupResolution.Status.BLOCKED)
			return rowLabel(formatRowLabel("[-] ", name, metrics, VersionedText.translatable("automodpack.selection.status.dependencyUnavailable").getString()), ChatFormatting.RED);
		if (explanation != null && explanation.status() == GroupResolution.Status.CONFLICT)
			return rowLabel(formatRowLabel("[!] ", name, metrics, VersionedText.translatable("automodpack.selection.status.conflict").getString()), ChatFormatting.RED);
		if (excluded.contains(groupId)) return rowLabel(formatRowLabel("[-] ", name, metrics, VersionedText.translatable("automodpack.selection.status.excluded").getString()), ChatFormatting.YELLOW);
		if (resolution.selectedGroups().contains(groupId)) {
			if (chosen.contains(groupId)) return rowLabel(formatRowLabel("[x] ", name, metrics, VersionedText.translatable("automodpack.selection.status.selected").getString()), ChatFormatting.GREEN);
			if (resolution.dependencyGroups().contains(groupId))
				return rowLabel(formatRowLabel("[+] ", name, metrics, VersionedText.translatable("automodpack.selection.status.requiredBySelection").getString()), ChatFormatting.AQUA);
			return rowLabel(formatRowLabel("[+] ", name, metrics, null), ChatFormatting.AQUA);
		}
		if (resolution.forcedGroups().contains(groupId)) return rowLabel(formatRowLabel("[>] ", name, metrics, VersionedText.translatable("automodpack.selection.status.forced").getString()), ChatFormatting.AQUA);
		return group.defaultSelected()
				? rowLabel(formatRowLabel("[ ] ", name, metrics, VersionedText.translatable("automodpack.selection.status.includedByDefault").getString()), ChatFormatting.YELLOW)
				: rowLabel(formatRowLabel("[ ] ", name, metrics, null), ChatFormatting.GRAY);
	}

	/** Keeps a row's state explanation visible when the optional file metrics do not fit. */
	private String formatRowLabel(String marker, String name, String metrics, String status) {
		int maxWidth = panelWidth(ROW_WIDTH) - 76;
		String full = marker + name + " " + metrics + (status == null ? "" : " " + status);
		if (status == null || this.font.width(full) <= maxWidth) return truncateToWidth(this.font, full, maxWidth);

		String stateOnly = marker + name + " " + status;
		if (this.font.width(stateOnly) <= maxWidth) return stateOnly;

		int nameWidth = maxWidth - this.font.width(marker) - this.font.width(" ") - this.font.width(status);
		if (nameWidth <= 0) return truncateToWidth(this.font, marker + status, maxWidth);
		return marker + truncateToWidth(this.font, name, nameWidth) + " " + status;
	}

	private MutableComponent rowLabel(String text, ChatFormatting color) {
		return VersionedText.literal(truncateToWidth(this.font, text, panelWidth(ROW_WIDTH) - 76)).withStyle(color);
	}

	private static boolean isMandatory(GroupManifest manifest, GroupManifest.Group group) {
		return group.required();
	}

	private static long groupBytes(GroupManifest.Group group) {
		long total = 0;
		for (GroupManifest.GroupFile file : group.files().values()) total = Math.addExact(total, file.size());
		return total;
	}

	private String names(Iterable<String> values) {
		StringBuilder result = new StringBuilder();
		for (String value : values) {
			if (result.length() > 0) result.append(", ");
			GroupManifest.Group related = groups.get(value);
			result.append(related == null || related.displayName().isBlank() ? value : related.displayName());
		}
		return result.length() == 0 ? VersionedText.translatable("automodpack.ui.none").getString() : result.toString();
	}

	private boolean hasOptionalCategoryGroups(String category) {
		return groups.values().stream().anyMatch(group -> category.equals(group.tag()) && !group.required());
	}

	private boolean categorySelected(String category) {
		return hasOptionalCategoryGroups(category) && groups.entrySet().stream()
				.filter(entry -> category.equals(entry.getValue().tag()) && !entry.getValue().required())
				.allMatch(entry -> chosen.contains(entry.getKey()));
	}

	private static String categoryLabel(String category) {
		if (category == null || category.isBlank()) return VersionedText.translatable("automodpack.ui.general").getString();
		String[] words = category.replace('_', ' ').replace('-', ' ').split(" +");
		StringBuilder result = new StringBuilder();
		for (String word : words) {
			if (result.length() > 0) result.append(' ');
			if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.toString();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		// Header names the modpack when the server set one, so the player knows which pack they are editing.
		MutableComponent header = modpackName.isBlank()
				? VersionedText.translatable("automodpack.selection.title")
				: VersionedText.literal(modpackName + " – ").append(VersionedText.translatable("automodpack.selection.title"));
		if (managerEntry && !isActiveModpack()) header = VersionedText.literal(modpackName + " – ").append(VersionedText.translatable("automodpack.packManager.reviewSwitch"));
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, header.getString(), this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 18,
				TextColors.WHITE);
		if (saved) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.saved").withStyle(ChatFormatting.GREEN), this.width / 2, this.height / 2 - 30,
					TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.restartRequired").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height / 2 - 15,
					TextColors.WHITE);
			// updateSelectedModpackOnLaunch skips reconciliation entirely on the next launch, so a
			// restart alone will not apply this selection change; say so instead of implying it will.
			if (clientConfig != null && !clientConfig.updateSelectedModpackOnLaunch) {
				drawCenteredTextWithShadow(matrices, this.font,
						VersionedText.translatable("automodpack.selection.updateOnLaunchDisabled").withStyle(ChatFormatting.RED), this.width / 2,
						this.height / 2 - 45, TextColors.WHITE);
			}
		} else {
			MutableComponent description = managerEntry && !isActiveModpack()
					? VersionedText.translatable("automodpack.packManager.switchDescription")
					: VersionedText.translatable("automodpack.selection.description");
			drawCenteredTextWithShadow(matrices, this.font, description.withStyle(ChatFormatting.GRAY),
					this.width / 2, 32, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.platformSummary", ClientPlatform.current().id(), resolution.selectedGroups().size())
					.withStyle(ChatFormatting.GRAY), this.width / 2, 43, TextColors.WHITE);
			if (resolutionErrors.isEmpty() && (pendingUpdater == null || pendingUpdater.getSourceAvailability().totalFiles() == 0) && !rows.isEmpty()) drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.selection.categoryExplanation").getString(), panelWidth(ROW_WIDTH) - 20)).withStyle(ChatFormatting.DARK_GRAY), this.width / 2, 55, TextColors.WHITE);
			if (!resolutionErrors.isEmpty()) {
				drawCenteredTextWithShadow(matrices, this.font,
						VersionedText.literal(truncateToWidth(this.font, resolutionErrors.get(0), this.width - 20)).withStyle(ChatFormatting.RED),
						this.width / 2, 54, TextColors.WHITE);
			} else if (pendingUpdater != null && pendingUpdater.getSourceAvailability().totalFiles() > 0) {
				ModpackUpdater.SourceAvailability availability = pendingUpdater.getSourceAvailability();
				String sourceStatus = VersionedText.translatable(availability.cancelled()
						? "automodpack.selection.sourcesCancelled"
						: !availability.complete()
								? "automodpack.selection.sourcesResolving"
								: "automodpack.selection.sourcesResolved", availability.resolvedFiles(), availability.totalFiles()).getString();
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, sourceStatus, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 54,
						TextColors.WHITE);
			}
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}

	private record Row(String section, String groupId, String tagId) {}

	private record ManagementAction(ManagementKind kind, MutableComponent label, Runnable action) {}

	private enum ManagementKind { REMOVE, RECOVERY, QUARANTINE, LOCAL_MODS, HISTORY, MANAGER }
}
