package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.QuarantineArchive;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Shows local mods kept safely aside after a same-ID conflict. */
public final class QuarantineArchiveScreen extends VersionedScreen {
	private static final int ROW_HEIGHT = 42;

	private final Screen parent;
	private final ClientStorage storage;
	private final String modpackId;
	private final String modpackName;
	private final boolean activePack;
	private final Runnable closedCallback;
	private QuarantineArchive.Snapshot snapshot;
	private boolean loading;
	private boolean busy;
	private boolean closed;
	private int page;
	private Future<?> work;

	public QuarantineArchiveScreen(Screen parent, ClientStorage storage, String modpackId, String modpackName, boolean activePack, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.quarantine.title"));
		this.parent = parent;
		this.storage = storage;
		this.modpackId = modpackId;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.activePack = activePack;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		if (!loading && snapshot == null) loadSnapshot();
		List<QuarantineArchive.ArchiveEntry> entries = entries();
		int pageCount = pageCount(entries);
		page = Math.max(0, Math.min(pageCount - 1, page));
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(entries.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			QuarantineArchive.ArchiveEntry entry = entries.get(index);
			int y = 72 + (index - start) * ROW_HEIGHT;
			if (activePack) {
				int rowWidth = panelWidth(310);
				int restoreWidth = Math.max(ActionAreaLayout.MIN_BUTTON_WIDTH, this.font.width(VersionedText.translatable("automodpack.quarantine.restore").getString()) + 16);
				restoreWidth = Math.min(rowWidth, restoreWidth);
				Button restore = buttonWidget(panelLeft(310) + rowWidth - restoreWidth, y + 9, restoreWidth, 20, VersionedText.translatable("automodpack.quarantine.restore"), press -> restore(entry));
				restore.active = !busy && !loading;
				this.addRenderableWidget(restore);
			}
		}
		boolean hasPagination = pageCount > 1;
		List<ActionRow> rows = new ArrayList<>();
		if (hasPagination) {
			rows.add(actionRow(ActionAreaLayout.RowKind.NAVIGATION,
					navigationAction(VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1)),
					disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount)),
					navigationAction(VersionedText.translatable("automodpack.ui.next"), press -> changePage(1))));
		}
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> back())));
		List<Button> actionButtons = addActionArea(310, this.height - 28, rows.toArray(ActionRow[]::new));
		if (hasPagination) {
			actionButtons.get(0).active = !busy && page > 0;
			actionButtons.get(2).active = !busy && page + 1 < pageCount;
		}
	}

	private void loadSnapshot() {
		loading = true;
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				QuarantineArchive.Snapshot loaded = QuarantineArchive.snapshot(storage, modpackId);
				this.minecraft.execute(() -> {
					if (closed) return;
					snapshot = loaded;
					loading = false;
					rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private List<QuarantineArchive.ArchiveEntry> entries() {
		return snapshot == null ? List.of() : snapshot.entries();
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 120) / ROW_HEIGHT);
	}

	private int pageCount(List<QuarantineArchive.ArchiveEntry> values) {
		return Math.max(1, (values.size() + rowsPerPage() - 1) / rowsPerPage());
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount(entries()) - 1, page + amount));
		rebuild();
	}

	private void restore(QuarantineArchive.ArchiveEntry entry) {
		if (busy || closed || !activePack) return;
		busy = true;
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				QuarantineArchive.restore(storage, modpackId, entry.conflictId());
				QuarantineArchive.Snapshot refreshed = QuarantineArchive.snapshot(storage, modpackId);
				this.minecraft.execute(() -> {
					if (closed) return;
					snapshot = refreshed;
					busy = false;
					page = Math.min(page, pageCount(entries()) - 1);
					rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void fail(Exception exception) {
		if (closed) return;
		busy = false;
		closeToParent();
		new ScreenManager().failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	private void closeToParent() {
		if (closed) return;
		closed = true;
		cancelWork();
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	private void cancelWork() {
		Future<?> currentWork = work;
		if (currentWork != null && !currentWork.isDone()) currentWork.cancel(true);
	}

	private void back() {
		if (closed) return;
		closeToParent();
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
	public void removed() {
		if (!closed) {
			closed = true;
			cancelWork();
			closedCallback.run();
		}
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = VersionedText.translatable("automodpack.quarantine.titleNamed", modpackName.isBlank() ? modpackId : modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.quarantine.description1").withStyle(ChatFormatting.GRAY), this.width / 2, 28, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.quarantine.description2").withStyle(ChatFormatting.GRAY), this.width / 2, 40, TextColors.WHITE);
		String state = loading ? VersionedText.translatable("automodpack.quarantine.loading").getString() : activePack ? VersionedText.translatable("automodpack.quarantine.activeState").getString() : VersionedText.translatable("automodpack.quarantine.inactiveState").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, state, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, 54, TextColors.WHITE);
		List<QuarantineArchive.ArchiveEntry> values = entries();
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(values.size(), start + pageSize);
		int restoreWidth = activePack ? Math.min(panelWidth(310), Math.max(ActionAreaLayout.MIN_BUTTON_WIDTH, this.font.width(VersionedText.translatable("automodpack.quarantine.restore").getString()) + 16)) : 0;
		int textWidth = panelWidth(310) - (activePack ? restoreWidth + 8 : 8);
		for (int index = start; index < end; index++) {
			QuarantineArchive.ArchiveEntry entry = values.get(index);
			int y = 72 + (index - start) * ROW_HEIGHT;
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.quarantine.from", entry.sourcePath()).getString(), textWidth)).withStyle(ChatFormatting.WHITE), panelLeft(310), y, TextColors.WHITE);
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.quarantine.ids", String.join(", ", entry.modIds())).getString(), textWidth)).withStyle(ChatFormatting.AQUA), panelLeft(310), y + 12, TextColors.WHITE);
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.quarantine.target", entry.targetPath()).getString(), textWidth)).withStyle(ChatFormatting.GRAY), panelLeft(310), y + 24, TextColors.WHITE);
		}
		if (!loading && values.isEmpty()) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.quarantine.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 92, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}
}
