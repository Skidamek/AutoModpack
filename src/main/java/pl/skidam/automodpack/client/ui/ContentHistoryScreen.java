package pl.skidam.automodpack.client.ui;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.update.ClientContentHistory;

public final class ContentHistoryScreen extends VersionedScreen {
	private static final int ROWS_PER_PAGE = 8;

	private final Screen parent;
	private final ClientContentHistory.History history;
	private final String modpackName;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public ContentHistoryScreen(Screen parent, ClientContentHistory.History history, String modpackName) {
		super(VersionedText.literal("ContentHistoryScreen"));
		this.parent = parent;
		this.history = history;
		this.modpackName = modpackName == null ? "" : modpackName;
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
		this.addRenderableWidget(buttonWidget(left + 80, y, 75, 20, VersionedText.literal("Back"), button -> this.minecraft.gui.setScreen(parent)));
	}

	private int pageCount() {
		return Math.max(1, (history.entries().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
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

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = modpackName.isBlank() ? "Content history" : modpackName + " content history";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(title).withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Equal content states are collapsed.").withStyle(ChatFormatting.GRAY), this.width / 2, 30, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Internal server operations are not shown here.").withStyle(ChatFormatting.GRAY), this.width / 2, 43, TextColors.WHITE);

		List<ClientContentHistory.Entry> entries = history.entries();
		int start = page * ROWS_PER_PAGE;
		int end = Math.min(entries.size(), start + ROWS_PER_PAGE);
		for (int index = start; index < end; index++) {
			ClientContentHistory.Entry entry = entries.get(index);
			String current = index == entries.size() - 1 ? "  Current content" : "";
			String groups = entry.selectedGroups().isEmpty() ? "none" : entry.selectedGroups().size() + " groups";
			String line = "State " + (index + 1) + "  " + entry.recordedAt() + "  " + groups + current;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(index == entries.size() - 1 ? ChatFormatting.GREEN : ChatFormatting.WHITE), this.width / 2,
					76 + (index - start) * 18, TextColors.WHITE);
		}
		if (entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("No content history is available.").withStyle(ChatFormatting.GRAY), this.width / 2, 82,
					TextColors.WHITE);
		if (pageCount() > 1)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 42,
					TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		this.minecraft.gui.setScreen(parent);
		return false;
	}
}
