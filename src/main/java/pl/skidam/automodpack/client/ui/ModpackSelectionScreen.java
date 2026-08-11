package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.QuarantineArchive;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.PageLayout;

/**
 * Lets the player pick which optional groups of a modpack they want. Deliberately built out of
 * plain buttons rather than a scrolling list widget: buttons are the one widget whose API is
 * stable across every Minecraft version this mod targets.
 *
 * Changes only take effect on the next launch, because mods are loaded during preload.
 */
public class ModpackSelectionScreen extends VersionedScreen {

	private static final int ROW_HEIGHT = 24;
	private static final int ROW_WIDTH = 500;
	private static final int BOTTOM_CONTROLS_GAP = 8;

	private final Screen parent;
	private final GroupManifest manifest;
	private final String modpackId;
	private final String modpackName;
	private final Map<String, GroupManifest.Group> groups;
	private final ClientStorage storage;
	private final ClientSelectionStore selectionStore;
	private final SelectionIntent expectedSelection;
	private final SelectionIntent initialSelection;
	private final Consumer<SelectionIntent> selectionAction;
	private final Runnable cancelAction;
	private final ModpackUpdater pendingUpdater;
	private final boolean managerEntry;
	private final boolean openedFromManager;
	private final boolean showManagement;
	private final boolean activeModpack;
	private final GenerationRecord localRecord;

	// What the player has actually ticked; resolved is what that implies once required groups,
	// dependencies, conflicts and platform rules are applied.
	private final Set<String> chosen = new LinkedHashSet<>();
	private final Set<String> chosenCategories = new LinkedHashSet<>();
	private final Set<String> excluded = new LinkedHashSet<>();
	private ResolvedSelection resolution;
	private String resolutionError = "";
	private final List<Row> rows = new ArrayList<>();

	private int page = 0;
	private boolean saved = false;
	private boolean closed;
	private boolean managementInFlight;
	private boolean switchInFlight;
	private Button recoveryButton;
	private Button quarantineButton;
	private Button historyButton;
	private Button filesButton;
	private Button saveButton;

	public ModpackSelectionScreen(Screen parent, GroupManifest manifest) {
		this(parent, manifest, null, null, null, () -> {}, null, false, false, null);
	}

	public ModpackSelectionScreen(Screen parent, ModpackUpdater updater, Consumer<SelectionIntent> selectionAction) {
		this(parent, updater.getSelectedTarget().manifest(), updater.getSelectedTarget().expectedPriorIntent(), updater.getSelectedTarget().selection().intent(), selectionAction, () -> {}, updater, false, false, null);
	}

	public static ModpackSelectionScreen repair(Screen parent, GroupManifest manifest, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
		return new ModpackSelectionScreen(parent, manifest, savedSelection, savedSelection, selectionAction, cancelAction, null, false, false, null);
	}

	public static ModpackSelectionScreen forInstalledRecord(Screen parent, GenerationRecord record, boolean managerEntry) {
		return forInstalledRecord(parent, record, managerEntry, true);
	}

	static ModpackSelectionScreen forInstalledRecord(Screen parent, GenerationRecord record, boolean managerEntry, boolean showManagement) {
		return new ModpackSelectionScreen(parent, record.manifest(), null, null, null, () -> {}, null, managerEntry, true, showManagement, record);
	}

	private ModpackSelectionScreen(Screen parent, GroupManifest manifest, SelectionIntent expectedSelection, SelectionIntent initialSelection,
			Consumer<SelectionIntent> selectionAction, Runnable cancelAction, ModpackUpdater pendingUpdater, boolean managerEntry, boolean openedFromManager,
			GenerationRecord localRecord) {
		this(parent, manifest, expectedSelection, initialSelection, selectionAction, cancelAction, pendingUpdater, managerEntry, openedFromManager, true, localRecord);
	}

