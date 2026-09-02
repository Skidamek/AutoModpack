package pl.skidam.automodpack_core.utils;

import java.util.ArrayList;
import java.util.List;

/** Calculates stable page boundaries for a fixed-height list. */
public final class PageLayout {
	private PageLayout() {}

	public static List<Range> paginate(int itemCount, int pageCapacity, List<Range> keepTogetherRanges) {
		if (itemCount < 0) throw new IllegalArgumentException("Item count cannot be negative");
		if (pageCapacity < 1) throw new IllegalArgumentException("Page capacity must be positive");
		for (int i = 0; i < keepTogetherRanges.size(); i++) {
			Range range = keepTogetherRanges.get(i);
			if (range.endExclusive() > itemCount) throw new IllegalArgumentException("Range exceeds the item count");
			if (i > 0 && keepTogetherRanges.get(i - 1).endExclusive() > range.start()) throw new IllegalArgumentException("Ranges overlap or are not ordered");
		}

		List<Range> pages = new ArrayList<>();
		for (int start = 0; start < itemCount;) {
			int end = Math.min(itemCount, start + pageCapacity);
			for (Range range : keepTogetherRanges) {
				if (range.endExclusive() - range.start() > pageCapacity) continue;
				if (range.start() >= start && range.start() < end && range.endExclusive() > end) {
					end = range.start();
					break;
				}
			}
			if (end == start) end = Math.min(itemCount, start + pageCapacity);
			pages.add(new Range(start, end));
			start = end;
		}
		if (pages.isEmpty()) pages.add(new Range(0, 0));
		return List.copyOf(pages);
	}

	public record Range(int start, int endExclusive) {
		public Range {
			if (start < 0 || endExclusive < start) throw new IllegalArgumentException("Invalid range");
		}
	}
}
