package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.GroupSelectionList;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Lets the player pick which optional groups of a modpack they want. Changes only take effect on the next launch, because mods are loaded during preload.
 */
public class ModpackSelectionScreen extends VersionedScreen {

	private static final int ROW_WIDTH = 500;
	private static final int PLATFORM_BUTTON_WIDTH = 90;

	private final Screen parent;
	private final GroupManifest manifest;
	private final String modpackId;
	private final String modpackName;
	private final Map<String, GroupManifest.Group> groups;
	private final InstalledModpackController controller;
	private final SelectionIntent expectedSelection;
	private final SelectionIntent initialSelection;
	private final Consumer<SelectionIntent> selectionAction;
	private final Runnable cancelAction;
	private final ModpackUpdater pendingUpdater;
	private final boolean managerEntry;
	private final boolean openedFromManager;
	private final boolean showManagement;
	private final boolean activeModpack;
	private final PackDocument localRecord;
	private final ClientPlatform detectedPlatform;
	private ClientPlatform platformOverride;

	// What the player has actually ticked; resolved is what that implies once required groups,
	// dependencies, conflicts and platform rules are applied.
	private final Set<String> chosen = new LinkedHashSet<>();
	private final Set<String> chosenCategories = new LinkedHashSet<>();
	private final Set<String> excluded = new LinkedHashSet<>();
	private ResolvedSelection resolution;
	private String resolutionError = "";
	private final List<Row> rows = new ArrayList<>();

	private boolean saved = false;
	private boolean closed;
	private boolean switchInFlight;
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

	public static ModpackSelectionScreen forInstalledRecord(Screen parent, PackDocument record, boolean managerEntry) {
		return forInstalledRecord(parent, record, managerEntry, true);
	}

	static ModpackSelectionScreen forInstalledRecord(Screen parent, PackDocument record, boolean managerEntry, boolean showManagement) {
		return new ModpackSelectionScreen(parent, record.manifest(), null, null, null, () -> {}, null, managerEntry, true, showManagement, record);
	}

	private ModpackSelectionScreen(Screen parent, GroupManifest manifest, SelectionIntent expectedSelection, SelectionIntent initialSelection,
			Consumer<SelectionIntent> selectionAction, Runnable cancelAction, ModpackUpdater pendingUpdater, boolean managerEntry, boolean openedFromManager,
			PackDocument localRecord) {
		this(parent, manifest, expectedSelection, initialSelection, selectionAction, cancelAction, pendingUpdater, managerEntry, openedFromManager, true, localRecord);
	}

	private ModpackSelectionScreen(Screen parent, GroupManifest manifest, SelectionIntent expectedSelection, SelectionIntent initialSelection,
			Consumer<SelectionIntent> selectionAction, Runnable cancelAction, ModpackUpdater pendingUpdater, boolean managerEntry, boolean openedFromManager,
			boolean showManagement, PackDocument localRecord) {
		super(VersionedText.translatable("automodpack.selection.title"));
		this.parent = parent;
		this.manifest = Objects.requireNonNull(manifest);
		this.modpackId = manifest.modpackId();
		this.modpackName = manifest.modpackName();
		this.groups = manifest.groups();
		this.controller = new InstalledModpackController();
		this.expectedSelection = expectedSelection == null && initialSelection == null
				? controller.savedSelection(modpackId)
				: expectedSelection;
		this.selectionAction = selectionAction;
		this.cancelAction = cancelAction;
		this.pendingUpdater = pendingUpdater;
		this.managerEntry = managerEntry;
		this.openedFromManager = openedFromManager;
		this.showManagement = showManagement;
		this.activeModpack = controller.activeRecord(modpackId) != null;
		this.localRecord = localRecord;
		SelectionIntent initial = initialSelection != null
				? initialSelection
				: this.expectedSelection == null ? GroupSelectionResolver.defaultIntent(manifest) : this.expectedSelection;
		this.initialSelection = initial;
		this.detectedPlatform = ClientPlatform.current();
		this.platformOverride = initial.platform() == null || initial.platform() == detectedPlatform ? null : initial.platform();
		this.chosen.addAll(initial.requestedGroups());
		this.chosenCategories.addAll(initial.requestedCategories());
		this.excluded.addAll(initial.excludedGroups());
		try {
			this.resolution = this.expectedSelection == null && initialSelection == null
					? GroupSelectionResolver.resolveDefault(manifest, effectivePlatform())
					: GroupSelectionResolver.resolve(manifest, initial, effectivePlatform());
		} catch (SelectionResolutionException e) {
			this.resolution = Objects.requireNonNull(e.resolution(), "Invalid selection did not include a partial resolution");
			this.resolutionError = VersionedText.translatable("automodpack.selection.savedInvalid").getString();
		}
		rebuildRows();
	}

