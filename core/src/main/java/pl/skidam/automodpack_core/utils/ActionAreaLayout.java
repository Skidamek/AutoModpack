package pl.skidam.automodpack_core.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure geometry for the action areas shared by the custom screens. */
public final class ActionAreaLayout {
	public static final int FOOTER_RAIL = 310;
	public static final int LONE_BUTTON = 200;
	public static final int BUTTON_HEIGHT = 20;
	/** The vanilla seam between paired buttons inside one rail (pause menu 98+98 in 200px). */
	public static final int SEAM = 4;
	public static final int GAP = 8;
	public static final int MIN_BUTTON_WIDTH = 88;

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

	public record Action(String id, Role role) {
		public Action {
			Objects.requireNonNull(id, "action id");
			Objects.requireNonNull(role, "action role");
			if (id.isBlank()) throw new IllegalArgumentException("action id must not be blank");
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

	/**
	 * Lays rows out from top to bottom. Every visible row fills the rail with an even split through a
	 * 4px seam; leftover pixels go to the last button so the row's right edge equals left + width.
	 */
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
			placements.addAll(layoutRow(left, cursor, safeWidth, row));
			cursor += BUTTON_HEIGHT;
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

	/**
	 * Splits one rail among the row's buttons with a 4px seam between neighbors. Widths stay equal
	 * except the last button absorbs the remainder so the right edge of the rail is exact. A lone
	 * row fills the rail, including a lone FOOTER action.
	 */
	private static List<Placement> layoutRow(int left, int top, int width, Row row) {
		List<Action> actions = row.actions();
		int count = actions.size();
		int totalSeams = SEAM * (count - 1);
		int base = (width - totalSeams) / count;
		int extra = (width - totalSeams) % count;
		List<Placement> placements = new ArrayList<>(count);
		int x = left;
		for (int i = 0; i < count; i++) {
			Action action = actions.get(i);
			int buttonWidth = i == count - 1 ? base + extra : base;
			placements.add(new Placement(action.id(), x, top, buttonWidth, BUTTON_HEIGHT, row.kind(), action.role()));
			x += buttonWidth + SEAM;
		}
		return placements;
	}
}
