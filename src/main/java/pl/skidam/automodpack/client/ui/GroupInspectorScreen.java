package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;

public final class GroupInspectorScreen extends VersionedScreen {
	private final Screen parent;
	private final String groupId;
	private final GroupManifest manifest;
	private final GroupManifest.Group group;
	private final List<Map.Entry<String, GroupManifest.GroupFile>> files;
	private Button previousButton;
	private Button nextButton;
	private int page;

	public GroupInspectorScreen(Screen parent, GroupManifest manifest, String groupId) {
		super(VersionedText.literal("GroupInspectorScreen"));
		this.parent = parent;
		this.groupId = groupId;
		this.manifest = manifest;
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
		this.addRenderableWidget(buttonWidget(this.width / 2 - 40, y, 80, 20, VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent)));
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		previousButton.active = page > 0;
		nextButton.active = page + 1 < pageCount();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = group.displayName().isBlank() ? groupId : group.displayName();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Group " + groupId, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 26, TextColors.WHITE);
		List<String> descriptionLines = descriptionLines();
		for (int index = 0; index < descriptionLines.size(); index++) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.WHITE), this.width / 2, 40 + index * 12, TextColors.WHITE);
		}

		int metadataY = 56 + (descriptionLines.size() - 1) * 12;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Tag: " + tagLabel(), this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, metadataY,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "Requires: " + join(group.requires()) + "  Conflicts: " + join(group.breaksWith()), this.width - 20)).withStyle(ChatFormatting.GRAY),
				this.width / 2, metadataY + 13, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Platforms: " + platforms(), this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, metadataY + 26,
				TextColors.WHITE);
		int nextMetadataY = metadataY + 39;
		String status = (group.required() ? "Required" : group.recommended() ? "Recommended" : "Optional") + "  |  " + files.size() + " files";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(status).withStyle(ChatFormatting.YELLOW), this.width / 2, nextMetadataY, TextColors.WHITE);

		int pageSize = rowsPerPage();
		int start = page * pageSize;
		int end = Math.min(files.size(), start + pageSize);
		int filesY = nextMetadataY + 15;
		for (int index = start; index < end; index++) {
			Map.Entry<String, GroupManifest.GroupFile> entry = files.get(index);
			GroupManifest.GroupFile file = entry.getValue();
			String line = entry.getKey() + "  " + file.type() + "  " + UiFormat.formatSize(file.size()) + fileFlags(file);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, line, this.width - 20)), this.width / 2, filesY + (index - start) * 16, TextColors.WHITE);
		}

		int pageCount = pageCount();
		int y = this.height - 52;
		if (pageCount > 1) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Page " + (page + 1) + " / " + pageCount).withStyle(ChatFormatting.GRAY), this.width / 2, y - 12,
					TextColors.WHITE);
		}
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (files.size() + pageSize - 1) / pageSize);
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 64 - filesY()) / 16);
	}

	private int filesY() {
		return 56 + (descriptionLines().size() - 1) * 12 + 39 + 15;
	}

	private List<String> descriptionLines() {
		String description = group.description().isBlank() ? "No description published." : group.description();
		return wrapToWidth(this.font, description, Math.max(1, this.width - 20), 2);
	}

	private String tagLabel() {
		if (group.tag().isEmpty()) return "General";
		GroupManifest.SelectionTag tag = manifest.selectionTags().get(group.tag());
		return tag == null || tag.displayName().isBlank() ? group.tag() : tag.displayName();
	}

	private String platforms() {
		return group.compatiblePlatforms().isEmpty() ? "All supported platforms" : group.compatiblePlatforms().stream().map(ClientPlatform::id).sorted().reduce((first, second) -> first + ", " + second).orElse("");
	}

	private String join(Iterable<String> values) {
		StringBuilder result = new StringBuilder();
		for (String value : values) {
			if (result.length() > 0) result.append(", ");
			result.append(value);
		}
		return result.length() == 0 ? "none" : truncateToWidth(this.font, result.toString(), Math.max(1, this.width - 20));
	}

	private static String fileFlags(GroupManifest.GroupFile file) {
		StringBuilder flags = new StringBuilder();
		if (file.editable()) flags.append(" editable");
		if (file.forceCopy()) flags.append(" copied");
		return flags.toString();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		ScreenImpl.setScreen(parent);
		return false;
	}
}
