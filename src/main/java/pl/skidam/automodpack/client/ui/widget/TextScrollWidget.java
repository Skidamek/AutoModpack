package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/*? if >= 1.21.9 {*/
import net.minecraft.client.input.MouseButtonEvent;
/*?}*/

/*? if >=26.1 {*/
import net.minecraft.client.gui.GuiGraphicsExtractor;
/*?} elif >=1.20 {*/
/*import net.minecraft.client.gui.GuiGraphics;
*//*?} else {*/
/*import com.mojang.blaze3d.vertex.PoseStack;
*//*?}*/

/** One ObjectSelectionList of already-wrapped text lines for a pinned-title / pinned-footer dialog body. */
public final class TextScrollWidget extends ObjectSelectionList<TextScrollWidget.Entry> implements RowViewport {
	public static final int ROW_HEIGHT = 9;
	private final int contentWidth;
	private final boolean center;

	public TextScrollWidget(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<? extends Component> lines, boolean center) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top, bottom, ROW_HEIGHT);
		*//*?} else {*/
		super(client, width, Math.max(ROW_HEIGHT, bottom - top), top, ROW_HEIGHT);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.center = center;
		this.centerListVertically = false;
		/*? if <1.21.1 {*/
		/*this.setRenderBackground(false);
		this.setRenderTopAndBottom(false);
		*//*?}*/
		/*? if <1.20.4 {*/
		/*this.setRenderSelection(false);
		*//*?}*/
		for (Component line : Objects.requireNonNull(lines, "lines")) this.addEntry(new Entry(mutableLine(line)));
	}

	private static MutableComponent mutableLine(Component line) {
		if (line == null) return VersionedText.literal("");
		if (line instanceof MutableComponent mutable) return mutable;
		return VersionedText.literal(line.getString());
	}

	protected int getScrollbarPosition() {
		return Math.min(this.width - 6, this.width / 2 + this.getRowWidth() / 2 + 6);
	}

	@Override
	public void revealRow(int index) {
		Entry entry = this.children().get(index);
		/*? if >=1.21.9 {*/
		this.scrollToEntry(entry);
		/*?} else {*/
		/*this.ensureVisible(entry);
		*//*?}*/
	}

	@Override
	public RowView rowView(int index) {
		// Text rows are never interactive: enabled stays false, so click-style selectors cannot land on a body line.
		return new RowView(this.children().get(index).line().getString(), false, null);
	}

	@Override
	public int rowCount() {
		return this.children().size();
	}

	@Override
	public int rowLeft() {
		return this.getRowLeft();
	}

	@Override
	public int rowTop(int index) {
		return this.getRowTop(index);
	}

	@Override
	public int rowWidth() {
		return this.contentWidth;
	}

	@Override
	public int rowHeight() {
		return ROW_HEIGHT;
	}

	@Override
	public int getRowWidth() {
		return this.contentWidth;
	}

	/*? if >=26.1 {*/
	@Override
	protected void extractListBackground(GuiGraphicsExtractor guiGraphics) {}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor guiGraphics) {}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	/*?} elif >=1.21.10 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {}

	@Override
	protected boolean entriesCanBeSelected() {
		return false;
	}
	*//*?} elif >=1.21.1 {*/
	/*@Override
	protected void renderListBackground(GuiGraphics guiGraphics) {}

	@Override
	protected void renderListSeparators(GuiGraphics guiGraphics) {}

	@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?} elif >=1.20.4 {*/
	/*@Override
	protected void renderSelection(GuiGraphics guiGraphics, int y, int entryWidth, int entryHeight, int outlineColor, int innerColor) {}
	*//*?}*/

	public final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final MutableComponent line;

		private Entry(MutableComponent line) {
			this.line = line;
		}

		Component line() {
			return line;
		}

		@Override
		public @NotNull Component getNarration() {
			return line;
		}

		/*? if >= 26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth());
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), TextScrollWidget.this.getRowWidth());
		}
		*//*?} else {*/
		/*@Override
		/^? if <1.20 {^/
		/^public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices versionedMatrices = new VersionedMatrices();
		^//^?} else {^/
		public void render(GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			VersionedMatrices versionedMatrices = new VersionedMatrices(guiGraphics);
		/^?}^/
			versionedRender(versionedMatrices, x, y, entryWidth);
		}
		*//*?}*/

		private void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth) {
			if (center) {
				VersionedScreen.drawCenteredTextWithShadow(matrices, minecraft.font, line, TextScrollWidget.this.width / 2, y, TextColors.WHITE);
				return;
			}
			int maxWidth = Math.max(1, entryWidth - 4);
			MutableComponent drawn = line;
			if (minecraft.font.width(line) > maxWidth) drawn = VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, line.getString(), maxWidth));
			VersionedScreen.drawTextWithShadow(matrices, minecraft.font, drawn, x + 2, y, TextColors.WHITE);
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			return false;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			return false;
		}
		*//*?}*/
	}
}
