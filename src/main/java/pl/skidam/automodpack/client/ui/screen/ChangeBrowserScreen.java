package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.ChangeBrowserWidget;
import pl.skidam.automodpack.client.ui.widget.DropdownWidget;
import pl.skidam.automodpack_core.change.ChangeBrowserProjection;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.change.PlatformReferences;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Shared vanilla-style file browser used for installed catalogues and generation diffs. */
public class ChangeBrowserScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int GAP = 6;
	private static final int LINE_STEP = 10;
	private final Screen parent;
	private final Component heading;
	private final Component description;
	private final List<MutableComponent> preamble;
	private final long downloadBytes;
	private ChangeSet changes;
	private final Map<String, String> groupNames;
	private final BrowserAction auxiliaryAction;
	private boolean closed;
	private boolean cacheLookupStarted;
	private boolean referencesResolved;
	private final Set<String> collapsedFolders = new TreeSet<>();
	private String search = "";
	private String selectedContent = "";
	private String selectedGroup;
	private Boolean selectedSource = null;
	private String selectedPath = "";
	private ChangeBrowserWidget browser;
	private DropdownWidget groupDropdown;
	private DropdownWidget contentDropdown;
	private DropdownWidget sourceDropdown;
	private EditBox searchField;
	private int browserTop;
	private int browserBottom;
	private int summaryY;
	private int paneTop;
	private int paneActionsY;
	private ChangeBrowserProjection.Projection currentProjection;
	private String summaryText;
	private final List<Button> paneButtons = new ArrayList<>();

	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> groupNames) {
		this(parent, heading, description, changes, groupNames, null, List.of(), 0, "");
	}

	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> groupNames, BrowserAction auxiliaryAction) {
		this(parent, heading, description, changes, groupNames, auxiliaryAction, List.of(), 0, "");
	}

	/** The preamble is a pre-wrapped text block (for example an entry's full patch notes) drawn between the description and the browser. */
	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> groupNames, BrowserAction auxiliaryAction, List<? extends MutableComponent> preamble,
			long downloadBytes, String initialGroup) {
		super(heading);
		this.parent = parent;
		this.heading = Objects.requireNonNull(heading, "browser heading");
		this.description = Objects.requireNonNull(description, "browser description");
		this.preamble = preamble == null ? List.of() : List.copyOf(preamble);
		this.changes = Objects.requireNonNull(changes, "browser changes");
		this.groupNames = Map.copyOf(groupNames == null ? Map.of() : groupNames);
		this.auxiliaryAction = auxiliaryAction;
		this.downloadBytes = Math.max(0, downloadBytes);
		this.selectedGroup = initialGroup == null ? "" : initialGroup;
	}

	@Override
	protected void init() {
		super.init();
		resolveCachedReferences();
		int panelLeft = panelLeft(PANEL_WIDTH);
		int panelWidth = panelWidth(PANEL_WIDTH);
		int preambleHeight = preamble.isEmpty() ? 0 : preamble.size() * LINE_STEP + 6;
		boolean narrow = panelWidth < 500;
		int searchWidth = narrow ? panelWidth : 280;
		int searchY = 35 + preambleHeight;
		int controlsY = (narrow ? 59 : 35) + preambleHeight;
		int controlsLeft = narrow ? panelLeft : panelLeft + searchWidth + GAP;
		int controlCount = 3;
		int controlWidth = Math.max(1, (panelWidth - (narrow ? GAP * controlCount : searchWidth + GAP * (controlCount + 1))) / controlCount);
		this.browserTop = (narrow ? 83 : 59) + preambleHeight;
		this.searchField = fieldWidget(panelLeft, searchY, searchWidth, VersionedText.text("automodpack.browser.search"), null, Integer.MAX_VALUE);
		this.searchField.setValue(search);
		String searchHint = VersionedText.text("automodpack.browser.search").getString();
		this.searchField.setSuggestion(search.isEmpty() ? searchHint : "");
		this.searchField.setResponder(value -> {
			search = value;
			searchField.setSuggestion(value.isEmpty() ? searchHint : "");
			rebuildBrowser();
		});
		List<ActionRow> actionRows = buildActionRows();
		List<AbstractWidget> actionButtons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows.toArray(ActionRow[]::new));
		if (auxiliaryAction != null) actionButtons.get(actionButtons.size() - 1).active = auxiliaryAction.active();
		int footerTop = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows.toArray(ActionRow[]::new));
		int summaryY = footerTop - this.font.lineHeight - 5;
		// The pane reserves all three bands — path, facts, action rail — so selection never moves the layout.
		int paneTop = summaryY - 3 - (2 * this.font.lineHeight + 2 + ActionAreaLayout.BUTTON_HEIGHT);
		this.summaryY = summaryY;
		this.paneTop = paneTop;
		this.browserBottom = paneTop - 4;
		this.paneActionsY = paneTop + 2 * this.font.lineHeight + 2;
		this.contentDropdown = dropdownWidget(controlsLeft, controlsY, controlWidth, 20, VersionedText.literal(""));
		this.groupDropdown = dropdownWidget(controlsLeft + GAP + controlWidth, controlsY, controlWidth, 20, VersionedText.literal(""));
		this.sourceDropdown = dropdownWidget(controlsLeft + (GAP + controlWidth) * 2, controlsY, controlWidth, 20, VersionedText.literal(""));
		refreshDropdowns();
		rebuildBrowser();
		addPaneActions(this.paneActionsY);
	}

	/** Re-sets every dropdown's label, options and highlight from the current selection and change set. */
	private void refreshDropdowns() {
		if (contentDropdown != null) {
			contentDropdown.setMessage(VersionedText.text("automodpack.browser.contentFilter",
					selectedContent.isBlank() ? VersionedText.text("automodpack.browser.all").getString() : VersionedText.text("automodpack.browser.content." + selectedContent).getString()));
			List<String> ids = new ArrayList<>();
			ids.add("");
			ids.addAll(contentKinds());
			List<Component> options = new ArrayList<>(ids.size());
			for (String id : ids)
				options.add(id.isBlank() ? VersionedText.text("automodpack.browser.all") : VersionedText.text("automodpack.browser.content." + id));
			contentDropdown.setOptions(options, Math.max(0, ids.indexOf(selectedContent)), browserBottom, index -> pickContent(ids.get(index)));
		}
		if (groupDropdown != null) {
			groupDropdown.active = !groupIds().isEmpty();
			groupDropdown.setMessage(VersionedText.text("automodpack.browser.groupFilter",
					selectedGroup.isBlank() ? VersionedText.text("automodpack.browser.allGroups").getString() : groupName(selectedGroup)));
			List<String> optionIds = new ArrayList<>();
			optionIds.add("");
			List<String> ids = new ArrayList<>(groupIds());
			ids.sort(Comparator.comparing(this::groupName, String.CASE_INSENSITIVE_ORDER));
			optionIds.addAll(ids);
			List<Component> options = new ArrayList<>(optionIds.size());
			for (String optionId : optionIds)
				options.add(optionId.isBlank() ? VersionedText.text("automodpack.browser.allGroups") : VersionedText.literal(groupName(optionId)));
			groupDropdown.setOptions(options, Math.max(0, optionIds.indexOf(selectedGroup)), browserBottom, index -> pickGroup(optionIds.get(index)));
		}
		if (sourceDropdown != null) {
			sourceDropdown.setMessage(VersionedText.text("automodpack.browser.sourceFilter",
					selectedSource == null
							? VersionedText.text("automodpack.browser.all").getString()
							: VersionedText.text(selectedSource.booleanValue() ? "automodpack.browser.source.published" : "automodpack.browser.source.custom").getString()));
			List<Boolean> ids = Arrays.asList(null, Boolean.TRUE, Boolean.FALSE);
			sourceDropdown.setOptions(List.of(
					VersionedText.text("automodpack.browser.all"),
					VersionedText.text("automodpack.browser.source.published"),
					VersionedText.text("automodpack.browser.source.custom")), selectedSource == null ? 0 : selectedSource.booleanValue() ? 1 : 2, browserBottom, index -> pickSource(ids.get(index)));
		}
	}

	/** The selection's fixed details rail: Modrinth, CurseForge and Copy hash keep their places and just enable per selection. */
	private void addPaneActions(int topY) {
		for (Button button : paneButtons) this.removeWidget(button);
		paneButtons.clear();
		String hash = selectedHash();
		List<ActionAreaLayout.Action> geometry = List.of(
				new ActionAreaLayout.Action("modrinth", ActionAreaLayout.Role.OPTIONAL),
				new ActionAreaLayout.Action("curseforge", ActionAreaLayout.Role.OPTIONAL),
				new ActionAreaLayout.Action("hash", ActionAreaLayout.Role.OPTIONAL));
		ActionAreaLayout.Layout layout = ActionAreaLayout.fromTop(panelLeft(ActionAreaLayout.FOOTER_RAIL), topY, panelWidth(ActionAreaLayout.FOOTER_RAIL), ActionAreaLayout.GAP,
				List.of(new ActionAreaLayout.Row(ActionAreaLayout.RowKind.AUXILIARY, geometry)));
		for (ActionAreaLayout.Placement placement : layout.placements()) {
			Button button = switch (placement.id()) {
				case "modrinth" ->
					buttonWidget(placement.x(), placement.y(), placement.width(), placement.height(), VersionedText.text("automodpack.browser.modrinth"), press -> openPage(platformUrl("modrinth")));
				case "curseforge" ->
					buttonWidget(placement.x(), placement.y(), placement.width(), placement.height(), VersionedText.text("automodpack.browser.curseforge"), press -> openPage(platformUrl("curseforge")));
				default -> buttonWidget(placement.x(), placement.y(), placement.width(), placement.height(), VersionedText.text("automodpack.browser.copyHash"), press -> copyHash());
			};
			button.active = placement.id().equals("hash") ? hash != null : platformUrl(placement.id()) != null;
			if (placement.id().equals("hash") && hash != null) setTooltip(button, VersionedText.text("automodpack.browser.copyHashTooltip").append("\n" + hash));
			paneButtons.add(button);
			this.addRenderableWidget(button);
		}
	}

	private List<ActionRow> buildActionRows() {
		List<ActionRow> actionRows = new ArrayList<>();
		if (auxiliaryAction != null)
			actionRows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), button -> back()),
					optionalAction(auxiliaryAction.label(), button -> auxiliaryAction.action().accept(this))));
		else
			actionRows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), button -> back())));
		return actionRows;
	}

	/** One representative hash for the selected file: the hash of the state written on disk. */
	private String selectedHash() {
		ChangeBrowserProjection.FileRow file = menuOpen() ? null : this.browser == null ? null : this.browser.selectedFile();
		return file == null ? null : file.writtenHash();
	}

	private void rebuildBrowser() {
		if (this.minecraft == null) return;
		ChangeBrowserProjection.Filter filter = new ChangeBrowserProjection.Filter(search,
				selectedContent.isBlank() ? Set.of() : Set.of(selectedContent), selectedGroup.isBlank() ? Set.of() : Set.of(selectedGroup), selectedSource);
		this.currentProjection = ChangeBrowserProjection.project(changes, ChangeBrowserProjection.Mode.TREE, filter).collapse(collapsedFolders);
		recomputeSummary();
		if (menuOpen()) return;
		if (this.browser != null) this.removeWidget(this.browser);
		this.browser = new ChangeBrowserWidget(this.currentProjection, collapsedFolders, groupNames, referencesResolved, this::toggleFolder, this::onFileSelected,
				this.minecraft, this.width, this.height, browserTop, browserBottom);
		this.addRenderableWidget(this.browser);
		this.browser.selectPath(selectedPath);
	}

	/** The totals line under the browser, computed from the cached projection whenever the projection changes. */
	private void recomputeSummary() {
		if (currentProjection == null) {
			this.summaryText = "";
			return;
		}
		String summary = UiFormat.plural(currentProjection.total().fileCount(), "automodpack.browser.summary", UiFormat.formatSize(currentProjection.total().byteCount())).getString();
		if (!currentProjection.effects().isEmpty()) summary += " | " + UiFormat.plural(currentProjection.effects().size(), "automodpack.browser.effectsSummary").getString();
		long custom = currentProjection.files().stream().filter(ChangeBrowserScreen::isUnreferencedJar).count();
		if (custom > 0) summary += " · " + UiFormat.plural(custom, "automodpack.browser.customSummary").getString();
		if (downloadBytes > 0) summary += " · " + VersionedText.text("automodpack.browser.downloadCost", UiFormat.formatSize(downloadBytes)).getString();
		this.summaryText = summary;
	}

	private void onFileSelected(ChangeBrowserProjection.FileRow file) {
		selectedPath = file == null ? "" : file.path();
		rebuild();
	}

	private void toggleFolder(String path) {
		if (!collapsedFolders.remove(path)) collapsedFolders.add(path);
		rebuildBrowser();
	}

	private void pickContent(String kind) {
		selectedContent = kind;
		refreshDropdowns();
		rebuildBrowser();
	}

	private void pickSource(Boolean source) {
		selectedSource = source;
		refreshDropdowns();
		rebuildBrowser();
	}

	private void pickGroup(String groupId) {
		selectedGroup = groupId;
		refreshDropdowns();
		rebuildBrowser();
	}

	private List<String> contentKinds() {
		Set<String> values = new TreeSet<>();
		for (ChangeSet.Change change : changes.changes()) for (ChangeSet.Occurrence occurrence : change.occurrences()) values.add(occurrence.contentKind());
		return List.copyOf(values);
	}

	private List<String> groupIds() {
		Set<String> values = new TreeSet<>();
		for (ChangeSet.Change change : changes.changes()) for (ChangeSet.Occurrence occurrence : change.occurrences()) values.addAll(occurrence.featureIds());
		return List.copyOf(values);
	}

	private String groupName(String groupId) {
		String name = groupNames.get(groupId);
		return name == null || name.isBlank() ? VersionedText.text("automodpack.browser.unknownGroup").getString() : name;
	}

	private void resolveCachedReferences() {
		if (cacheLookupStarted) return;
		cacheLookupStarted = true;
		if (!hasAnyHash()) {
			referencesResolved = true;
			return;
		}
		ScreenManager.background(() -> {
			ChangeSet referenced = PlatformReferences.withCachedReferences(changes, ClientStorage.open(GameDirectory.current()).platformCacheDirectory());
			this.minecraft.execute(() -> {
				referencesResolved = true;
				if (closed || referenced == changes) return;
				changes = referenced;
				// In-place refresh instead of a full screen rebuild, so the search field keeps its content and focus.
				double scrollAmount = this.browser == null ? 0 : this.browser.preservedScrollAmount();
				rebuildBrowser();
				if (this.browser == null) return;
				// Both vanilla setters clamp, so a stale scroll from the smaller old list is safe.
				this.browser.restoreScrollAmount(scrollAmount);
				addPaneActions(this.paneActionsY);
			});
		});
	}

	private boolean hasAnyHash() {
		for (ChangeSet.Change change : changes.changes())
			for (ChangeSet.Occurrence occurrence : change.occurrences())
				if (occurrence.beforeHash() != null || occurrence.afterHash() != null) return true;
		return false;
	}

	/** A jar whose every occurrence lacks a platform reference: no Modrinth or CurseForge page is recorded for it. */
	private static boolean isUnreferencedJar(ChangeBrowserProjection.FileRow file) {
		if (!file.path().toLowerCase(Locale.ROOT).endsWith(".jar")) return false;
		for (ChangeSet.Occurrence occurrence : file.occurrences()) if (!occurrence.references().isEmpty()) return false;
		return true;
	}

	/** The selected file's first cached page URL for the platform, or null when it has none. */
	private String platformUrl(String platform) {
		if (selectedPath == null || selectedPath.isBlank()) return null;
		for (ChangeSet.Change change : changes.changes()) {
			if (!change.logicalPath().equals(selectedPath)) continue;
			for (ChangeSet.Occurrence occurrence : change.occurrences())
				for (String reference : occurrence.references())
					if (ChangeBrowserWidget.platform(reference).equals(platform)) return reference;
		}
		return null;
	}

	private void openPage(String url) {
		if (url != null) Util.getPlatform().openUri(url);
	}

	private void copyHash() {
		String hash = selectedHash();
		if (hash != null) Minecraft.getInstance().keyboardHandler.setClipboard(hash);
	}

	private void back() {
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int contentWidth = panelWidth(PANEL_WIDTH);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, heading.getString(), contentWidth)).withStyle(ChatFormatting.BOLD), this.width / 2, 8, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, description.getString(), contentWidth)).withStyle(ChatFormatting.GRAY), this.width / 2, 21, TextColors.WHITE);
		int preambleY = 34;
		for (MutableComponent line : preamble) {
			drawCenteredTextWithShadow(matrices, this.font, line, this.width / 2, preambleY, TextColors.WHITE);
			preambleY += LINE_STEP;
		}
		String summary = summaryText == null ? "" : summaryText;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, summary, contentWidth)).withStyle(ChatFormatting.GRAY), this.width / 2, this.summaryY, TextColors.WHITE);
		if (currentProjection == null || currentProjection.rows().isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.browser.empty").withStyle(ChatFormatting.GRAY), this.width / 2, browserTop + 24, TextColors.WHITE);
		ChangeBrowserProjection.FileRow selected = menuOpen() || this.browser == null ? null : this.browser.selectedFile();
		if (selected == null) drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.browser.selectHint").withStyle(ChatFormatting.GRAY), this.width / 2, this.paneTop, TextColors.WHITE);
		else {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, selected.path(), contentWidth)).withStyle(ChatFormatting.WHITE), this.width / 2, this.paneTop,
					TextColors.WHITE);
			String facts = this.browser.facts();
			String hash = selected.writtenHash();
			if (hash != null) facts += " · sha1 " + hash.substring(0, Math.min(12, hash.length()));
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, facts, contentWidth)).withStyle(ChatFormatting.GRAY), this.width / 2, this.paneTop + this.font.lineHeight,
					TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		if (closeOpenMenus()) {
			rebuildBrowser();
			return false;
		}
		return handleBackOnEscape(this::back);
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	public record BrowserAction(Component label, Consumer<Screen> action, boolean active) {
		public BrowserAction {
			label = Objects.requireNonNull(label, "browser action");
			action = Objects.requireNonNull(action, "browser action");
		}
	}
}
