package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/**
 * A category header over its group rows: the tri-state box telling the truth about the children
 * (all in, some in, none in), the category's bold state-colored label, and the selected-of-total
 * count right-aligned. Clicking still includes/excludes the whole category; the resolution owns the state.
 */
public final class CategoryHeaderRow extends CheckboxWidget {
	private final Font font;
	private final MutableComponent counterLine;

	public CategoryHeaderRow(Font font, int x, int y, int maxWidth, Component message, State state, String counter, Runnable onToggle) {
		super(font, x, y, maxWidth, message, state, selected -> onToggle.run(), font.width(counter));
		this.font = font;
		this.counterLine = VersionedText.literal(counter).withStyle(ChatFormatting.GRAY);
	}

	@Override
	protected void versionedRender(VersionedMatrices matrices) {
		super.versionedRender(matrices);
		VersionedScreen.drawTextWithShadow(matrices, font, counterLine, left() + getWidth() - font.width(counterLine), top() + BOX_SIZE / 2 - font.lineHeight / 2, TextColors.WHITE);
	}
}
