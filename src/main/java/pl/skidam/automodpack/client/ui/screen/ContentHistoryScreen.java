package pl.skidam.automodpack.client.ui.screen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.ChangeSummary;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.change.PlatformReferences;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * The one journal timeline of one installed pack. Every entry shows its own patch notes inline, so
 * history and patch notes are one screen; opening an entry reveals its recorded content diff.
 */
public final class ContentHistoryScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int ROW_HEIGHT = 56;
	private static final int TEXT_MARGIN = 6;
	// Rows carry the notes' first lines; the entry detail carries every line.
	private static final int ROW_NOTES_LINES = 3;
	private static final int DETAIL_NOTES_LINES = 12;
	private static final int LIST_TOP = 44;

	private final Screen parent;
	private final List<JournalEntry> entries;
	private final long currentSeq;
	private final String modpackName;
	private final Runnable closedCallback;
	private final Set<Long> restorableSeqs;
	private final Consumer<JournalEntry> restore;
	private RowListWidget historyList;
	private boolean closed;

	public ContentHistoryScreen(Screen parent, HistoryViewRequest request) {
		super(VersionedText.text("automodpack.history.title"));
		this.parent = parent;
		List<JournalEntry> newestFirst = new ArrayList<>(request.journal());
		Collections.reverse(newestFirst);
		this.entries = List.copyOf(newestFirst);
		this.currentSeq = request.currentSeq();
		this.modpackName = request.modpackName();
		this.closedCallback = request.closed();
		this.restorableSeqs = request.restorableSeqs();
		this.restore = request.restore();
	}

	@Override
	protected void init() {
		super.init();
		ActionRow[] rowArray = {actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), button -> back()))};
		int width = panelWidth(PANEL_WIDTH);
		int currentIndex = -1;
		List<RowListWidget.Row> rows = new ArrayList<>(entries.size());
		for (int index = 0; index < entries.size(); index++) {
			rows.add(row(entries.get(index), width - TEXT_MARGIN * 2));
			if (isCurrent(entries.get(index))) currentIndex = index;
		}
		// The list fills the space between the header and the pinned actions; only a real overflow scrolls.
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray) - 6;
		this.historyList = this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, width, 0, LIST_TOP, listBottom, ROW_HEIGHT, rows, this::openEntry));
		// The installed generation is the entry the player came here for, so it starts selected and scrolled into view.
		if (currentIndex >= 0) {
			this.historyList.setSelected(this.historyList.children().get(currentIndex));
			this.historyList.revealRow(currentIndex);
		}
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
	}

	/** One journal entry as a self-contained row: date, first note lines, and the diff summary. */
	private RowListWidget.Row row(JournalEntry entry, int width) {
		List<MutableComponent> lines = new ArrayList<>();
		String updated = VersionedText.str("automodpack.history.updated", UiFormat.formatInstant(entry.createdAt()));
		MutableComponent header = VersionedText.literal(truncateToWidth(this.font, updated, width)).withStyle(ChatFormatting.GRAY);
		if (isCurrent(entry)) {
			MutableComponent badge = VersionedText.literal(VersionedText.str("automodpack.history.current") + " · ").withStyle(ChatFormatting.GREEN);
			badge.append(VersionedText.literal(truncateToWidth(this.font, updated, width - this.font.width(badge))).withStyle(ChatFormatting.GRAY));
			header = badge;
		} else if (restore != null && restorableSeqs.contains(entry.seq())) {
			MutableComponent badge = VersionedText.literal(VersionedText.translatable("automodpack.history.hadThis").getString() + " · ").withStyle(ChatFormatting.YELLOW);
			badge.append(VersionedText.literal(truncateToWidth(this.font, updated, width - this.font.width(badge))).withStyle(ChatFormatting.GRAY));
			header = badge;
		} else if (restore != null && !restorableSeqs.contains(entry.seq())) {
			String badge = " · " + VersionedText.str("automodpack.history.notRestorable");
			MutableComponent marked = VersionedText.literal(truncateToWidth(this.font, updated, width - this.font.width(badge))).withStyle(ChatFormatting.GRAY);
			marked.append(VersionedText.literal(badge).withStyle(ChatFormatting.DARK_GRAY));
			header = marked;
		}
		lines.add(header);
		if (entry.notes().isBlank()) {
			lines.add(VersionedText.text("automodpack.history.noPatchNotes").withStyle(ChatFormatting.GRAY));
		} else {
			for (String line : wrapToWidth(this.font, entry.notes(), width, ROW_NOTES_LINES)) lines.add(VersionedText.literal(line).withStyle(ChatFormatting.WHITE));
		}
		JournalEntry.Summary summary = entry.summary();
		String diff = ChangeSummary.diffLine(summary.added(), summary.changed(), summary.removed(), 0, 0);
		lines.add(VersionedText.literal(truncateToWidth(this.font, diff, width)).withStyle(ChatFormatting.GRAY));
		if (restore != null && !restorableSeqs.contains(entry.seq()) && !isCurrent(entry))
			return new RowListWidget.Row(lines, VersionedText.text("automodpack.history.notRestorableTooltip"));
		return new RowListWidget.Row(lines);
	}

	private boolean isCurrent(JournalEntry entry) {
		return entry.seq() == currentSeq;
	}

	private void openEntry(int index) {
		JournalEntry entry = entries.get(index);
		Component heading = VersionedText.text("automodpack.history.detailsTitle", UiFormat.formatInstant(entry.createdAt()));
		List<MutableComponent> notes = new ArrayList<>();
		for (String line : wrapToWidth(this.font, entry.notes(), panelWidth(PANEL_WIDTH) - TEXT_MARGIN * 2, DETAIL_NOTES_LINES))
			notes.add(VersionedText.literal(line).withStyle(ChatFormatting.WHITE));
		ChangeBrowserScreen.BrowserAction restoreAction = restorableSeqs.contains(entry.seq()) && restore != null
				? new ChangeBrowserScreen.BrowserAction(VersionedText.text("automodpack.history.restore"), screen -> restore.accept(entry), true)
				: null;
		openBrowserScreen(heading, VersionedText.text("automodpack.history.detailsDescription"), notes, changeSet(entry), restoreAction);
	}

	/** Turns one journal entry's recorded changes into the shared logical change model: a removal's size is the bytes it freed, a modification also carries its "was" size. */
	private static ChangeSet changeSet(JournalEntry entry) {
		List<ChangeSet.Change> changes = new ArrayList<>(entry.changes().size());
		for (JournalEntry.Change change : entry.changes()) {
			JournalEntry.Change.Kind kind = change.kind();
			long size = kind == JournalEntry.Change.Kind.REMOVED ? change.fromSize() : change.toSize();
			Long beforeSize = kind == JournalEntry.Change.Kind.CHANGED ? change.fromSize() : null;
			changes.add(new ChangeSet.Change(change.path(),
					switch (kind) {
						case ADDED -> ChangeSet.Kind.ADDED;
						case CHANGED -> ChangeSet.Kind.MODIFIED;
						case REMOVED -> ChangeSet.Kind.REMOVED;
					},
					List.of(new ChangeSet.Occurrence("journal", change.path(), size, beforeSize, change.fromSha1(), change.toSha1(), null, List.of(), List.of()))));
		}
		return ChangeSet.of(changes);
	}

	/** Resolves the cached Modrinth/CurseForge page references off the render thread, then opens the shared browser. */
	private void openBrowserScreen(Component heading, Component description, List<MutableComponent> notes, ChangeSet changes, ChangeBrowserScreen.BrowserAction restoreAction) {
		ScreenManager.background(() -> {
			ChangeSet referenced = PlatformReferences.withCachedReferences(changes, platformCacheDirectory());
			this.minecraft.execute(() -> {
				if (closed) return;
				ScreenImpl.setScreen(new ChangeBrowserScreen(this, heading, description, referenced, Map.of(), restoreAction, notes, 0, ""));
			});
		});
	}

	private static Path platformCacheDirectory() {
		return ClientStorage.open(GameDirectory.current()).platformCacheDirectory();
	}

	private void back() {
		if (closed) return;
		closed = true;
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = VersionedText.str(modpackName.isBlank() ? "automodpack.history.title" : "automodpack.history.titleNamed", modpackName);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), this.width / 2, 10, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.history.description").withStyle(ChatFormatting.GRAY), this.width / 2, 25, TextColors.WHITE);
		if (entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.history.empty").withStyle(ChatFormatting.GRAY), this.width / 2, LIST_TOP + 24, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}
}
