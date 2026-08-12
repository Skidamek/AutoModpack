package pl.skidam.automodpack.client.ui;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
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
		super(VersionedText.translatable("automodpack.recovery.title"));
		this.parent = parent;
		this.updater = updater;
		this.snapshot = snapshot;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		int left = panelLeft(310);
		int rowWidth = panelWidth(310);
		int gap = 10;
		int tabWidth = (rowWidth - gap) / 2;
		Button availableTab = buttonWidget(left, 50, tabWidth, 20, VersionedText.translatable("automodpack.recovery.availableTab"), button -> selectAvailable());
		Button archivedTab = buttonWidget(left + tabWidth + gap, 50, tabWidth, 20, VersionedText.translatable("automodpack.recovery.archivedTab"), button -> selectArchived());
		availableTab.active = !showAvailable;
		archivedTab.active = showAvailable;
		this.addRenderableWidget(availableTab);
		this.addRenderableWidget(archivedTab);
		boolean hasPagination = pageCount() > 1;
		List<ActionRow> rows = new ArrayList<>();
		if (hasPagination) {
			rows.add(actionRow(ActionAreaLayout.RowKind.NAVIGATION,
					navigationAction(VersionedText.translatable("automodpack.ui.previous"), button -> changePage(-1)),
					disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount())),
					navigationAction(VersionedText.translatable("automodpack.ui.next"), button -> changePage(1))));
		}
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> back())));
		List<Button> buttons = addActionArea(310, this.height - 28, rows.toArray(ActionRow[]::new));
		if (hasPagination) {
			this.previousButton = buttons.get(0);
			this.nextButton = buttons.get(2);
		} else {
			this.previousButton = null;
			this.nextButton = null;
		}
		updateNavigation();
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
					VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.recovery.preserveFile", file.logicalPath(), UiFormat.formatSize(file.size())).getString(), rowWidth - 20)), press -> archive(file));
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
				new ScreenManager().failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
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
		String title = VersionedText.translatable(modpackName.isBlank() ? "automodpack.recovery.title" : "automodpack.recovery.titleNamed", modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.recovery.description").withStyle(ChatFormatting.GRAY), this.width / 2, 30,
				TextColors.WHITE);
		String counts = busy ? VersionedText.translatable("automodpack.recovery.busy").getString() : VersionedText.translatable("automodpack.recovery.counts", snapshot.available().size(), snapshot.archived().size()).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(counts).withStyle(ChatFormatting.YELLOW), this.width / 2, 42, TextColors.WHITE);
		List<ModpackUpdater.RecoveryFile> files = files();
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(files.size(), start + pageSize);
		if (!showAvailable) for (int index = start; index < end; index++) {
			ModpackUpdater.RecoveryFile file = files.get(index);
			int y = 76 + (index - start) * ARCHIVED_ROW_HEIGHT;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.recovery.path", file.logicalPath()).getString(), this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2, y,
					TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.recovery.size", UiFormat.formatSize(file.size())).withStyle(ChatFormatting.GREEN), this.width / 2, y + 12, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.recovery.preservedAt", displayPreservedAt(file.preservedAt())).getString(), this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y + 24, TextColors.WHITE);
		}

		if (files.isEmpty()) {
			String empty = VersionedText.translatable(showAvailable ? "automodpack.recovery.emptyAvailable" : "automodpack.recovery.emptyArchived").getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(empty).withStyle(ChatFormatting.GRAY), this.width / 2, 90, TextColors.WHITE);
		}
	}

	private String displayPreservedAt(String preservedAt) {
		return preservedAt == null || preservedAt.isEmpty() ? VersionedText.translatable("automodpack.recovery.unknown").getString() : UiFormat.formatInstant(Instant.parse(preservedAt));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}
}