	private ModpackSelectionScreen(Screen parent, GroupManifest manifest, SelectionIntent expectedSelection, SelectionIntent initialSelection,
			Consumer<SelectionIntent> selectionAction, Runnable cancelAction, ModpackUpdater pendingUpdater, boolean managerEntry, boolean openedFromManager,
			boolean showManagement, GenerationRecord localRecord) {
		super(VersionedText.translatable("automodpack.selection.title"));
		this.parent = parent;
		this.manifest = Objects.requireNonNull(manifest);
		this.modpackId = manifest.modpackId();
		this.modpackName = manifest.modpackName();
		this.groups = manifest.groups();
		this.storage = ClientStorage.fromGameDirectory(GameDirectory.current());
		this.selectionStore = new ClientSelectionStore(storage.selectionFile());
		this.expectedSelection = expectedSelection == null && initialSelection == null
				? selectionStore.get(modpackId).orElse(null)
				: expectedSelection;
		this.selectionAction = selectionAction;
		this.cancelAction = cancelAction;
		this.pendingUpdater = pendingUpdater;
		this.managerEntry = managerEntry;
		this.openedFromManager = openedFromManager;
		this.showManagement = showManagement;
		this.activeModpack = activeModpack(storage, modpackId);
		this.localRecord = localRecord;
		SelectionIntent initial = initialSelection != null
				? initialSelection
				: this.expectedSelection == null ? GroupSelectionResolver.defaultIntent(manifest) : this.expectedSelection;
		this.initialSelection = initial;
		this.chosen.addAll(initial.requestedGroups());
		this.chosenCategories.addAll(initial.requestedCategories());
		this.excluded.addAll(initial.excludedGroups());
		try {
		this.resolution = this.expectedSelection == null && initialSelection == null
					? GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.current())
					: GroupSelectionResolver.resolve(manifest, initial, ClientPlatform.current());
		} catch (SelectionResolutionException e) {
			this.resolution = Objects.requireNonNull(e.resolution(), "Invalid selection did not include a partial resolution");
			this.resolutionError = VersionedText.translatable("automodpack.selection.savedInvalid").getString();
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
			return parent;
		}

		GenerationRecord record = activeGeneration(modpackId);
		GroupManifest manifest = record == null ? null : record.manifest();
		if (manifest == null) {
			return parent;
		}

