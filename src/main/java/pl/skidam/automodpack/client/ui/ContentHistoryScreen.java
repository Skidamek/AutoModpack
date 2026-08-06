package pl.skidam.automodpack.client.ui;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

public final class ContentHistoryScreen extends VersionedScreen {
	private static final int ENTRY_TOP = 44;
	private static final int ENTRY_HEIGHT = 46;

	private final Screen parent;
	private final List<GenerationRecord> history;
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
		this.history = history;
		this.patchNotesHistory = List.copyOf(patchNotesHistory);
		this.modpackName = modpackName == null ? "" : modpackName;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		int y = this.height - 28;
		int left = panelLeft(310);
		int rowWidth = panelWidth(310);
		this.previousButton = buttonWidget(left, y, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(left + rowWidth - 70, y, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		updateNavigation();
		if (pageCount() > 1) {
			this.addRenderableWidget(this.previousButton);
			this.addRenderableWidget(this.nextButton);
		}
		if (GenerationPatchNoteHistory.containsNotes(patchNotesHistory)) {
			this.addRenderableWidget(buttonWidget(this.width / 2 - 76, y, 72, 20, VersionedText.translatable("automodpack.back"), button -> back()));
			this.addRenderableWidget(buttonWidget(this.width / 2 + 4, y, 72, 20, VersionedText.literal("Notes"), button -> openPatchNotes()));
		} else {
			this.addRenderableWidget(buttonWidget(this.width / 2 - 40, y, 80, 20, VersionedText.translatable("automodpack.back"), button -> back()));
		}
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (history.size() + pageSize - 1) / pageSize);
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Previous server versions of this modpack.").withStyle(ChatFormatting.GRAY), this.width / 2, 25, TextColors.WHITE);
		List<GenerationRecord> entries = history;
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(entries.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			GenerationRecord entry = entries.get(index);
			int y = ENTRY_TOP + (index - start) * ENTRY_HEIGHT;
			boolean current = index == entries.size() - 1;
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.literal(truncateToWidth(this.font, "Generation " + (index + 1) + "  " + entry.metadata().createdAt() + (current ? "  Current" : ""), this.width - 20))
							.withStyle(current ? ChatFormatting.GREEN : ChatFormatting.WHITE),
					this.width / 2, y, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Files: " + fileCount(entry)).withStyle(ChatFormatting.GRAY), this.width / 2, y + 12, TextColors.WHITE);
			String notes = entry.metadata().patchNotes().isBlank() ? "No patch notes" : firstLine(entry.metadata().patchNotes());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Notes: " + notes, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, y + 26,
					TextColors.WHITE);
		}
		if (entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("No content history is available.").withStyle(ChatFormatting.GRAY), this.width / 2, 82,
					TextColors.WHITE);
		if (pageCount() > 1)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 42,
					TextColors.WHITE);
	}

	private int fileCount(GenerationRecord record) {
		return record.manifest().groups().values().stream().mapToInt(group -> group.files().size()).sum();
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
