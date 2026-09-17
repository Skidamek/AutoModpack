package pl.skidam.automodpack.client.ui.widget;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

import net.minecraft.client.Minecraft;
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

/** One scrollable vanilla list of pre-styled text rows; screens own the content, this owns scrolling, hit-testing and row picks. */
public final class RowListWidget extends ChromelessList<RowListWidget.RowEntry> implements RowViewport {
	public static final int LINE_STEP = 10;
	private static final int TEXT_MARGIN = 6;
	/** Left-side space a checkbox row reserves for the box, so screen-side pre-wrapping clears it. */
	public static final int CHECKBOX_RESERVE = CheckboxWidget.BOX_SIZE + CheckboxWidget.TEXT_SPACING;
	/** The hovered-row wash reads as "you can click this"; the selected wash is the stronger one that stays. */
	private static final int HOVER_COLOR = 0x40FFFFFF;
	private static final int SELECTED_COLOR = 0x60FFFFFF;
	private final IntConsumer rowPicked;

	/** One row: pre-wrapped, pre-styled lines plus an optional hover tooltip; a checkbox state draws our checkbox and reports it to tooling. */
	public record Row(List<MutableComponent> lines, Component tooltip, CheckboxWidget.State state) {
		public Row {
			lines = List.copyOf(Objects.requireNonNull(lines, "row lines"));
		}

		public Row(List<MutableComponent> lines, Component tooltip) {
			this(lines, tooltip, null);
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

	public final class RowEntry extends ChromelessList.Row<RowEntry> {
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

		@Override
		protected void versionedRender(VersionedMatrices matrices, int x, int y, int width, int mouseX, int mouseY, boolean hovered, float tickDelta) {
			boolean checkboxRow = row.state() != null;
			int lineWidth = Math.max(1, width - TEXT_MARGIN * 2 - (checkboxRow ? CHECKBOX_RESERVE : 0));
			int lines = row.lines().size();
			// A checkbox row mirrors CheckboxWidget's own geometry from the row's left edge, so every checkbox
			// on the screen starts on the same x; plain rows keep their centered text.
			int textY = y + Math.max(0, (rowHeight() - lines * LINE_STEP) / 2) + 1;
			// Washes carry the row state: the selected row stays washed, a hovered row washes while the pointer is on it.
			if (getSelected() == this) matrices.fill(x, y, x + width, y + rowHeight(), SELECTED_COLOR);
			else if (hovered) matrices.fill(x, y, x + width, y + rowHeight(), HOVER_COLOR);
			if (checkboxRow) CheckboxWidget.drawBox(matrices, x, y + Math.max(0, (rowHeight() - CheckboxWidget.BOX_SIZE) / 2), row.state());
			for (MutableComponent line : row.lines()) {
				MutableComponent drawn = line;
				if (minecraft.font.width(line) > lineWidth) drawn = VersionedText.literal(VersionedScreen.truncateToWidth(minecraft.font, line.getString(), lineWidth)).withStyle(line.getStyle());
				int lineX = checkboxRow ? x + CheckboxWidget.BOX_SIZE + CheckboxWidget.TEXT_SPACING : x + Math.max(0, (width - TEXT_MARGIN * 2 - minecraft.font.width(drawn)) / 2);
				VersionedScreen.drawTextWithShadow(matrices, minecraft.font, drawn, lineX, textY, TextColors.WHITE);
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
		Row row = this.children().get(index).row();
		return new RowView(row.text(), true, row.state() == null ? null : row.state() == CheckboxWidget.State.CHECKED, row.state() == CheckboxWidget.State.PARTIAL);
	}

	@Override
	protected void pinEntry(RowEntry entry, int index) {
		entry.layoutEntry(getRowLeft(), getRowTop(index), getRowWidth());
	}
}
