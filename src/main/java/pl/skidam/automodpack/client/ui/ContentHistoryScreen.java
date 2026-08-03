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
import pl.skidam.automodpack_core.update.ClientContentHistory;

public final class ContentHistoryScreen extends VersionedScreen {
	private static final int ENTRY_TOP = 58;
	private static final int ENTRY_HEIGHT = 58;

	private final Screen parent;
	private final ClientContentHistory.History history;
	private final String modpackName;
	private final Runnable closedCallback;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public ContentHistoryScreen(Screen parent, ClientContentHistory.History history, String modpackName) {
		this(parent, history, modpackName, () -> {});
	}

	public ContentHistoryScreen(Screen parent, ClientContentHistory.History history, String modpackName, Runnable closedCallback) {
		super(VersionedText.literal("ContentHistoryScreen"));
		this.parent = parent;
		this.history = history;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		int y = this.height - 28;
		int left = this.width / 2 - 155;
		this.previousButton = buttonWidget(left, y, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(left + 240, y, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		updateNavigation();
		this.addRenderableWidget(this.previousButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(buttonWidget(left + 80, y, 75, 20, VersionedText.translatable("automodpack.back"), button -> back()));
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (history.entries().size() + pageSize - 1) / pageSize);
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 56 - ENTRY_TOP) / ENTRY_HEIGHT);
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

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = modpackName.isBlank() ? "Content history" : modpackName + " content history";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(title).withStyle(ChatFormatting.BOLD), this.width / 2, 10, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Equal content states are collapsed.").withStyle(ChatFormatting.GRAY), this.width / 2, 25, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("This history describes content choices, not server operations.").withStyle(ChatFormatting.GRAY), this.width / 2, 38, TextColors.WHITE);

		List<ClientContentHistory.Entry> entries = history.entries();
		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(entries.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			ClientContentHistory.Entry entry = entries.get(index);
			int y = ENTRY_TOP + (index - start) * ENTRY_HEIGHT;
			boolean current = index == entries.size() - 1;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "State " + (index + 1) + "  " + entry.recordedAt() + (current ? "  Current content" : ""), this.width - 20))
					.withStyle(current ? ChatFormatting.GREEN : ChatFormatting.WHITE), this.width / 2, y, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Content state: " + shortDigest(entry.stateDigest()), this.width - 20)).withStyle(ChatFormatting.GRAY),
					this.width / 2, y + 13,
					TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, entry.fileSummary(), this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2, y + 26,
					TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.literal(truncateToWidth(this.font, "Tags: " + join(entry.selectedTags()) + "  Groups: " + join(entry.selectedGroups()), this.width - 20)).withStyle(ChatFormatting.WHITE),
					this.width / 2, y + 39, TextColors.WHITE);
			String notes = entry.patchNotes().isBlank() ? "No patch notes" : firstLine(entry.patchNotes());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Notes: " + notes, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, y + 52,
					TextColors.WHITE);
		}
		if (entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("No content history is available.").withStyle(ChatFormatting.GRAY), this.width / 2, 82,
					TextColors.WHITE);
		if (pageCount() > 1)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 42,
					TextColors.WHITE);
	}

	private String shortDigest(String digest) {
		return truncateToWidth(this.font, digest, Math.max(1, this.width - 20));
	}

	private String join(Iterable<String> values) {
		List<String> result = new ArrayList<>();
		for (String value : values) result.add(value);
		if (result.isEmpty()) return "none";
		String text = String.join(", ", result);
		return truncateToWidth(this.font, text, Math.max(1, this.width - 20));
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
