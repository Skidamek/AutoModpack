package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.PreservationVault;

/** Lists locally installed packs; lifecycle actions live behind the details screen. */
public final class InstalledModpacksScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 320;
	private static final int ROW_HEIGHT = 34;
	// Vanilla Select World (WorldListEntry) double-click window.
	private static final long DOUBLE_CLICK_MILLIS = 250L;

	private final Screen parent;
	private final InstalledModpackController controller;
	private List<InstalledModpackController.Pack> entries;
	private List<PreservationVault.Snapshot> orphanedPreservations;
	private int page;
	private boolean discoveryFailureShown;
	private boolean busy;
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
		this.orphanedPreservations = controller.orphanedPreservations();
	}

	@Override
	protected void init() {
		super.init();
		if (controller.discoveryFailure() != null && !discoveryFailureShown) {
			discoveryFailureShown = true;
			ScreenManager.failure(FailureRequest.of(controller.discoveryFailure(), "automodpack.error.storage", FailureCategory.STORAGE,
					FailureDestination.CURRENT_SCREEN, null));
		}
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		int listTop = 68;
		int actionY = this.height - 28;
		int listBottomWithoutPagination = actionY - 8;
		int rowsWithoutPagination = Math.max(1, (listBottomWithoutPagination - listTop) / ROW_HEIGHT);
		int totalEntries = entries.size() + orphanedPreservations.size();
		boolean showPagination = totalEntries > rowsWithoutPagination;
		int listBottom = showPagination ? actionY - 20 - actionRowGap() - 8 : listBottomWithoutPagination;
		int rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		int pageCount = Math.max(1, (int) Math.ceil((double) totalEntries / rowsPerPage));
		if (page >= pageCount) page = pageCount - 1;

		int start = page * rowsPerPage;
		for (int index = start; index < Math.min(totalEntries, start + rowsPerPage); index++) {
			int y = listTop + (index - start) * ROW_HEIGHT;
			Button row;
			if (index < entries.size()) {
				InstalledModpackController.Pack entry = entries.get(index);
				row = buttonWidget(x, y, rowWidth, 28, rowLabel(entry, rowWidth), press -> clickPack(entry));
			} else {
				PreservationVault.Snapshot snapshot = orphanedPreservations.get(index - entries.size());
				row = buttonWidget(x, y, rowWidth, 28, orphanedLabel(snapshot, rowWidth), press -> open(snapshot));
			}
			this.addRenderableWidget(row);
		}

		if (showPagination) {
			List<ActionRow> rows = List.of(
					actionRow(ActionAreaLayout.RowKind.NAVIGATION,
							navigationAction(VersionedText.translatable("automodpack.ui.previous"), press -> {
								if (page > 0) {
									page--;
									rebuild();
								}
							}),
							disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount)),
							navigationAction(VersionedText.translatable("automodpack.ui.next"), press -> {
								if (page < pageCount - 1) {
									page++;
									rebuild();
								}
							})),
						actionRow(ActionAreaLayout.RowKind.FOOTER,
								secondaryAction(VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)),
								optionalAction(VersionedText.translatable("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller)))));
			List<Button> actionButtons = addActionArea(PANEL_WIDTH, actionY, rows.toArray(ActionRow[]::new));
			actionButtons.get(0).active = page > 0;
			actionButtons.get(2).active = page < pageCount - 1;
			return;
		}
		this.addActionArea(PANEL_WIDTH, actionY, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)),
				optionalAction(VersionedText.translatable("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller)))));
	}

	private void clickPack(InstalledModpackController.Pack entry) {
		if (busy) return;
		long now = Util.getMillis();
		if (pendingPack != null && pendingPack.modpackId().equals(entry.modpackId()) && now - pendingAt < DOUBLE_CLICK_MILLIS) {
			pendingPack = null;
			if (entry.active()) open(entry);
			else activate(entry);
			return;
		}
		pendingPack = entry;
		pendingAt = now;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.minecraft == null || this.minecraft.screen != this || pendingPack == null || Util.getMillis() - pendingAt < DOUBLE_CLICK_MILLIS) return;
		InstalledModpackController.Pack entry = pendingPack;
		pendingPack = null;
		open(entry);
	}

	private void activate(InstalledModpackController.Pack entry) {
		if (busy || entry.active()) return;
		busy = true;
		controller.activate(entry, this::released);
	}

	private void released() {
		busy = false;
		refreshEntries();
		rebuild();
	}

	private void open(InstalledModpackController.Pack entry) {
		pendingPack = null;
		ScreenImpl.setScreen(new ModpackDetailsScreen(this, controller, entry));
	}

	private void open(PreservationVault.Snapshot snapshot) {
		pendingPack = null;
		ScreenImpl.setScreen(new PreservationVaultScreen(this, controller, snapshot.modpackId(), snapshot.modpackId(), false, () -> {
			refreshEntries();
			rebuild();
		}));
	}

	private MutableComponent rowLabel(InstalledModpackController.Pack entry, int width) {
		// State is carried by color, not bracket markers: green = active pack, white = installed pack.
		String connection = VersionedText.translatable(entry.connectionAvailable() ? "automodpack.packManager.connected" : "automodpack.packManager.localRecord").getString();
		return VersionedText.literal(truncateToWidth(this.font, entry.name() + "  " + connection, width - 12)).withStyle(entry.active() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
	}

	private MutableComponent orphanedLabel(PreservationVault.Snapshot snapshot, int width) {
		// The raw modpack id is internal; the row names what the player will find inside instead.
		String label = VersionedText.translatable("automodpack.management.preservedFilesCount", snapshot.claims().size()).getString();
		return VersionedText.literal(truncateToWidth(this.font, label, width - 12)).withStyle(ChatFormatting.YELLOW);
	}

	private void rebuild() {
		pendingPack = null;
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packManager.title").withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
		boolean hasEntries = !entries.isEmpty() || !orphanedPreservations.isEmpty();
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
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, active, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, descriptionLines.size() > 1 ? 50 : 44, TextColors.WHITE);
		}
		if (entries.isEmpty() && !orphanedPreservations.isEmpty()) {
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.translatable("automodpack.management.preservedFiles").withStyle(ChatFormatting.YELLOW), this.width / 2, descriptionLines.size() > 1 ? 50 : 44, TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
