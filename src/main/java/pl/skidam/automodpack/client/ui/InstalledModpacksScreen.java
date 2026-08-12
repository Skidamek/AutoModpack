package pl.skidam.automodpack.client.ui;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Lists locally installed packs; lifecycle actions live behind the details screen. */
public final class InstalledModpacksScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 320;
	private static final int ROW_HEIGHT = 34;

	private final Screen parent;
	private final InstalledModpackController controller;
	private List<InstalledModpackController.Pack> entries;
	private int page;
	private boolean discoveryFailureShown;

	public InstalledModpacksScreen(Screen parent) {
		super(VersionedText.translatable("automodpack.packManager.title"));
		this.parent = parent;
		this.controller = new InstalledModpackController();
		this.entries = controller.installed();
	}

	@Override
	protected void init() {
		super.init();
		if (controller.discoveryFailure() != null && !discoveryFailureShown) {
			discoveryFailureShown = true;
			new ScreenManager().failure(FailureRequest.of(controller.discoveryFailure(), "automodpack.error.storage", FailureCategory.STORAGE,
					FailureDestination.CURRENT_SCREEN, null));
		}
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		int listTop = 68;
		int actionY = this.height - 28;
		int listBottomWithoutPagination = actionY - 8;
		int rowsWithoutPagination = Math.max(1, (listBottomWithoutPagination - listTop) / ROW_HEIGHT);
		boolean showPagination = entries.size() > rowsWithoutPagination;
		int paginationY = showPagination ? actionY - ROW_HEIGHT : -1;
		int listBottom = showPagination ? paginationY - 8 : listBottomWithoutPagination;
		int rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		int pageCount = Math.max(1, (int) Math.ceil((double) entries.size() / rowsPerPage));
		if (page >= pageCount) page = pageCount - 1;

		int start = page * rowsPerPage;
		for (int index = start; index < Math.min(entries.size(), start + rowsPerPage); index++) {
			InstalledModpackController.Pack entry = entries.get(index);
			int y = listTop + (index - start) * ROW_HEIGHT;
			Button row = buttonWidget(x, y, rowWidth, 28, rowLabel(entry, rowWidth), press -> open(entry));
			this.addRenderableWidget(row);
		}

		if (showPagination) {
			int paginationWidth = actionButtonWidth(PANEL_WIDTH, 3);
			Button previous = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 0), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.previous"), press -> {
						if (page > 0) {
							page--;
							rebuild();
						}
					});
			previous.active = page > 0;
			this.addRenderableWidget(previous);
			Button pageLabel = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 1), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			Button next = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 2), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.next"), press -> {
						if (page < pageCount - 1) {
							page++;
							rebuild();
						}
					});
			next.active = page < pageCount - 1;
			this.addRenderableWidget(next);
		}

		int footerWidth = actionButtonWidth(PANEL_WIDTH, 2);
		this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 2, 0), actionY, footerWidth, 20,
				VersionedText.translatable("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller.storage()))));
		this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 2, 1), actionY, footerWidth, 20,
				VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
	}

	private void open(InstalledModpackController.Pack entry) {
		ScreenImpl.setScreen(new ModpackDetailsScreen(this, controller, entry));
	}

	private MutableComponent rowLabel(InstalledModpackController.Pack entry, int width) {
		String state = VersionedText.translatable(entry.active() ? "automodpack.packManager.activeMarker" : "automodpack.packManager.reviewMarker").getString();
		String connection = VersionedText.translatable(entry.connectionAvailable() ? "automodpack.packManager.connected" : "automodpack.packManager.localRecord").getString();
		return VersionedText.literal(truncateToWidth(this.font, entry.name() + "  " + state + "  " + connection, width - 12)).withStyle(entry.active() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
	}

	private void rebuild() {
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
		String description = entries.isEmpty()
				? VersionedText.translatable("automodpack.packManager.empty").getString()
				: VersionedText.translatable("automodpack.packManager.description").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, description, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 32, TextColors.WHITE);
		if (!entries.isEmpty()) {
			String active = entries.stream().filter(InstalledModpackController.Pack::active).findFirst()
					.map(entry -> VersionedText.translatable("automodpack.packManager.active", entry.name()).getString())
					.orElse(VersionedText.translatable("automodpack.packManager.noActive").getString());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, active, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, 44, TextColors.WHITE);
		}
	}
}
