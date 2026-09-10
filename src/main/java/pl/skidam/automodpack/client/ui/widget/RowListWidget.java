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
public final class RowListWidget extends ChromelessList<RowListWidget.RowEntry> implements RowViewport {
	public static final int LINE_STEP = 10;
	private static final int TEXT_MARGIN = 6;
	/** The hovered-row wash reads as "you can click this"; the selected wash is the stronger one that stays. */
	private static final int HOVER_COLOR = 0x40FFFFFF;
	private static final int SELECTED_COLOR = 0x60FFFFFF;
	private final IntConsumer rowPicked;

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

	public RowListWidget(Minecraft client, int width, int screenHeight, int contentWidth, int left, int top, int bottom, int rowHeight, List<Row> rows, IntConsumer rowPicked) {
		super(client, width, screenHeight, left, top, bottom, contentWidth, rowHeight);
		this.rowPicked = Objects.requireNonNull(rowPicked, "row pick");
		for (Row row : Objects.requireNonNull(rows, "rows")) this.addEntry(new RowEntry(row));
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
			this.setHeight(rowHeight());
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
			int textY = y + Math.max(0, (rowHeight() - lines * LINE_STEP) / 2) + 1;
			// Washes carry the row state: the selected row stays washed, a hovered row washes while the pointer is on it.
			if (getSelected() == this) matrices.fill(x, y, x + entryWidth, y + rowHeight(), SELECTED_COLOR);
			else if (hovered) matrices.fill(x, y, x + entryWidth, y + rowHeight(), HOVER_COLOR);
			for (MutableComponent line : row.lines()) {
				MutableComponent drawn = line;
				if (minecraft.font.width(line) > lineWidth) drawn = VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, line.getString(), lineWidth)).withStyle(line.getStyle());
				VersionedScreen.drawTextWithShadow(matrices, minecraft.font, drawn, x + Math.max(0, (entryWidth - minecraft.font.width(drawn)) / 2), textY, TextColors.WHITE);
				textY += LINE_STEP;
			}
			if (hovered && row.tooltip() != null) VersionedScreen.showComponentTooltip(row.tooltip(), mouseX, mouseY);
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

	@Override
	public RowView rowView(int index) {
		return new RowView(this.children().get(index).row().text(), true, null, false);
	}

	@Override
	protected void pinEntry(RowEntry entry, int index) {
		entry.layoutEntry(getRowLeft(), getRowTop(index), getRowWidth());
	}
}
