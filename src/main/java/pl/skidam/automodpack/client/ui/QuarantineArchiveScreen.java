package pl.skidam.automodpack.client.ui;

import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.QuarantineArchive;
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
				Button restore = buttonWidget(panelLeft(310) + rowWidth - 72, y + 9, 72, 20, VersionedText.translatable("automodpack.quarantine.restore"), press -> restore(entry));
				restore.active = !busy && !loading;
				this.addRenderableWidget(restore);
			}
		}
		boolean hasPagination = pageCount > 1;
		int actionCount = hasPagination ? 3 : 1;
		int actionWidth = actionButtonWidth(310, actionCount);
		int actionY = this.height - 28;
		if (hasPagination) {
			Button previous = buttonWidget(actionButtonX(310, 3, 0), actionY, actionWidth, 20, VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1));
			previous.active = page > 0;
			this.addRenderableWidget(previous);
			Button pageLabel = buttonWidget(actionButtonX(310, 3, 1), actionY, actionWidth, 20, VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			Button next = buttonWidget(actionButtonX(310, 3, 2), actionY, actionWidth, 20, VersionedText.translatable("automodpack.ui.next"), press -> changePage(1));
			next.active = page + 1 < pageCount;
			this.addRenderableWidget(next);
		} else {
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 1, 1, 0), actionY, actionWidth, 20, VersionedText.translatable("automodpack.back"), press -> back()));
		}
		if (hasPagination) this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 3, 1, 0), actionY - 26, actionButtonWidth(310, 3), 20,
				VersionedText.translatable("automodpack.back"), press -> back()));
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
		new ScreenManager().error(exception, "automodpack.error.critical", String.valueOf(exception.getMessage()), "automodpack.error.logs");
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
		int textWidth = panelWidth(310) - (activePack ? 82 : 8);
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
		back();
		return false;
	}
}
