package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Lists locally installed packs; lifecycle actions live behind the details screen. */
public final class InstalledModpacksScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int ROW_HEIGHT = 34;
	// Vanilla Select World (WorldListEntry) double-click window.
	private static final long DOUBLE_CLICK_MILLIS = 250L;

	private final Screen parent;
	private final InstalledModpackController controller;
	private List<InstalledModpackController.Pack> entries;
	private int preservedCount;
	private int page;
	private boolean discoveryFailureShown;
	private InstalledModpackController.Pack pendingPack;
	private long pendingAt;

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
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		int listTop = 68;
		int actionY = this.height - 28;
		MutableComponent preservedLabel = preservedCount > 0
				? VersionedText.translatable("automodpack.management.preservedFilesCount", preservedCount)
				: VersionedText.translatable("automodpack.management.preservedFiles");
		ActionRow management = actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				optionalAction(preservedLabel, press -> controller.openPreservedFiles(this, () -> {
					refreshEntries();
					pendingPack = null;
					rebuild();
				})),
				optionalAction(VersionedText.translatable("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller))),
				optionalAction(VersionedText.translatable("automodpack.pinnedMods.button"), press -> ScreenImpl.setScreen(new PinnedModsScreen(this))));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
		List<ActionRow> rows = new ArrayList<>();
		rows.add(management);
		rows.add(footer);
		int totalEntries = entries.size();
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, actionY, rows.toArray(ActionRow[]::new)) - 8;
		int rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		boolean showPagination = totalEntries > rowsPerPage;
		if (showPagination) {
			rows.add(0, actionRow(ActionAreaLayout.RowKind.NAVIGATION, navigationAction(VersionedText.literal(""), press -> {}),
					disabledNavigationAction(VersionedText.literal("")), navigationAction(VersionedText.literal(""), press -> {})));
			listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, actionY, rows.toArray(ActionRow[]::new)) - 8;
			rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		}
		int pageCount = Math.max(1, (int) Math.ceil((double) Math.max(totalEntries, 1) / rowsPerPage));
		if (showPagination)
			rows.set(0, actionRow(ActionAreaLayout.RowKind.NAVIGATION,
					navigationAction(VersionedText.translatable("automodpack.ui.previous"), press -> {
						if (page > 0) {
							page--;
							pendingPack = null;
							rebuild();
						}
					}),
					disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount)),
					navigationAction(VersionedText.translatable("automodpack.ui.next"), press -> {
						if (page < pageCount - 1) {
							page++;
							pendingPack = null;
							rebuild();
						}
					})));
		if (page >= pageCount) page = pageCount - 1;
		int start = page * rowsPerPage;
		for (int index = start; index < Math.min(totalEntries, start + rowsPerPage); index++) {
			int y = listTop + (index - start) * ROW_HEIGHT;
			InstalledModpackController.Pack entry = entries.get(index);
			Button row = buttonWidget(x, y, rowWidth, 28, rowLabel(entry, rowWidth), press -> clickPack(entry));
			setTooltip(row, VersionedText.literal(entry.modpackId()));
			this.addRenderableWidget(row);
		}
		List<Button> actionButtons = addActionArea(ActionAreaLayout.FOOTER_RAIL, actionY, rows.toArray(ActionRow[]::new));
		if (preservedCount == 0) setTooltip(actionButtons.get(showPagination ? 3 : 0), VersionedText.translatable("automodpack.vault.empty"));
		if (pageCount > 1) {
			actionButtons.get(0).active = page > 0;
			actionButtons.get(2).active = page < pageCount - 1;
		}
	}

	private void clickPack(InstalledModpackController.Pack entry) {
		long now = Util.getMillis();
		if (pendingPack != null && pendingPack.modpackId().equals(entry.modpackId()) && now - pendingAt < DOUBLE_CLICK_MILLIS) {
			pendingPack = null;
			open(entry);
			return;
		}
		pendingPack = entry;
		pendingAt = now;
	}

	@Override
	public void tick() {
		super.tick();
		if (pendingPack == null || Util.getMillis() - pendingAt < DOUBLE_CLICK_MILLIS) return;
		InstalledModpackController.Pack entry = pendingPack;
		pendingPack = null;
		open(entry);
	}

	private void open(InstalledModpackController.Pack entry) {
		pendingPack = null;
		ScreenImpl.setScreen(new ModpackDetailsScreen(this, controller, entry));
	}

	private MutableComponent rowLabel(InstalledModpackController.Pack entry, int width) {
		// State is carried by color, not bracket markers: green = active pack, white = installed pack.
		String source = VersionedText.translatable(entry.connectionAvailable() ? "automodpack.packManager.sourceServer" : "automodpack.packManager.sourceLocal").getString();
		return VersionedText.literal(truncateToWidth(this.font, entry.name() + " · " + source, width - 12)).withStyle(entry.active() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
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
