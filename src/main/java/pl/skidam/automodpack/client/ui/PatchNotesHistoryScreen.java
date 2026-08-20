package pl.skidam.automodpack.client.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;

public final class PatchNotesHistoryScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int CONTENT_PADDING = 16;
	private static final int LINE_HEIGHT = 12;
	private final Screen parent;
	private final List<DisplayEntry> history;
	private final String modpackName;
	private final Runnable closedCallback;
	private List<String> displayLines;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public PatchNotesHistoryScreen(Screen parent, List<GenerationPatchNoteHistory.Entry> history, String modpackName) {
		this(parent, history, modpackName, () -> {});
	}

	public PatchNotesHistoryScreen(Screen parent, List<GenerationPatchNoteHistory.Entry> history, String modpackName, Runnable closedCallback) {
		this(parent, displayEntries(history), modpackName, closedCallback, true);
	}

	static PatchNotesHistoryScreen fromIndex(Screen parent, GenerationHistoryIndex index, String modpackName) {
		return new PatchNotesHistoryScreen(parent, index.entries().stream().map(entry -> new DisplayEntry(entry.createdAt(), entry.patchNotes())).collect(Collectors.toList()), modpackName, () -> {}, true);
	}

	private PatchNotesHistoryScreen(Screen parent, List<DisplayEntry> history, String modpackName, Runnable closedCallback, boolean displayEntries) {
		super(VersionedText.translatable("automodpack.patchNotes.title"));
		this.parent = parent;
		this.history = List.copyOf(history);
		this.modpackName = modpackName == null ? "" : modpackName;
		this.closedCallback = closedCallback == null ? () -> {} : closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		this.displayLines = buildDisplayLines();
		boolean hasPagination = pageCount() > 1;
		List<ActionRow> rows = new ArrayList<>();
		if (hasPagination) {
			rows.add(actionRow(ActionAreaLayout.RowKind.NAVIGATION,
					navigationAction(VersionedText.translatable("automodpack.ui.previous"), button -> changePage(-1)),
					disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount())),
					navigationAction(VersionedText.translatable("automodpack.ui.next"), button -> changePage(1))));
		}
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> back())));
		List<Button> buttons = addActionArea(310, this.height - 28, rows.toArray(ActionRow[]::new));
		if (hasPagination) {
			this.previousButton = buttons.get(0);
			this.nextButton = buttons.get(2);
		} else {
			this.previousButton = null;
			this.nextButton = null;
		}
		updateNavigation();
	}

	private int pageCount() {
		int pageSize = pageSize();
		return Math.max(1, (displayLines().size() + pageSize - 1) / pageSize);
	}

	private int pageSize() {
		return Math.max(1, (this.height - 88) / LINE_HEIGHT);
	}

	private List<String> displayLines() {
		return displayLines;
	}

	private List<String> buildDisplayLines() {
		int width = Math.max(1, panelWidth(PANEL_WIDTH) - CONTENT_PADDING * 2);
		List<String> lines = new ArrayList<>();
		for (int index = history.size() - 1; index >= 0; index--) {
			DisplayEntry entry = history.get(index);
			lines.add(truncateToWidth(this.font, VersionedText.translatable("automodpack.patchNotes.entry", UiFormat.formatInstant(entry.createdAt())).getString(), width));
			String notes = entry.patchNotes().isBlank() ? VersionedText.translatable("automodpack.patchNotes.none").getString() : entry.patchNotes();
			lines.addAll(wrapToWidth(this.font, notes, width, Integer.MAX_VALUE));
			if (index > 0) lines.add("");
		}
		if (lines.isEmpty()) lines.add(VersionedText.translatable("automodpack.patchNotes.empty").getString());
		return lines;
	}

	private static List<DisplayEntry> displayEntries(List<GenerationPatchNoteHistory.Entry> history) {
		return history == null ? List.of() : history.stream().map(entry -> new DisplayEntry(entry.createdAt(), entry.patchNotes())).toList();
	}

	private void updateNavigation() {
		page = Math.max(0, Math.min(pageCount() - 1, page));
		if (previousButton != null) previousButton.active = page > 0;
		if (nextButton != null) nextButton.active = page + 1 < pageCount();
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		updateNavigation();
	}

	private void back() {
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int left = panelLeft(PANEL_WIDTH) + CONTENT_PADDING;
		String title = VersionedText.translatable(modpackName.isBlank() ? "automodpack.patchNotes.title" : "automodpack.patchNotes.titleNamed", modpackName).getString();
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH) - CONTENT_PADDING * 2)).withStyle(ChatFormatting.BOLD), left, 16,
				TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.patchNotes.description").withStyle(ChatFormatting.GRAY), left, 31, TextColors.WHITE);
		List<String> lines = displayLines();
		int pageSize = pageSize();
		int start = page * pageSize;
		int end = Math.min(lines.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			String line = lines.get(index);
			String generationPrefix = VersionedText.translatable("automodpack.patchNotes.entry", "").getString().stripTrailing();
			ChatFormatting color = line.startsWith(generationPrefix) ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
			drawTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(color), left, 50 + (index - start) * LINE_HEIGHT, TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}

	private record DisplayEntry(Instant createdAt, String patchNotes) {}
}
