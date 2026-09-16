package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.widget.ChangeBrowserWidget;
import pl.skidam.automodpack_core.change.ChangeBrowserProjection;
import pl.skidam.automodpack_core.change.ChangeSet;

/** Shared vanilla-style file browser used for installed catalogues and generation diffs. */
public class ChangeBrowserScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int GAP = 6;
	private final Screen parent;
	private final Component heading;
	private final Component description;
	private final ChangeSet changes;
	private final Map<String, String> featureNames;
	private final BrowserAction auxiliaryAction;
	private final Set<String> collapsedFolders = new TreeSet<>();
	private ChangeBrowserProjection.Mode mode = ChangeBrowserProjection.Mode.TREE;
	private String search = "";
	private String selectedContent = "";
	private String selectedFeature = "";
	private boolean technicalDetails;
	private ChangeBrowserWidget browser;
	private EditBox searchField;
	private Button contentButton;
	private Button featureButton;
	private Button modeButton;
	private Button detailsButton;
	private Button openPageButton;
	private int browserTop;
	private int browserBottom;

	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> featureNames) {
		this(parent, heading, description, changes, featureNames, null);
	}

	public ChangeBrowserScreen(Screen parent, Component heading, Component description, ChangeSet changes, Map<String, String> featureNames, BrowserAction auxiliaryAction) {
		super(heading);
		this.parent = parent;
		this.heading = Objects.requireNonNull(heading, "browser heading");
		this.description = Objects.requireNonNull(description, "browser description");
		this.changes = Objects.requireNonNull(changes, "browser changes");
		this.featureNames = Map.copyOf(featureNames == null ? Map.of() : featureNames);
		this.auxiliaryAction = auxiliaryAction;
	}

	@Override
	protected void init() {
		super.init();
		int panelLeft = panelLeft(PANEL_WIDTH);
		int panelWidth = panelWidth(PANEL_WIDTH);
		boolean narrow = panelWidth < 500;
		int searchWidth = narrow ? panelWidth : 280;
		int controlsY = narrow ? 59 : 35;
		int controlsLeft = narrow ? panelLeft : panelLeft + searchWidth + GAP;
		int controlWidth = Math.max(1, (panelWidth - (narrow ? GAP * 2 : searchWidth + GAP * 3)) / 3);
		this.browserTop = narrow ? 83 : 59;
		this.searchField = new EditBox(this.font, panelLeft, 35, searchWidth, 20, VersionedText.translatable("automodpack.browser.search"));
		this.searchField.setMaxLength(Integer.MAX_VALUE);
		this.searchField.setValue(search);
		this.searchField.setSuggestion(VersionedText.translatable("automodpack.browser.search").getString());
		this.searchField.setResponder(value -> {
			search = value;
			rebuildBrowser();
		});
		this.addRenderableWidget(this.searchField);
		this.contentButton = buttonWidget(controlsLeft, controlsY, controlWidth, 20, VersionedText.literal(""), button -> cycleContent());
		this.featureButton = buttonWidget(controlsLeft + GAP + controlWidth, controlsY, controlWidth, 20, VersionedText.literal(""), button -> cycleFeature());
		this.modeButton = buttonWidget(controlsLeft + GAP * 2 + controlWidth * 2, controlsY, controlWidth, 20, VersionedText.literal(""), button -> toggleMode());
		this.addRenderableWidget(this.contentButton);
		this.addRenderableWidget(this.featureButton);
		this.addRenderableWidget(this.modeButton);
		updateControlLabels();
		List<ActionRow> actionRows = new ArrayList<>();
		if (auxiliaryAction != null) {
			actionRows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(auxiliaryAction.label(), button -> auxiliaryAction.action().accept(this))));
		}
		actionRows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> back()),
				optionalAction(VersionedText.literal(""), button -> toggleDetails()),
				optionalAction(VersionedText.translatable("automodpack.changelog.noPage"), button -> openSelectedPage())));
		List<Button> actionButtons = this.addActionArea(PANEL_WIDTH, this.height - 28, actionRows.toArray(ActionRow[]::new));
		int footerOffset = auxiliaryAction == null ? 0 : 1;
		this.detailsButton = actionButtons.get(footerOffset + 1);
		this.openPageButton = actionButtons.get(footerOffset + 2);
		if (auxiliaryAction != null) actionButtons.get(0).active = auxiliaryAction.active();
		this.browserBottom = actionAreaTop(PANEL_WIDTH, this.height - 28, actionRows.toArray(ActionRow[]::new)) - 8;
		rebuildBrowser();
		updateDetailsLabel();
	}

	private void rebuildBrowser() {
		if (this.minecraft == null) return;
		if (this.browser != null) this.removeWidget(this.browser);
		ChangeBrowserProjection.Filter filter = new ChangeBrowserProjection.Filter(search,
				selectedContent.isBlank() ? Set.of() : Set.of(selectedContent), selectedFeature.isBlank() ? Set.of() : Set.of(selectedFeature));
		ChangeBrowserProjection.Projection projection = ChangeBrowserProjection.project(changes, mode, filter).collapse(collapsedFolders);
		this.browser = new ChangeBrowserWidget(projection, collapsedFolders, featureNames, technicalDetails, this::toggleFolder,
				this.minecraft, this.width, this.height, browserTop, browserBottom);
		this.addRenderableWidget(this.browser);
	}

	private void toggleFolder(String path) {
		if (!collapsedFolders.remove(path)) collapsedFolders.add(path);
		rebuildBrowser();
	}

	private void toggleMode() {
		mode = mode == ChangeBrowserProjection.Mode.TREE ? ChangeBrowserProjection.Mode.LIST : ChangeBrowserProjection.Mode.TREE;
		updateControlLabels();
		rebuildBrowser();
	}

	private void toggleDetails() {
		technicalDetails = !technicalDetails;
		updateDetailsLabel();
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
		if (modeButton != null) modeButton.setMessage(VersionedText.translatable(mode == ChangeBrowserProjection.Mode.TREE ? "automodpack.browser.tree" : "automodpack.browser.list"));
	}

	private void updateDetailsLabel() {
		if (detailsButton != null) detailsButton.setMessage(VersionedText.translatable(technicalDetails ? "automodpack.browser.detailsHide" : "automodpack.browser.detailsShow"));
	}

	private void openSelectedPage() {
		String reference = firstReference();
		if (reference != null) Util.getPlatform().openUri(reference);
	}

	private String firstReference() {
		ChangeBrowserProjection.FileRow selected = browser == null ? null : browser.selectedFile();
		if (selected == null) return null;
		for (ChangeSet.Occurrence occurrence : selected.occurrences()) if (!occurrence.references().isEmpty()) return occurrence.references().get(0);
		return null;
	}

	private void back() {
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		/*? if <26.1 {*/
		/*this.browser.render(matrices.getContext(), mouseX, mouseY, delta);
		*//*?}*/
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, heading.getString(), panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), panelLeft(PANEL_WIDTH), 8,
				TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, description.getString(), panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.GRAY), panelLeft(PANEL_WIDTH), 21,
				TextColors.WHITE);
		ChangeBrowserProjection.Projection projection = ChangeBrowserProjection.project(changes, mode,
				new ChangeBrowserProjection.Filter(search, selectedContent.isBlank() ? Set.of() : Set.of(selectedContent), selectedFeature.isBlank() ? Set.of() : Set.of(selectedFeature)));
		String summary = VersionedText.translatable("automodpack.browser.summary", projection.total().fileCount(), UiFormat.formatSize(projection.total().byteCount())).getString();
		if (!projection.effects().isEmpty()) summary += " | " + projection.effects().size() + " " + VersionedText.translatable("automodpack.browser.kind.metadata_only").getString();
		drawTextWithShadow(matrices, this.font, VersionedText.literal(summary).withStyle(ChatFormatting.GRAY), panelLeft(PANEL_WIDTH), this.height - 43, TextColors.WHITE);
		if (projection.rows().isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.browser.empty").withStyle(ChatFormatting.GRAY), this.width / 2, browserTop + 24, TextColors.WHITE);
		String reference = firstReference();
		this.openPageButton.active = reference != null;
		this.openPageButton.setMessage(VersionedText.translatable(reference == null ? "automodpack.changelog.noPage" : "automodpack.changelog.openPage"));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}

	public record BrowserAction(Component label, Consumer<Screen> action, boolean active) {
		public BrowserAction {
			label = Objects.requireNonNull(label, "browser action label");
			action = Objects.requireNonNull(action, "browser action");
		}
	}
}
