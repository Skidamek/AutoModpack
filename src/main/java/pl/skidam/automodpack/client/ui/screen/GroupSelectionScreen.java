package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import pl.skidam.automodpack_core.modpack.group.CategoryPickMode;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionSummary;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ReviewConsent;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * Group selection screen: shows optional/recommended groups as 2-line cards under optional category headers
 * (with radio buttons for exclusive categories and bulk toggle only on multi-group ANY categories), delegating
 * selection math to {@link GroupSelectionResolver} and submission lifecycle to {@link Session}.
 */
public class GroupSelectionScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int LIST_FOOTER_GAP = 12;
	private static final int ACCENT_SELECTED = 0xFF55FF55;
	private static final int ACCENT_DEFAULT = 0xFF55FFFF;
	private static final int ACCENT_ERROR = 0xFFFF5555;

	interface Session {
		String submitLabelKey();

		boolean allowSubmitUnchanged();

		default String unchangedTooltipKey() {
			return null;
		}

		SelectionIntent initialSelection();

		default String origin() {
			return "";
		}

		default boolean isCancelled() {
			return false;
		}

		default SourceAvailability sourceAvailability() {
			return null;
		}

		void submit(SelectionIntent intent, Runnable release);

		default void cancel() {}
	}

	private final Screen parent;
	private final GroupManifest manifest;
	private final String modpackName;
	private final Map<String, GroupManifest.Group> groups;
	private final String origin;
	private final Session session;
	private final SelectionIntent initialSelection;
	private final ClientPlatform detectedPlatform;

	private SelectionIntent draft;
	private ResolvedSelection resolution;
	private GroupSelectionSummary summary;
	private String resolutionError = "";
	private String notice = "";

	private boolean closed;
	private boolean submitting;
	private AbstractWidget saveButton;
	private GroupSelectionList groupList;
	private double scrollAmount;
	private DropdownWidget platformDropdown;

	public static GroupSelectionScreen forReview(Screen parent, SelectedModpackTarget target, String origin, ReviewConsent actions, Consumer<SelectionIntent> selectionAction) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(selectionAction, "selectionAction");
		String safeOrigin = origin == null ? "" : origin;
		return new GroupSelectionScreen(parent, target.manifest(), new Session() {
			@Override
			public String submitLabelKey() {
				return "automodpack.selection.preview";
			}

			@Override
			public boolean allowSubmitUnchanged() {
				return true;
			}

			@Override
			public SelectionIntent initialSelection() {
				return target.selection().intent();
			}

			@Override
			public String origin() {
				return safeOrigin;
			}

			@Override
			public boolean isCancelled() {
				return actions != null && actions.reviewCancelled().getAsBoolean();
			}

			@Override
			public SourceAvailability sourceAvailability() {
				return actions == null ? null : actions.sourceAvailability().get();
			}

			@Override
			public void submit(SelectionIntent intent, Runnable release) {
				try {
					selectionAction.accept(intent);
				} catch (RuntimeException e) {
					release.run();
					ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
				}
			}
		});
	}

	public static GroupSelectionScreen repair(Screen parent, GroupManifest manifest, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
		Objects.requireNonNull(selectionAction, "selectionAction");
		Runnable safeCancel = cancelAction == null ? () -> {} : cancelAction;
		return new GroupSelectionScreen(parent, manifest, new Session() {
			@Override
			public String submitLabelKey() {
				return "automodpack.selection.preview";
			}

			@Override
			public boolean allowSubmitUnchanged() {
				return true;
			}

			@Override
			public SelectionIntent initialSelection() {
				return savedSelection;
			}

			@Override
			public void submit(SelectionIntent intent, Runnable release) {
				try {
					selectionAction.accept(intent);
				} catch (RuntimeException e) {
					release.run();
					ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
				}
			}

			@Override
			public void cancel() {
				safeCancel.run();
			}
		});
	}

	static GroupSelectionScreen forInstalled(Screen parent, InstalledModpackController controller, PackDocument record) {
		Objects.requireNonNull(controller, "controller");
		Objects.requireNonNull(record, "record");
		GroupManifest manifest = record.manifest();
		return new GroupSelectionScreen(parent, manifest, new Session() {
			@Override
			public String submitLabelKey() {
				return "automodpack.selection.save";
			}

			@Override
			public boolean allowSubmitUnchanged() {
				return false;
			}

			@Override
			public String unchangedTooltipKey() {
				return "automodpack.selection.noChanges";
			}

			@Override
			public SelectionIntent initialSelection() {
				return controller.savedSelection(manifest.modpackId());
			}

			@Override
			public String origin() {
				return controller.originFor(manifest.modpackId());
			}

			@Override
			public void submit(SelectionIntent intent, Runnable release) {
				controller.switchSelection(record, intent, manifest.modpackName(), release);
			}
		});
	}

	private GroupSelectionScreen(Screen parent, GroupManifest manifest, Session session) {
		super(VersionedText.text("automodpack.selection.title"));
		this.parent = parent;
		this.manifest = Objects.requireNonNull(manifest, "manifest");
		this.session = Objects.requireNonNull(session, "session");
		this.modpackName = manifest.modpackName();
		this.groups = manifest.groups();
		this.origin = session.origin();
		this.detectedPlatform = ClientPlatform.current();
		SelectionIntent initial = session.initialSelection();
		if (initial == null) initial = SelectionIntent.DEFAULTS;
		this.initialSelection = initial;
		this.draft = initial;
		try {
			useResolved(GroupSelectionResolver.resolve(manifest, draft, effectivePlatform()));
		} catch (SelectionResolutionException e) {
			useResolved(Objects.requireNonNull(e.resolution(), "Invalid selection did not include a partial resolution"));
			this.resolutionError = VersionedText.str("automodpack.selection.savedInvalid");
		}
	}

	@Override
	protected void init() {
		if (this.groupList != null) this.scrollAmount = this.groupList.preservedScrollAmount();
		super.init();

		int actionY = this.height - 28;
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.text("automodpack.back"), press -> back()),
				optionalAction(VersionedText.text("automodpack.selection.reset"), press -> resetToDefaults()),
				primaryAction(VersionedText.text(session.submitLabelKey()), press -> save()));
		List<AbstractWidget> actionButtons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, actionY, footer);
		actionButtons.get(1).active = !submitting && !draft.isDefaults();
		this.saveButton = actionButtons.get(2);
		this.saveButton.active = canSave();
		String unchangedTooltip = session.unchangedTooltipKey();
		if (unchangedTooltip != null && !submitting && resolutionError.isEmpty() && !this.saveButton.active) setTooltip(this.saveButton, VersionedText.text(unchangedTooltip));

		int listTop = 78;
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, actionY, footer) - LIST_FOOTER_GAP;
		this.groupList = this.addRenderableWidget(
				new GroupSelectionList(this.minecraft, this.width, this.height, panelWidth(PANEL_WIDTH), listTop, listBottom, listItems(), this::onListToggle, this::onListInspect));
		if (this.scrollAmount > 0) this.groupList.restoreScrollAmount(this.scrollAmount);

		this.platformDropdown = null;
		if (manifest.hasDeclaredPlatforms()) {
			String platformLabel = platformLabel();
			int platformButtonWidth = Math.max(90, Math.min(160, this.font.width(platformLabel) + 26));
			this.platformDropdown = dropdownWidget(panelLeft(PANEL_WIDTH) + panelWidth(PANEL_WIDTH) - platformButtonWidth, 24, platformButtonWidth, 20, VersionedText.literal(platformLabel));
			setTooltip(this.platformDropdown, VersionedText.text("automodpack.selection.platformTooltip"));
			List<ClientPlatform> choices = platformChoices();
			int selected = choices.indexOf(effectivePlatform());
			if (selected < 0 && detectedPlatform != null) selected = choices.indexOf(detectedPlatform);
			this.platformDropdown.setOptions(platformOptions(choices), Math.max(0, selected), listBottom, index -> pickPlatform(choices.get(index)));
		}
	}

	private List<ClientPlatform> platformChoices() {
		List<ClientPlatform> choices = new ArrayList<>(ClientPlatform.builtIns());
		for (ClientPlatform platform : manifest.declaredPlatforms()) if (!choices.contains(platform)) choices.add(platform);
		return choices;
	}

	private List<Component> platformOptions(List<ClientPlatform> choices) {
		List<Component> options = new ArrayList<>(choices.size());
		for (ClientPlatform platform : choices)
			options.add(platform.equals(detectedPlatform)
					? VersionedText.text("automodpack.selection.platformDetected", platformDisplay(platform))
					: VersionedText.literal(platformDisplay(platform)));
		return options;
	}

	private List<GroupSelectionList.Item> listItems() {
		List<GroupSelectionList.Item> items = new ArrayList<>();

		for (var catEntry : manifest.categories().entrySet()) {
			String catId = catEntry.getKey();
			GroupManifest.Category cat = catEntry.getValue();
			List<String> members = new ArrayList<>();
			for (var groupEntry : groups.entrySet()) {
				if (groupEntry.getValue().selection().choosable() && catId.equals(groupEntry.getValue().category())) members.add(groupEntry.getKey());
			}
			if (members.isEmpty()) continue;

			items.add(categoryHeaderItem(catId, cat, members));
			boolean exclusive = cat.pick().isExclusive();
			if (cat.pick() == CategoryPickMode.AT_MOST_ONE) items.add(noneRadioItem(catId, members));
			for (String groupId : members) items.add(groupItem(groupId, exclusive));
		}

		List<String> uncategorized = new ArrayList<>();
		for (var entry : groups.entrySet()) {
			if (entry.getValue().selection().choosable() && entry.getValue().category().isEmpty()) uncategorized.add(entry.getKey());
		}
		if (!uncategorized.isEmpty()) {
			if (!manifest.categories().isEmpty()) {
				items.add(new GroupSelectionList.Item(GroupSelectionList.Kind.HEADER, "", "", VersionedText.text("automodpack.selection.otherCategory").withStyle(ChatFormatting.BOLD),
						VersionedText.literal(""), VersionedText.literal(""), null, false, false, false, "", 0));
			}
			for (String groupId : uncategorized) items.add(groupItem(groupId, false));
		}

		List<String> included = new ArrayList<>();
		for (var entry : groups.entrySet()) {
			if (entry.getValue().selection().choosable() || !resolution.selectedGroups().contains(entry.getKey())) continue;
			included.add(entry.getKey());
		}
		if (!included.isEmpty()) {
			items.add(new GroupSelectionList.Item(GroupSelectionList.Kind.HEADER, "", "", VersionedText.text("automodpack.selection.includedAutomatically").withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE),
					VersionedText.literal(""), VersionedText.literal(""), null, false, false, false, "", 0));
			for (String groupId : included) items.add(includedGroupItem(groupId));
		}
		return List.copyOf(items);
	}

	private GroupSelectionList.Item includedGroupItem(String groupId) {
		GroupManifest.Group group = groups.get(groupId);
		GroupResolution explanation = resolution.resolution(groupId);
		MutableComponent title = VersionedText.literal(manifest.displayName(groupId)).withStyle(ChatFormatting.AQUA);
		MutableComponent badge = VersionedText.literal(UiFormat.plural(group.files().size(), "automodpack.selection.metrics", UiFormat.formatSize(groupBytes(group))).getString())
				.withStyle(ChatFormatting.GRAY);
		Component subtitle = includedSubtitle(group, explanation);
		Component tooltip = cardTooltip(groupId, group, explanation, subtitle);
		return new GroupSelectionList.Item(GroupSelectionList.Kind.CHECKBOX_GROUP, groupId, group.category(), title, subtitle, badge, tooltip, true, false, true, "", ACCENT_DEFAULT);
	}

	private Component includedSubtitle(GroupManifest.Group group, GroupResolution explanation) {
		if (group.selection() == GroupSelectionMode.REQUIRED) return VersionedText.text("automodpack.selection.required").withStyle(ChatFormatting.AQUA);
		if (explanation != null && !explanation.relatedGroups().isEmpty()) return VersionedText.text("automodpack.selection.dependencyNamed", names(explanation.relatedGroups())).withStyle(ChatFormatting.AQUA);
		return VersionedText.text("automodpack.selection.required").withStyle(ChatFormatting.AQUA);
	}

	private GroupSelectionList.Item categoryHeaderItem(String catId, GroupManifest.Category cat, List<String> members) {
		String actionLabel = "";
		if (!cat.pick().isExclusive()) {
			List<String> toggleable = compatibleNonCoreChoosable(members);
			if (toggleable.size() >= 2) {
				boolean allSelected = toggleable.stream().allMatch(resolution.selectedGroups()::contains);
				actionLabel = VersionedText.str(allSelected ? "automodpack.selection.clearCategory" : "automodpack.selection.selectAll");
			}
		}
		MutableComponent title = VersionedText.literal(manifest.categoryDisplayName(catId)).withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE);
		String subtitleText = headerSubtitle(cat);
		return new GroupSelectionList.Item(GroupSelectionList.Kind.HEADER, catId, catId, title,
				subtitleText.isBlank() ? VersionedText.literal("") : VersionedText.literal(subtitleText), VersionedText.literal(""), null, false, !actionLabel.isEmpty(), false, actionLabel, 0);
	}

	private static String headerSubtitle(GroupManifest.Category cat) {
		String pickHint = cat.pick() == CategoryPickMode.EXACTLY_ONE
				? VersionedText.str("automodpack.selection.pickOne")
				: cat.pick() == CategoryPickMode.AT_MOST_ONE ? VersionedText.str("automodpack.selection.pickAtMostOne") : "";
		if (cat.description().isBlank()) return pickHint;
		if (pickHint.isBlank()) return cat.description();
		return pickHint + " · " + cat.description();
	}

	private GroupSelectionList.Item noneRadioItem(String catId, List<String> members) {
		boolean noneSelected = members.stream().noneMatch(resolution.selectedGroups()::contains);
		boolean locked = members.stream().anyMatch(id -> resolution.selectedGroups().contains(id) && resolution.requiredGroups().contains(id));
		return new GroupSelectionList.Item(GroupSelectionList.Kind.RADIO_GROUP, "", catId,
				VersionedText.text("automodpack.selection.noneOption").withStyle(noneSelected ? ChatFormatting.WHITE : ChatFormatting.GRAY),
				VersionedText.text("automodpack.selection.noneSubtitle"), VersionedText.literal(""), null, noneSelected, !locked, false, "",
				noneSelected ? ACCENT_SELECTED : 0);
	}

	private GroupSelectionList.Item groupItem(String groupId, boolean radio) {
		GroupManifest.Group group = groups.get(groupId);
		boolean selected = resolution.selectedGroups().contains(groupId);
		boolean canToggle = canToggle(groupId, group);
		GroupResolution explanation = resolution.resolution(groupId);
		ChatFormatting color = rowColor(groupId, group, explanation);
		MutableComponent title = VersionedText.literal(manifest.displayName(groupId)).withStyle(color);
		MutableComponent badge = VersionedText.literal(UiFormat.plural(group.files().size(), "automodpack.selection.metrics", UiFormat.formatSize(groupBytes(group))).getString())
				.withStyle(ChatFormatting.GRAY);
		Component subtitle = cardSubtitle(groupId, group, explanation);
		Component tooltip = cardTooltip(groupId, group, explanation, subtitle);
		int accent = accentColor(groupId, selected, explanation);
		return new GroupSelectionList.Item(radio ? GroupSelectionList.Kind.RADIO_GROUP : GroupSelectionList.Kind.CHECKBOX_GROUP,
				groupId, group.category(), title, subtitle, badge, tooltip, selected, canToggle, true, "", accent);
	}

	private Component cardSubtitle(String groupId, GroupManifest.Group group, GroupResolution explanation) {
		if (explanation != null && explanation.status() == GroupResolution.Status.CONFLICT && !explanation.relatedGroups().isEmpty()) {
			return VersionedText.text("automodpack.selection.conflictsWith", names(explanation.relatedGroups())).withStyle(ChatFormatting.RED);
		}
		if (explanation != null && explanation.status() == GroupResolution.Status.UNAVAILABLE) {
			return VersionedText.text("automodpack.selection.unavailableOn", platformDisplay(effectivePlatform())).withStyle(ChatFormatting.RED);
		}
		if (explanation != null && explanation.status() == GroupResolution.Status.BLOCKED) {
			String text = explanation.relatedGroups().isEmpty()
					? VersionedText.str("automodpack.selection.status.dependencyUnavailable")
					: VersionedText.str("automodpack.selection.blockedBy", names(explanation.relatedGroups()));
			return VersionedText.literal(text).withStyle(ChatFormatting.RED);
		}
		if (resolution.requiredGroups().contains(groupId)) {
			return VersionedText.text("automodpack.selection.includedWithCore").withStyle(ChatFormatting.AQUA);
		}
		if (resolution.dependencyGroups().contains(groupId) && explanation != null && !explanation.relatedGroups().isEmpty()) {
			return VersionedText.text("automodpack.selection.dependencyNamed", names(explanation.relatedGroups())).withStyle(ChatFormatting.AQUA);
		}
		if (!group.description().isBlank()) return VersionedText.literal(group.description());
		return VersionedText.literal("");
	}

	private Component cardTooltip(String groupId, GroupManifest.Group group, GroupResolution explanation, Component subtitle) {
		List<String> lines = new ArrayList<>();
		if (!group.description().isBlank()) lines.add(group.description());
		if (!group.requires().isEmpty()) lines.add(VersionedText.str("automodpack.selection.requires", names(group.requires())));
		Set<String> conflicts = manifest.conflictsOf(groupId);
		if (!conflicts.isEmpty()) lines.add(VersionedText.str("automodpack.selection.conflictsWith", names(conflicts)));
		if (explanation != null && !explanation.relatedGroups().isEmpty() && explanation.reasons().contains(GroupResolution.Reason.DEPENDENCY)) {
			lines.add(VersionedText.str("automodpack.selection.dependencyNamed", names(explanation.relatedGroups())));
		}
		if (!group.compatiblePlatforms().isEmpty()) {
			List<String> platforms = group.compatiblePlatforms().stream().map(this::platformDisplay).toList();
			lines.add(VersionedText.str("automodpack.selection.onlyFor", String.join(", ", platforms)));
		}
		if (explanation != null && explanation.status() == GroupResolution.Status.BLOCKED) {
			lines.add(explanation.relatedGroups().isEmpty()
					? VersionedText.str("automodpack.selection.status.dependencyUnavailable")
					: VersionedText.str("automodpack.selection.blockedBy", names(explanation.relatedGroups())));
		} else if (!group.supports(effectivePlatform())) {
			lines.add(VersionedText.str("automodpack.selection.unavailableOn", platformDisplay(effectivePlatform())));
		}
		if (lines.isEmpty()) return null;
		String subtitleText = subtitle == null ? "" : subtitle.getString();
		if (lines.size() == 1 && lines.get(0).equals(subtitleText) && this.font.width(subtitleText) <= GroupSelectionList.cardTextWidth(panelWidth(PANEL_WIDTH))) {
			return null;
		}
		return VersionedText.literal(String.join("\n", lines)).withStyle(ChatFormatting.GRAY);
	}

	private int accentColor(String groupId, boolean selected, GroupResolution explanation) {
		if (explanation != null && (explanation.status() == GroupResolution.Status.UNAVAILABLE || explanation.status() == GroupResolution.Status.BLOCKED
				|| explanation.status() == GroupResolution.Status.CONFLICT || explanation.reasons().contains(GroupResolution.Reason.EXPLICIT_ENABLE_UNAVAILABLE))) {
			return ACCENT_ERROR;
		}
		if (!selected) return 0;
		if (draft.enabled().contains(groupId)) return ACCENT_SELECTED;
		return ACCENT_DEFAULT;
	}

	private ChatFormatting rowColor(String groupId, GroupManifest.Group group, GroupResolution explanation) {
		if (explanation != null && (explanation.status() == GroupResolution.Status.UNAVAILABLE || explanation.status() == GroupResolution.Status.BLOCKED
				|| explanation.status() == GroupResolution.Status.CONFLICT || explanation.reasons().contains(GroupResolution.Reason.EXPLICIT_ENABLE_UNAVAILABLE))) {
			return ChatFormatting.RED;
		}
		if (resolution.selectedGroups().contains(groupId)) {
			if (draft.enabled().contains(groupId)) return ChatFormatting.GREEN;
			return ChatFormatting.AQUA;
		}
		return ChatFormatting.WHITE;
	}

	private void onListToggle(GroupSelectionList.Item item) {
		if (item.kind() == GroupSelectionList.Kind.HEADER) {
			List<String> members = new ArrayList<>();
			for (var entry : groups.entrySet()) {
				if (entry.getValue().selection().choosable() && item.categoryId().equals(entry.getValue().category())) members.add(entry.getKey());
			}
			List<String> toggleable = compatibleNonCoreChoosable(members);
			boolean allSelected = toggleable.stream().allMatch(resolution.selectedGroups()::contains);
			applyUpdate(GroupSelectionResolver.setCategoryAll(manifest, draft, item.categoryId(), !allSelected, effectivePlatform()));
			return;
		}
		if (item.kind() == GroupSelectionList.Kind.RADIO_GROUP) {
			String chosen = item.id().isEmpty() ? null : item.id();
			applyUpdate(GroupSelectionResolver.pickInCategory(manifest, draft, item.categoryId(), chosen, effectivePlatform()));
			return;
		}
		applyUpdate(GroupSelectionResolver.toggleGroup(manifest, draft, item.id(), effectivePlatform()));
	}

	private void applyUpdate(GroupSelectionResolver.SelectionUpdate update) {
		if (!update.ok()) {
			this.resolutionError = update.error();
			rebuild();
			return;
		}
		this.draft = update.intent();
		useResolved(update.resolution());
		this.resolutionError = "";
		String noticeKey = update.turnedOffByConflict() ? "automodpack.selection.conflictTurnedOff" : "automodpack.selection.cascadeTurnedOff";
		this.notice = update.turnedOff().isEmpty() ? "" : UiFormat.plural(update.turnedOff().size(), noticeKey, names(update.turnedOff())).getString();
		rebuild();
	}

	private void resetToDefaults() {
		SelectionIntent reset = SelectionIntent.DEFAULTS.withPlatform(override());
		try {
			ResolvedSelection resolved = GroupSelectionResolver.resolve(manifest, reset, effectivePlatform());
			this.draft = reset;
			useResolved(resolved);
			this.resolutionError = "";
			this.notice = "";
			rebuild();
		} catch (SelectionResolutionException e) {
			this.resolutionError = String.join("; ", e.errors());
			rebuild();
		}
	}

	private void onListInspect(GroupSelectionList.Item item) {
		if (item.kind() != GroupSelectionList.Kind.HEADER && !item.id().isEmpty()) inspect(item.id());
	}

	private void inspect(String groupId) {
		if (!groups.containsKey(groupId)) return;
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.literal(manifest.displayName(groupId)),
				VersionedText.text("automodpack.browser.groupDescription"), ChangeSet.catalogue(manifest), InstalledModpackController.groupNames(manifest), null, List.of(), 0, groupId));
	}

	@Override
	public void tick() {
		super.tick();
		if (session.isCancelled()) ScreenImpl.multiplayer();
	}

	private boolean canToggle(String groupId, GroupManifest.Group group) {
		if (group == null || !group.selection().choosable()) return false;
		if (resolution.requiredGroups().contains(groupId)) return false;
		GroupManifest.Category cat = manifest.categoryOf(groupId);
		if (cat != null && cat.pick() == CategoryPickMode.EXACTLY_ONE && resolution.selectedGroups().contains(groupId)) return false;
		GroupResolution explanation = resolution.resolution(groupId);
		if (explanation != null && explanation.status() == GroupResolution.Status.BLOCKED && !resolution.selectedGroups().contains(groupId)) return false;
		return group.supports(effectivePlatform()) || draft.enabled().contains(groupId);
	}

	private List<String> compatibleNonCoreChoosable(List<String> members) {
		List<String> list = new ArrayList<>();
		ClientPlatform platform = effectivePlatform();
		for (String id : members) {
			GroupManifest.Group group = groups.get(id);
			if (group != null && group.selection().choosable() && group.supports(platform) && !resolution.requiredGroups().contains(id)) list.add(id);
		}
		return list;
	}

	private void useResolved(ResolvedSelection resolved) {
		this.resolution = Objects.requireNonNull(resolved, "resolution");
		this.summary = GroupSelectionSummary.of(manifest, resolved.selectedGroups());
	}

	private void save() {
		if (!canSave()) return;
		submitting = true;
		if (saveButton != null) saveButton.active = false;
		session.submit(draft, this::releaseSubmit);
	}

	private void releaseSubmit() {
		Runnable finish = () -> {
			submitting = false;
			if (!closed) rebuild();
		};
		if (this.minecraft != null) this.minecraft.execute(finish);
		else finish.run();
	}

	private void back() {
		if (closed) return;
		closed = true;
		session.cancel();
		ScreenImpl.setScreen(parent);
	}

	private ClientPlatform override() {
		ClientPlatform current = draft.platform();
		return Objects.equals(detectedPlatform, current) ? null : current;
	}

	private ClientPlatform effectivePlatform() {
		return ClientPlatform.effective(draft);
	}

	private String platformLabel() {
		return VersionedText.str("automodpack.selection.platformButton", platformDisplay(effectivePlatform()));
	}

	private String platformDisplay(ClientPlatform platform) {
		if (platform == null) return VersionedText.str("automodpack.selection.platformUndetected");
		if (platform.equals(ClientPlatform.WINDOWS)) return "Windows";
		if (platform.equals(ClientPlatform.LINUX)) return "Linux";
		if (platform.equals(ClientPlatform.MACOS)) return "macOS";
		return platform.id();
	}

	private void pickPlatform(ClientPlatform platform) {
		ClientPlatform nextOverride = Objects.equals(detectedPlatform, platform) ? null : platform;
		ClientPlatform effective = nextOverride != null ? nextOverride : detectedPlatform;
		applyUpdate(GroupSelectionResolver.withPlatform(manifest, draft, nextOverride, effective));
	}

	private boolean canSave() {
		boolean platformChanged = !Objects.equals(ClientPlatform.effective(initialSelection), effectivePlatform());
		return !submitting && resolutionError.isEmpty() && (session.allowSubmitUnchanged() || !initialSelection.equals(draft) || platformChanged);
	}

	private static long groupBytes(GroupManifest.Group group) {
		long total = 0;
		for (GroupManifest.GroupFile file : group.files().values()) total = Math.addExact(total, file.size());
		return total;
	}

	private String names(Iterable<String> ids) {
		List<String> list = new ArrayList<>();
		for (String id : ids) list.add(groups.containsKey(id) ? manifest.displayName(id) : VersionedText.str("automodpack.browser.unknownGroup"));
		return list.isEmpty() ? VersionedText.str("automodpack.ui.none") : String.join(", ", list);
	}

	private String summaryText() {
		int coreCount = 0;
		int optionalSelected = 0;
		int optionalTotal = 0;
		ClientPlatform platform = effectivePlatform();
		for (var entry : groups.entrySet()) {
			GroupManifest.Group group = entry.getValue();
			boolean selected = resolution.selectedGroups().contains(entry.getKey());
			if (group.selection().choosable()) {
				if (group.supports(platform)) {
					optionalTotal++;
					if (selected) optionalSelected++;
				}
			} else if (selected) {
				coreCount++;
			}
		}
		String counts = optionalTotal > 0
				? VersionedText.str("automodpack.selection.countsWithOptional", coreCount, optionalSelected, optionalTotal)
				: VersionedText.str("automodpack.selection.countsCoreOnly", coreCount);
		String size = UiFormat.formatSize(summary == null ? 0 : summary.byteCount());
		if (!manifest.hasDeclaredPlatforms()) return VersionedText.str("automodpack.selection.summary", counts, size);
		return VersionedText.str("automodpack.selection.summaryPlatform", platformDisplay(platform), counts, size);
	}

	private List<String> wrappedDescription = List.of();
	private int wrappedDescriptionWidth = -1;

	private List<String> wrappedDescription(int width, String description) {
		if (width != wrappedDescriptionWidth) {
			wrappedDescription = wrapToWidth(this.font, description, width);
			wrappedDescriptionWidth = width;
		}
		return wrappedDescription;
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		MutableComponent header = modpackName.isBlank() ? VersionedText.text("automodpack.selection.title") : VersionedText.literal(modpackName);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, header.getString(), this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 11,
				TextColors.WHITE);
		MutableComponent description = VersionedText.text("automodpack.selection.description");
		int railLeft = panelLeft(PANEL_WIDTH);
		int railWidth = panelWidth(PANEL_WIDTH);
		int railY = 22;
		if (!origin.isBlank()) {
			drawTextWithShadow(matrices, this.font, VersionedText.text("automodpack.packDetails.server", origin).withStyle(ChatFormatting.GRAY), railLeft, railY, TextColors.WHITE);
			railY += 11;
		}
		int dropdownReserve = platformDropdown != null ? platformDropdown.getWidth() + 8 : 0;
		List<String> descriptionLines = wrappedDescription(railWidth - dropdownReserve, description.getString());
		int maxDescriptionLines = Math.max(1, (44 - railY) / 11);
		if (descriptionLines.size() > maxDescriptionLines) descriptionLines = descriptionLines.subList(0, maxDescriptionLines);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.GRAY), railLeft, railY + index * 11, TextColors.WHITE);

		drawTextWithShadow(matrices, this.font,
				VersionedText.literal(summaryText()).withStyle(override() == null ? ChatFormatting.GRAY : ChatFormatting.YELLOW),
				railLeft, 44, TextColors.WHITE);

		if (!resolutionError.isEmpty()) {
			drawWrappedStatus(matrices, VersionedText.literal(resolutionError).withStyle(ChatFormatting.RED));
		} else if (!notice.isEmpty()) {
			drawWrappedStatus(matrices, VersionedText.literal(notice).withStyle(ChatFormatting.YELLOW));
		} else {
			SourceAvailability availability = session.sourceAvailability();
			if (availability != null && availability.totalFiles() > 0) {
				String sourceStatus = VersionedText.str(availability.cancelled()
						? "automodpack.selection.sourcesCancelled"
						: !availability.complete()
								? "automodpack.selection.sourcesResolving"
								: "automodpack.selection.sourcesResolved",
						availability.resolvedFiles(), availability.totalFiles());
				drawWrappedStatus(matrices, VersionedText.literal(sourceStatus).withStyle(ChatFormatting.GRAY));
			}
		}
	}

	private void drawWrappedStatus(VersionedMatrices matrices, MutableComponent text) {
		List<String> lines = wrapToWidth(this.font, text.getString(), panelWidth(PANEL_WIDTH), 2);
		int firstY = lines.size() > 1 ? 55 : 60;
		for (String line : lines) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(text.getStyle()), this.width / 2, firstY, TextColors.WHITE);
			firstY += 11;
		}
	}

	@Override
	public void onClose() {
		if (closeOpenMenus()) return;
		back();
	}
}
