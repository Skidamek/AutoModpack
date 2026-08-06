package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;

public final class PatchNotesHistoryScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int CONTENT_PADDING = 16;
	private static final int LINE_HEIGHT = 12;
	private final Screen parent;
	private final List<GenerationPatchNoteHistory.Entry> history;
	private final String modpackName;
	private List<String> displayLines;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public PatchNotesHistoryScreen(Screen parent, List<GenerationPatchNoteHistory.Entry> history, String modpackName) {
		super(VersionedText.literal("PatchNotesHistoryScreen"));
		this.parent = parent;
		this.history = history.stream().filter(entry -> !entry.patchNotes().isBlank()).toList();
		this.modpackName = modpackName == null ? "" : modpackName;
	}

	@Override
	protected void init() {
		super.init();
		this.displayLines = buildDisplayLines();
		int y = this.height - 28;
		int left = Math.max(5, (this.width - 310) / 2);
		this.previousButton = buttonWidget(left, y, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(left + 240, y, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		this.addRenderableWidget(this.previousButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(buttonWidget(left + 80, y, 75, 20, VersionedText.translatable("automodpack.back"), button -> back()));
		updateNavigation();
	}

	private int pageCount() {
		int pageSize = Math.max(1, (this.height - 70) / LINE_HEIGHT);
		return Math.max(1, (displayLines().size() + pageSize - 1) / pageSize);
	}

	private int pageSize() {
		return Math.max(1, (this.height - 70) / LINE_HEIGHT);
	}

	private List<String> displayLines() {
		return displayLines;
	}

	private List<String> buildDisplayLines() {
		int width = Math.max(1, panelWidth(PANEL_WIDTH) - CONTENT_PADDING * 2);
		List<String> lines = new ArrayList<>();
		for (int index = 0; index < history.size(); index++) {
			GenerationPatchNoteHistory.Entry entry = history.get(index);
			lines.add(truncateToWidth(this.font, "Generation " + (index + 1) + "  " + entry.createdAt(), width));
			lines.add(truncateToWidth(this.font, "ID: " + entry.generationId(), width));
			lines.addAll(wrapToWidth(this.font, entry.patchNotes(), width, Integer.MAX_VALUE));
			if (index + 1 < history.size()) lines.add("");
		}
		if (lines.isEmpty()) lines.add("No patch notes are available.");
		return lines;
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
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int left = panelLeft(PANEL_WIDTH) + CONTENT_PADDING;
		String title = modpackName.isBlank() ? "Patch-note history" : modpackName + " patch-note history";
		drawTextWithShadow(matrices, this.font, VersionedText.literal(title).withStyle(ChatFormatting.BOLD), left, 16, TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal("All received generation notes").withStyle(ChatFormatting.GRAY), left, 31, TextColors.WHITE);
		List<String> lines = displayLines();
		int pageSize = pageSize();
		int start = page * pageSize;
		int end = Math.min(lines.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			String line = lines.get(index);
			ChatFormatting color = line.startsWith("Generation ") || line.startsWith("ID: ") ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
			drawTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(color), left, 50 + (index - start) * LINE_HEIGHT, TextColors.WHITE);
		}
		if (pageCount() > 1)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 42,
					TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}
}
