package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/**
 * The one renderer for change statistics: canonical order (changed files, then size, then
 * consequence), canonical colors, zero segments omitted, wrapped never truncated, diff legend on
 * hover. Screens declare facts; this component owns layout and language.
 */
public final class ChangeSummary {
	/** One labeled fact: a count and what was counted, drawn in the segment's canonical color. */
	public record Segment(String text, ChatFormatting color) {}

	private static final int LINE_HEIGHT = 12;

	private ChangeSummary() {}

	/** "+1 added ~4 changed -6 removed 3 kept !0 unsafe" — zero segments are omitted. */
	public static String diffLine(int added, int modified, int removed, int preserved, int unsafe) {
		List<String> parts = new ArrayList<>();
		if (added > 0) parts.add("+" + added + " " + VersionedText.translatable("automodpack.summary.kind.added").getString());
		if (modified > 0) parts.add("~" + modified + " " + VersionedText.translatable("automodpack.summary.kind.modified").getString());
		if (removed > 0) parts.add("-" + removed + " " + VersionedText.translatable("automodpack.summary.kind.removed").getString());
		if (preserved > 0) parts.add(VersionedText.translatable("automodpack.summary.kind.kept", preserved).getString());
		if (unsafe > 0) parts.add("!" + unsafe + " " + VersionedText.translatable("automodpack.summary.kind.unsafe").getString());
		return parts.isEmpty() ? VersionedText.translatable("automodpack.summary.noChanges").getString() : String.join("  ", parts);
	}

	/** One line per non-zero diff kind in canonical order and color; a single gray line when nothing changed. */
	public static List<MutableComponent> diffLines(int added, int modified, int removed, int preserved, int unsafe) {
		List<MutableComponent> lines = new ArrayList<>();
		if (added > 0) lines.add(VersionedText.literal("+" + added + " " + kind("added")).withStyle(ChatFormatting.GREEN));
		if (modified > 0) lines.add(VersionedText.literal("~" + modified + " " + kind("modified")).withStyle(ChatFormatting.YELLOW));
		if (removed > 0) lines.add(VersionedText.literal("-" + removed + " " + kind("removed")).withStyle(ChatFormatting.RED));
		if (preserved > 0) lines.add(VersionedText.translatable("automodpack.summary.kind.kept", preserved).withStyle(ChatFormatting.GRAY));
		if (unsafe > 0) lines.add(VersionedText.literal("!" + unsafe + " " + kind("unsafe")).withStyle(ChatFormatting.RED));
		if (lines.isEmpty()) lines.add(VersionedText.translatable("automodpack.summary.noChanges").withStyle(ChatFormatting.GRAY));
		return lines;
	}

	/** The hover legend for {@link #diffLine}: one meaning per glyph, built from the translated kind words. */
	public static MutableComponent diffLegend() {
		return VersionedText.literal("+ " + kind("added") + "   ~ " + kind("modified") + "   - " + kind("removed") + "   ! " + kind("unsafe")).withStyle(ChatFormatting.GRAY);
	}

	private static String kind(String kind) {
		return VersionedText.translatable("automodpack.summary.kind." + kind).getString();
	}
}
