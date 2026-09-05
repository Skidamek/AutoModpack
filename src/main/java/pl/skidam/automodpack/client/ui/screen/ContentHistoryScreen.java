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
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;

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
	private boolean closed;

	public ContentHistoryScreen(Screen parent, HistoryViewRequest request) {
		super(VersionedText.translatable("automodpack.history.title"));
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
		List<ActionRow> actionRows = List.of(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> back())));
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows.toArray(ActionRow[]::new)) - 6;
		if (!entries.isEmpty()) {
			int width = panelWidth(PANEL_WIDTH);
			List<RowListWidget.Row> rows = new ArrayList<>(entries.size());
			for (int index = 0; index < entries.size(); index++) rows.add(row(entries.get(index), width - TEXT_MARGIN * 2));
			this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, width, LIST_TOP, listBottom, ROW_HEIGHT, rows, this::openEntry, this::showComponentTooltip));
		}
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows.toArray(ActionRow[]::new));
	}

	/** One journal entry as a self-contained row: date, first note lines, and the diff summary. */
	private RowListWidget.Row row(JournalEntry entry, int width) {
		List<MutableComponent> lines = new ArrayList<>();
		String updated = VersionedText.translatable("automodpack.history.updated", UiFormat.formatInstant(entry.createdAt())).getString();
		MutableComponent header = VersionedText.literal(truncateToWidth(this.font, updated, width)).withStyle(ChatFormatting.GRAY);
		if (isCurrent(entry)) {
			MutableComponent badge = VersionedText.literal(VersionedText.translatable("automodpack.history.current").getString() + " · ").withStyle(ChatFormatting.GREEN);
			badge.append(VersionedText.literal(truncateToWidth(this.font, updated, width - this.font.width(badge))).withStyle(ChatFormatting.GRAY));
			header = badge;
		} else if (restore != null && !restorableSeqs.contains(entry.seq())) {
			String badge = " · " + VersionedText.translatable("automodpack.history.notRestorable").getString();
			MutableComponent marked = VersionedText.literal(truncateToWidth(this.font, updated, width - this.font.width(badge))).withStyle(ChatFormatting.GRAY);
			marked.append(VersionedText.literal(badge).withStyle(ChatFormatting.DARK_GRAY));
			header = marked;
		}
		lines.add(header);
		if (entry.notes().isBlank()) {
			lines.add(VersionedText.translatable("automodpack.history.noPatchNotes").withStyle(ChatFormatting.GRAY));
		} else {
			for (String line : wrapToWidth(this.font, entry.notes(), width, ROW_NOTES_LINES)) lines.add(VersionedText.literal(line).withStyle(ChatFormatting.WHITE));
		}
		JournalEntry.Summary summary = entry.summary();
		String diff = ChangeSummary.diffLine(summary.added(), summary.changed(), summary.removed(), 0, 0);
		lines.add(VersionedText.literal(truncateToWidth(this.font, diff, width)).withStyle(ChatFormatting.GRAY));
		if (restore != null && !restorableSeqs.contains(entry.seq()) && !isCurrent(entry))
			return new RowListWidget.Row(lines, VersionedText.translatable("automodpack.history.notRestorableTooltip"));
		return new RowListWidget.Row(lines);
	}

	private boolean isCurrent(JournalEntry entry) {
		return entry.seq() == currentSeq;
	}

	private void openEntry(int index) {
		JournalEntry entry = entries.get(index);
		Component heading = VersionedText.translatable("automodpack.history.detailsTitle", UiFormat.formatInstant(entry.createdAt()));
		List<MutableComponent> notes = new ArrayList<>();
		for (String line : wrapToWidth(this.font, entry.notes(), panelWidth(PANEL_WIDTH) - TEXT_MARGIN * 2, DETAIL_NOTES_LINES))
			notes.add(VersionedText.literal(line).withStyle(ChatFormatting.WHITE));
		ChangeBrowserScreen.BrowserAction restoreAction = restorableSeqs.contains(entry.seq()) && restore != null
				? new ChangeBrowserScreen.BrowserAction(VersionedText.translatable("automodpack.history.restore"), screen -> restore.accept(entry), true)
				: null;
		openBrowserScreen(heading, VersionedText.translatable("automodpack.history.detailsDescription"), notes, changeSet(entry), restoreAction);
	}

	/** Turns one journal entry's recorded changes into the shared logical change model. */
	private static ChangeSet changeSet(JournalEntry entry) {
		List<ChangeSet.Change> changes = new ArrayList<>(entry.changes().size());
		for (JournalEntry.Change change : entry.changes())
			changes.add(new ChangeSet.Change(change.path(),
					switch (change.kind()) {
						case ADDED -> ChangeSet.Kind.ADDED;
						case CHANGED -> ChangeSet.Kind.MODIFIED;
						case REMOVED -> ChangeSet.Kind.REMOVED;
					},
					List.of(new ChangeSet.Occurrence("journal", change.path(), change.toSize(), change.fromSha1(), change.toSha1()))));
		return ChangeSet.of(changes);
	}

	/** Resolves the cached Modrinth/CurseForge page references off the render thread, then opens the shared browser. */
	private void openBrowserScreen(Component heading, Component description, List<MutableComponent> notes, ChangeSet changes, ChangeBrowserScreen.BrowserAction restoreAction) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ChangeSet referenced = PlatformReferences.withCachedReferences(changes, platformCacheDirectory());
			this.minecraft.execute(() -> {
				if (closed) return;
				ScreenImpl.setScreen(new ChangeBrowserScreen(this, heading, description, referenced, Map.of(), restoreAction, notes));
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
		String title = VersionedText.translatable(modpackName.isBlank() ? "automodpack.history.title" : "automodpack.history.titleNamed", modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), this.width / 2, 10, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.description").withStyle(ChatFormatting.GRAY), this.width / 2, 25, TextColors.WHITE);
		if (entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.empty").withStyle(ChatFormatting.GRAY), this.width / 2, LIST_TOP + 24, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}
}
