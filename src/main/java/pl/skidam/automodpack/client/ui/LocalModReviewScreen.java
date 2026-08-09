package pl.skidam.automodpack.client.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.LocalModArchive;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;

/** Explicit first-install review of ordinary jars already present in the client mods directory. */
public final class LocalModReviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 310;
	private static final int ROW_HEIGHT = 24;

	private final Screen parent;
	private final ModpackUpdater updater;
	private final LocalModArchive.Snapshot snapshot;
	private final Runnable completed;
	private final Set<String> selected = new HashSet<>();
	private int page;
	private boolean busy;
	private boolean closed;
	private String error;
	private Future<?> work;

	public LocalModReviewScreen(Screen parent, ModpackUpdater updater, LocalModArchive.Snapshot snapshot, Runnable completed) {
		super(VersionedText.translatable("automodpack.localMods.title"));
		this.parent = parent;
		this.updater = updater;
		this.snapshot = snapshot;
		this.completed = completed;
	}

	@Override
	protected void init() {
		super.init();
		List<LocalModArchive.ArchiveEntry> entries = snapshot.entries();
		int listTop = 64;
		int listBottom = this.height - 58;
		int rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		int pageCount = Math.max(1, (entries.size() + rowsPerPage - 1) / rowsPerPage);
		page = Math.max(0, Math.min(pageCount - 1, page));
		int rowWidth = panelWidth(PANEL_WIDTH);
		int start = page * rowsPerPage;
		for (int index = start; index < Math.min(entries.size(), start + rowsPerPage); index++) {
			LocalModArchive.ArchiveEntry entry = entries.get(index);
			Button row = buttonWidget(panelLeft(PANEL_WIDTH), listTop + (index - start) * ROW_HEIGHT, rowWidth, 20, rowLabel(entry), press -> toggle(entry));
			row.active = !busy;
			this.addRenderableWidget(row);
		}
		if (pageCount > 1) {
			int pageY = listBottom + 4;
			int pageWidth = actionButtonWidth(PANEL_WIDTH, 3);
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 0), pageY, pageWidth, 20,
					VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1)));
			Button pageLabel = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 1), pageY, pageWidth, 20,
					VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 2), pageY, pageWidth, 20,
					VersionedText.translatable("automodpack.ui.next"), press -> changePage(1)));
		}
		int actionCount = entries.isEmpty() ? 1 : 2;
		int actionWidth = actionButtonWidth(PANEL_WIDTH, actionCount);
		int actionY = this.height - 28;
		if (entries.isEmpty()) {
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 1, 1, 0), actionY, actionWidth,
					20, VersionedText.translatable("automodpack.back"), press -> back()));
		} else {
			Button move = buttonWidget(actionButtonX(PANEL_WIDTH, 2, 0), actionY, actionWidth,
					20, VersionedText.translatable(busy ? "automodpack.localMods.moving" : "automodpack.localMods.move"), press -> archiveSelected());
			move.active = !busy && !selected.isEmpty();
			this.addRenderableWidget(move);
			this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 2, 1), actionY, actionWidth, 20,
					VersionedText.translatable("automodpack.back"), press -> back()));
		}
	}

	private MutableComponent rowLabel(LocalModArchive.ArchiveEntry entry) {
		boolean checked = selected.contains(entry.entryId());
		String marker = checked ? "[x] " : "[ ] ";
		return VersionedText.literal(truncateToWidth(this.font, marker + entry.originalPath(), panelWidth(PANEL_WIDTH) - 12))
				.withStyle(checked ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
	}

	private void toggle(LocalModArchive.ArchiveEntry entry) {
		if (busy) return;
		if (!selected.add(entry.entryId())) selected.remove(entry.entryId());
		rebuild();
	}

	private void archiveSelected() {
		if (busy || selected.isEmpty()) return;
		busy = true;
		error = null;
		rebuild();
		List<LocalModArchive.ArchiveEntry> chosen = snapshot.entries().stream().filter(entry -> selected.contains(entry.entryId())).collect(Collectors.toList());
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				updater.archiveLocalMods(chosen);
				this.minecraft.execute(() -> {
					if (closed) return;
					completed.run();
					ScreenImpl.setScreen(parent);
				});
			} catch (Exception exception) {
				this.minecraft.execute(() -> fail(exception));
			}
		});
	}

	private void fail(Exception exception) {
		busy = false;
		error = exception.getMessage() == null || exception.getMessage().isBlank() ? exception.getClass().getSimpleName() : exception.getMessage();
		rebuild();
	}

	private void changePage(int amount) {
		int rowsPerPage = Math.max(1, (this.height - 122) / ROW_HEIGHT);
		int pageCount = Math.max(1, (snapshot.entries().size() + rowsPerPage - 1) / rowsPerPage);
		page = Math.max(0, Math.min(pageCount - 1, page + amount));
		rebuild();
	}

	private void back() {
		if (busy) return;
		closed = true;
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
		closed = true;
		Future<?> current = work;
		if (current != null && !current.isDone()) current.cancel(false);
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.localMods.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		int y = 30;
		for (String line : wrapToWidth(this.font, VersionedText.translatable("automodpack.localMods.description1").getString(), this.width - 20, 2)) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
			y += 12;
		}
		for (String line : wrapToWidth(this.font, VersionedText.translatable("automodpack.localMods.description2").getString(), this.width - 20, 2)) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.YELLOW), this.width / 2, y, TextColors.WHITE);
			y += 12;
		}
		if (snapshot.entries().isEmpty()) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.localMods.empty").withStyle(ChatFormatting.GREEN), this.width / 2, 84, TextColors.WHITE);
		else drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.localMods.selected", selected.size()).withStyle(ChatFormatting.AQUA), this.width / 2, 56, TextColors.WHITE);
		if (error != null) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, error, this.width - 20)).withStyle(ChatFormatting.RED), this.width / 2, this.height - 44, TextColors.WHITE);
	}
}
