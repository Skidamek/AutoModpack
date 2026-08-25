package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ActionAreaLayoutTest {
	private static final int LEFT = 10;

	@Test
	void threeButtonsOn600PutRemainderOnLastAndMeetRightEdge() {
		ActionAreaLayout.Layout layout = ActionAreaLayout.fromTop(LEFT, 0, 600, ActionAreaLayout.GAP, List.of(row(ActionAreaLayout.RowKind.FOOTER, 3)));

		ActionAreaLayout.Placement last = layout.placements().get(2);
		assertEquals(LEFT + 600, last.x() + last.width());
	}

	@Test
	void threeButtonsOn310PutRemainderTwoOnLastAndMeetRightEdge() {
		ActionAreaLayout.Layout layout = ActionAreaLayout.fromTop(LEFT, 0, 310, ActionAreaLayout.GAP, List.of(row(ActionAreaLayout.RowKind.FOOTER, 3)));

		ActionAreaLayout.Placement first = layout.placements().get(0);
		ActionAreaLayout.Placement second = layout.placements().get(1);
		ActionAreaLayout.Placement last = layout.placements().get(2);
		assertEquals(100, first.width());
		assertEquals(100, second.width());
		assertEquals(102, last.width());
		assertTrue(first.width() >= ActionAreaLayout.MIN_BUTTON_WIDTH);
		assertEquals(LEFT + 310, last.x() + last.width());
	}

	@Test
	void twoButtonsOn310MeetRightEdge() {
		ActionAreaLayout.Layout layout = ActionAreaLayout.fromTop(LEFT, 0, 310, ActionAreaLayout.GAP, List.of(row(ActionAreaLayout.RowKind.FOOTER, 2)));

		ActionAreaLayout.Placement first = layout.placements().get(0);
		ActionAreaLayout.Placement last = layout.placements().get(1);
		assertEquals(153, first.width());
		assertEquals(153, last.width());
		assertTrue(first.width() >= ActionAreaLayout.MIN_BUTTON_WIDTH);
		assertEquals(LEFT + 310, last.x() + last.width());
	}

	@Test
	void loneFooterOn310Is200Centered() {
		ActionAreaLayout.Layout layout = ActionAreaLayout.fromTop(LEFT, 0, 310, ActionAreaLayout.GAP, List.of(row(ActionAreaLayout.RowKind.FOOTER, 1)));

		ActionAreaLayout.Placement placement = layout.placements().get(0);
		assertEquals(ActionAreaLayout.LONE_BUTTON, placement.width());
		assertEquals(LEFT + (310 - ActionAreaLayout.LONE_BUTTON) / 2, placement.x());
	}

	@Test
	void emptyRowIsSkipped() {
		ActionAreaLayout.Layout layout = ActionAreaLayout.fromTop(LEFT, 5, 310, ActionAreaLayout.GAP, List.of(new ActionAreaLayout.Row(ActionAreaLayout.RowKind.FOOTER, List.of())));

		assertEquals(List.of(), layout.placements());
		assertEquals(5, layout.top());
		assertEquals(5, layout.bottom());
	}

	private static ActionAreaLayout.Row row(ActionAreaLayout.RowKind kind, int count) {
		List<ActionAreaLayout.Action> actions = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			actions.add(new ActionAreaLayout.Action("a" + i, ActionAreaLayout.Role.SECONDARY));
		}
		return new ActionAreaLayout.Row(kind, actions);
	}
}
