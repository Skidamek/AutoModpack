package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.widget.ChangeBrowserWidget;
import pl.skidam.automodpack_core.change.ChangeBrowserProjection;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.change.PlatformReferences;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Shared vanilla-style file browser used for installed catalogues and generation diffs. */
public class ChangeBrowserScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int GAP = 6;
	private static final int LINE_STEP = 10;
	private final Screen parent;
	private final Component heading;
	private final Component description;
	private final List<MutableComponent> preamble;
	private final boolean warnUnverified;
	private final long downloadBytes;
	private ChangeSet changes;
	private final Map<String, String> featureNames;
	private final BrowserAction auxiliaryAction;
	private boolean closed;
	private boolean cacheLookupStarted;
	private boolean referencesResolved;
	private final Set<String> collapsedFolders = new TreeSet<>();
	private String search = "";
	private String selectedContent = "";
	private String selectedFeature = "";
	private Boolean selectedSource = null;
	private String selectedPath = "";
	private ChangeBrowserWidget browser;
	private EditBox searchField;
	private Button contentButton;
	private Button featureButton;
	private Button sourceButton;
	private int browserTop;
	private int browserBottom;
	private int summaryY;
	private int paneTop;
	private int paneActionsY;
	private ChangeBrowserProjection.Projection currentProjection;
	private String summaryText;
	private final List<Button> paneButtons = new ArrayList<>();

	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> featureNames) {
		this(parent, heading, description, changes, featureNames, null, List.of(), false, 0);
	}

	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> featureNames, BrowserAction auxiliaryAction) {
		this(parent, heading, description, changes, featureNames, auxiliaryAction, List.of(), false, 0);
	}

	/** The preamble is a pre-wrapped text block (for example an entry's full patch notes) drawn between the description and the browser. */
	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> featureNames, BrowserAction auxiliaryAction, List<? extends MutableComponent> preamble, boolean warnUnverified, long downloadBytes) {
		super(heading);
		this.parent = parent;
		this.heading = Objects.requireNonNull(heading, "browser heading");
		this.description = Objects.requireNonNull(description, "browser description");
		this.preamble = preamble == null ? List.of() : List.copyOf(preamble);
		this.changes = Objects.requireNonNull(changes, "browser changes");
		this.featureNames = Map.copyOf(featureNames == null ? Map.of() : featureNames);
		this.auxiliaryAction = auxiliaryAction;
		this.warnUnverified = warnUnverified;
		this.downloadBytes = Math.max(0, downloadBytes);
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
		int controlCount = warnUnverified ? 3 : 2;
		int controlWidth = Math.max(1, (panelWidth - (narrow ? GAP * controlCount : searchWidth + GAP * (controlCount + 1))) / controlCount);
		this.browserTop = (narrow ? 83 : 59) + preambleHeight;
		this.searchField = fieldWidget(panelLeft, searchY, searchWidth, VersionedText.translatable("automodpack.browser.search"), null, Integer.MAX_VALUE);
		this.searchField.setValue(search);
		String searchHint = VersionedText.translatable("automodpack.browser.search").getString();
		this.searchField.setSuggestion(search.isEmpty() ? searchHint : "");
		this.searchField.setResponder(value -> {
			search = value;
			searchField.setSuggestion(value.isEmpty() ? searchHint : "");
			rebuildBrowser();
		});
		this.contentButton = buttonWidget(controlsLeft, controlsY, controlWidth, 20, VersionedText.literal(""), button -> cycleContent());
		this.featureButton = buttonWidget(controlsLeft + GAP + controlWidth, controlsY, controlWidth, 20, VersionedText.literal(""), button -> cycleFeature());
		this.addRenderableWidget(this.contentButton);
		this.addRenderableWidget(this.featureButton);
		if (warnUnverified) {
			this.sourceButton = buttonWidget(controlsLeft + (GAP + controlWidth) * 2, controlsY, controlWidth, 20, VersionedText.literal(""), button -> cycleSource());
			this.addRenderableWidget(this.sourceButton);
		}
		updateControlLabels();
		List<ActionRow> actionRows = buildActionRows();
		List<Button> actionButtons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows.toArray(ActionRow[]::new));
		if (auxiliaryAction != null) actionButtons.get(actionButtons.size() - 2).active = auxiliaryAction.active();
		int footerTop = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows.toArray(ActionRow[]::new));
		int summaryY = footerTop - this.font.lineHeight - 5;
		// The pane reserves all three bands — path, facts, action rail — so selection never moves the layout.
		int paneTop = summaryY - 3 - (2 * this.font.lineHeight + 2 + ActionAreaLayout.BUTTON_HEIGHT);
		this.summaryY = summaryY;
		this.paneTop = paneTop;
		this.browserBottom = paneTop - 4;
		this.paneActionsY = paneTop + 2 * this.font.lineHeight + 2;
		rebuildBrowser();
		addPaneActions(this.paneActionsY);
	}

	/** The selected file's pane buttons on the details line: one per storefront link, then Copy hash. */
	private void addPaneActions(int topY) {
		for (Button button : paneButtons) this.removeWidget(button);
		paneButtons.clear();
		List<PlatformLink> platforms = platformLinks();
		String hash = selectedHash();
		if (platforms.isEmpty() && hash == null) return;
		List<PaneAction> actions = new ArrayList<>();
		for (PlatformLink platform : platforms) actions.add(new PaneAction(platform.label(), button -> Util.getPlatform().openUri(platform.url()), false));
		if (hash != null) actions.add(new PaneAction(VersionedText.translatable("automodpack.browser.copyHash"), button -> Minecraft.getInstance().keyboardHandler.setClipboard(hash), true));
		List<ActionAreaLayout.Action> geometry = new ArrayList<>();
		for (int index = 0; index < actions.size(); index++) geometry.add(new ActionAreaLayout.Action(String.valueOf(index), ActionAreaLayout.Role.OPTIONAL));
		ActionAreaLayout.Layout layout = ActionAreaLayout.fromTop(panelLeft(ActionAreaLayout.FOOTER_RAIL), topY, panelWidth(ActionAreaLayout.FOOTER_RAIL), ActionAreaLayout.GAP, List.of(new ActionAreaLayout.Row(ActionAreaLayout.RowKind.AUXILIARY, geometry)));
		List<ActionAreaLayout.Placement> placements = layout.placements();
		for (int index = 0; index < placements.size(); index++) {
			ActionAreaLayout.Placement placement = placements.get(index);
			PaneAction action = actions.get(index);
			Button button = buttonWidget(placement.x(), placement.y(), placement.width(), placement.height(), action.label(), action.onPress());
			if (action.copyHash()) setTooltip(button, VersionedText.translatable("automodpack.browser.copyHashTooltip").append("\n" + hash));
			paneButtons.add(button);
			this.addRenderableWidget(button);
		}
	}

	private List<ActionRow> buildActionRows() {
		List<ActionRow> actionRows = new ArrayList<>();
		if (auxiliaryAction != null) actionRows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, optionalAction(auxiliaryAction.label(), button -> auxiliaryAction.action().accept(this)), secondaryAction(VersionedText.translatable("automodpack.back"), button -> back())));
		else actionRows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> back())));
		return actionRows;
	}

	/** One representative hash for the selected file: the hash of the state written on disk. */
	private String selectedHash() {
		ChangeBrowserProjection.FileRow file = this.browser == null ? null : this.browser.selectedFile();
		return file == null ? null : file.writtenHash();
	}

	private void rebuildBrowser() {
		if (this.minecraft == null) return;
		if (this.browser != null) this.removeWidget(this.browser);
		ChangeBrowserProjection.Filter filter = new ChangeBrowserProjection.Filter(search,
				selectedContent.isBlank() ? Set.of() : Set.of(selectedContent), selectedFeature.isBlank() ? Set.of() : Set.of(selectedFeature), selectedSource);
		this.currentProjection = ChangeBrowserProjection.project(changes, ChangeBrowserProjection.Mode.TREE, filter).collapse(collapsedFolders);
		this.browser = new ChangeBrowserWidget(this.currentProjection, collapsedFolders, featureNames, warnUnverified, referencesResolved, this::toggleFolder, this::onFileSelected,
				this.minecraft, this.width, this.height, browserTop, browserBottom);
		this.addRenderableWidget(this.browser);
		this.browser.selectPath(selectedPath);
		recomputeSummary();
	}

	/** The totals line under the browser, computed from the cached projection whenever the projection changes. */
	private void recomputeSummary() {
		if (currentProjection == null) {
			this.summaryText = "";
			return;
		}
		String summary = UiFormat.plural(currentProjection.total().fileCount(), "automodpack.browser.summary", UiFormat.formatSize(currentProjection.total().byteCount())).getString();
		if (!currentProjection.effects().isEmpty()) summary += " | " + UiFormat.plural(currentProjection.effects().size(), "automodpack.browser.effectsSummary").getString();
		if (warnUnverified) {
			long custom = currentProjection.files().stream().filter(ChangeBrowserScreen::isUnreferencedJar).count();
			if (custom > 0) summary += " · " + UiFormat.plural(custom, "automodpack.browser.customSummary").getString();
		}
		if (downloadBytes > 0) summary += " · " + VersionedText.translatable("automodpack.browser.downloadCost", UiFormat.formatSize(downloadBytes)).getString();
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

	private void cycleContent() {
		selectedContent = next(selectedContent, contentKinds());
		updateControlLabels();
		rebuildBrowser();
	}

	private void cycleFeature() {
		selectedFeature = next(selectedFeature, features());
		updateControlLabels();
		rebuildBrowser();
	}

	private void cycleSource() {
		selectedSource = selectedSource == null ? Boolean.FALSE : selectedSource.booleanValue() ? null : Boolean.TRUE;
		updateControlLabels();
		rebuildBrowser();
	}

	private List<String> contentKinds() {
		Set<String> values = new TreeSet<>();
		for (ChangeSet.Change change : changes.changes()) for (ChangeSet.Occurrence occurrence : change.occurrences()) values.add(occurrence.contentKind());
		return List.copyOf(values);
	}

	private List<String> features() {
		Set<String> values = new TreeSet<>();
		for (ChangeSet.Change change : changes.changes()) for (ChangeSet.Occurrence occurrence : change.occurrences()) values.addAll(occurrence.featureIds());
		return List.copyOf(values);
	}

	private static String next(String current, List<String> values) {
		if (values.isEmpty()) return "";
		if (current.isBlank()) return values.get(0);
		int index = values.indexOf(current);
		return index < 0 || index + 1 >= values.size() ? "" : values.get(index + 1);
	}

	private void updateControlLabels() {
		if (contentButton != null) contentButton.setMessage(VersionedText.translatable("automodpack.browser.contentFilter",
				selectedContent.isBlank() ? VersionedText.translatable("automodpack.browser.all").getString() : VersionedText.translatable("automodpack.browser.content." + selectedContent).getString()));
		if (featureButton != null) {
			featureButton.active = !features().isEmpty();
			String label = featureNames.get(selectedFeature);
			if (selectedFeature.isBlank()) label = VersionedText.translatable("automodpack.browser.all").getString();
			else if (label == null || label.isBlank()) label = VersionedText.translatable("automodpack.browser.unknownFeature").getString();
			featureButton.setMessage(VersionedText.translatable("automodpack.browser.featureFilter", label));
		}
		if (sourceButton != null) sourceButton.setMessage(VersionedText.translatable("automodpack.browser.sourceFilter",
				selectedSource == null ? VersionedText.translatable("automodpack.browser.all").getString() : VersionedText.translatable(selectedSource.booleanValue() ? "automodpack.browser.source.published" : "automodpack.browser.source.custom").getString()));
	}

	private void resolveCachedReferences() {
		if (cacheLookupStarted) return;
		cacheLookupStarted = true;
		if (!hasAnyHash()) {
			referencesResolved = true;
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ChangeSet referenced = PlatformReferences.withCachedReferences(changes, ClientStorage.open(GameDirectory.current()).platformCacheDirectory());
			this.minecraft.execute(() -> {
				referencesResolved = true;
				if (closed || referenced == changes) return;
				changes = referenced;
				// In-place refresh instead of a full screen rebuild, so the search field keeps its content and focus.
				/*? if >=1.21.4 {*/
				double scrollAmount = this.browser.scrollAmount();
				/*?} else {*/
				/*double scrollAmount = this.browser.getScrollAmount();
				*//*?}*/
				rebuildBrowser();
				// Both vanilla setters clamp, so a stale scroll from the smaller old list is safe.
				this.browser.setScrollAmount(scrollAmount);
				recomputeSummary();
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

	/** A jar whose every occurrence lacks a platform reference: the pack ships a custom copy of it. */
	private static boolean isUnreferencedJar(ChangeBrowserProjection.FileRow file) {
		if (!file.path().toLowerCase(Locale.ROOT).endsWith(".jar")) return false;
		for (ChangeSet.Occurrence occurrence : file.occurrences()) if (!occurrence.references().isEmpty()) return false;
		return true;
	}

	private List<PlatformLink> platformLinks() {
		if (selectedPath == null || selectedPath.isBlank()) return List.of();
		LinkedHashMap<String, PlatformLink> distinct = new LinkedHashMap<>();
		for (ChangeSet.Change change : changes.changes()) {
			if (!change.logicalPath().equals(selectedPath)) continue;
			for (ChangeSet.Occurrence occurrence : change.occurrences()) {
				for (String reference : occurrence.references()) {
					PlatformLink link = toPlatformLink(reference);
					distinct.putIfAbsent(link.key(), link);
				}
			}
		}
		return List.copyOf(distinct.values());
	}

	private static PlatformLink toPlatformLink(String url) {
		try {
			URI uri = new URI(url);
			String host = uri.getHost();
			if (host == null || host.isBlank()) return new PlatformLink("open", VersionedText.translatable("automodpack.changelog.openPage"), url);
			String lower = host.toLowerCase(Locale.ROOT);
			if (lower.equals("modrinth.com") || lower.endsWith(".modrinth.com")) return new PlatformLink("modrinth", VersionedText.translatable("automodpack.browser.modrinth"), url);
			if (lower.equals("curseforge.com") || lower.endsWith(".curseforge.com") || lower.equals("curseforge.net") || lower.endsWith(".curseforge.net")) return new PlatformLink("curseforge", VersionedText.translatable("automodpack.browser.curseforge"), url);
			return new PlatformLink(lower, VersionedText.literal(host), url);
		} catch (URISyntaxException | IllegalArgumentException ignored) {
			return new PlatformLink("open", VersionedText.translatable("automodpack.changelog.openPage"), url);
		}
	}

	private void back() {
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		/*? if <26.1 {*/
		/*this.browser.render(matrices.getContext(), mouseX, mouseY, delta);
		*//*?}*/
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
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.browser.empty").withStyle(ChatFormatting.GRAY), this.width / 2, browserTop + 24, TextColors.WHITE);
		ChangeBrowserProjection.FileRow selected = this.browser == null ? null : this.browser.selectedFile();
		if (selected == null) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.browser.selectHint").withStyle(ChatFormatting.GRAY), this.width / 2, this.paneTop, TextColors.WHITE);
		else {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, selected.path(), contentWidth)).withStyle(ChatFormatting.WHITE), this.width / 2, this.paneTop, TextColors.WHITE);
			String facts = this.browser.facts();
			String hash = selected.writtenHash();
			if (hash != null) facts += " · sha1 " + hash.substring(0, Math.min(12, hash.length()));
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, facts, contentWidth)).withStyle(ChatFormatting.GRAY), this.width / 2, this.paneTop + this.font.lineHeight, TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	private record PlatformLink(String key, Component label, String url) {
		private PlatformLink {
			key = Objects.requireNonNull(key, "platform key");
			label = Objects.requireNonNull(label, "platform label");
			url = Objects.requireNonNull(url, "platform url");
		}
	}

	private record PaneAction(Component label, Button.OnPress onPress, boolean copyHash) {
		private PaneAction {
			label = Objects.requireNonNull(label, "pane action label");
			onPress = Objects.requireNonNull(onPress, "pane action press");
		}
	}

	public record BrowserAction(Component label, Consumer<Screen> action, boolean active) {
		public BrowserAction {
			label = Objects.requireNonNull(label, "browser action label");
			action = Objects.requireNonNull(action, "browser action");
		}
	}
}
