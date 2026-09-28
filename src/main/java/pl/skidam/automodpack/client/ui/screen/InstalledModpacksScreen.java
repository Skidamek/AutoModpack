package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
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
	private List<InstalledModpackController.Pack> entries = List.of();
	private RowListWidget packList;
	private boolean discoveryFailureShown;
	private boolean loading = true;
	private boolean closed;
	private Future<?> load;
	// The header is one init-time build: no frame re-wraps text or re-filters the entries.
	private MutableComponent titleLine;
	private final List<MutableComponent> descriptionLines = new ArrayList<>();
	private MutableComponent activeLine;
	private int activeLineY;

	public InstalledModpacksScreen(Screen parent) {
		super(VersionedText.text("automodpack.packManager.title"));
		this.parent = parent;
		this.controller = new InstalledModpackController();
	}

	@Override
	protected void init() {
		super.init();
		closed = false;
		if (loading && load == null) load = ScreenManager.background(this::loadEntries);
		if (controller.discoveryFailure() != null && !discoveryFailureShown) {
			discoveryFailureShown = true;
			ScreenManager.failure(FailureRequest.of(controller.discoveryFailure(), "automodpack.error.storage", FailureCategory.STORAGE,
					FailureDestination.CURRENT_SCREEN, null));
		}
		buildHeader();
		ActionRow management = actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				optionalAction(VersionedText.text("automodpack.management.stateHistory"), press -> controller.openStateHistory(this, this::reloadEntries)),
				optionalAction(VersionedText.text("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller))),
				optionalAction(VersionedText.text("automodpack.pinnedMods.button"), press -> ScreenImpl.setScreen(new PinnedModsScreen(this))));
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
		ActionRow[] actionRows = {management, footer};
		int rowWidth = panelWidth(PANEL_WIDTH) - TEXT_MARGIN * 2;
		int activeIndex = -1;
		List<RowListWidget.Row> rows = new ArrayList<>(entries.size());
		for (int index = 0; index < entries.size(); index++) {
			InstalledModpackController.Pack entry = entries.get(index);
			String source = VersionedText.str(entry.connectionAvailable() ? "automodpack.packManager.sourceServer" : "automodpack.packManager.sourceLocal");
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
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows);
	}

	private void buildHeader() {
		titleLine = VersionedText.text("automodpack.packManager.title").withStyle(ChatFormatting.BOLD);
		String description = loading
				? VersionedText.str("automodpack.packManager.loading")
				: entries.isEmpty()
						? VersionedText.str("automodpack.packManager.empty")
						: VersionedText.str("automodpack.packManager.description");
		descriptionLines.clear();
		for (String line : wrapToWidth(this.font, description, this.width - 20, 2))
			descriptionLines.add(VersionedText.literal(line).withStyle(ChatFormatting.GRAY));
		activeLine = null;
		activeLineY = descriptionLines.size() > 1 ? 50 : 44;
		if (loading || entries.isEmpty()) return;
		String active = entries.stream().filter(InstalledModpackController.Pack::active).findFirst()
				.map(entry -> VersionedText.str("automodpack.packManager.active", entry.name()))
				.orElse(VersionedText.str("automodpack.packManager.noActive"));
		activeLine = VersionedText.literal(truncateToWidth(this.font, active, this.width - 20)).withStyle(ChatFormatting.YELLOW);
	}

	/** Drops the loaded list so the next init re-scans; a resize reuses the list, refresh paths call this first. */
	void reload() {
		entries = List.of();
		loading = true;
		load = null;
	}

	private void reloadEntries() {
		reload();
		rebuild();
	}

	private void loadEntries() {
		try {
			List<InstalledModpackController.Pack> loaded = controller.installed();
			this.minecraft.execute(() -> loaded(loaded));
		} catch (Exception e) {
			this.minecraft.execute(() -> fail(e));
		}
	}

	private void loaded(List<InstalledModpackController.Pack> loaded) {
		if (closed) return;
		entries = loaded;
		loading = false;
		load = null;
		rebuild();
	}

	private void fail(Exception exception) {
		if (closed) return;
		ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	private void open(InstalledModpackController.Pack entry) {
		ScreenImpl.setScreen(new ModpackSettingsScreen(this, controller, entry));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, titleLine, this.width / 2, 16, TextColors.WHITE);
		int descriptionY = descriptionLines.size() > 1 ? 28 : 32;
		for (MutableComponent line : descriptionLines) {
			drawCenteredTextWithShadow(matrices, this.font, line, this.width / 2, descriptionY, TextColors.WHITE);
			descriptionY += 10;
		}
		if (activeLine != null) drawCenteredTextWithShadow(matrices, this.font, activeLine, this.width / 2, activeLineY, TextColors.WHITE);
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
