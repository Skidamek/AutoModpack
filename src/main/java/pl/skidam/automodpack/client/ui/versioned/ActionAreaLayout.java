package pl.skidam.automodpack.client.ui.versioned;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure geometry for the action areas shared by the custom screens. */
public final class ActionAreaLayout {
	public static final int BUTTON_HEIGHT = 20;
	public static final int GAP = 8;
	public static final int MIN_BUTTON_WIDTH = 88;
	public static final int SINGLE_BUTTON_WIDTH = 200;
	public static final int MULTI_BUTTON_WIDTH = 150;
	public static final int COMPACT_BUTTON_WIDTH = 120;

	private ActionAreaLayout() {}

	public enum RowKind {
		AUXILIARY,
		NAVIGATION,
		FOOTER
	}

	public enum Role {
		SECONDARY,
		OPTIONAL,
		PRIMARY,
		NAVIGATION
	}

	public record Action(String id, int preferredWidth, int minimumWidth, Role role) {
		public Action {
			Objects.requireNonNull(id, "action id");
			Objects.requireNonNull(role, "action role");
			if (id.isBlank()) throw new IllegalArgumentException("action id must not be blank");
			if (preferredWidth < 1) throw new IllegalArgumentException("preferred width must be positive");
			if (minimumWidth < 1) throw new IllegalArgumentException("minimum width must be positive");
		}
	}

	public record Row(RowKind kind, List<Action> actions) {
		public Row {
			Objects.requireNonNull(kind, "row kind");
			actions = List.copyOf(actions);
		}
	}

	public record Placement(String id, int x, int y, int width, int height, RowKind rowKind, Role role) {}

	public record Layout(List<Placement> placements, int top, int bottom) {
		public Layout {
			placements = List.copyOf(placements);
		}
	}

	public static int preferredWidth(int actionCount) {
		return switch (actionCount) {
			case 0, 1 -> SINGLE_BUTTON_WIDTH;
			case 2, 3 -> MULTI_BUTTON_WIDTH;
			default -> COMPACT_BUTTON_WIDTH;
		};
	}

	/** Lays rows out from top to bottom and stacks a row when its labels cannot fit safely. */
	public static Layout fromTop(int left, int top, int width, int rowGap, List<Row> rows) {
		int safeWidth = Math.max(1, width);
		int safeGap = Math.max(0, rowGap);
		List<Placement> placements = new ArrayList<>();
		int cursor = top;
		boolean firstRow = true;
		for (Row row : rows) {
			if (row.actions().isEmpty()) continue;
			if (!firstRow) cursor += safeGap;
			firstRow = false;
			RowLayout rowLayout = layoutRow(left, cursor, safeWidth, safeGap, row);
			placements.addAll(rowLayout.placements());
			cursor += rowLayout.height();
		}
		return new Layout(placements, top, cursor);
	}

	/** Lays rows out above a fixed bottom edge, preserving the supplied top-to-bottom row order. */
	public static Layout fromBottom(int left, int bottom, int width, int rowGap, List<Row> rows) {
		Layout unanchored = fromTop(left, 0, width, rowGap, rows);
		int shift = bottom - unanchored.bottom();
		List<Placement> placements = new ArrayList<>(unanchored.placements().size());
		for (Placement placement : unanchored.placements()) {
			placements.add(new Placement(placement.id(), placement.x(), placement.y() + shift, placement.width(), placement.height(), placement.rowKind(), placement.role()));
		}
		return new Layout(placements, unanchored.top() + shift, bottom);
	}

	private static RowLayout layoutRow(int left, int top, int width, int rowGap, Row row) {
		List<Action> actions = row.actions();
		int count = actions.size();
		int available = Math.max(1, width - rowGap * (count - 1));
		int preferredTotal = actions.stream().mapToInt(Action::preferredWidth).sum();
		int minimumTotal = actions.stream().mapToInt(action -> Math.min(width, action.minimumWidth())).sum();
		if (preferredTotal <= available || minimumTotal <= available) {
			int[] widths = actions.stream().mapToInt(Action::preferredWidth).toArray();
			if (preferredTotal > available) shrink(widths, actions, available);
			int groupWidth = 0;
			for (int buttonWidth : widths) groupWidth += buttonWidth;
			groupWidth += rowGap * (count - 1);
			int x = left + (width - groupWidth) / 2;
			List<Placement> placements = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				Action action = actions.get(index);
				int buttonWidth = widths[index];
				placements.add(new Placement(action.id(), x, top, buttonWidth, BUTTON_HEIGHT, row.kind(), action.role()));
				x += buttonWidth + rowGap;
			}
			return new RowLayout(placements, BUTTON_HEIGHT);
		}

		List<Placement> placements = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			Action action = actions.get(index);
			int buttonWidth = Math.min(width, Math.max(action.minimumWidth(), action.preferredWidth()));
			int x = left + (width - buttonWidth) / 2;
			placements.add(new Placement(action.id(), x, top + index * (BUTTON_HEIGHT + rowGap), buttonWidth, BUTTON_HEIGHT, row.kind(), action.role()));
		}
		return new RowLayout(placements, count * BUTTON_HEIGHT + (count - 1) * rowGap);
	}

	private static void shrink(int[] widths, List<Action> actions, int available) {
		int total = 0;
		for (int width : widths) total += width;
		while (total > available) {
			boolean reduced = false;
			for (int index = 0; index < widths.length && total > available; index++) {
				int minimum = Math.min(available, actions.get(index).minimumWidth());
				if (widths[index] > minimum) {
					widths[index]--;
					total--;
					reduced = true;
				}
			}
			if (!reduced) break;
		}
	}

	private record RowLayout(List<Placement> placements, int height) {}
}
