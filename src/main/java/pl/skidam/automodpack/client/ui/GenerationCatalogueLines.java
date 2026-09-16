package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import net.minecraft.ChatFormatting;

import pl.skidam.automodpack.client.ui.PagedTextScreen.Line;
import pl.skidam.automodpack_core.modpack.generation.GenerationDiff;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;

/** Builds complete deterministic catalogue views for generation review screens. */
public final class GenerationCatalogueLines {
	private GenerationCatalogueLines() {}

	public static List<Line> diff(GenerationRecord previous, GenerationRecord current) {
		Objects.requireNonNull(current);
		List<Line> lines = new ArrayList<>();
		lines.add(line("generation " + current.metadata().generationId(), ChatFormatting.GRAY));
		lines.add(line("created " + current.metadata().createdAt(), ChatFormatting.GRAY));
		lines.add(line("parent " + current.metadata().parentGenerationId(), ChatFormatting.GRAY));
		lines.add(line("patch notes: " + (current.metadata().patchNotes().isBlank() ? "none" : current.metadata().patchNotes()), ChatFormatting.YELLOW));
		lines.add(blank());
		appendPackMetadata(lines, previous == null ? null : previous.manifest(), current.manifest());
		appendGroupMetadata(lines, previous == null ? null : previous.manifest(), current.manifest());
		GenerationDiff diff = GenerationDiff.between(previous == null ? null : previous.manifest(), current.manifest());
		for (GenerationDiff.FileChange change : diff.files()) {
			ChatFormatting color = color(change.classification());
			lines.add(line(marker(change.classification()) + " " + change.groupId() + "/" + change.logicalPath(), color));
			if (change.before() != null) lines.add(line("  - " + fileMetadata(change.before()), ChatFormatting.RED));
			if (change.after() != null) lines.add(line("  + " + fileMetadata(change.after()), ChatFormatting.GREEN));
		}
		return List.copyOf(lines);
	}

	public static List<Line> files(GenerationRecord generation) {
		Objects.requireNonNull(generation);
		List<Line> lines = new ArrayList<>();
		lines.add(line("generation " + generation.metadata().generationId(), ChatFormatting.GRAY));
		lines.add(blank());
		for (var groupEntry : generation.manifest().groups().entrySet()) {
			GroupManifest.Group group = groupEntry.getValue();
			String name = group.displayName().isBlank() ? groupEntry.getKey() : group.displayName();
			lines.add(line("[" + groupEntry.getKey() + "] " + name, ChatFormatting.YELLOW));
			for (var fileEntry : group.files().entrySet()) {
				lines.add(line("  " + fileEntry.getKey(), ChatFormatting.WHITE));
				lines.add(line("    " + fileMetadata(fileEntry.getValue()), ChatFormatting.GRAY));
			}
			if (group.files().isEmpty()) lines.add(line("  no files", ChatFormatting.DARK_GRAY));
			lines.add(blank());
		}
		return List.copyOf(lines);
	}

	private static void appendPackMetadata(List<Line> lines, GroupManifest before, GroupManifest after) {
		lines.add(line("pack metadata", ChatFormatting.YELLOW));
		appendChange(lines, "modpackId", before == null ? null : before.modpackId(), after.modpackId());
		appendChange(lines, "modpackName", before == null ? null : before.modpackName(), after.modpackName());
		appendChange(lines, "automodpackVersion", before == null ? null : before.automodpackVersion(), after.automodpackVersion());
		appendChange(lines, "loader", before == null ? null : before.loader(), after.loader());
		appendChange(lines, "loaderVersion", before == null ? null : before.loaderVersion(), after.loaderVersion());
		appendChange(lines, "mcVersion", before == null ? null : before.mcVersion(), after.mcVersion());
		lines.add(blank());
	}

	private static void appendGroupMetadata(List<Line> lines, GroupManifest before, GroupManifest after) {
		TreeSet<String> groupIds = new TreeSet<>();
		if (before != null) groupIds.addAll(before.groups().keySet());
		groupIds.addAll(after.groups().keySet());
		for (String groupId : groupIds) {
			GroupManifest.Group oldGroup = before == null ? null : before.groups().get(groupId);
			GroupManifest.Group newGroup = after.groups().get(groupId);
			if (oldGroup != null && newGroup != null && sameGroupMetadata(oldGroup, newGroup)) continue;
			lines.add(line((oldGroup == null ? "+ " : newGroup == null ? "- " : "~ ") + "group " + groupId,
					oldGroup == null ? ChatFormatting.GREEN : newGroup == null ? ChatFormatting.RED : ChatFormatting.YELLOW));
			appendChange(lines, "displayName", oldGroup == null ? null : oldGroup.displayName(), newGroup == null ? null : newGroup.displayName());
			appendChange(lines, "description", oldGroup == null ? null : oldGroup.description(), newGroup == null ? null : newGroup.description());
			appendChange(lines, "tag", oldGroup == null ? null : oldGroup.tag(), newGroup == null ? null : newGroup.tag());
			appendChange(lines, "required", oldGroup == null ? null : oldGroup.required(), newGroup == null ? null : newGroup.required());
			appendChange(lines, "defaultSelected", oldGroup == null ? null : oldGroup.defaultSelected(), newGroup == null ? null : newGroup.defaultSelected());
			appendChange(lines, "breaksWith", oldGroup == null ? null : oldGroup.breaksWith(), newGroup == null ? null : newGroup.breaksWith());
			appendChange(lines, "requires", oldGroup == null ? null : oldGroup.requires(), newGroup == null ? null : newGroup.requires());
			appendChange(lines, "compatiblePlatforms", oldGroup == null ? null : oldGroup.compatiblePlatforms(), newGroup == null ? null : newGroup.compatiblePlatforms());
			lines.add(blank());
		}
	}

	private static boolean sameGroupMetadata(GroupManifest.Group before, GroupManifest.Group after) {
		return Objects.equals(before.displayName(), after.displayName()) && Objects.equals(before.description(), after.description()) && Objects.equals(before.tag(), after.tag())
				&& before.required() == after.required() && before.defaultSelected() == after.defaultSelected() && Objects.equals(before.breaksWith(), after.breaksWith())
				&& Objects.equals(before.requires(), after.requires()) && Objects.equals(before.compatiblePlatforms(), after.compatiblePlatforms());
	}

	private static void appendChange(List<Line> lines, String field, Object before, Object after) {
		if (Objects.equals(before, after)) return;
		if (before != null) lines.add(line("  - " + field + ": " + before, ChatFormatting.RED));
		if (after != null) lines.add(line("  + " + field + ": " + after, ChatFormatting.GREEN));
	}

	private static String fileMetadata(GroupManifest.GroupFile file) {
		return "size=" + UiFormat.formatSize(file.size()) + " type=" + file.type() + " editable=" + file.editable() + " overwriteEditable=" + file.overwriteEditable()
				+ " sha1=" + file.sha1() + " murmur=" + file.murmur();
	}

	private static String marker(GenerationDiff.FileClassification classification) {
		return switch (classification) {
			case ADDED -> "+";
			case MODIFIED, METADATA_ONLY -> "~";
			case REMOVED -> "-";
		};
	}

	private static ChatFormatting color(GenerationDiff.FileClassification classification) {
		return switch (classification) {
			case ADDED -> ChatFormatting.GREEN;
			case MODIFIED, METADATA_ONLY -> ChatFormatting.YELLOW;
			case REMOVED -> ChatFormatting.RED;
		};
	}

	private static Line line(String text, ChatFormatting color) {
		return new Line(text, color);
	}

	private static Line blank() {
		return line("", ChatFormatting.WHITE);
	}
}
