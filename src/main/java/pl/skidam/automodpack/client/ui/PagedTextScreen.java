package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** A vanilla-style, wrapped and paginated viewer for complete technical text. */
public final class PagedTextScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int CONTENT_PADDING = 16;
	private static final int LINE_HEIGHT = 12;

	private final Screen parent;
	private final MutableComponent heading;
	private final MutableComponent description;
	private final List<Line> sourceLines;
	private List<Line> displayLines = List.of();
	private Button previousButton;
	private Button nextButton;
	private int page;

	public PagedTextScreen(Screen parent, MutableComponent heading, MutableComponent description, List<Line> lines) {
		super(heading);
		this.parent = Objects.requireNonNull(parent);
		this.heading = Objects.requireNonNull(heading);
		this.description = Objects.requireNonNull(description);
		this.sourceLines = List.copyOf(lines);
	}

	@Override
	protected void init() {
		super.init();
		displayLines = wrapLines();
		int y = this.height - 28;
		boolean hasPagination = pageCount() > 1;
		int buttonWidth = actionButtonWidth(310, 3);
		this.previousButton = buttonWidget(actionButtonX(310, 3, 0), y, buttonWidth, 20, VersionedText.translatable("automodpack.ui.previous"), button -> changePage(-1));
		this.nextButton = buttonWidget(actionButtonX(310, 3, 2), y, buttonWidth, 20, VersionedText.translatable("automodpack.ui.next"), button -> changePage(1));
		if (hasPagination) {
			this.addRenderableWidget(previousButton);
			this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 1), y, buttonWidth, 20, VersionedText.translatable("automodpack.back"), button -> back()));
			this.addRenderableWidget(nextButton);
		} else {
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(310, 3, 1, 0), y, buttonWidth, 20, VersionedText.translatable("automodpack.back"), button -> back()));
		}
		updateNavigation();
	}

	private List<Line> wrapLines() {
		int width = Math.max(1, panelWidth(PANEL_WIDTH) - CONTENT_PADDING * 2);
		List<Line> wrapped = new ArrayList<>();
		for (Line line : sourceLines) {
			if (line.text().isBlank()) {
				wrapped.add(line);
				continue;
			}
			for (String text : wrapToWidth(this.font, line.text(), width, Integer.MAX_VALUE)) wrapped.add(new Line(text, line.color()));
		}
		return wrapped.isEmpty() ? List.of(new Line(VersionedText.translatable("automodpack.history.details.empty").getString(), ChatFormatting.GRAY)) : List.copyOf(wrapped);
	}

	private int pageCount() {
		return Math.max(1, (displayLines.size() + pageSize() - 1) / pageSize());
	}

	private int pageSize() {
		return Math.max(1, (this.height - 88) / LINE_HEIGHT);
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
		int width = panelWidth(PANEL_WIDTH) - CONTENT_PADDING * 2;
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, heading.getString(), width)).withStyle(ChatFormatting.BOLD), left, 16, TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, description.getString(), width)).withStyle(ChatFormatting.GRAY), left, 31, TextColors.WHITE);
		int start = page * pageSize();
		int end = Math.min(displayLines.size(), start + pageSize());
		for (int index = start; index < end; index++) {
			Line line = displayLines.get(index);
			drawTextWithShadow(matrices, this.font, VersionedText.literal(line.text()).withStyle(line.color()), left, 50 + (index - start) * LINE_HEIGHT, TextColors.WHITE);
		}
		if (pageCount() > 1)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.ui.page", page + 1, pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 42,
					TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}

	public record Line(String text, ChatFormatting color) {
		public Line {
			text = Objects.requireNonNull(text);
			color = Objects.requireNonNull(color);
		}
	}
}
