package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.ChangeSummary;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.change.PlatformReferences;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.client.Changelogs;

/**
 * Shows the journal timeline of one installed pack. Every journal entry already carries its own
 * content diff, so opening an entry is purely local work.
 */
public final class ContentHistoryScreen extends VersionedScreen {
	private static final int ENTRY_TOP = 52;
	private static final int ENTRY_HEIGHT = 54;
	private static final int PANEL_WIDTH = 600;

	private final Screen parent;
	private final List<JournalEntry> journal;
	private final List<JournalEntry> entries;
	private final long currentSeq;
	private final String modpackName;
	private final Runnable closedCallback;
	private Button previousButton;
	private Button nextButton;
	private int page;
	private boolean closed;

	public ContentHistoryScreen(Screen parent, HistoryViewRequest request) {
		super(VersionedText.translatable("automodpack.history.title"));
		this.parent = parent;
		this.journal = request.journal();
		List<JournalEntry> newestFirst = new ArrayList<>(journal);
		Collections.reverse(newestFirst);
		this.entries = List.copyOf(newestFirst);
		this.currentSeq = request.currentSeq();
		this.modpackName = request.modpackName() == null ? "" : request.modpackName();
		this.closedCallback = request.closed() == null ? () -> {} : request.closed();
	}

	@Override
	protected void init() {
		super.init();
		int bottomY = this.height - 28;
		boolean hasPagination = pageCount() > 1;
		List<ActionRow> actionRows = new ArrayList<>();
		if (hasPagination) {
			actionRows.add(actionRow(ActionAreaLayout.RowKind.NAVIGATION,
					navigationAction(VersionedText.translatable("automodpack.ui.previous"), button -> changePage(-1)),
					disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount())),
					navigationAction(VersionedText.translatable("automodpack.ui.next"), button -> changePage(1))));
		}
		List<ActionDefinition> footerActions = new ArrayList<>();
		footerActions.add(secondaryAction(VersionedText.translatable("automodpack.back"), button -> back()));
		if (hasPatchNotesHistory()) footerActions.add(optionalAction(VersionedText.translatable("automodpack.patchNotes.button"), button -> openPatchNotes()));
		actionRows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, footerActions.toArray(ActionDefinition[]::new)));
		List<Button> actionButtons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, bottomY, actionRows.toArray(ActionRow[]::new));
		if (hasPagination) {
			this.previousButton = actionButtons.get(0);
			this.nextButton = actionButtons.get(2);
		} else {
			this.previousButton = null;
			this.nextButton = null;
		}
		updateNavigation();

		int start = page * rowsPerPage();
		int end = Math.min(entries.size(), start + rowsPerPage());
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		for (int index = start; index < end; index++) {
			int entryIndex = index;
			JournalEntry entry = entries.get(index);
			String label = VersionedText.translatable("automodpack.history.updated", UiFormat.formatInstant(entry.createdAt())).getString();
			Button row = buttonWidget(x, ENTRY_TOP + (index - start) * ENTRY_HEIGHT, rowWidth, ENTRY_HEIGHT - 2,
					VersionedText.literal(truncateToWidth(this.font, label, rowWidth - 12)).withStyle(isCurrent(entry) ? ChatFormatting.GREEN : ChatFormatting.WHITE),
					button -> openEntry(entries.get(entryIndex)));
			this.addRenderableWidget(row);
		}
	}

	private boolean hasPatchNotesHistory() {
		return journal.size() > 1 || Changelogs.hasNotes(journal);
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (entries.size() + pageSize - 1) / pageSize);
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 92 - ENTRY_TOP) / ENTRY_HEIGHT);
	}

	private void updateNavigation() {
		page = Math.max(0, Math.min(pageCount() - 1, page));
		if (previousButton != null) previousButton.active = page > 0;
		if (nextButton != null) nextButton.active = page + 1 < pageCount();
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		rebuild();
	}

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	private boolean isCurrent(JournalEntry entry) {
		return entry.seq() == currentSeq;
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, journal, modpackName));
	}

	private void openEntry(JournalEntry entry) {
		Component heading = VersionedText.translatable("automodpack.history.detailsTitle",
				VersionedText.translatable("automodpack.history.updated", UiFormat.formatInstant(entry.createdAt())).getString());
		openBrowserScreen(heading, VersionedText.translatable("automodpack.history.detailsDescription"), changeSet(entry));
	}

	/** Turns one journal entry's recorded changes into the shared logical change model. */
	private static ChangeSet changeSet(JournalEntry entry) {
		List<ChangeSet.Change> changes = new ArrayList<>(entry.changes().size());
		for (JournalEntry.Change change : entry.changes())
			changes.add(new ChangeSet.Change(change.path(),
					change.fromSha1() == null ? ChangeSet.Kind.ADDED : change.toSha1() == null ? ChangeSet.Kind.REMOVED : ChangeSet.Kind.MODIFIED,
					List.of(new ChangeSet.Occurrence("journal", change.path(), change.toSize(), change.fromSha1(), change.toSha1()))));
		return ChangeSet.of(changes);
	}

	/** Resolves the cached Modrinth/CurseForge page references off the render thread, then opens the shared browser. */
	private void openBrowserScreen(Component heading, Component description, ChangeSet changes) {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ChangeSet referenced = PlatformReferences.withCachedReferences(changes, platformMetadataDirectory());
			this.minecraft.execute(() -> {
				if (closed) return;
				ScreenImpl.setScreen(new ChangeBrowserScreen(this, heading, description, referenced, Map.of()));
			});
		});
	}

	private static Path platformMetadataDirectory() {
		return ClientStorage.open(GameDirectory.current()).platformMetadataDirectory();
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
		int left = panelLeft(PANEL_WIDTH);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), left, 10, TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.description").withStyle(ChatFormatting.GRAY), left, 25, TextColors.WHITE);
		int start = page * rowsPerPage();
		int end = Math.min(entries.size(), start + rowsPerPage());
		int rowWidth = panelWidth(PANEL_WIDTH);
		for (int index = start; index < end; index++) {
			JournalEntry entry = entries.get(index);
			int y = ENTRY_TOP + (index - start) * ENTRY_HEIGHT;
			String status = isCurrent(entry) ? VersionedText.translatable("automodpack.history.current").getString() : "";
			String note = entry.notes().isBlank() ? VersionedText.translatable("automodpack.history.noPatchNotes").getString() : firstLine(entry.notes());
			JournalEntry.Summary summary = entry.summary();
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, status, rowWidth - 12)).withStyle(ChatFormatting.GREEN), left + 6, y + 4, TextColors.WHITE);
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.history.patchNotes", note).getString(), rowWidth - 12)).withStyle(ChatFormatting.WHITE), left + 6, y + 31,
					TextColors.WHITE);
			String diffText = ChangeSummary.diffLine(summary.added(), summary.changed(), summary.removed(), 0, 0);
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, diffText, rowWidth - 12)).withStyle(ChatFormatting.GRAY), left + 6, y + 42, TextColors.WHITE);
		}
		if (entries.isEmpty()) drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.empty").withStyle(ChatFormatting.GRAY), left, ENTRY_TOP, TextColors.WHITE);
	}

	private String firstLine(String notes) {
		return truncateToWidth(this.font, notes.split("\\R", -1)[0], Math.max(1, panelWidth(PANEL_WIDTH) - 12));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}
}
