package pl.skidam.automodpack.client.ui.screen;

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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.DropdownWidget;
import pl.skidam.automodpack.client.ui.widget.GroupSelectionList;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.client.SourceAvailability;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ReviewActions;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * Lets the player pick which optional groups of a modpack they want. Changes only take effect on the next launch, because mods are loaded during preload.
 */
public class ModpackSelectionScreen extends VersionedScreen {

	/** Symmetric margins on both sides of the group list; rows span the rest of the screen, like vanilla list screens. */
	private static final int LIST_MARGIN = 12;
	/** Vanilla checkbox geometry: the box plus the gap before its label. */
	private static final int CHECKBOX_LABEL_OFFSET = 24;

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
	private final ReviewActions actions;
	private final boolean managerEntry;
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

	private boolean closed;
	private boolean switchInFlight;
	private AbstractWidget saveButton;
	private DropdownWidget platformDropdown;
	private int listBottom;

	public ModpackSelectionScreen(Screen parent, SelectedModpackTarget target, ReviewActions actions, Consumer<SelectionIntent> selectionAction) {
		this(parent, target.manifest(),
				new Entry(target.expectedPriorIntent(), target.selection().intent(), selectionAction, () -> {}, actions, false, null));
	}

	public static ModpackSelectionScreen repair(Screen parent, GroupManifest manifest, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
		return new ModpackSelectionScreen(parent, manifest, new Entry(savedSelection, savedSelection, selectionAction, cancelAction, null, false, null));
	}

	static ModpackSelectionScreen forInstalledRecord(Screen parent, PackDocument record, boolean managerEntry) {
		return new ModpackSelectionScreen(parent, record.manifest(), new Entry(null, null, null, () -> {}, null, managerEntry, record));
	}

	/** What an entry point varies; the screen settles everything else itself. */
	private record Entry(SelectionIntent expectedSelection, SelectionIntent initialSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction,
			ReviewActions actions, boolean managerEntry, PackDocument localRecord) {}

	private ModpackSelectionScreen(Screen parent, GroupManifest manifest, Entry entry) {
		super(VersionedText.translatable("automodpack.selection.title"));
		this.parent = parent;
		this.manifest = Objects.requireNonNull(manifest);
		this.modpackId = manifest.modpackId();
		this.modpackName = manifest.modpackName();
		this.groups = manifest.groups();
		this.controller = new InstalledModpackController();
		this.expectedSelection = entry.expectedSelection() == null && entry.initialSelection() == null
				? controller.savedSelection(modpackId)
				: entry.expectedSelection();
		this.selectionAction = entry.selectionAction();
		this.cancelAction = entry.cancelAction();
		this.actions = entry.actions();
		this.managerEntry = entry.managerEntry();
		this.activeModpack = controller.activeRecord(modpackId) != null;
		this.localRecord = entry.localRecord();
		SelectionIntent initial = entry.initialSelection() != null
				? entry.initialSelection()
				: this.expectedSelection == null ? GroupSelectionResolver.defaultIntent(manifest) : this.expectedSelection;
		this.initialSelection = initial;
		this.detectedPlatform = ClientPlatform.current();
		this.platformOverride = initial.platform() == null || initial.platform().equals(detectedPlatform) ? null : initial.platform();
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
	}

