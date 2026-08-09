package pl.skidam.automodpack.client.ui;

import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.LocalModArchive;

/** Global management view for explicitly archived local mods. */
public final class LocalModArchiveScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 310;
	private static final int ROW_HEIGHT = 42;

	private final Screen parent;
	private final ClientStorage storage;
	private final Runnable closedCallback;
	private LocalModArchive.Snapshot snapshot;
	private boolean loading;
	private boolean busy;
	private boolean closed;
	private String error;
	private int page;
	private Future<?> work;

	public LocalModArchiveScreen(Screen parent, ClientStorage storage, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.localMods.archiveTitle"));
		this.parent = parent;
		this.storage = storage;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		if (!loading && snapshot == null) loadSnapshot();
		List<LocalModArchive.ArchiveEntry> entries = entries();
		int pageSize = rowsPerPage();
		int pageCount = pageCount(entries);
		page = Math.max(0, Math.min(pageCount - 1, page));
		int start = page * pageSize;
		for (int index = start; index < Math.min(entries.size(), start + pageSize); index++) {
			LocalModArchive.ArchiveEntry entry = entries.get(index);
			int y = 70 + (index - start) * ROW_HEIGHT;
			Button restore = buttonWidget(panelLeft(PANEL_WIDTH) + panelWidth(PANEL_WIDTH) - 78, y + 10, 78, 20,
					VersionedText.translatable("automodpack.localMods.restore"), press -> restore(entry));
			restore.active = !loading && !busy;
			this.addRenderableWidget(restore);
		}
		boolean hasPagination = pageCount > 1;
		int actionY = this.height - 28;
		if (hasPagination) {
			int width = actionButtonWidth(PANEL_WIDTH, 3);
			this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 3, 0), actionY, width, 20, VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1)));
			Button label = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 1), actionY, width, 20, VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			label.active = false;
			this.addRenderableWidget(label);
			this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 3, 2), actionY, width, 20, VersionedText.translatable("automodpack.ui.next"), press -> changePage(1)));
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 1, 0), actionY - 26, actionButtonWidth(PANEL_WIDTH, 3), 20, VersionedText.translatable("automodpack.back"), press -> back()));
		} else {
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 1, 1, 0), actionY, actionButtonWidth(PANEL_WIDTH, 1), 20, VersionedText.translatable("automodpack.back"), press -> back()));
		}
	}

	private List<LocalModArchive.ArchiveEntry> entries() {
		return snapshot == null ? List.of() : snapshot.entries();
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 126) / ROW_HEIGHT);
	}

	private int pageCount(List<LocalModArchive.ArchiveEntry> values) {
		return Math.max(1, (values.size() + rowsPerPage() - 1) / rowsPerPage());
	}

	private void loadSnapshot() {
		loading = true;
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				LocalModArchive.Snapshot loaded = LocalModArchive.snapshot(storage);
				this.minecraft.execute(() -> {
					if (closed) return;
					snapshot = loaded;
					loading = false;
					rebuild();
				});
			} catch (Exception exception) {
				this.minecraft.execute(() -> fail(exception));
			}
		});
	}

	private void restore(LocalModArchive.ArchiveEntry entry) {
		if (busy || loading || closed) return;
		busy = true;
		error = null;
		rebuild();
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				LocalModArchive.restore(storage, entry.entryId());
				LocalModArchive.Snapshot refreshed = LocalModArchive.snapshot(storage);
				this.minecraft.execute(() -> {
					if (closed) return;
					snapshot = refreshed;
					busy = false;
					page = Math.min(page, pageCount(entries()) - 1);
					rebuild();
				});
			} catch (Exception exception) {
				this.minecraft.execute(() -> fail(exception));
			}
		});
	}

	private void fail(Exception exception) {
		busy = false;
		loading = false;
		error = exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage();
		rebuild();
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount(entries()) - 1, page + amount));
		rebuild();
	}

	private MutableComponent rowLabel(LocalModArchive.ArchiveEntry entry) {
		return VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.localMods.entry", entry.originalPath(), UiFormat.formatSize(entry.size())).getString(), panelWidth(PANEL_WIDTH) - 90));
	}

	private void back() {
		if (busy) return;
		closed = true;
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
	public void removed() {
		if (!closed) {
			closed = true;
			if (closedCallback != null) closedCallback.run();
		}
		Future<?> current = work;
		if (current != null && !current.isDone()) current.cancel(false);
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.localMods.archiveTitle").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.localMods.archiveDescription").withStyle(ChatFormatting.GRAY), this.width / 2, 30, TextColors.WHITE);
		if (!loading && entries().isEmpty()) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.localMods.archiveEmpty").withStyle(ChatFormatting.GREEN), this.width / 2, 70, TextColors.WHITE);
		for (int index = page * rowsPerPage(); index < Math.min(entries().size(), page * rowsPerPage() + rowsPerPage()); index++) {
			LocalModArchive.ArchiveEntry entry = entries().get(index);
			int y = 70 + (index - page * rowsPerPage()) * ROW_HEIGHT;
			drawTextWithShadow(matrices, this.font, rowLabel(entry), panelLeft(PANEL_WIDTH), y, TextColors.WHITE);
			drawTextWithShadow(matrices, this.font, VersionedText.literal(entry.sha1()).withStyle(ChatFormatting.DARK_GRAY), panelLeft(PANEL_WIDTH), y + 14, TextColors.WHITE);
		}
		if (error != null) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, error, this.width - 20)).withStyle(ChatFormatting.RED), this.width / 2, this.height - 44, TextColors.WHITE);
	}
}
