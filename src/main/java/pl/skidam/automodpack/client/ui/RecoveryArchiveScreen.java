package pl.skidam.automodpack.client.ui;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public final class RecoveryArchiveScreen extends VersionedScreen {
	private static final int ARCHIVED_ROW_HEIGHT = 34;

	private final Screen parent;
	private final ModpackUpdater updater;
	private final String modpackName;
	private final Runnable closedCallback;
	private ModpackUpdater.RecoverySnapshot snapshot;
	private boolean showAvailable = true;
	private boolean busy;
	private boolean closed;
	private int page;
	private Button previousButton;
	private Button nextButton;

	public RecoveryArchiveScreen(Screen parent, ModpackUpdater updater, ModpackUpdater.RecoverySnapshot snapshot, String modpackName, Runnable closedCallback) {
		super(VersionedText.literal("RecoveryArchiveScreen"));
		this.parent = parent;
		this.updater = updater;
		this.snapshot = snapshot;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		int navigationY = this.height - 28;
		int left = panelLeft(310);
		int rowWidth = panelWidth(310);
		int gap = 10;
		int tabWidth = (rowWidth - gap) / 2;
		Button availableTab = buttonWidget(left, 50, tabWidth, 20, VersionedText.literal("Files to preserve"), button -> selectAvailable());
		Button archivedTab = buttonWidget(left + tabWidth + gap, 50, tabWidth, 20, VersionedText.literal("Preserved files"), button -> selectArchived());
		availableTab.active = !showAvailable;
		archivedTab.active = showAvailable;
		this.addRenderableWidget(availableTab);
		this.addRenderableWidget(archivedTab);
		int actionWidth = actionButtonWidth(310, 3);
		boolean hasPagination = pageCount() > 1;
		this.previousButton = buttonWidget(actionButtonX(310, 3, 1), navigationY, actionWidth, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(actionButtonX(310, 3, 2), navigationY, actionWidth, 20, VersionedText.literal("Next >"), button -> changePage(1));
		updateNavigation();
		if (hasPagination) {
			this.addRenderableWidget(this.previousButton);
			this.addRenderableWidget(this.nextButton);
		}
		this.addRenderableWidget(buttonWidget(hasPagination ? actionButtonX(310, 3, 0) : centeredActionButtonX(310, 3, 1, 0), navigationY, actionWidth, 20, VersionedText.translatable("automodpack.back"), button -> back()));
		addFileButtons();
	}

	private void addFileButtons() {
		if (!showAvailable) return;
		List<ModpackUpdater.RecoveryFile> files = files();
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(files.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			ModpackUpdater.RecoveryFile file = files.get(index);
			int y = 72 + (index - start) * 22;
			int rowWidth = panelWidth(310);
			Button button = buttonWidget(panelLeft(310), y, rowWidth, 20,
					VersionedText.literal(truncateToWidth(this.font, "Preserve " + file.logicalPath() + " (" + UiFormat.formatSize(file.size()) + ")", rowWidth - 20)), press -> archive(file));
			button.active = !busy;
			this.addRenderableWidget(button);
		}
	}

	private void selectAvailable() {
		if (!showAvailable) {
			showAvailable = true;
			page = 0;
			rebuild();
		}
	}

	private void selectArchived() {
		if (showAvailable) {
			showAvailable = false;
			page = 0;
			rebuild();
		}
	}

	private List<ModpackUpdater.RecoveryFile> files() {
		return showAvailable ? snapshot.available() : snapshot.archived();
	}

	private int rowsPerPage() {
		return showAvailable
				? Math.max(1, (this.height - 48 - 72) / 22)
				: Math.max(1, (this.height - 38 - 76) / ARCHIVED_ROW_HEIGHT);
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (files().size() + pageSize - 1) / pageSize);
	}

	private void updateNavigation() {
		page = Math.max(0, Math.min(pageCount() - 1, page));
		if (previousButton != null) previousButton.active = page > 0;
		if (nextButton != null) nextButton.active = page + 1 < pageCount();
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		updateNavigation();
		rebuild();
	}

	private void archive(ModpackUpdater.RecoveryFile file) {
		if (busy || closed) return;
		busy = true;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				updater.recoverDeletedFile(file.logicalPath(), file.sha1(), file.size());
				ModpackUpdater.RecoverySnapshot refreshed = updater.recoverySnapshot();
				this.minecraft.execute(() -> {
					if (closed) return;
					snapshot = refreshed;
					busy = false;
					page = Math.min(page, pageCount() - 1);
					rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> busy = false);
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void back() {
		if (closed) return;
		closed = true;
		updater.close();
		closedCallback.run();
		ScreenImpl.setScreen(parent);
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
		String title = modpackName.isBlank() ? "Recovery archive" : modpackName + " recovery archive";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Preserve files removed by newer modpack versions.", this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 30,
				TextColors.WHITE);
		String counts = busy ? "Preserving file..." : "Available: " + snapshot.available().size() + "  Preserved: " + snapshot.archived().size();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(counts).withStyle(ChatFormatting.YELLOW), this.width / 2, 42, TextColors.WHITE);
		List<ModpackUpdater.RecoveryFile> files = files();
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(files.size(), start + pageSize);
		if (!showAvailable) for (int index = start; index < end; index++) {
			ModpackUpdater.RecoveryFile file = files.get(index);
			int y = 76 + (index - start) * ARCHIVED_ROW_HEIGHT;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Path: " + file.logicalPath(), this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2, y,
					TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Size: " + UiFormat.formatSize(file.size())).withStyle(ChatFormatting.GREEN), this.width / 2, y + 12, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "Preserved at: " + displayPreservedAt(file.preservedAt()), this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y + 24, TextColors.WHITE);
		}

		if (files.isEmpty()) {
			String empty = showAvailable ? "No deleted modpack files are available." : "No files have been preserved.";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(empty).withStyle(ChatFormatting.GRAY), this.width / 2, 90, TextColors.WHITE);
		}
		if (pageCount() > 1) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 40,
				TextColors.WHITE);
	}

	private String displayPreservedAt(String preservedAt) {
		return preservedAt == null || preservedAt.isEmpty() ? "Unknown" : truncateToWidth(this.font, preservedAt, Math.max(1, this.width - 20));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}
}
