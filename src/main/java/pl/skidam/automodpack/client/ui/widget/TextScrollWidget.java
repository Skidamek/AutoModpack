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
public final class TextScrollWidget extends ChromelessList<TextScrollWidget.Entry> implements RowViewport {
	private final boolean center;

	public TextScrollWidget(Minecraft client, int width, int height, int contentWidth, int top, int bottom, List<? extends Component> lines, boolean center) {
		super(client, width, height, 0, top, bottom, contentWidth, VersionedScreen.LINE_HEIGHT);
		this.center = center;
		for (Component line : Objects.requireNonNull(lines, "lines")) this.addEntry(new Entry(mutableLine(line)));
	}

	private static MutableComponent mutableLine(Component line) {
		if (line == null) return VersionedText.literal("");
		if (line instanceof MutableComponent mutable) return mutable;
		return VersionedText.literal(line.getString());
	}

	@Override
	public RowView rowView(int index) {
		// Text rows are never interactive: enabled stays false, so click-style selectors cannot land on a body line.
		return new RowView(this.children().get(index).line().getString(), false, null, false);
	}

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
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), TextScrollWidget.this.getRowWidth());
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
