package pl.skidam.automodpack.client.ui;

import java.util.List;
import java.time.temporal.ChronoUnit;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.GenerationDiff;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistorySummary;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

public final class ContentHistoryScreen extends VersionedScreen {
	private static final int ENTRY_TOP = 44;
	private static final int ENTRY_HEIGHT = 50;
	private static final int PANEL_WIDTH = 600;

	private final Screen parent;
	private final List<GenerationRecord> history;
	private final List<GenerationHistorySummary.Entry> entries;
	private final List<GenerationPatchNoteHistory.Entry> patchNotesHistory;
	private final String modpackName;
	private final Runnable closedCallback;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public ContentHistoryScreen(Screen parent, List<GenerationRecord> history, String modpackName, List<GenerationPatchNoteHistory.Entry> patchNotesHistory,
			Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.history.title"));
		this.parent = parent;
		this.history = List.copyOf(history);
		this.entries = GenerationHistorySummary.summarize(this.history, patchNotesHistory);
		this.patchNotesHistory = List.copyOf(patchNotesHistory);
		this.modpackName = modpackName == null ? "" : modpackName;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		int y = this.height - 28;
		boolean hasNotes = GenerationPatchNoteHistory.containsNotes(patchNotesHistory);
		boolean hasFiles = !history.isEmpty();
		boolean hasPagination = pageCount() > 1;
		int navigationY = hasPagination ? y - 24 : -1;
		int actionWidth = actionButtonWidth(310, 3);
		this.previousButton = buttonWidget(actionButtonX(310, 3, 0), navigationY, actionWidth, 20, VersionedText.translatable("automodpack.ui.previous"), button -> changePage(-1));
		this.nextButton = buttonWidget(actionButtonX(310, 3, 2), navigationY, actionWidth, 20, VersionedText.translatable("automodpack.ui.next"), button -> changePage(1));
		updateNavigation();
		if (hasPagination) {
			this.addRenderableWidget(this.previousButton);
			Button pageLabel = buttonWidget(actionButtonX(310, 3, 1), navigationY, actionWidth, 20,
					VersionedText.translatable("automodpack.ui.page", page + 1, pageCount()), button -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			this.addRenderableWidget(this.nextButton);
		}
		int bottomButtonCount = 1 + (hasNotes ? 1 : 0) + (hasFiles ? 1 : 0);
		int bottomIndex = 0;
		this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 3, bottomButtonCount, bottomIndex++), y, actionWidth, 20, VersionedText.translatable("automodpack.back"), button -> back()));
		if (hasNotes)
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 3, bottomButtonCount, bottomIndex++), y, actionWidth, 20,
					VersionedText.translatable("automodpack.patchNotes.button"), button -> openPatchNotes()));
		if (hasFiles)
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 3, bottomButtonCount, bottomIndex), y, actionWidth, 20,
					VersionedText.translatable("automodpack.management.files"), button -> openFiles()));

		int start = page * rowsPerPage();
		int end = Math.min(entries.size(), start + rowsPerPage());
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		for (int index = start; index < end; index++) {
			int entryIndex = index;
			GenerationHistorySummary.Entry entry = entries.get(index);
			boolean current = index == entries.size() - 1;
			String label = VersionedText.translatable("automodpack.history.generation", entry.number(), entry.createdAt().truncatedTo(ChronoUnit.SECONDS)).getString()
					+ (current ? "  " + VersionedText.translatable("automodpack.history.latest").getString() : "");
			this.addRenderableWidget(buttonWidget(x, ENTRY_TOP + (index - start) * ENTRY_HEIGHT, rowWidth, 20,
					VersionedText.literal(truncateToWidth(this.font, label, rowWidth - 12)).withStyle(current ? ChatFormatting.GREEN : ChatFormatting.WHITE), button -> openGeneration(entryIndex)));
		}
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (entries.size() + pageSize - 1) / pageSize);
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 76 - ENTRY_TOP) / ENTRY_HEIGHT);
	}

	private void updateNavigation() {
		page = Math.max(0, Math.min(pageCount() - 1, page));
		previousButton.active = page > 0;
		nextButton.active = page + 1 < pageCount();
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

	private void back() {
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, patchNotesHistory, modpackName));
	}

	private void openFiles() {
		GenerationRecord generation = history.get(history.size() - 1);
		ScreenImpl.setScreen(new PagedTextScreen(this,
				VersionedText.translatable("automodpack.files.title", modpackName),
				VersionedText.translatable("automodpack.files.description"), GenerationCatalogueLines.files(generation)));
	}

	private void openGeneration(int index) {
		GenerationRecord previous = index == 0 ? null : history.get(index - 1);
		GenerationRecord current = history.get(index);
		ScreenImpl.setScreen(new PagedTextScreen(this,
				VersionedText.translatable("automodpack.history.details.title", entries.get(index).number()),
				VersionedText.translatable("automodpack.history.details.description"), GenerationCatalogueLines.diff(previous, current)));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = VersionedText.translatable(modpackName.isBlank() ? "automodpack.history.title" : "automodpack.history.titleNamed", modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 10, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.description").withStyle(ChatFormatting.GRAY), this.width / 2, 25, TextColors.WHITE);
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(entries.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			GenerationHistorySummary.Entry entry = entries.get(index);
			int y = ENTRY_TOP + (index - start) * ENTRY_HEIGHT;
			String notes = entry.patchNotes().isBlank() ? VersionedText.translatable("automodpack.history.noPatchNotes").getString() : firstLine(entry.patchNotes());
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.history.patchNotes", notes).getString(), this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2,
					y + 23,
					TextColors.WHITE);
			GenerationDiff.Summary diff = entry.diff().summary();
			String diffText = VersionedText.translatable("automodpack.history.diff", diff.addedFiles(), diff.modifiedFiles(), diff.removedFiles(), diff.metadataOnlyFiles(), diff.metadataChanges()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, diffText, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y + 36,
					TextColors.WHITE);
		}
		if (entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 82,
					TextColors.WHITE);
	}

	private String firstLine(String notes) {
		String line = notes.split("\\R", -1)[0];
		return truncateToWidth(this.font, line, Math.max(1, this.width - 20));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}
}
