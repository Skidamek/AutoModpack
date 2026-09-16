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
	private static final int ARCHIVED_ROW_HEIGHT = 42;

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

	public RecoveryArchiveScreen(Screen parent, ModpackUpdater updater, ModpackUpdater.RecoverySnapshot snapshot, String modpackName) {
		this(parent, updater, snapshot, modpackName, () -> {});
	}

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
		int left = this.width / 2 - 155;
		this.addRenderableWidget(buttonWidget(left, 50, 150, 20, VersionedText.literal("Recoverable files"), button -> selectAvailable()));
		this.addRenderableWidget(buttonWidget(left + 160, 50, 150, 20, VersionedText.literal("Archived files"), button -> selectArchived()));
		this.previousButton = buttonWidget(left, navigationY, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(left + 240, navigationY, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		updateNavigation();
		this.addRenderableWidget(this.previousButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(buttonWidget(left + 80, navigationY, 75, 20, VersionedText.translatable("automodpack.back"), button -> back()));
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
			Button button = buttonWidget(this.width / 2 - 155, y, 310, 20,
					VersionedText.literal(truncateToWidth(this.font, "Archive " + file.logicalPath() + " (" + formatSize(file.size()) + ")", 290)), press -> archive(file));
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
				? Math.max(1, (this.height - 28 - 72) / 22)
				: Math.max(1, (this.height - 64 - 76) / ARCHIVED_ROW_HEIGHT);
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Deleted files stay outside the managed modpack tree.", this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 30,
				TextColors.WHITE);
		String counts = "Recoverable: " + snapshot.available().size() + "  Archived: " + snapshot.archived().size();
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
			drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "SHA-1: " + shortHash(file.sha1()) + "  Size: " + formatSize(file.size()) + "  State: Archived", this.width - 20)).withStyle(ChatFormatting.GREEN), this.width / 2, y + 10,
					TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "Source generation: " + displayGeneration(file.sourceGenerationId()), this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y + 20, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "Preserved at: " + displayPreservedAt(file.preservedAt()), this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y + 30, TextColors.WHITE);
		}

		if (files.isEmpty()) {
			String empty = showAvailable ? "No deleted CAS objects are available." : "No files are archived.";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(empty).withStyle(ChatFormatting.GRAY), this.width / 2, 90, TextColors.WHITE);
		}
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Archiving file...").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height - 46, TextColors.WHITE);
		if (pageCount() > 1) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 40,
				TextColors.WHITE);
	}

	private String shortHash(String hash) {
		return truncateToWidth(this.font, hash, Math.max(1, this.width - 20));
	}

	private String displayGeneration(String generationId) {
		return generationId == null || generationId.isEmpty() ? "Unknown" : shortHash(generationId);
	}

	private String displayPreservedAt(String preservedAt) {
		return preservedAt == null || preservedAt.isEmpty() ? "Unknown" : truncateToWidth(this.font, preservedAt, Math.max(1, this.width - 20));
	}

	private static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
		return (bytes / (1024 * 1024)) + " MiB";
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}
}