	@Override
	protected void init() {
		super.init();

		// Once saved, the screen becomes a restart prompt: the new selection only takes effect after
		// a relaunch, so there is nothing left to toggle here.
		if (saved) {
			this.addActionAreaAt(ActionAreaLayout.FOOTER_RAIL, this.height / 2 + 20, actionRow(ActionAreaLayout.RowKind.FOOTER,
					secondaryAction(VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)),
					primaryAction(VersionedText.translatable("automodpack.selection.restartNow").withStyle(ChatFormatting.BOLD), press -> this.minecraft.stop())));
			return;
		}

		int actionY = this.height - 28;
		String saveLabel = selectionAction != null ? "automodpack.selection.preview" : managerEntry && !activeModpack ? "automodpack.packManager.reviewSwitch" : "automodpack.selection.save";
		SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), press -> back()),
				optionalAction(VersionedText.translatable("automodpack.selection.reset"), press -> {
					chosen.clear();
					chosen.addAll(defaults.requestedGroups());
					chosenCategories.clear();
					excluded.clear();
					reresolveDefault();
				}),
				primaryAction(VersionedText.translatable(saveLabel), press -> save()));
		List<Button> actionButtons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, actionY, footer);
		this.saveButton = actionButtons.get(2);
		this.saveButton.active = canSave();
		if (selectionAction == null && resolutionError.isEmpty() && !this.saveButton.active) setTooltip(this.saveButton, VersionedText.translatable("automodpack.selection.noChanges"));
		int listTop = 64;
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, actionY, footer) - 8;
		this.addRenderableWidget(new GroupSelectionList(this.minecraft, this.width, this.height, panelWidth(ROW_WIDTH), listTop, listBottom, listItems(), this::onListToggle, this::onListInspect));
		Button platformButton = buttonWidget(panelLeft(ROW_WIDTH) + panelWidth(ROW_WIDTH) - PLATFORM_BUTTON_WIDTH, 24, PLATFORM_BUTTON_WIDTH, 20,
				VersionedText.literal(platformLabel(effectivePlatform())), press -> cyclePlatform());
		setTooltip(platformButton, VersionedText.translatable("automodpack.selection.platformTooltip"));
		this.addRenderableWidget(platformButton);
	}

	private List<GroupSelectionList.Item> listItems() {
		List<GroupSelectionList.Item> items = new ArrayList<>();
		for (Row row : rows) {
			if (row.groupId() == null) {
				boolean canToggle = row.categoryId() != null && hasOptionalCategoryGroups(row.categoryId());
				Component tooltip = canToggle ? VersionedText.translatable("automodpack.selection.categoryTooltip").withStyle(ChatFormatting.GRAY) : null;
				items.add(new GroupSelectionList.Item(GroupSelectionList.Kind.HEADER, row.categoryId() == null ? "" : row.categoryId(), sectionLabel(row), tooltip, false, canToggle));
				continue;
			}
			GroupManifest.Group group = groups.get(row.groupId());
			boolean togglable = group != null && canToggle(row.groupId(), group);
			items.add(new GroupSelectionList.Item(GroupSelectionList.Kind.GROUP, row.groupId(), rowLabel(row.groupId(), group), rowTooltip(row.groupId(), group), resolution.selectedGroups().contains(row.groupId()),
					togglable));
		}
		return List.copyOf(items);
	}

	private void onListToggle(GroupSelectionList.Item item) {
		if (item.kind() == GroupSelectionList.Kind.HEADER) {
			if (!item.id().isBlank()) toggleCategory(item.id());
			return;
		}
		toggle(item.id());
	}

	private void onListInspect(GroupSelectionList.Item item) {
		if (item.kind() == GroupSelectionList.Kind.GROUP) inspect(item.id());
	}

	private void inspect(String groupId) {
		if (!groups.containsKey(groupId)) return;
		ScreenImpl.setScreen(new GroupInspectorScreen(this, manifest, groupId));
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
		GroupResolution explanation = resolution.resolution(groupId);
		if (explanation == null) return group.supports(effectivePlatform());
		if (explanation.selected() && (resolution.requiredGroups().contains(groupId) || resolution.forcedGroups().contains(groupId)
				|| resolution.dependencyGroups().contains(groupId)))
			return false;
		return group.supports(effectivePlatform()) || excluded.contains(groupId);
	}

	/** Toggling a category requests or removes its optional groups through the persisted group intent. */
	private void toggleCategory(String category) {
		SelectionIntent previous = currentIntent();
		// Direction mirrors the header glyph: when every optional group is already in, the click excludes them all.
		SelectionIntent resolved = categoryFullySelected(category)
				? GroupSelectionResolver.excludeCategory(manifest, previous, category, effectivePlatform())
				: GroupSelectionResolver.preferCategory(manifest, previous, category, effectivePlatform());
		SelectionIntent next = resolved.withPlatform(override());
		Set<String> preferred = next.requestedCategories().contains(category) ? categoryGroups(category) : Set.of();
		applySelectionChange(next, preferred, categoryLabel(category));
	}

	private boolean categoryFullySelected(String category) {
		long optional = optionalGroupCount(category);
		return optional > 0 && selectedOptionalGroupCount(category) == optional;
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
		SelectionIntent next = GroupSelectionResolver.prefer(manifest, previous, groupId, effectivePlatform()).withPlatform(override());
		Set<String> preferred = resolution.selectedGroups().contains(groupId) ? Set.of() : Set.of(groupId);
		applySelectionChange(next, preferred, displayName(groupId));
	}

	private void applySelectionChange(SelectionIntent next, Set<String> preferredGroups, String preferredName) {
		try {
			ResolvedSelection nextResolution = GroupSelectionResolver.resolve(manifest, next, effectivePlatform());
			applyResolved(next, nextResolution);
		} catch (SelectionResolutionException exception) {
			GroupSelectionResolver.ConflictReplacement replacement = GroupSelectionResolver.replaceConflicts(manifest, next, preferredGroups, effectivePlatform(), exception.resolution()).orElse(null);
			if (replacement != null) {
				ScreenImpl.setScreen(
						new FeatureConflictScreen(this, preferredName, names(replacement.conflictingGroups()), () -> applySelectionChange(replacement.intent().withPlatform(override()), Set.of(), preferredName)));
				return;
			}
			resolutionError = preferredGroups.isEmpty()
					? VersionedText.translatable("automodpack.selection.changeInvalid").getString()
					: VersionedText.translatable("automodpack.selection.cannotSelect", preferredName).getString();
			rebuild();
		}
	}

	private Set<String> categoryGroups(String category) {
		Set<String> result = new TreeSet<>();
		for (var entry : groups.entrySet()) if (category.equals(entry.getValue().category()) && !entry.getValue().required() && entry.getValue().supports(effectivePlatform())) result.add(entry.getKey());
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

	private void reresolveDefault() {
		resolution = GroupSelectionResolver.resolveDefault(manifest, effectivePlatform());
		resolutionError = "";
		rebuild();
	}

	private boolean isActiveModpack() {
		return activeModpack;
	}

	private void save() {
		SelectionIntent target = currentIntent();
		if (!resolutionError.isEmpty()) return;
		if (selectionAction != null) {
			try {
				selectionAction.accept(target);
			} catch (RuntimeException e) {
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
			}
			return;
		}
		if (localRecord != null) {
			startCachedSwitch(target);
			return;
		}
		try {
			controller.saveSelection(modpackId, expectedSelection, target);
			saved = true;
			rebuild();
		} catch (IOException e) {
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
		}
	}

	private void startCachedSwitch(SelectionIntent targetIntent) {
		if (switchInFlight) return;
		switchInFlight = true;
		controller.switchSelection(localRecord, expectedSelection, targetIntent, modpackName, () -> switchInFlight = false);
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

	private MutableComponent sectionLabel(Row row) {
		if (row.categoryId() == null) return VersionedText.literal(row.section()).withStyle(ChatFormatting.BOLD);
		String title = VersionedText.translatable("automodpack.selection.category", categoryLabel(row.categoryId())).getString();
		// The glyph names what the next click does: full = exclude all, empty = include all, partial = include the rest.
		long optional = optionalGroupCount(row.categoryId());
		long selected = selectedOptionalGroupCount(row.categoryId());
		boolean allSelected = optional > 0 && selected == optional;
		boolean noneSelected = selected == 0;
		String glyph = allSelected ? "[x]" : noneSelected ? "[ ]" : "[-]";
		String label = glyph + " " + title;
		if (selected > 0 && !allSelected) label += "  " + VersionedText.translatable("automodpack.selection.categoryPart", selected, optional).getString();
		return VersionedText.literal(truncateToWidth(this.font, label, panelWidth(ROW_WIDTH) - 12))
				.withStyle(ChatFormatting.BOLD, allSelected ? ChatFormatting.GREEN : noneSelected ? ChatFormatting.GRAY : ChatFormatting.YELLOW);
	}

	private long optionalGroupCount(String category) {
		return groups.values().stream().filter(group -> category.equals(group.category()) && !group.required()).count();
	}

	private long selectedOptionalGroupCount(String category) {
		return groups.entrySet().stream().filter(entry -> category.equals(entry.getValue().category()) && !entry.getValue().required() && resolution.selectedGroups().contains(entry.getKey())).count();
	}

	/** The group's metadata and the resolver explanation, shown on hover. */
	private MutableComponent rowTooltip(String groupId, GroupManifest.Group group) {
		if (group == null) return null;
		StringBuilder tooltip = new StringBuilder();
		if (!group.description().isBlank()) tooltip.append(group.description());
		GroupResolution explanation = resolution.resolution(groupId);
		if (explanation != null) appendTooltipLine(tooltip, resolutionText(explanation));
		appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.category", categoryLabel(group)).getString());
		if (group.required()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.requiredAlways").getString());
		if (group.defaultSelected()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.defaultSelected").getString());
		if (resolution.forcedGroups().contains(groupId)) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.forced").getString());
		if (!group.requires().isEmpty()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.requires", names(group.requires())).getString());
		if (!group.breaksWith().isEmpty()) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.conflicts", names(group.breaksWith())).getString());
		appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.files", group.files().size(), UiFormat.formatSize(groupBytes(group))).getString());
		if (!group.supports(effectivePlatform())) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.unavailableOn", effectivePlatform().id()).getString());
		return VersionedText.literal(tooltip.toString()).withStyle(ChatFormatting.GRAY);
	}

	private String resolutionText(GroupResolution groupResolution) {
		return switch (groupResolution.status()) {
			case SELECTED -> selectedResolutionText(groupResolution);
			case AVAILABLE -> VersionedText.translatable("automodpack.selection.status.available").getString();
			case UNAVAILABLE -> VersionedText.translatable("automodpack.selection.unavailableOn", effectivePlatform().id()).getString();
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
		if (group == null) return VersionedText.translatable("automodpack.browser.unknownFeature");

		String name = displayName(groupId);
		GroupResolution explanation = resolution.resolution(groupId);
		String metrics = UiFormat.plural(group.files().size(), "automodpack.selection.metrics", UiFormat.formatSize(groupBytes(group))).getString();
		String status = statusWord(explanation);
		if (isMandatory(manifest, group)) return rowLabel(formatRowLabel("[#] ", name, metrics, status), ChatFormatting.GRAY, status);
		if (explanation != null && explanation.reasons().contains(GroupResolution.Reason.EXPLICIT_REQUEST_UNAVAILABLE))
			return rowLabel(formatRowLabel("[-] ", name, metrics, status), ChatFormatting.RED, status);
		if (explanation != null && explanation.status() == GroupResolution.Status.UNAVAILABLE)
			return rowLabel(formatRowLabel("[-] ", name, metrics, status), ChatFormatting.RED, status);
		if (explanation != null && explanation.status() == GroupResolution.Status.BLOCKED)
			return rowLabel(formatRowLabel("[-] ", name, metrics, status), ChatFormatting.RED, status);
		if (explanation != null && explanation.status() == GroupResolution.Status.CONFLICT)
			return rowLabel(formatRowLabel("[!] ", name, metrics, status), ChatFormatting.RED, status);
		if (excluded.contains(groupId)) return rowLabel(formatRowLabel("[-] ", name, metrics, status), ChatFormatting.YELLOW, status);
		if (resolution.selectedGroups().contains(groupId)) {
			// A dependency lock is the load-bearing fact: the row cannot be unchecked while its dependent needs it.
			if (resolution.dependencyGroups().contains(groupId)) return rowLabel(formatRowLabel("[+] ", name, metrics, status), ChatFormatting.AQUA, status);
			if (chosen.contains(groupId)) return rowLabel(formatRowLabel("[x] ", name, metrics, status), ChatFormatting.GREEN, status);
			return rowLabel(formatRowLabel("[+] ", name, metrics, status), ChatFormatting.AQUA, status);
		}
		if (resolution.forcedGroups().contains(groupId)) return rowLabel(formatRowLabel("[>] ", name, metrics, status), ChatFormatting.AQUA, status);
		return group.defaultSelected()
				? rowLabel(formatRowLabel("[ ] ", name, metrics, status), ChatFormatting.YELLOW, status)
				: rowLabel(formatRowLabel("[ ] ", name, metrics, status), ChatFormatting.GRAY, status);
	}

	/** The row's state word in the surviving status keys; statuses whose explanation is a full sentence stay hover-only. */
	private String statusWord(GroupResolution explanation) {
		if (explanation == null) return "";
		return switch (explanation.status()) {
			case SELECTED -> explanation.reasons().contains(GroupResolution.Reason.REQUIRED) || explanation.reasons().contains(GroupResolution.Reason.FORCED)
					|| explanation.reasons().contains(GroupResolution.Reason.DEPENDENCY) || explanation.reasons().contains(GroupResolution.Reason.DEFAULT_SELECTED)
							? ""
							: VersionedText.translatable("automodpack.selection.status.selected").getString();
			case AVAILABLE -> VersionedText.translatable("automodpack.selection.status.available").getString();
			case BLOCKED -> explanation.relatedGroups().isEmpty() ? VersionedText.translatable("automodpack.selection.status.dependencyUnavailable").getString() : "";
			case EXCLUDED -> VersionedText.translatable("automodpack.selection.status.excluded").getString();
			case STALE -> VersionedText.translatable("automodpack.selection.status.stale").getString();
			default -> "";
		};
	}

	private String formatRowLabel(String marker, String name, String metrics, String status) {
		int maxWidth = groupLabelWidth();
		return truncateToWidth(this.font, marker + name + " " + metrics, status.isEmpty() ? maxWidth : Math.max(1, maxWidth - this.font.width(" " + status)));
	}

	private MutableComponent rowLabel(String text, ChatFormatting color, String status) {
		MutableComponent label = VersionedText.literal(truncateToWidth(this.font, text, groupLabelWidth())).withStyle(color);
		if (!status.isEmpty()) label.append(VersionedText.literal(" " + status).withStyle(ChatFormatting.GRAY));
		return label;
	}

	private int groupLabelWidth() {
		return Math.max(1, panelWidth(ROW_WIDTH) - 28 - GroupSelectionList.INFO_BUTTON_WIDTH - 4);
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
			result.append(related == null || related.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownFeature").getString() : related.displayName());
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

	private SelectionIntent currentIntent() {
		return new SelectionIntent(chosen, chosenCategories, excluded, override());
	}

	private ClientPlatform override() {
		return platformOverride == detectedPlatform ? null : platformOverride;
	}

	private ClientPlatform effectivePlatform() {
		return ClientPlatform.effective(currentIntent());
	}

	private void cyclePlatform() {
		ClientPlatform[] platforms = ClientPlatform.values();
		ClientPlatform candidate = platformOverride == null ? platforms[(detectedPlatform.ordinal() + 1) % platforms.length] : platforms[(platformOverride.ordinal() + 1) % platforms.length];
		platformOverride = candidate == detectedPlatform ? null : candidate;
		applySelectionChange(currentIntent(), Set.of(), null);
	}

	private static String platformLabel(ClientPlatform platform) {
		return switch (platform) {
			case WINDOWS -> "Windows";
			case LINUX -> "Linux";
			case MACOS -> "macOS";
			case ANDROID -> "Android";
		};
	}

	private boolean canSave() {
		return resolutionError.isEmpty()
				&& (selectionAction != null || managerEntry && !activeModpack || !initialSelection.equals(currentIntent()) || !Objects.equals(initialSelection.platform(), currentIntent().platform()));
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, header.getString(), this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 11,
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
					this.width / 2, 22, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.platformSummary", effectivePlatform().id(), resolution.selectedGroups().size())
					.withStyle(platformOverride == null ? ChatFormatting.GRAY : ChatFormatting.YELLOW), this.width / 2, 33, TextColors.WHITE);
			// Status lines are load-bearing sentences: they wrap, they never hard-truncate mid-sentence.
			if (!resolutionError.isEmpty()) {
				drawWrappedStatus(matrices, VersionedText.literal(resolutionError).withStyle(ChatFormatting.RED));
			} else if (pendingUpdater == null || pendingUpdater.getSourceAvailability().totalFiles() == 0) {
				if (!rows.isEmpty()) drawWrappedStatus(matrices, VersionedText.translatable("automodpack.selection.categoryExplanation").withStyle(ChatFormatting.DARK_GRAY));
			} else {
				ModpackUpdater.SourceAvailability availability = pendingUpdater.getSourceAvailability();
				String sourceStatus = VersionedText.translatable(availability.cancelled()
						? "automodpack.selection.sourcesCancelled"
						: !availability.complete()
								? "automodpack.selection.sourcesResolving"
								: "automodpack.selection.sourcesResolved",
						availability.resolvedFiles(), availability.totalFiles()).getString();
				drawWrappedStatus(matrices, VersionedText.literal(sourceStatus).withStyle(ChatFormatting.GRAY));
			}
		}
	}

	/** Draws one status line centered below the summary; two wrapped lines fit between the header stack and the first row. */
	private void drawWrappedStatus(VersionedMatrices matrices, MutableComponent text) {
		List<String> lines = wrapToWidth(this.font, text.getString(), panelWidth(ROW_WIDTH), 2);
		int firstY = lines.size() > 1 ? 44 : 49;
		for (String line : lines) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(text.getStyle()), this.width / 2, firstY, TextColors.WHITE);
			firstY += 11;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}

	private record Row(String section, String groupId, String categoryId) {}
}