	@Override
	protected void init() {
		super.init();

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
		List<AbstractWidget> actionButtons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, actionY, footer);
		this.saveButton = actionButtons.get(2);
		this.saveButton.active = canSave();
		if (selectionAction == null && resolutionError.isEmpty() && !this.saveButton.active) setTooltip(this.saveButton, VersionedText.translatable("automodpack.selection.noChanges"));
		int listTop = 80;
		listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, actionY, footer) - 8;
		this.addRenderableWidget(new GroupSelectionList(this.minecraft, this.width, this.height, listWidth(), listTop, listBottom, listItems(), this::onListToggle, this::onListInspect));
		String platformLabel = platformLabel();
		int platformButtonWidth = Math.max(90, Math.min(160, this.font.width(platformLabel) + 26));
		this.platformDropdown = dropdownWidget(listLeft() + listWidth() - platformButtonWidth, 24, platformButtonWidth, 20, VersionedText.literal(platformLabel));
		setTooltip(this.platformDropdown, VersionedText.translatable("automodpack.selection.platformTooltip"));
		List<ClientPlatform> choices = platformChoices();
		// The saved platform may no longer be declared by a refreshed manifest; the detected platform is always a choice.
		int selected = choices.indexOf(effectivePlatform());
		if (selected < 0) selected = choices.indexOf(detectedPlatform);
		this.platformDropdown.setOptions(platformOptions(choices), selected, listBottom, index -> pickPlatform(choices.get(index)));
	}

	/** The detectable platforms plus every platform the loaded manifest declares, so a declared-only group stays reachable by explicit choice. */
	private List<ClientPlatform> platformChoices() {
		List<ClientPlatform> choices = new ArrayList<>(ClientPlatform.builtIns());
		for (ClientPlatform platform : manifest.declaredPlatforms()) if (!choices.contains(platform)) choices.add(platform);
		return choices;
	}

	/** The OS dropdown options: every platform choice, with the detected one marked in the list. */
	private List<Component> platformOptions(List<ClientPlatform> choices) {
		List<Component> options = new ArrayList<>(choices.size());
		for (ClientPlatform platform : choices)
			options.add(platform.equals(detectedPlatform)
					? VersionedText.translatable("automodpack.selection.platformDetected", platformDisplay(platform))
					: VersionedText.literal(platformDisplay(platform)));
		return options;
	}

	private List<GroupSelectionList.Item> listItems() {
		List<GroupSelectionList.Item> items = new ArrayList<>();
		items.add(new GroupSelectionList.Item(GroupSelectionList.Kind.CAPTION, "", generalCaption(), null, false, false, false, ""));
		for (var entry : groups.entrySet()) {
			if (entry.getValue().category().isEmpty()) items.add(groupItem(entry.getKey()));
		}
		for (String category : sortedCategories()) {
			items.add(new GroupSelectionList.Item(GroupSelectionList.Kind.HEADER, category, headerLabel(category), headerTooltip(category, hasOptionalCategoryGroups(category)),
					categoryFullySelected(category), hasOptionalCategoryGroups(category), categoryPartiallySelected(category), headerCounter(category)));
			for (var entry : groups.entrySet()) {
				if (category.equals(entry.getValue().category())) items.add(groupItem(entry.getKey()));
			}
		}
		return List.copyOf(items);
	}

	private MutableComponent generalCaption() {
		return VersionedText.literal(VersionedText.translatable("automodpack.ui.general").getString()).withStyle(ChatFormatting.BOLD);
	}

	private GroupSelectionList.Item groupItem(String groupId) {
		GroupManifest.Group group = groups.get(groupId);
		boolean togglable = group != null && canToggle(groupId, group);
		return new GroupSelectionList.Item(GroupSelectionList.Kind.GROUP, groupId, rowLabel(groupId, group), rowTooltip(groupId, group), resolution.selectedGroups().contains(groupId), togglable, false, "");
	}

	private List<String> sortedCategories() {
		Set<String> categories = new TreeSet<>();
		for (GroupManifest.Group group : groups.values()) if (!group.category().isEmpty()) categories.add(group.category());
		return List.copyOf(categories);
	}

	private void onListToggle(GroupSelectionList.Item item) {
		if (item.kind() == GroupSelectionList.Kind.CAPTION) return;
		if (item.kind() == GroupSelectionList.Kind.HEADER) {
			if (!item.id().isBlank()) toggleCategory(item.id());
			return;
		}
		toggle(item.id());
	}

	private void onListInspect(GroupSelectionList.Item item) {
		if (item.kind() == GroupSelectionList.Kind.GROUP) inspect(item.id());
	}

	/** The Files button on a group row opens the file browser pre-filtered to that group — the row tooltip already carries the group's metadata. */
	private void inspect(String groupId) {
		if (!groups.containsKey(groupId)) return;
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.literal(displayName(groupId)),
				VersionedText.translatable("automodpack.browser.groupDescription"), groupChanges(groupId), Map.of(groupId, displayName(groupId)), null, List.of(), false, 0, groupId));
	}

	/** The group's shipped files as a preserved catalogue for the shared browser. */
	private ChangeSet groupChanges(String groupId) {
		List<ChangeSet.Change> changes = new ArrayList<>();
		for (var entry : groups.get(groupId).files().entrySet()) {
			GroupManifest.GroupFile file = entry.getValue();
			ChangeSet.Occurrence occurrence = new ChangeSet.Occurrence("catalogue", entry.getKey(), file.size(), null, null, file.sha1(), file.type(), List.of(groupId), List.of());
			changes.add(new ChangeSet.Change(entry.getKey(), ChangeSet.Kind.PRESERVED, List.of(occurrence)));
		}
		return ChangeSet.of(changes);
	}

	@Override
	public void tick() {
		super.tick();
		if (actions == null) return;
		if (actions.reviewCancelled().getAsBoolean()) ScreenImpl.multiplayer();
	}

	private boolean canToggle(String groupId, GroupManifest.Group group) {
		if (group.required()) return false;
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
		if (group.required()) return;
		SelectionIntent previous = currentIntent();
		SelectionIntent next = GroupSelectionResolver.prefer(manifest, previous, groupId, effectivePlatform()).withPlatform(override());
		Set<String> preferred = resolution.selectedGroups().contains(groupId) ? Set.of() : Set.of(groupId);
		applySelectionChange(next, preferred, displayName(groupId));
	}

	private void applySelectionChange(SelectionIntent next, Set<String> preferredGroups, String preferredName) {
		InstalledModpackController.SelectionChange change = controller.planSelectionChange(manifest, next, preferredGroups, preferredName, effectivePlatform());
		if (change.resolution() != null) {
			applyResolved(change.intent(), change.resolution());
			return;
		}
		if (change.conflict() != null) {
			GroupSelectionResolver.ConflictReplacement replacement = change.conflict();
			ScreenImpl.setScreen(
					new GroupConflictScreen(this, preferredName, names(replacement.conflictingGroups()), () -> applySelectionChange(replacement.intent().withPlatform(override()), Set.of(), preferredName)));
			return;
		}
		resolutionError = change.failure();
		rebuild();
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
			ScreenImpl.setScreen(new SelectionSavedScreen(this, modpackName));
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

	/** The header glyph names what the next click does: full = exclude all, empty = include all, partial = include the rest. */
	private MutableComponent headerLabel(String category) {
		long optional = optionalGroupCount(category);
		long selected = selectedOptionalGroupCount(category);
		boolean allSelected = optional > 0 && selected == optional;
		return VersionedText.literal(VersionedText.translatable("automodpack.selection.category", categoryLabel(category)).getString())
				.withStyle(ChatFormatting.BOLD, allSelected ? ChatFormatting.GREEN : selected == 0 ? ChatFormatting.GRAY : ChatFormatting.YELLOW);
	}

	private boolean categoryPartiallySelected(String category) {
		long optional = optionalGroupCount(category);
		long selected = selectedOptionalGroupCount(category);
		return selected > 0 && selected < optional;
	}

	private String headerCounter(String category) {
		long optional = optionalGroupCount(category);
		return optional == 0 ? "" : selectedOptionalGroupCount(category) + "/" + optional;
	}

	private Component headerTooltip(String category, boolean canToggle) {
		if (!canToggle) return null;
		StringBuilder tooltip = new StringBuilder(VersionedText.translatable("automodpack.selection.categoryTooltip").getString());
		if (categoryPartiallySelected(category))
			tooltip.append("\n").append(VersionedText.translatable("automodpack.selection.categoryPart", selectedOptionalGroupCount(category), optionalGroupCount(category)).getString());
		return VersionedText.literal(tooltip.toString()).withStyle(ChatFormatting.GRAY);
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
		if (!group.description().isBlank()) {
			tooltip.append(group.description());
			// The description is the server's words; the attribution keeps them from reading as client-authored copy.
			appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.serverDescription").getString());
		}
		GroupResolution explanation = resolution.resolution(groupId);
		if (explanation != null) appendTooltipLine(tooltip, resolutionText(explanation));
		appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.category", categoryLabel(group)).getString());
		// The resolution text already carries "Required: always included" whenever it exists; only fall back to it here.
		if (group.required() && explanation == null) appendTooltipLine(tooltip, VersionedText.translatable("automodpack.selection.requiredAlways").getString());
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
		if (group == null) return VersionedText.translatable("automodpack.browser.unknownGroup");

		GroupResolution explanation = resolution.resolution(groupId);
		String status = statusWord(explanation);
		int maxWidth = groupLabelWidth();
		int textWidth = status.isEmpty() ? maxWidth : Math.max(1, maxWidth - this.font.width(" " + status));
		String name = displayName(groupId);
		String metrics = UiFormat.plural(group.files().size(), "automodpack.selection.metrics", UiFormat.formatSize(groupBytes(group))).getString();
		MutableComponent label = VersionedText.literal(truncateToWidth(this.font, name + " " + metrics, textWidth)).withStyle(rowColor(groupId, group, explanation));
		if (!status.isEmpty()) label.append(VersionedText.literal(" " + status).withStyle(ChatFormatting.GRAY));
		return label;
	}

	/** The color is the load-bearing row state; the wording lives in the status word and the hover tooltip. */
	private ChatFormatting rowColor(String groupId, GroupManifest.Group group, GroupResolution explanation) {
		if (group.required()) return ChatFormatting.GRAY;
		if (explanation != null && (explanation.reasons().contains(GroupResolution.Reason.EXPLICIT_REQUEST_UNAVAILABLE) || explanation.status() == GroupResolution.Status.UNAVAILABLE
				|| explanation.status() == GroupResolution.Status.BLOCKED || explanation.status() == GroupResolution.Status.CONFLICT))
			return ChatFormatting.RED;
		if (excluded.contains(groupId)) return ChatFormatting.YELLOW;
		if (resolution.selectedGroups().contains(groupId)) {
			// A dependency lock is the load-bearing fact: the row cannot be unchecked while its dependent needs it.
			if (resolution.dependencyGroups().contains(groupId)) return ChatFormatting.AQUA;
			if (chosen.contains(groupId)) return ChatFormatting.GREEN;
			return ChatFormatting.AQUA;
		}
		if (resolution.forcedGroups().contains(groupId)) return ChatFormatting.AQUA;
		return group.defaultSelected() ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
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
			default -> "";
		};
	}

	private int groupLabelWidth() {
		return Math.max(1, listWidth() - CHECKBOX_LABEL_OFFSET - GroupSelectionList.filesButtonWidth() - ActionAreaLayout.SEAM - 4);
	}

	/** The group list spans the screen between two symmetric margins. */
	private int listWidth() {
		return Math.max(1, this.width - LIST_MARGIN * 2);
	}

	private int listLeft() {
		return (this.width - listWidth()) / 2;
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
			result.append(related == null || related.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownGroup").getString() : related.displayName());
		}
		return result.length() == 0 ? VersionedText.translatable("automodpack.ui.none").getString() : result.toString();
	}

	private String displayName(String groupId) {
		GroupManifest.Group group = groups.get(groupId);
		return group == null || group.displayName().isBlank() ? VersionedText.translatable("automodpack.browser.unknownGroup").getString() : group.displayName();
	}

	private boolean hasOptionalCategoryGroups(String category) {
		return groups.values().stream().anyMatch(group -> category.equals(group.category()) && !group.required());
	}

	private SelectionIntent currentIntent() {
		return new SelectionIntent(chosen, chosenCategories, excluded, override());
	}

	private ClientPlatform override() {
		return Objects.equals(detectedPlatform, platformOverride) ? null : platformOverride;
	}

	private ClientPlatform effectivePlatform() {
		return ClientPlatform.effective(currentIntent());
	}

	private String platformLabel() {
		return VersionedText.translatable("automodpack.selection.platformButton", platformDisplay(effectivePlatform())).getString();
	}

	/** The display name of a platform choice; nothing is known about the platform when none is detected or chosen. */
	private String platformDisplay(ClientPlatform platform) {
		if (platform == null) return VersionedText.translatable("automodpack.selection.platformUndetected").getString();
		if (platform.equals(ClientPlatform.WINDOWS)) return "Windows";
		if (platform.equals(ClientPlatform.LINUX)) return "Linux";
		if (platform.equals(ClientPlatform.MACOS)) return "macOS";
		return platform.id();
	}

	private void pickPlatform(ClientPlatform platform) {
		platformOverride = platform.equals(detectedPlatform) ? null : platform;
		applySelectionChange(currentIntent(), Set.of(), null);
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
				: VersionedText.literal(modpackName);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, header.getString(), this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 11,
				TextColors.WHITE);
		MutableComponent description = managerEntry && !isActiveModpack()
				? VersionedText.translatable("automodpack.packManager.switchDescription")
				: VersionedText.translatable("automodpack.selection.description");
		// The header stack shares the rail with the platform dropdown (y 24..44), so the description
		// wraps inside the space left of it and the summary waits until that zone ends.
		int railLeft = listLeft();
		int railWidth = listWidth();
		List<String> descriptionLines = wrapToWidth(this.font, description.getString(), railWidth - platformDropdown.getWidth() - 8);
		if (descriptionLines.size() > 2) descriptionLines = descriptionLines.subList(0, 2);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.GRAY), railLeft, 22 + index * 11, TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.platformSummary", platformDisplay(effectivePlatform()), resolution.selectedGroups().size())
				.withStyle(platformOverride == null ? ChatFormatting.GRAY : ChatFormatting.YELLOW), railLeft, 44, TextColors.WHITE);
		// Status lines are load-bearing sentences: they wrap, they never hard-truncate mid-sentence.
		if (!resolutionError.isEmpty()) {
			drawWrappedStatus(matrices, VersionedText.literal(resolutionError).withStyle(ChatFormatting.RED));
		} else if (actions == null || actions.sourceAvailability().get().totalFiles() == 0) {
			if (!groups.isEmpty()) drawWrappedStatus(matrices, VersionedText.translatable("automodpack.selection.categoryExplanation").withStyle(ChatFormatting.GRAY));
		} else {
			SourceAvailability availability = actions.sourceAvailability().get();
			String sourceStatus = VersionedText.translatable(availability.cancelled()
					? "automodpack.selection.sourcesCancelled"
					: !availability.complete()
							? "automodpack.selection.sourcesResolving"
							: "automodpack.selection.sourcesResolved",
					availability.resolvedFiles(), availability.totalFiles()).getString();
			drawWrappedStatus(matrices, VersionedText.literal(sourceStatus).withStyle(ChatFormatting.GRAY));
		}
	}

	/** Draws one status line centered below the header stack; two wrapped lines fit between it and the first row. */
	private void drawWrappedStatus(VersionedMatrices matrices, MutableComponent text) {

		List<String> lines = wrapToWidth(this.font, text.getString(), listWidth(), 2);
		int firstY = lines.size() > 1 ? 55 : 60;
		for (String line : lines) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(text.getStyle()), this.width / 2, firstY, TextColors.WHITE);
			firstY += 11;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		if (closeOpenMenus()) return false;
		return handleBackOnEscape(this::back);
	}

}
