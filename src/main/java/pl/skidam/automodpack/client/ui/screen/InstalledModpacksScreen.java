package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Lists locally installed packs; lifecycle actions live behind the details screen. */
public final class InstalledModpacksScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int ROW_HEIGHT = 34;
	private static final int TEXT_MARGIN = 6;
	private static final int LIST_TOP = 68;

	private final Screen parent;
	private final InstalledModpackController controller;
	private List<InstalledModpackController.Pack> entries;
	private int preservedCount;
	private boolean discoveryFailureShown;

	public InstalledModpacksScreen(Screen parent) {
		super(VersionedText.translatable("automodpack.packManager.title"));
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
				? VersionedText.translatable("automodpack.management.preservedFilesCount", preservedCount)
				: VersionedText.translatable("automodpack.management.preservedFiles");
		ActionRow management = actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				optionalAction(preservedLabel, press -> controller.openPreservedFiles(this, () -> {
					refreshEntries();
					rebuild();
				})),
				optionalAction(VersionedText.translatable("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller))),
				optionalAction(VersionedText.translatable("automodpack.pinnedMods.button"), press -> ScreenImpl.setScreen(new PinnedModsScreen(this))));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
		ActionRow[] actionRows = {management, footer};
		List<Button> actionButtons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows);
		if (preservedCount == 0) setTooltip(actionButtons.get(0), VersionedText.translatable("automodpack.vault.empty"));
		if (entries.isEmpty()) return;
		int rowWidth = panelWidth(PANEL_WIDTH) - TEXT_MARGIN * 2;
		List<RowListWidget.Row> rows = new ArrayList<>(entries.size());
		for (InstalledModpackController.Pack entry : entries) {
			String source = VersionedText.translatable(entry.connectionAvailable() ? "automodpack.packManager.sourceServer" : "automodpack.packManager.sourceLocal").getString();
			// State is carried by color, not bracket markers: green = active pack, white = installed pack.
			rows.add(new RowListWidget.Row(List.of(
					VersionedText.literal(truncateToWidth(this.font, entry.name(), rowWidth)).withStyle(entry.active() ? ChatFormatting.GREEN : ChatFormatting.WHITE),
					VersionedText.literal(truncateToWidth(this.font, source, rowWidth)).withStyle(ChatFormatting.GRAY))));
		}
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows) - 8;
		this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, panelWidth(PANEL_WIDTH), LIST_TOP, listBottom, ROW_HEIGHT, rows,
				index -> open(entries.get(index)), null));
	}

	private void open(InstalledModpackController.Pack entry) {
		ScreenImpl.setScreen(new ModpackDetailsScreen(this, controller, entry));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packManager.title").withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
		boolean hasEntries = !entries.isEmpty();
		String description = !hasEntries
				? VersionedText.translatable("automodpack.packManager.empty").getString()
				: VersionedText.translatable("automodpack.packManager.description").getString();
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 20, 2);
		int descriptionY = descriptionLines.size() > 1 ? 28 : 32;
		for (String line : descriptionLines) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.GRAY), this.width / 2, descriptionY, TextColors.WHITE);
			descriptionY += 10;
		}
		if (!entries.isEmpty()) {
			String active = entries.stream().filter(InstalledModpackController.Pack::active).findFirst()
					.map(entry -> VersionedText.translatable("automodpack.packManager.active", entry.name()).getString())
					.orElse(VersionedText.translatable("automodpack.packManager.noActive").getString());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, active, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2,
					descriptionLines.size() > 1 ? 50 : 44, TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
