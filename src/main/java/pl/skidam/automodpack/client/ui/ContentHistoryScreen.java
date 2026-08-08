package pl.skidam.automodpack.client.ui;

import java.util.List;

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
	private static final int ENTRY_HEIGHT = 44;

	private final Screen parent;
	private final List<GenerationHistorySummary.Entry> entries;
	private final List<GenerationPatchNoteHistory.Entry> patchNotesHistory;
	private final String modpackName;
	private final Runnable closedCallback;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public ContentHistoryScreen(Screen parent, List<GenerationRecord> history, String modpackName, List<GenerationPatchNoteHistory.Entry> patchNotesHistory,
			Runnable closedCallback) {
		super(VersionedText.literal("ContentHistoryScreen"));
		this.parent = parent;
		this.entries = GenerationHistorySummary.summarize(history, patchNotesHistory);
		this.patchNotesHistory = List.copyOf(patchNotesHistory);
		this.modpackName = modpackName == null ? "" : modpackName;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		int y = this.height - 28;
		int actionWidth = actionButtonWidth(310, 3);
		boolean hasNotes = GenerationPatchNoteHistory.containsNotes(patchNotesHistory);
		boolean hasPagination = pageCount() > 1;
		int navigationY = hasNotes && hasPagination ? y - 26 : y;
		this.previousButton = buttonWidget(hasNotes ? centeredActionButtonX(310, 3, 2, 0) : actionButtonX(310, 3, 1), navigationY, actionWidth, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(hasNotes ? centeredActionButtonX(310, 3, 2, 1) : actionButtonX(310, 3, 2), navigationY, actionWidth, 20, VersionedText.literal("Next >"), button -> changePage(1));
		updateNavigation();
		if (hasPagination) {
			this.addRenderableWidget(this.previousButton);
			this.addRenderableWidget(this.nextButton);
		}
		int bottomButtonCount = hasNotes ? 2 : hasPagination ? 3 : 1;
		this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 3, bottomButtonCount, 0), y, actionWidth, 20, VersionedText.translatable("automodpack.back"), button -> back()));
		if (hasNotes)
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 3, bottomButtonCount, 1), y, actionWidth, 20,
					VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes()));
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (entries.size() + pageSize - 1) / pageSize);
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 50 - ENTRY_TOP) / ENTRY_HEIGHT);
	}

	private void updateNavigation() {
		page = Math.max(0, Math.min(pageCount() - 1, page));
		previousButton.active = page > 0;
		nextButton.active = page + 1 < pageCount();
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		updateNavigation();
	}

	private void back() {
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, patchNotesHistory, modpackName));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = modpackName.isBlank() ? "Content history" : modpackName + " content history";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 10, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Downloaded generations and their patch notes.").withStyle(ChatFormatting.GRAY), this.width / 2, 25, TextColors.WHITE);
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(entries.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			GenerationHistorySummary.Entry entry = entries.get(index);
			int y = ENTRY_TOP + (index - start) * ENTRY_HEIGHT;
			boolean current = index == entries.size() - 1;
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.literal(truncateToWidth(this.font, "Generation " + entry.number() + "  " + entry.createdAt() + (current ? "  Latest available" : ""), this.width - 20))
							.withStyle(current ? ChatFormatting.GREEN : ChatFormatting.WHITE),
					this.width / 2, y, TextColors.WHITE);
			String notes = entry.patchNotes().isBlank() ? "No patch notes" : firstLine(entry.patchNotes());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Patch notes: " + notes, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, y + 13,
					TextColors.WHITE);
			GenerationDiff.Summary diff = entry.diff().summary();
			String diffText = "Diff vs previous: +" + diff.addedFiles() + " added, ~" + diff.modifiedFiles() + " changed, -" + diff.removedFiles() + " removed, metadata-only "
					+ diff.metadataOnlyFiles() + ", other metadata " + diff.metadataChanges();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, diffText, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y + 26,
					TextColors.WHITE);
		}
		if (entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("No content history is available.").withStyle(ChatFormatting.GRAY), this.width / 2, 82,
					TextColors.WHITE);
		if (pageCount() > 1)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 42,
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
