package pl.skidam.automodpack.client.ui.versioned;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure geometry for the action areas shared by the custom screens. */
public final class ActionAreaLayout {
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
	 * Lays rows out from top to bottom. Every row fills the whole rail: buttons split the rail
	 * evenly through a 4px seam and the outer edges repeat on every row, like the vanilla pause
	 * menu. A lone button spans the full rail instead of floating centered.
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
	 * Splits one rail evenly among the row's buttons with a 4px seam between neighbors. Widths stay
	 * equal — vanilla pairs are 98+98, never label-grown — so every row of a screen shares exact
	 * left/right rails no matter how long its labels are.
	 */
	private static List<Placement> layoutRow(int left, int top, int width, Row row) {
		List<Action> actions = row.actions();
		int count = Math.max(1, actions.size());
		int totalSeams = SEAM * (count - 1);
		int buttonWidth = Math.max(MIN_BUTTON_WIDTH, (width - totalSeams) / count);
		List<Placement> placements = new ArrayList<>(actions.size());
		int x = left;
		for (Action action : actions) {
			placements.add(new Placement(action.id(), x, top, buttonWidth, BUTTON_HEIGHT, row.kind(), action.role()));
			x += buttonWidth + SEAM;
		}
		return placements;
	}
}