		return new ModpackSelectionScreen(parent, manifest, null, null, null, () -> {}, null, false, false, record);
	}

	public static boolean hasModpackManagement() {
		return hasActiveModpackManagement() || hasInstalledModpacks();
	}

	public static boolean hasActiveModpackManagement() {
		return modpackHasGeneration(clientConfig == null ? null : clientConfig.selectedModpackId);
	}

	public static Screen managementScreen(Screen parent) {
		return hasActiveModpackManagement() ? forSelectedModpack(parent) : new InstalledModpacksScreen(parent);
	}

	private static boolean hasInstalledModpacks() {
		try {
			ClientStorage storage = ClientStorage.fromGameDirectory(GameDirectory.current());
			return !new ClientGenerationStore(storage).installedRecords().isEmpty();
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static boolean modpackHasGeneration(String modpackId) {
		if (modpackId == null || modpackId.isBlank()) return false;
		return activeGeneration(modpackId) != null;
	}

	private static GenerationRecord activeGeneration(String modpackId) {
		try {
			ClientStorage storage = ClientStorage.fromGameDirectory(GameDirectory.current());
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !modpackId.equals(state.modpackId)) return null;
			return new ClientGenerationStore(storage).read(state.generationId).orElse(null);
		} catch (IOException | RuntimeException e) {
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
		List<ManagementAction> managementActions = selectionAction == null && showManagement ? managementActions() : List.of();
		int managementRows = managementRowCount(managementActions.size());
		int reservedManagementRows = Math.max(1, managementRows);
		// Keep page breaks and list-to-control spacing stable when a flow hides the management row.
		int actionY = this.height - 24;
		int managementTop = actionY - managementRows * ROW_HEIGHT;
		int listBottomWithoutPagination = actionY - reservedManagementRows * ROW_HEIGHT - BOTTOM_CONTROLS_GAP;
		int rowsWithoutPagination = Math.max(1, (listBottomWithoutPagination - listTop) / ROW_HEIGHT);
		boolean showPagination = rows.size() > rowsWithoutPagination;
		int paginationY = showPagination ? managementTop - ROW_HEIGHT : -1;
		int listBottom = showPagination ? paginationY - BOTTOM_CONTROLS_GAP : listBottomWithoutPagination;
		int rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		List<PageLayout.Range> pages = PageLayout.paginate(rows.size(), rowsPerPage, categoryRanges());

		int pageCount = pages.size();
		if (page >= pageCount) page = pageCount - 1;

		int rowWidth = panelWidth(ROW_WIDTH);
		int x = panelLeft(ROW_WIDTH);
		PageLayout.Range visibleRows = pages.get(page);
		int start = visibleRows.start();

		for (int i = start; i < visibleRows.endExclusive(); i++) {
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

		if (showPagination) {
			int paginationWidth = actionButtonWidth(ROW_WIDTH, 3);
			Button previous = buttonWidget(centeredActionButtonX(ROW_WIDTH, 3, 3, 0), paginationY, paginationWidth, 20, VersionedText.translatable("automodpack.ui.previous"), press -> {
				if (page > 0) {
					page--;
					rebuild();
				}
			});
			previous.active = page > 0;
			this.addRenderableWidget(previous);
			Button pageLabel = buttonWidget(centeredActionButtonX(ROW_WIDTH, 3, 3, 1), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			Button next = buttonWidget(centeredActionButtonX(ROW_WIDTH, 3, 3, 2), paginationY, paginationWidth, 20, VersionedText.translatable("automodpack.ui.next"), press -> {
				if (page < pageCount - 1) {
					page++;
					rebuild();
				}
			});
			next.active = page < pageCount - 1;
			this.addRenderableWidget(next);
		}

		if (!managementActions.isEmpty()) {
			for (int index = 0; index < managementActions.size(); index++) {
				ManagementAction action = managementActions.get(index);
				int row = index / 3;
				int rowStart = row * 3;
				int rowCount = Math.min(3, managementActions.size() - rowStart);
				int managementWidth = actionButtonWidth(ROW_WIDTH, rowCount);
				Button button = buttonWidget(centeredActionButtonX(ROW_WIDTH, rowCount, rowCount, index - rowStart), managementTop + row * ROW_HEIGHT,
						managementWidth, 20, action.label(), press -> action.action().run());
				this.addRenderableWidget(button);
				if (action.kind() == ManagementKind.RECOVERY) this.recoveryButton = button;
				if (action.kind() == ManagementKind.QUARANTINE) this.quarantineButton = button;
				if (action.kind() == ManagementKind.HISTORY) this.historyButton = button;
				if (action.kind() == ManagementKind.FILES) this.filesButton = button;
			}
			updateManagementButtons();
		}

		int actionWidth = actionButtonWidth(ROW_WIDTH, 3);
		this.addRenderableWidget(buttonWidget(actionButtonX(ROW_WIDTH, 3, 0), actionY, actionWidth, 20, VersionedText.translatable("automodpack.selection.cancel"), press -> back()));

		this.addRenderableWidget(buttonWidget(actionButtonX(ROW_WIDTH, 3, 1), actionY, actionWidth, 20, VersionedText.translatable("automodpack.selection.reset"), press -> {
			SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);
			chosen.clear();
			chosen.addAll(defaults.requestedGroups());
			chosenCategories.clear();
			excluded.clear();
			reresolveDefault();
		}));

		String saveLabel = selectionAction != null ? "automodpack.selection.preview" : managerEntry && !activeModpack ? "automodpack.packManager.reviewSwitch" : "automodpack.selection.save";
		this.saveButton = buttonWidget(actionButtonX(ROW_WIDTH, 3, 2), actionY, actionWidth, 20,
				VersionedText.translatable(saveLabel).withStyle(ChatFormatting.BOLD), press -> save());
		this.saveButton.active = canSave();
		this.addRenderableWidget(this.saveButton);
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
		SelectionIntent previous = currentIntent();
		SelectionIntent next = GroupSelectionResolver.preferCategory(manifest, previous, category, ClientPlatform.current());
		Set<String> preferred = next.requestedCategories().contains(category) ? categoryGroups(category) : Set.of();
		applySelectionChange(next, preferred, categoryLabel(category));
	}

	/**
	 * A group inside a selected category becomes an explicit exclusion when it is not otherwise required.
	 * Direct choices remain in the intent when they conflict, so the player can remove either choice explicitly.
	 */
	private void toggle(String groupId) {
		GroupManifest.Group group = groups.get(groupId);
		if (group == null) return;
		if (isMandatory(manifest, group)) return;
		SelectionIntent previous = currentIntent();
		SelectionIntent next = GroupSelectionResolver.prefer(manifest, previous, groupId, ClientPlatform.current());
		Set<String> preferred = resolution.selectedGroups().contains(groupId) ? Set.of() : Set.of(groupId);
		applySelectionChange(next, preferred, displayName(groupId));
	}

	private void applySelectionChange(SelectionIntent next, Set<String> preferredGroups, String preferredName) {
		try {
			ResolvedSelection nextResolution = GroupSelectionResolver.resolve(manifest, next, ClientPlatform.current());
			applyResolved(next, nextResolution);
		} catch (SelectionResolutionException exception) {
			ConflictReplacement replacement = conflictReplacement(next, preferredGroups, exception.resolution());
			if (replacement != null) {
				ScreenImpl.setScreen(new FeatureConflictScreen(this, preferredName, names(replacement.conflictingGroups()), () -> applySelectionChange(replacement.intent(), Set.of(), preferredName)));
				return;
			}
			resolutionError = preferredGroups.isEmpty()
					? VersionedText.translatable("automodpack.selection.changeInvalid").getString()
					: VersionedText.translatable("automodpack.selection.cannotSelect", preferredName).getString();
			rebuild();
		}
	}

	private ConflictReplacement conflictReplacement(SelectionIntent next, Set<String> preferredGroups, ResolvedSelection partial) {
		if (preferredGroups.isEmpty() || partial == null) return null;
		Set<String> conflicts = new TreeSet<>();
		for (String preferred : preferredGroups) {
			GroupResolution explanation = partial.explanation(preferred);
			if (explanation != null && explanation.status() == GroupResolution.Status.CONFLICT) conflicts.addAll(explanation.relatedGroups());
		}
		conflicts.removeAll(preferredGroups);
		if (conflicts.isEmpty()) return null;

		Set<String> requestedGroups = new TreeSet<>(next.requestedGroups());
		requestedGroups.removeIf(groupId -> !preferredGroups.contains(groupId) && selectsAny(new SelectionIntent(Set.of(groupId)), conflicts));
		Set<String> requestedCategories = new TreeSet<>(next.requestedCategories());
		Set<String> expandedGroups = new TreeSet<>();
		for (String category : new TreeSet<>(requestedCategories)) {
			if (!selectsAny(new SelectionIntent(Set.of(), Set.of(category), Set.of()), conflicts)) continue;
			requestedCategories.remove(category);
			for (String groupId : categoryGroups(category)) if (!conflicts.contains(groupId)) expandedGroups.add(groupId);
		}
		requestedGroups.addAll(expandedGroups);
		SelectionIntent replacement = new SelectionIntent(requestedGroups, requestedCategories, next.excludedGroups());
		try {
			GroupSelectionResolver.resolve(manifest, replacement, ClientPlatform.current());
			return new ConflictReplacement(replacement, conflicts);
		} catch (SelectionResolutionException ignored) {
			return null;
		}
	}

	private boolean selectsAny(SelectionIntent intent, Set<String> groupIds) {
		try {
			return !Collections.disjoint(GroupSelectionResolver.resolve(manifest, intent, ClientPlatform.current()).selectedGroups(), groupIds);
		} catch (SelectionResolutionException exception) {
			return exception.resolution() != null && !Collections.disjoint(exception.resolution().selectedGroups(), groupIds);
		}
	}

	private Set<String> categoryGroups(String category) {
		Set<String> result = new TreeSet<>();
		for (var entry : groups.entrySet()) if (category.equals(entry.getValue().category()) && !entry.getValue().required() && entry.getValue().supports(ClientPlatform.current())) result.add(entry.getKey());
		return Set.copyOf(result);
	}

	private void applyResolved(SelectionIntent intent, ResolvedSelection resolved) {
		applyIntent(intent);
		resolution = resolved;
		resolutionError = "";
		rebuild();
	}

	private void applyIntent(SelectionIntent intent) {
		chosen.clear();
		chosen.addAll(intent.requestedGroups());
		chosenCategories.clear();
		chosenCategories.addAll(intent.requestedCategories());
		excluded.clear();
		excluded.addAll(intent.excludedGroups());
	}

	private void reresolve() {
		resolution = GroupSelectionResolver.resolve(manifest, currentIntent(), ClientPlatform.current());
		resolutionError = "";
		rebuild();
	}

	private void reresolveDefault() {
		resolution = GroupSelectionResolver.resolveDefault(manifest, ClientPlatform.current());
		resolutionError = "";
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
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			return null;
		}
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
				new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			}
		});
	}

	private void requestHistory() {
		if (!beginManagement()) return;
		try {
			GenerationHistoryController.open(storage, modpackId, historyGenerationId(), modpackName, this::endManagement);
		} catch (Exception e) {
			endManagement();
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
		}
	}

	private void requestQuarantine() {
		if (!beginManagement()) return;
		ScreenImpl.setScreen(new QuarantineArchiveScreen(this, storage, modpackId, modpackName, activeModpack, this::endManagement));
	}

	private void requestFiles() {
		GenerationRecord generation = localRecord == null ? activeGeneration(modpackId) : localRecord;
		if (generation == null) return;
		Map<String, String> featureNames = new TreeMap<>();
		generation.manifest().groups().forEach((groupId, group) -> featureNames.put(groupId, displayName(groupId)));
		ScreenImpl.setScreen(new ChangeBrowserScreen(this,
				VersionedText.translatable("automodpack.files.title", modpackName),
				VersionedText.translatable("automodpack.files.description"), ChangeSet.catalogue(generation.manifest()), featureNames));
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
		if (recoveryButton != null) recoveryButton.active = !managementInFlight;
		if (quarantineButton != null) quarantineButton.active = !managementInFlight;
		if (historyButton != null) historyButton.active = !managementInFlight;
		if (filesButton != null) filesButton.active = !managementInFlight;
	}

	private List<ManagementAction> managementActions() {
		List<ManagementAction> actions = new ArrayList<>();
		if (activeModpack && hasRecoveryArchive()) actions.add(new ManagementAction(ManagementKind.RECOVERY, VersionedText.translatable("automodpack.management.recovery"), this::requestRecovery));
		if (hasQuarantineArchive()) actions.add(new ManagementAction(ManagementKind.QUARANTINE, VersionedText.translatable("automodpack.management.quarantine"), this::requestQuarantine));
		if (hasHistory()) actions.add(new ManagementAction(ManagementKind.HISTORY, VersionedText.translatable("automodpack.management.history"), this::requestHistory));
		if (localRecord != null || activeGeneration(modpackId) != null) actions.add(new ManagementAction(ManagementKind.FILES, VersionedText.translatable("automodpack.management.files"), this::requestFiles));
		if (hasInstalledPacks()) actions.add(new ManagementAction(ManagementKind.MANAGER, VersionedText.translatable("automodpack.packManager.switch"), this::requestPackManager));
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

	private static int managementRowCount(int actionCount) {
		return actionCount == 0 ? 0 : (actionCount + 2) / 3;
	}

	private boolean hasHistory() {
		try {
			ClientGenerationStore generationStore = new ClientGenerationStore(storage);
			String generationId = historyGenerationId();
			List<GenerationRecord> availableLineage = generationStore.availableLineage(modpackId, generationId);
			List<GenerationPatchNoteHistory.Entry> patchNotesHistory = generationStore.patchNotesHistory(generationId);
			return availableLineage.size() > 1 || GenerationPatchNoteHistory.containsNotes(patchNotesHistory);
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

	private boolean hasInstalledPacks() {
		try {
			return !new ClientGenerationStore(storage).installedRecords().isEmpty();
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
		ScreenImpl.setScreen(openedFromManager ? parent : new InstalledModpacksScreen(this, modpackId));
	}

	private void save() {
		SelectionIntent target = currentIntent();
		if (!resolutionError.isEmpty()) return;
		if (selectionAction != null) {
			try {
				selectionAction.accept(target);
			} catch (RuntimeException e) {
				new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			}
			return;
		}
		if (localRecord != null) {
			startCachedSwitch(target);
			return;
		}
		try {
			selectionStore.compareAndSet(modpackId, expectedSelection, target);
			saved = true;
			rebuild();
		} catch (IOException e) {
			new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
		}
	}

	private void startCachedSwitch(SelectionIntent targetIntent) {
		if (switchInFlight) return;
		switchInFlight = true;
		InstalledModpackSwitch.start(storage, localRecord, expectedSelection, targetIntent, modpackName, true, () -> switchInFlight = false);
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
		for (var entry : groups.entrySet()) if (entry.getValue().category().isEmpty()) rows.add(new Row("", entry.getKey(), null));
		Set<String> categories = new TreeSet<>();
		for (GroupManifest.Group group : groups.values()) if (!group.category().isEmpty()) categories.add(group.category());
		for (String category : categories) {
			rows.add(new Row(categoryLabel(category), null, category));
			for (var entry : groups.entrySet()) if (category.equals(entry.getValue().category())) rows.add(new Row("", entry.getKey(), category));
		}
	}

	private List<PageLayout.Range> categoryRanges() {
		List<PageLayout.Range> ranges = new ArrayList<>();
		for (int start = 0; start < rows.size(); start++) {
			Row row = rows.get(start);
			if (row.groupId() != null || row.tagId() == null) continue;
			int end = start + 1;
			while (end < rows.size() && rows.get(end).groupId() != null && row.tagId().equals(rows.get(end).tagId())) end++;
			ranges.add(new PageLayout.Range(start, end));
		}
		return List.copyOf(ranges);
	}

	private MutableComponent sectionLabel(Row row) {
		if (row.tagId() == null) return VersionedText.literal(row.section()).withStyle(ChatFormatting.BOLD);
		String title = categoryLabel(row.tagId());
		boolean selected = categorySelected(row.tagId());
		return VersionedText.literal(truncateToWidth(this.font, (selected ? "[x] " : "[ ] ") + title, panelWidth(ROW_WIDTH) - 12))
				.withStyle(ChatFormatting.BOLD, selected ? ChatFormatting.GREEN : ChatFormatting.GRAY);
	}

	/** The group's metadata and the resolver explanation, shown on hover. */
	private MutableComponent rowTooltip(String groupId, GroupManifest.Group group) {
		if (group == null) return null;
		StringBuilder tooltip = new StringBuilder();
		if (!group.description().isBlank()) tooltip.append(group.description());
		GroupResolution explanation = resolution.explanation(groupId);
		if (explanation != null) appendTooltipLine(tooltip, resolutionText(explanation));
		appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.category", categoryLabel(group)).getString());
		if (group.required()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.requiredAlways").getString());
		if (group.defaultSelected()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.defaultSelected").getString());
		if (resolution.forcedGroups().contains(groupId)) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.forced").getString());
		if (!group.requires().isEmpty()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.requires", names(group.requires())).getString());
		if (!group.breaksWith().isEmpty()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.conflicts", names(group.breaksWith())).getString());
		appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.files", group.files().size(), UiFormat.formatSize(groupBytes(group))).getString());
		if (!group.supports(ClientPlatform.current())) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.unavailableOn", ClientPlatform.current().id()).getString());
		return VersionedText.literal(tooltip.toString()).withStyle(ChatFormatting.GRAY);
	}

	private String resolutionText(GroupResolution groupResolution) {
		return switch (groupResolution.status()) {
			case SELECTED -> selectedResolutionText(groupResolution);
			case AVAILABLE -> VersionedText.translatable("automodpack.selection.status.available").getString();
			case UNAVAILABLE -> VersionedText.translatable("automodpack.selection.unavailableOn", ClientPlatform.current().id()).getString();
			case BLOCKED -> groupResolution.relatedGroups().isEmpty()
					? VersionedText.translatable("automodpack.selection.status.dependencyUnavailable").getString()
					: VersionedText.translatable("automodpack.selection.blockedBy", names(groupResolution.relatedGroups())).getString();
			case EXCLUDED -> VersionedText.translatable("automodpack.selection.status.excluded").getString();
			case CONFLICT -> VersionedText.translatable("automodpack.selection.conflictsWith", names(groupResolution.relatedGroups())).getString();
			case STALE -> VersionedText.translatable("automodpack.selection.status.stale").getString();
		};
	}

	private String selectedResolutionText(GroupResolution groupResolution) {
		if (groupResolution.reasons().contains(GroupResolution.Reason.REQUIRED)) return VersionedText.translatable("automodpack.selection.requiredAlways").getString();
		if (groupResolution.reasons().contains(GroupResolution.Reason.FORCED)) return VersionedText.translatable("automodpack.selection.forced").getString();
		if (groupResolution.reasons().contains(GroupResolution.Reason.DEPENDENCY)) return VersionedText.translatable("automodpack.selection.dependencyNamed", names(groupResolution.relatedGroups())).getString();
		if (groupResolution.reasons().contains(GroupResolution.Reason.DEFAULT_SELECTED)) return VersionedText.translatable("automodpack.selection.defaultSelected").getString();
		return VersionedText.translatable("automodpack.selection.status.selected").getString();
	}

	private String categoryLabel(GroupManifest.Group group) {
		if (group.category().isEmpty()) return VersionedText.translatable("automodpack.ui.general").getString();
		return categoryLabel(group.category());
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
				return rowLabel(formatRowLabel("[+] ", name, metrics, VersionedText.translatable("automodpack.selection.status.requiredBySelection", names(explanation.relatedGroups())).getString()), ChatFormatting.AQUA);
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

	private String displayName(String groupId) {
		GroupManifest.Group group = groups.get(groupId);
		return group == null || group.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownFeature").getString() : group.displayName();
	}

	private boolean hasOptionalCategoryGroups(String category) {
		return groups.values().stream().anyMatch(group -> category.equals(group.category()) && !group.required());
	}

	private boolean categorySelected(String category) {
		return chosenCategories.contains(category);
	}

	private SelectionIntent currentIntent() {
		return new SelectionIntent(chosen, chosenCategories, excluded);
	}

	private boolean canSave() {
		return resolutionError.isEmpty() && (selectionAction != null || managerEntry && !activeModpack || !initialSelection.equals(currentIntent()));
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
			if (resolutionError.isEmpty() && (pendingUpdater == null || pendingUpdater.getSourceAvailability().totalFiles() == 0) && !rows.isEmpty()) drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.selection.categoryExplanation").getString(), panelWidth(ROW_WIDTH) - 20)).withStyle(ChatFormatting.DARK_GRAY), this.width / 2, 55, TextColors.WHITE);
			if (!resolutionError.isEmpty()) {
				drawCenteredTextWithShadow(matrices, this.font,
						VersionedText.literal(truncateToWidth(this.font, resolutionError, this.width - 20)).withStyle(ChatFormatting.RED),
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

	private record ConflictReplacement(SelectionIntent intent, Set<String> conflictingGroups) {}

	private record ManagementAction(ManagementKind kind, MutableComponent label, Runnable action) {}

	private enum ManagementKind { RECOVERY, QUARANTINE, HISTORY, FILES, MANAGER }
}
