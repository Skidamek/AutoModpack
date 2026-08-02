package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;

public final class GroupInspectorScreen extends VersionedScreen {
	private static final int ROWS_PER_PAGE = 8;

	private final Screen parent;
	private final String groupId;
	private final GroupManifest.Group group;
	private final List<Map.Entry<String, GroupManifest.GroupFile>> files;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public GroupInspectorScreen(Screen parent, GroupManifest manifest, String groupId) {
		super(VersionedText.literal("GroupInspectorScreen"));
		this.parent = parent;
		this.groupId = groupId;
		this.group = manifest.groups().get(groupId);
		if (this.group == null) throw new IllegalArgumentException("Unknown group: " + groupId);
		this.files = new ArrayList<>(this.group.files().entrySet());
	}

	@Override
	protected void init() {
		super.init();
		int y = this.height - 28;
		this.previousButton = buttonWidget(this.width / 2 - 155, y, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(this.width / 2 + 85, y, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		this.previousButton.active = page > 0;
		this.nextButton.active = page + 1 < pageCount();
		this.addRenderableWidget(this.previousButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(buttonWidget(this.width / 2 - 40, y, 80, 20, VersionedText.literal("Back"), button -> this.minecraft.gui.setScreen(parent)));
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		previousButton.active = page > 0;
		nextButton.active = page + 1 < pageCount();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = group.displayName().isBlank() ? groupId : group.displayName();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(name).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Group " + groupId).withStyle(ChatFormatting.GRAY), this.width / 2, 26, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(description()).withStyle(ChatFormatting.WHITE), this.width / 2, 40, TextColors.WHITE);

		int metadataY = 56;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Tags: " + join(group.tags())).withStyle(ChatFormatting.GRAY), this.width / 2, metadataY,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Requires: " + join(group.requires()) + "  Conflicts: " + join(group.breaksWith())).withStyle(ChatFormatting.GRAY),
				this.width / 2, metadataY + 13, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Platforms: " + platforms()).withStyle(ChatFormatting.GRAY), this.width / 2, metadataY + 26, TextColors.WHITE);
		String status = (group.required() ? "Required" : group.recommended() ? "Recommended" : "Optional") + "  |  " + files.size() + " files";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(status).withStyle(ChatFormatting.YELLOW), this.width / 2, metadataY + 39, TextColors.WHITE);

		int start = page * ROWS_PER_PAGE;
		int end = Math.min(files.size(), start + ROWS_PER_PAGE);
		for (int index = start; index < end; index++) {
			Map.Entry<String, GroupManifest.GroupFile> entry = files.get(index);
			GroupManifest.GroupFile file = entry.getValue();
			String line = shortPath(entry.getKey()) + "  " + file.type() + "  " + formatSize(file.size()) + fileFlags(file);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line), this.width / 2, 110 + (index - start) * 16, TextColors.WHITE);
		}

		int pageCount = pageCount();
		int y = this.height - 52;
		if (pageCount > 1) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Page " + (page + 1) + " / " + pageCount).withStyle(ChatFormatting.GRAY), this.width / 2, y - 12,
					TextColors.WHITE);
			if (page > 0) {
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Use the mouse wheel to inspect earlier files").withStyle(ChatFormatting.GRAY), this.width / 2, y,
						TextColors.WHITE);
			}
		}
	}

	private int pageCount() {
		return Math.max(1, (files.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
	}

	private String description() {
		return group.description().isBlank() ? "No description published." : shortPath(group.description());
	}

	private String platforms() {
		return group.compatiblePlatforms().isEmpty() ? "All supported platforms" : group.compatiblePlatforms().stream().map(ClientPlatform::id).sorted().reduce((first, second) -> first + ", " + second).orElse("");
	}

	private static String join(Iterable<String> values) {
		StringBuilder result = new StringBuilder();
		for (String value : values) {
			if (result.length() > 0) result.append(", ");
			result.append(value);
		}
		return result.length() == 0 ? "none" : result.toString();
	}

	private static String fileFlags(GroupManifest.GroupFile file) {
		StringBuilder flags = new StringBuilder();
		if (file.editable()) flags.append(" editable");
		if (file.forceCopy()) flags.append(" copied");
		return flags.toString();
	}

	private static String shortPath(String value) {
		return value.length() <= 70 ? value : "..." + value.substring(value.length() - 67);
	}

	private static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
		return (bytes / (1024 * 1024)) + " MiB";
	}

	@Override
	public boolean shouldCloseOnEsc() {
		this.minecraft.gui.setScreen(parent);
		return false;
	}
}
