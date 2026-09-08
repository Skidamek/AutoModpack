package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedScissor;
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

/** One scrollable vanilla list of pre-styled text rows; screens own the content, this owns scrolling, hit-testing and row picks. */
public final class RowListWidget extends ObjectSelectionList<RowListWidget.RowEntry> implements RowViewport {
	public static final int LINE_STEP = 10;
	private static final int TEXT_MARGIN = 6;
	/** The hovered-row wash: soft enough to sit under text, strong enough to read as a control. */
	private static final int HOVER_COLOR = 0x40FFFFFF;
	private final int contentWidth;
	private final int rowHeight;
	private final IntConsumer rowPicked;
	private final TooltipShower tooltipShower;

	/** Shows a component tooltip at the pointer; screens bridge this to their versioned tooltip rendering. */
	public interface TooltipShower {
		void showTooltip(VersionedMatrices matrices, Component tooltip, int mouseX, int mouseY);
	}

	/** One row: pre-wrapped, pre-styled lines plus an optional hover tooltip. */
	public record Row(List<MutableComponent> lines, Component tooltip) {
		public Row {
			lines = List.copyOf(Objects.requireNonNull(lines, "row lines"));
		}

		public Row(List<MutableComponent> lines) {
			this(lines, null);
		}

		public String text() {
			return String.join(" | ", lines.stream().map(Component::getString).toList());
		}
	}

	public RowListWidget(Minecraft client, int width, int height, int contentWidth, int top, int bottom, int rowHeight, List<Row> rows, IntConsumer rowPicked, TooltipShower tooltipShower) {
		/*? if <1.20.3 {*/
		/*super(client, width, height, top, bottom, rowHeight);
		*//*?} else {*/
		super(client, width, Math.max(rowHeight, bottom - top), top, rowHeight);
		/*?}*/
		this.contentWidth = Math.max(1, contentWidth);
		this.rowHeight = rowHeight;
		this.rowPicked = Objects.requireNonNull(rowPicked, "row pick");
		this.tooltipShower = tooltipShower;
		this.centerListVertically = false;
		/*? if <1.20.6 {*/
		/*// Vanilla's list render repaints opaque dirt bands across the whole screen above and below the list;
		// with the screen's text and action rows drawn before the list, those bands erase them (invisible: same dirt as the background).
		this.setRenderTopAndBottom(false);
		*//*?}*/
		for (Row row : Objects.requireNonNull(rows, "rows")) this.addEntry(new RowEntry(row));
	}

	/** Offsets the list window horizontally; the vertical place comes from the constructor, and only the horizontal mover is shared by every version. */
	public void placeAt(int left) {
		/*? if >=1.20.3 {*/
		this.setX(left);
		/*?} else {*/
		/*this.setLeftPos(left);
		*//*?}*/
	}

	@Override
	public void revealRow(int index) {
		RowEntry entry = this.children().get(index);
		/*? if >=1.21.9 {*/
		this.scrollToEntry(entry);
		/*?} else {*/
		/*this.ensureVisible(entry);
		*//*?}*/
		entry.layoutEntry(this.getRowLeft(), this.getRowTop(index), this.getRowWidth());
	}

	/*? if <1.19.4 {*/
	/*@Override
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(matrices, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?}*/
	/*? if >=1.19.4 <1.20.3 {*/
	/*@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		VersionedScissor.enable(minecraft, x0, y0, x1, y1);
		super.render(graphics, mouseX, mouseY, delta);
		VersionedScissor.disable();
	}
	*//*?}*/
	@Override
	public RowView rowView(int index) {
		return new RowView(this.children().get(index).row().text(), true, null, false);
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
		return rowHeight;
	}

	protected int getScrollbarPosition() {
		return Math.min(this.width - 6, this.width / 2 + this.getRowWidth() / 2 + 6);
	}

	@Override
	public int getRowWidth() {
		return this.contentWidth;
	}

	public final class RowEntry extends ObjectSelectionList.Entry<RowEntry> {
		private final Row row;

		private RowEntry(Row row) {
			this.row = Objects.requireNonNull(row, "row");
		}

		private Row row() {
			return row;
		}

		/** Pins the row's hit-test rectangle to its live position, so tooling can click a row that has not rendered yet. */
		private void layoutEntry(int x, int y, int width) {
			/*? if >=1.21.9 {*/
			this.setX(x);
			this.setY(y);
			this.setWidth(width);
			this.setHeight(rowHeight);
			/*?}*/
		}

		@Override
		public @NotNull Component getNarration() {
			return VersionedText.literal(row.text());
		}

		/*? if >= 26.1 {*/
		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getContentX(), this.getContentY(), this.getContentWidth(), mouseX, mouseY, hovered);
		}
		/*?} elif >= 1.21.9 {*/
		/*@Override
		public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			versionedRender(new VersionedMatrices(guiGraphics), this.getX(), this.getY(), RowListWidget.this.getRowWidth(), mouseX, mouseY, hovered);
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
			versionedRender(versionedMatrices, x, y, entryWidth, mouseX, mouseY, hovered);
		}
		*//*?}*/

		private void versionedRender(VersionedMatrices matrices, int x, int y, int entryWidth, int mouseX, int mouseY, boolean hovered) {
			int lineWidth = Math.max(1, entryWidth - TEXT_MARGIN * 2);
			int lines = row.lines().size();
			int textY = y + Math.max(0, (rowHeight - lines * LINE_STEP) / 2) + 1;
			// A hovered row washes over, so rows read as clickable and not as static text.
			if (hovered) matrices.fill(x, y, x + entryWidth, y + rowHeight, HOVER_COLOR);
			for (MutableComponent line : row.lines()) {
				MutableComponent drawn = line;
				if (minecraft.font.width(line) > lineWidth) drawn = VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, line.getString(), lineWidth)).withStyle(line.getStyle());
				VersionedScreen.drawTextWithShadow(matrices, minecraft.font, drawn, x + Math.max(0, (entryWidth - minecraft.font.width(drawn)) / 2), textY, TextColors.WHITE);
				textY += LINE_STEP;
			}
			if (hovered && row.tooltip() != null && tooltipShower != null) tooltipShower.showTooltip(matrices, row.tooltip(), mouseX, mouseY);
		}

		/*? if >= 1.21.9 {*/
		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean bl) {
			activate(this);
			return true;
		}
		/*?} else {*/
		/*@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			activate(this);
			return true;
		}
		*//*?}*/
	}

	private void activate(RowEntry entry) {
		this.setSelected(entry);
		rowPicked.accept(this.children().indexOf(entry));
	}
}
