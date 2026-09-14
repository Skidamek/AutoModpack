package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Lists locally installed packs; lifecycle actions live behind the details screen. */
public final class InstalledModpacksScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int ROW_HEIGHT = 34;
	private static final int TEXT_MARGIN = 6;
	private static final int LIST_TOP = 68;

	private final Screen parent;
	private final InstalledModpackController controller;
	private List<InstalledModpackController.Pack> entries;
	private RowListWidget packList;
	private int preservedCount;
	private boolean discoveryFailureShown;

	public InstalledModpacksScreen(Screen parent) {
		super(VersionedText.text("automodpack.packManager.title"));
		this.parent = parent;
		this.controller = new InstalledModpackController();
		refreshEntries();
	}

	private void refreshEntries() {
		this.entries = controller.installed();
		this.preservedCount = controller.preservedClaimCount();
	}

	@Override
	protected void init() {
		super.init();
		refreshEntries();
		if (controller.discoveryFailure() != null && !discoveryFailureShown) {
			discoveryFailureShown = true;
			ScreenManager.failure(FailureRequest.of(controller.discoveryFailure(), "automodpack.error.storage", FailureCategory.STORAGE,
					FailureDestination.CURRENT_SCREEN, null));
		}
		MutableComponent preservedLabel = preservedCount > 0
				? VersionedText.text("automodpack.management.preservedFilesCount", preservedCount)
				: VersionedText.text("automodpack.management.preservedFiles");
		ActionRow management = actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				optionalAction(preservedLabel, press -> controller.openPreservedFiles(this, () -> {
					refreshEntries();
					rebuild();
				})),
				optionalAction(VersionedText.text("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller))),
				optionalAction(VersionedText.text("automodpack.pinnedMods.button"), press -> ScreenImpl.setScreen(new PinnedModsScreen(this))));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
		ActionRow[] actionRows = {management, footer};
		int rowWidth = panelWidth(PANEL_WIDTH) - TEXT_MARGIN * 2;
		int activeIndex = -1;
		List<RowListWidget.Row> rows = new ArrayList<>(entries.size());
		for (int index = 0; index < entries.size(); index++) {
			InstalledModpackController.Pack entry = entries.get(index);
			String source = VersionedText.text(entry.connectionAvailable() ? "automodpack.packManager.sourceServer" : "automodpack.packManager.sourceLocal").getString();
			// State is carried by color, not bracket markers: green = active pack, white = installed pack.
			rows.add(new RowListWidget.Row(List.of(
					VersionedText.literal(truncateToWidth(this.font, entry.name(), rowWidth)).withStyle(entry.active() ? ChatFormatting.GREEN : ChatFormatting.WHITE),
					VersionedText.literal(truncateToWidth(this.font, source, rowWidth)).withStyle(ChatFormatting.GRAY))));
			if (entry.active()) activeIndex = index;
		}
		// The list fills the space between the header and the pinned actions; only a real overflow scrolls.
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows) - 8;
		this.packList = this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, panelWidth(PANEL_WIDTH), 0, LIST_TOP, listBottom, ROW_HEIGHT, rows,
				index -> open(entries.get(index))));
		// The active pack is the row the player came here for, so it starts selected and scrolled into view.
		if (activeIndex >= 0) {
			this.packList.setSelected(this.packList.children().get(activeIndex));
			this.packList.revealRow(activeIndex);
		}
		List<AbstractWidget> actionButtons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows);
		if (preservedCount == 0) setTooltip(actionButtons.get(0), VersionedText.text("automodpack.vault.empty"));
	}

	private void open(InstalledModpackController.Pack entry) {
		ScreenImpl.setScreen(new ModpackSettingsScreen(this, controller, entry));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.packManager.title").withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
		boolean hasEntries = !entries.isEmpty();
		String description = !hasEntries
				? VersionedText.text("automodpack.packManager.empty").getString()
				: VersionedText.text("automodpack.packManager.description").getString();
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 20, 2);
		int descriptionY = descriptionLines.size() > 1 ? 28 : 32;
		for (String line : descriptionLines) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.GRAY), this.width / 2, descriptionY, TextColors.WHITE);
			descriptionY += 10;
		}
		if (!entries.isEmpty()) {
			String active = entries.stream().filter(InstalledModpackController.Pack::active).findFirst()
					.map(entry -> VersionedText.text("automodpack.packManager.active", entry.name()).getString())
					.orElse(VersionedText.text("automodpack.packManager.noActive").getString());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, active, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2,
					descriptionLines.size() > 1 ? 50 : 44, TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
